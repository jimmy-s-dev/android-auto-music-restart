package com.jimmyshin.automusicrestart

import android.app.KeyguardManager
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

object Runner {
    private data class Run(val manual: Boolean, val cancelled: AtomicBoolean = AtomicBoolean(false),
        val deadline: Long = SystemClock.elapsedRealtime() + 100000)
    @Volatile private var current: Run? = null
    val running get() = current != null
    @Synchronized fun cancel(reason: String) {
        current?.let { it.cancelled.set(true); Store.log("중단 요청: $reason") }
    }
    fun cancelAutomatic(reason: String) { if (current?.manual == false) cancel(reason) }
    @Synchronized fun start(manual: Boolean, initialDelayMs: Long) {
        if (current != null) { Store.log("이미 실행 중 — 중복 요청 무시"); return }
        val run = Run(manual)
        current = run
        Thread({ execute(run, initialDelayMs) }, "music-restart").start()
    }
    private fun checkRun(run: Run) {
        if (run.cancelled.get() || (!run.manual && (!Store.enabled || !AutoListener.connected)))
            throw CancellationException("요청 취소 또는 차량 연결 해제")
        check(SystemClock.elapsedRealtime() < run.deadline) { "전체 실행 제한 시간(100초)을 초과했습니다" }
        check(Bridge.ready()) { "Shizuku 권한 또는 연결이 없어 중단했습니다" }
        check(AutoListener.instance != null) { "알림 접근 권한 또는 연결이 없어 중단했습니다" }
    }
    private fun waitFor(run: Run, millis: Long) {
        val until = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < until) {
            checkRun(run); Thread.sleep(minOf(200L, (until - SystemClock.elapsedRealtime()).coerceAtLeast(1)))
        }
        checkRun(run)
    }
    private fun controllers(): List<MediaController> = Store.context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(ComponentName(Store.context, AutoListener::class.java)).filter { it.packageName == Target.PACKAGE }

    private fun play(run: Run, control: IControl): MediaController {
        checkRun(run)
        var list = controllers()
        if (list.isEmpty()) {
            Store.log("백그라운드 재생 서비스 시작: ${control.preparePlayback()}")
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (list.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
                waitFor(run, 250); list = controllers()
            }
        }
        check(list.isNotEmpty()) { "백그라운드 미디어 세션 생성 시간 초과(15초)" }
        val controller = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.first()
        checkRun(run)
        // Explicit PLAY is idempotent and never pauses another player.
        controller.transportControls.play()
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (controller.playbackState?.state != PlaybackState.STATE_PLAYING && SystemClock.elapsedRealtime() < deadline)
            waitFor(run, 300)
        check(controller.playbackState?.state == PlaybackState.STATE_PLAYING) { "15초 안에 재생 상태가 되지 않았습니다" }
        val keyguard = Store.context.getSystemService(KeyguardManager::class.java)
        val power = Store.context.getSystemService(PowerManager::class.java)
        Store.log("백그라운드 재생 확인 (보안잠금=${keyguard.isDeviceLocked}, 잠금화면=${keyguard.isKeyguardLocked}, 화면켜짐=${power.isInteractive})")
        return controller
    }
    private fun execute(run: Run, delay: Long) {
        val wake = Store.context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoMusicRestart:sequence")
        val bridge = Bridge()
        try {
            wake.acquire(120000)
            Store.log("${if (run.manual) "수동 시험" else "차량 자동 실행"}: ${delay / 1000}초 후 시작")
            waitFor(run, delay)
            val control = bridge.connect()
            val before = play(run, control)
            waitFor(run, 5000)
            val beforePid = control.inspect()
            checkRun(run)
            Store.log("강제 종료 전 PID=$beforePid, 대상 세션=${controllers().size}")
            control.stopTarget()
            waitFor(run, 2000)
            val afterPid = control.inspect()
            val stale = controllers().any { it.sessionToken == before.sessionToken }
            Store.log("강제 종료 후 PID=${afterPid.ifBlank { "없음" }}, 이전 세션=$stale")
            val oldPids = beforePid.split(" ").filter { it.isNotBlank() }.toSet()
            check(afterPid.split(" ").none { it in oldPids }) { "종료 전 프로세스가 남아 있습니다" }
            check(!stale) { "이전 미디어 세션이 남아 있어 재시작을 중단했습니다" }
            val after = play(run, control)
            check(after.sessionToken != before.sessionToken) { "새 미디어 세션 생성이 확인되지 않았습니다" }
            Store.log("완료: 새 세션 재생, PID=${control.inspect()} (실차 버퍼링 해결 여부는 별도 확인)")
            Store.prefs.edit().putString("lastResult", "success").commit()
            Store.context.getSystemService(android.app.NotificationManager::class.java).cancel(1)
        } catch (e: CancellationException) {
            Store.log("취소: ${e.message}")
            Store.prefs.edit().putString("lastResult", "cancelled").commit()
        } catch (e: Exception) {
            Store.fail("실행 실패: ${e.message ?: e.javaClass.simpleName}")
            Store.prefs.edit().putString("lastResult", "failed: ${e.message}").commit()
        } finally {
            bridge.close()
            if (wake.isHeld) wake.release()
            synchronized(this) { if (current === run) current = null }
        }
    }
}
