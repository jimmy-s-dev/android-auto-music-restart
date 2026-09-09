package com.jimmyshin.automusicrestart

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import java.security.MessageDigest
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

data class AutomaticRequest(val reason: RestartReason, val processKey: String,
    val token: MediaSession.Token, val track: String?)

class PlaybackMonitor {
    companion object {
        fun condition(state: PlaybackState?): PlaybackCondition = when (state?.state) {
            PlaybackState.STATE_PLAYING -> PlaybackCondition.PLAYING
            PlaybackState.STATE_BUFFERING -> PlaybackCondition.BUFFERING
            else -> PlaybackCondition.INACTIVE
        }
        fun track(controller: MediaController): String? {
            val metadata = controller.metadata ?: return null
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
            val raw = listOf(title, metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM), metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        fun controllers(): List<MediaController> = Store.context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(Store.context, AutoListener::class.java)).filter { it.packageName == Target.PACKAGE }
    }
    private val manager = Store.context.getSystemService(MediaSessionManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val subscriptions = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val gate = PlaybackGate()
    private var history: ProcessHistory? = null
    private var historyToken: MediaSession.Token? = null
    private var generation = 0
    private var inspecting = false
    private var closed = false
    private var lastStatus = ""
    private var suspendedToken: MediaSession.Token? = null
    private val bufferWake = Store.context.getSystemService(android.os.PowerManager::class.java)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AutoMusicRestart:buffer-debounce")
        .apply { setReferenceCounted(false) }
    private val timer = Runnable { evaluate() }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
    private val binderReceived = Shizuku.OnBinderReceivedListener { Store.main.post { refresh(true) } }
    private val binderDead = Shizuku.OnBinderDeadListener { Store.main.post {
        invalidate(); Runner.cancelAutomatic("Shizuku 연결 상실"); status("Shizuku 준비 대기")
    } }
    private val permissionChanged = Shizuku.OnRequestPermissionResultListener { _, _ -> Store.main.post { refresh(true) } }

    fun start() {
        manager.addOnActiveSessionsChangedListener(sessionsChanged, ComponentName(Store.context, AutoListener::class.java), Store.main)
        Shizuku.addBinderReceivedListener(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionChanged)
        refresh(true)
    }
    fun close() {
        closed = true; invalidate()
        if (bufferWake.isHeld) bufferWake.release()
        manager.removeOnActiveSessionsChangedListener(sessionsChanged)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionChanged)
        subscriptions.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        subscriptions.clear(); executor.shutdownNow()
    }
    private fun invalidate() { generation++; history = null; historyToken = null; Store.main.removeCallbacks(timer) }
    fun suspendCurrent() {
        suspendedToken = subscriptions.values.singleOrNull()?.first?.sessionToken
        Store.main.removeCallbacks(timer)
        if (bufferWake.isHeld) bufferWake.release()
    }
    fun refresh(force: Boolean = false) {
        if (closed) return
        if (force) invalidate()
        val list = runCatching { controllers() }.getOrElse { status("미디어 세션 접근 불가"); return }
        subscriptions.keys.filter { token -> list.none { it.sessionToken == token } }.forEach {
            subscriptions.remove(it)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
        }
        list.forEach { controller ->
            if (controller.sessionToken !in subscriptions) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        Runner.onPlaybackObservation(controller.sessionToken, state?.state)
                        evaluate()
                    }
                    override fun onMetadataChanged(metadata: MediaMetadata?) { evaluate() }
                    override fun onSessionDestroyed() { refresh() }
                }
                controller.registerCallback(callback, Store.main)
                subscriptions[controller.sessionToken] = controller to callback
                Runner.onPlaybackObservation(controller.sessionToken, controller.playbackState?.state)
            }
        }
        evaluate()
    }
    private fun status(value: String) {
        if (lastStatus == value) return
        lastStatus = value
        Store.prefs.edit().putString("monitorStatus", value).apply()
        Store.log("감지: $value")
    }
    private fun evaluate() {
        if (closed) return
        Store.main.removeCallbacks(timer)
        if (bufferWake.isHeld) bufferWake.release()
        val controller = subscriptions.values.singleOrNull()?.first
        val previousToken = gate.token
        val previousState = gate.state
        val state = condition(controller?.playbackState)
        gate.observe(controller?.sessionToken, controller?.let(::track), state, SystemClock.elapsedRealtime())
        if (state == PlaybackCondition.INACTIVE || suspendedToken != controller?.sessionToken) suspendedToken = null
        if (previousToken != gate.token || (previousState == PlaybackCondition.INACTIVE && state != previousState)) invalidate()
        if (!Store.enabled) { status("자동화 꺼짐"); return }
        if (Runner.running) { status("복구 진행 중 — 감지 중복 차단"); return }
        if (controller == null) { status(if (subscriptions.size > 1) "복수 미디어 세션 — 자동 실행 보류" else "Morphe 재생 대기"); return }
        if (state == PlaybackCondition.INACTIVE) { status("일시정지·정지 — 자동 실행 없음"); return }
        if (suspendedToken == controller.sessionToken) { status("사용자 중단 — 다음 재생 시작까지 자동 실행 보류"); return }
        if (!Bridge.ready()) { status("Shizuku 준비 대기 — 자동 실행 보류"); return }
        if (history == null || historyToken != controller.sessionToken) { inspect(controller.sessionToken); return }
        val value = checkNotNull(history)
        if (value.key == null) { status("${value.reason} — 프로세스 식별 불가로 자동 실행 보류"); return }
        if (!Store.processAllowed(value.key)) { status("현재 프로세스는 이미 처리했거나 복구 결과 확인 필요"); return }
        val reason = gate.decide(value.activity, SystemClock.elapsedRealtime())
        if (reason != null) {
            status("${value.reason} — ${reason.label}")
            Runner.startAutomatic(AutomaticRequest(reason, value.key, controller.sessionToken, gate.track))
        } else {
            status(if (value.activity == ActivityHistory.PRESENT) value.reason + " — 예방 재시작 생략"
                else "${value.reason} — 연속 10초 버퍼링 감지 대기")
            if (value.activity == ActivityHistory.UNKNOWN && gate.bufferingSince != null) {
                val remaining = 10000 - (SystemClock.elapsedRealtime() - checkNotNull(gate.bufferingSince))
                bufferWake.acquire(12000)
                Store.main.postDelayed(timer, remaining.coerceAtLeast(1))
            }
        }
    }
    private fun inspect(token: MediaSession.Token) {
        if (inspecting) return
        inspecting = true
        val revision = generation
        status("프로세스 Activity 이력 확인 중")
        executor.execute {
            val wake = Store.context.getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AutoMusicRestart:history")
            val result = runCatching {
                wake.acquire(40000)
                Bridge().use { bridge ->
                    val control = bridge.connect()
                    val first = ProcessInspection.decode(control.inspectHistory())
                    if (first.activity != ActivityHistory.ABSENT) first else {
                        Thread.sleep(1000)
                        val second = ProcessInspection.decode(control.inspectHistory())
                        if (first.key == second.key) second else ProcessHistory.unknown("판별 중 대상 프로세스 교체")
                    }
                }
            }.getOrElse { ProcessHistory.unknown("Shizuku 이력 조회 실패") }
            if (wake.isHeld) wake.release()
            Store.main.post {
                inspecting = false
                if (closed) return@post
                if (generation == revision && gate.token == token) { history = result; historyToken = token }
                evaluate()
            }
        }
    }
}
