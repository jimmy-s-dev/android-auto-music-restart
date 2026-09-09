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

    private fun stateLabel(controller: MediaController): String {
        val state = controller.playbackState
        val name = when (state?.state) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_ERROR -> "ERROR"
            else -> state?.state?.toString() ?: "UNKNOWN"
        }
        return "$name(position=${state?.position})"
    }
    private fun snapshot(control: IControl): RestartSequence.Snapshot {
        val pids = control.inspect().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val list = controllers()
        return RestartSequence.Snapshot(pids, list.map { it.sessionToken }.toSet(),
            list.joinToString { stateLabel(it) }.ifBlank { "세션 없음" })
    }
    private fun requestPlayback(run: Run, control: IControl) {
        checkRun(run)
        val list = controllers()
        val controller = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
        checkRun(run)
        if (controller == null) {
            Store.log("첫 재생 요청: 백그라운드 서비스=${control.preparePlayback()}")
        } else {
            Store.log("첫 재생 요청: 상태=${stateLabel(controller)}")
            controller.transportControls.play()
        }
    }

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
        Store.log("최종 재생 상태=${stateLabel(controller)}")
        check(controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            "15초 안에 재생 상태가 되지 않았습니다: ${stateLabel(controller)}"
        }
        val keyguard = Store.context.getSystemService(KeyguardManager::class.java)
        val power = Store.context.getSystemService(PowerManager::class.java)
        Store.log("백그라운드 재생 확인 (보안잠금=${keyguard.isDeviceLocked}, 잠금화면=${keyguard.isKeyguardLocked}, 화면켜짐=${power.isInteractive})")
        return controller
    }
    private fun execute(run: Run, delay: Long) {
        val wake = Store.context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoMusicRestart:sequence")
        val bridge = Bridge()
        var sequence: RestartSequence? = null
        try {
            wake.acquire(120000)
            Store.log("${if (run.manual) "수동 시험" else "차량 자동 실행"}: ${delay / 1000}초 후 시작")
            waitFor(run, delay)
            val control = bridge.connect()
            sequence = RestartSequence(object : RestartSequence.Port {
                override fun check() = checkRun(run)
                override fun waitFor(millis: Long) = Runner.waitFor(run, millis)
                override fun requestPlayback() = Runner.requestPlayback(run, control)
                override fun snapshot() = Runner.snapshot(control)
                override fun stopTarget() { control.stopTarget() }
                override fun confirmPlayback(): Any = play(run, control).sessionToken
                override fun log(message: String) = Store.log(message)
            })
            sequence.execute()
            Store.log("완료: 새 세션 재생, PID=${control.inspect()} (실차 버퍼링 해결 여부는 별도 확인)")
            Store.prefs.edit().putString("lastResult", "success").commit()
            Store.context.getSystemService(android.app.NotificationManager::class.java).cancel(1)
        } catch (e: CancellationException) {
            Store.log("취소: ${e.message}")
            Store.prefs.edit().putString("lastResult", "cancelled").commit()
        } catch (e: Exception) {
            Store.fail("실행 실패 [${sequence?.stage ?: "시작 대기·연결"}]: ${e.message ?: e.javaClass.simpleName}")
            Store.prefs.edit().putString("lastResult", "failed: ${e.message}").commit()
        } finally {
            bridge.close()
            if (wake.isHeld) wake.release()
            synchronized(this) { if (current === run) current = null }
        }
    }
}
