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
    private data class Run(val reason: RestartReason, val request: AutomaticRequest? = null,
        val cancelled: AtomicBoolean = AtomicBoolean(false), @Volatile var stopped: Boolean = false,
        var newPlayingToken: Any? = null,
        val deadline: Long = SystemClock.elapsedRealtime() + 100000) {
        val manual get() = reason == RestartReason.MANUAL
    }
    @Volatile private var current: Run? = null
    val running get() = current != null
    @Synchronized fun cancel(reason: String) {
        Store.main.post { AutoListener.instance?.suspendCurrent() }
        current?.let { it.cancelled.set(true); Store.log("중단 요청: $reason") }
    }
    fun cancelAutomatic(reason: String) { if (current?.manual == false) cancel(reason) }
    fun onPlaybackObservation(token: Any, state: Int?) {
        val run = current ?: return
        if (run.manual || !run.stopped || token == run.request?.token) return
        if (state == PlaybackState.STATE_PLAYING) run.newPlayingToken = token
        if (token == run.newPlayingToken && (state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED))
            cancelAutomatic("새 세션 재생 중 일시정지·정지")
    }
    @Synchronized fun start(manual: Boolean, initialDelayMs: Long) {
        require(manual) { "자동 실행에는 검증된 프로세스 요청이 필요합니다" }
        launch(Run(RestartReason.MANUAL), initialDelayMs)
    }
    @Synchronized fun startAutomatic(request: AutomaticRequest) {
        require(request.reason != RestartReason.MANUAL)
        launch(Run(request.reason, request), 0)
    }
    private fun launch(run: Run, initialDelayMs: Long) {
        if (current != null) { Store.log("이미 실행 중 — 중복 요청 무시"); return }
        run.request?.let {
            if (!Store.processAllowed(it.processKey)) return
            // Consume at acceptance, not at force-stop: connection failures must not loop.
            Store.reserveAttempt(it.processKey)
            Store.prefs.edit().putInt("automaticStarts", Store.prefs.getInt("automaticStarts", 0) + 1).commit()
        }
        current = run
        Thread({ execute(run, initialDelayMs) }, "music-restart").start()
    }
    private fun checkRun(run: Run) {
        if (run.cancelled.get() || (!run.manual && !Store.enabled))
            throw CancellationException("사용자 취소 또는 자동화 꺼짐")
        check(SystemClock.elapsedRealtime() < run.deadline) { "전체 실행 제한 시간(100초)을 초과했습니다" }
        check(Bridge.ready()) { "Shizuku 권한 또는 연결이 없어 중단했습니다" }
        check(AutoListener.instance != null) { "알림 접근 권한 또는 연결이 없어 중단했습니다" }
        if (!run.stopped) run.request?.let { request ->
            val controller = controllers().singleOrNull()
            if (controller == null || controller.sessionToken != request.token)
                throw CancellationException("대상 세션 교체·소멸")
            val state = PlaybackMonitor.condition(controller.playbackState)
            if (state == PlaybackCondition.INACTIVE) throw CancellationException("사용자가 재생을 멈췄습니다")
            if (request.reason == RestartReason.BUFFERING && state != PlaybackCondition.BUFFERING)
                throw CancellationException("버퍼링이 자연 복구됐습니다")
            if (request.track != null && PlaybackMonitor.track(controller) != request.track)
                throw CancellationException("대상 곡이 변경됐습니다")
        }
    }
    private fun beforeStop(run: Run, control: IControl) {
        checkRun(run)
        val history = ProcessInspection.decode(GuardedCall.execute({ checkRun(run) }) { control.inspectHistory() })
        run.request?.let { request ->
            if (history.key != request.processKey) throw CancellationException("종료 직전 프로세스 식별값 변경·조회 실패")
            if (history.activity == ActivityHistory.PRESENT) throw CancellationException("Activity 초기화 이력이 확인됐습니다")
            if (request.reason == RestartReason.PREVENTIVE && history.activity != ActivityHistory.ABSENT)
                throw CancellationException("Activity 이력 없음 판정을 유지할 수 없습니다")
        }
        checkRun(run)
        history.key?.let { Store.beginAttempt(it) }
        run.stopped = true // Subsequent session disappearance belongs to our force-stop.
        if (!run.manual) {
            Store.prefs.edit().putInt("automaticAttempts", Store.prefs.getInt("automaticAttempts", 0) + 1).commit()
        }
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
            Store.log("첫 재생 요청: 백그라운드 서비스=${GuardedCall.execute({ checkRun(run) }) { control.preparePlayback() }}")
        } else {
            Store.log("첫 재생 요청: 상태=${stateLabel(controller)}")
            controller.transportControls.play()
        }
    }

    private fun play(run: Run, control: IControl): MediaController {
        checkRun(run)
        Store.log("가상 화면에서 음악 앱 초기화 시작")
        Store.log(GuardedCall.execute({ checkRun(run) }) { control.preparePlayback() })
        var list = controllers()
        if (list.isEmpty()) {
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
        var control: IControl? = null
        var completed = false
        var sequence: RestartSequence? = null
        try {
            wake.acquire(120000)
            Store.log("${run.reason.label}: ${delay / 1000}초 후 시작")
            if (run.manual) Store.clearPendingAttempt()
            waitFor(run, delay)
            val connectedControl = bridge.connect()
            control = connectedControl
            sequence = RestartSequence(object : RestartSequence.Port {
                override fun check() = checkRun(run)
                override fun waitFor(millis: Long) = Runner.waitFor(run, millis)
                override fun requestPlayback() = Runner.requestPlayback(run, connectedControl)
                override fun snapshot() = Runner.snapshot(connectedControl)
                override fun stopTarget() {
                    beforeStop(run, connectedControl)
                    GuardedCall.execute({ checkRun(run) }) { connectedControl.stopTarget() }
                }
                override fun confirmPlayback(): Any = play(run, connectedControl).sessionToken
                override fun log(message: String) = Store.log(message)
            })
            sequence.execute()
            Store.log("완료: 새 세션 재생, PID=${connectedControl.inspect()} (장시간 재생 여부는 별도 확인)")
            Store.prefs.edit().putString("lastResult", "success").commit()
            Store.context.getSystemService(android.app.NotificationManager::class.java).cancel(1)
            completed = true
        } catch (e: CancellationException) {
            Store.log("취소: ${e.message}")
            Store.prefs.edit().putString("lastResult", "cancelled").commit()
        } catch (e: Exception) {
            Store.fail("실행 실패 [${sequence?.stage ?: "시작 대기·연결"}]: ${e.message ?: e.javaClass.simpleName}")
            Store.prefs.edit().putString("lastResult", "failed: ${e.message}").commit()
        } finally {
            if (completed && run.stopped) runCatching {
                Store.finishAttempt(control?.let { ProcessInspection.decode(it.inspectHistory()).key })
            }
            // A failed/cancelled Binder initialization must lose its display immediately.
            // Successful calls keep the reusable service; destroy/rebind races caused failures.
            bridge.close(destroy = !completed)
            if (wake.isHeld) wake.release()
            synchronized(this) { if (current === run) current = null }
            Store.main.post { AutoListener.instance?.refresh(true) }
        }
    }
}
