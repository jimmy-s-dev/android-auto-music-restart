package com.jimmyshin.automusicrestart

import android.app.Instrumentation
import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import android.app.Activity

/** Separate signed test APK; no exported test or shell-command entrypoint in the shipped app. */
class DeviceChecks : Instrumentation() {
    private var scenario = "probe"
    private var observeSeconds = 185
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        scenario = arguments?.getString("scenario") ?: "probe"
        observeSeconds = arguments?.getString("seconds")?.toIntOrNull()?.coerceIn(30, 600) ?: 185
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            runOnMainSync {
                if (scenario == "automationOff" || scenario == "automaticService" || scenario == "prepareWidget") Store.enabled = false
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(targetContext, AutoListener::class.java))
            }
            val deadline = SystemClock.elapsedRealtime() + 30000
            while (AutoListener.instance == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
            result.putString("shizukuReady", Bridge.ready().toString())
            result.putString("listenerReady", (AutoListener.instance != null).toString())
            result.putString("locked", targetContext.getSystemService(KeyguardManager::class.java).isDeviceLocked.toString())
            result.putString("screenOn", targetContext.getSystemService(android.os.PowerManager::class.java).isInteractive.toString())
            when (scenario) {
                "probe" -> Unit
                "prepareWidget" -> {
                    check(Bridge.ready() && AutoListener.instance != null)
                    val bridge = Bridge()
                    try { bridge.connect().stopTarget() } finally { bridge.close(destroy = true) }
                    runOnMainSync { Store.enabled = true; AutoListener.instance?.refresh(true) }
                    result.putInt("automaticAttempts", Store.prefs.getInt("automaticAttempts", 0))
                    result.putInt("automaticStarts", Store.prefs.getInt("automaticStarts", 0))
                }
                "cancelExitWait" -> {
                    check(Bridge.ready() && AutoListener.instance != null)
                    fun count(marker: String) = Store.prefs.getString("log", "").orEmpty()
                        .lineSequence().count { marker in it }
                    val initialReturns = count("시간 측정: 종료 반환")
                    val initialInitializations = count("시간 측정: 초기화 시작")
                    val displays = targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)
                    try {
                        runOnMainSync { Runner.start(true, 0) }
                        val by = SystemClock.elapsedRealtime() + 30000
                        while (count("시간 측정: 종료 반환") == initialReturns && Runner.running &&
                            SystemClock.elapsedRealtime() < by) Thread.sleep(10)
                        check(count("시간 측정: 종료 반환") > initialReturns) { "Stop return was not observed" }
                        runOnMainSync { Runner.cancel("종료 확인 대기 중 취소 시험") }
                        val finishBy = SystemClock.elapsedRealtime() + 10000
                        while (Runner.running && SystemClock.elapsedRealtime() < finishBy) Thread.sleep(50)
                        check(!Runner.running && Store.prefs.getString("lastResult", "") == "cancelled")
                        check(count("시간 측정: 초기화 시작") == initialInitializations) { "Initialization after cancellation" }
                        check(displays.displays.none { it.name == HiddenPlayback.DISPLAY_NAME }) { "Display leaked" }
                        check(PlaybackMonitor.controllers().none {
                            it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                        }) { "Playback resumed after cancellation" }
                    } finally { if (Runner.running) Runner.cancel("종료 확인 취소 시험 정리") }
                }
                "pausePlayback" -> {
                    runOnMainSync {
                        Store.enabled = false
                        Runner.cancel("기기 재생 시험 종료")
                        AutoListener.instance?.refresh(true)
                    }
                    val controllers = PlaybackMonitor.controllers()
                    controllers.forEach { it.transportControls.pause() }
                    val pauseBy = SystemClock.elapsedRealtime() + 5000
                    while (controllers.any { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } &&
                        SystemClock.elapsedRealtime() < pauseBy) Thread.sleep(100)
                    check(controllers.none { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING })
                }
                "observeTwoTracks" -> {
                    val count = Store.prefs.getInt("automaticStarts", 0)
                    PlaybackProbe(targetContext).observeTwoTracks { sample ->
                        sendStatus(1, Bundle().apply { putString("continuity", sample) })
                    }
                    check(count == Store.prefs.getInt("automaticStarts", 0)) { "Automatic restart during healthy playback" }
                }
                "prepareTrackStart" -> {
                    val controller = PlaybackMonitor.controllers().single()
                    check(controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING)
                    check((controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L) >= 190000)
                    controller.transportControls.seekTo(0)
                }
                "automationOff", "automationOn" -> runOnMainSync {
                    Store.enabled = scenario == "automationOn"
                    if (!Store.enabled) Runner.cancel("기기 판별 시험 준비")
                    AutoListener.instance?.refresh(true)
                }
                "history" -> Bridge().use { bridge ->
                    result.putString("processHistory", bridge.connect().inspectHistory())
                    result.putInt("automaticAttempts", Store.prefs.getInt("automaticAttempts", 0))
                    result.putInt("automaticStarts", Store.prefs.getInt("automaticStarts", 0))
                    result.putString("monitorStatus", Store.prefs.getString("monitorStatus", ""))
                }
                "historyRepeat" -> {
                    val initial = Store.prefs.getInt("automaticStarts", 0)
                    var key: String? = null
                    repeat(20) { index ->
                        val history = Bridge().use { ProcessInspection.decode(it.connect().inspectHistory()) }
                        check(history.key != null) { "History binding $index failed" }
                        if (key == null) key = history.key else check(history.key == key) { "Target changed during read-only inspection" }
                    }
                    check(initial == Store.prefs.getInt("automaticStarts", 0))
                    result.putString("historyBindings", "20 successful; no target restart")
                }
                "automaticService" -> {
                    check(Bridge.ready() && AutoListener.instance != null)
                    val previousCount = Store.prefs.getInt("automaticAttempts", 0)
                    val previousStarts = Store.prefs.getInt("automaticStarts", 0)
                    val coldBridge = Bridge()
                    try { coldBridge.connect().stopTarget() } finally { coldBridge.close(destroy = true) }
                    Thread.sleep(2000)
                    runOnMainSync { Store.enabled = true; AutoListener.instance?.refresh(true) }
                    // A fresh shell UserService performs service-only startup (no preceding stop in this binding).
                    Bridge().use { bridge -> result.putString("serviceStart", bridge.connect().preparePlayback()) }
                    val by = SystemClock.elapsedRealtime() + 100000
                    while (SystemClock.elapsedRealtime() < by) {
                        if (Store.prefs.getInt("automaticAttempts", 0) > previousCount && !Runner.running) break
                        Thread.sleep(200)
                    }
                    check(Store.prefs.getInt("automaticAttempts", 0) == previousCount + 1) { "Automatic attempt count mismatch" }
                    check(Store.prefs.getString("lastResult", "") == "success") { "Automatic recovery failed" }
                    Bridge().use { bridge ->
                        val history = ProcessInspection.decode(bridge.connect().inspectHistory())
                        result.putString("processHistory", ProcessInspection.encode(history))
                        check(history.activity == ActivityHistory.PRESENT) { "Recovered process has no Activity history" }
                    }
                    Thread.sleep(3000)
                    check(Store.prefs.getInt("automaticAttempts", 0) == previousCount + 1) { "Recovery loop" }
                    check(Store.prefs.getInt("automaticStarts", 0) == previousStarts + 1) { "Repeated connection/start attempts" }
                }
                "mediaProbe", "recoverBuffering", "reloadCurrentItem", "stopAndPlay", "browseRoot", "observePlayback" -> {
                    val probe = PlaybackProbe(targetContext)
                    result.putString("mediaBefore", probe.describe())
                    if (scenario == "browseRoot") {
                        val outcome = probe.browseRoot { sample ->
                            sendStatus(1, Bundle().apply { putString("browseSample", sample) })
                        }
                        result.putString("browseOutcome", outcome)
                        check(outcome == "CONNECTED") { "Browse: $outcome" }
                    }
                    if (scenario == "stopAndPlay") {
                        val outcome = probe.stopAndPlay { sample ->
                            sendStatus(1, Bundle().apply { putString("mediaSample", sample) })
                        }
                        result.putString("stopPlayOutcome", outcome)
                        result.putString("mediaAfter", probe.describe())
                        check(outcome == "PLAYING") { "Stop/play: $outcome" }
                    }
                    if (scenario == "reloadCurrentItem") {
                        val outcome = probe.reloadCurrentItem()
                        result.putString("reloadOutcome", outcome)
                        result.putString("mediaAfter", probe.describe())
                        check(outcome == "PLAYING") { "Queue item reload: $outcome" }
                    }
                    if (scenario == "recoverBuffering") {
                        val outcome = probe.recover()
                        result.putString("recoveryOutcome", outcome.name)
                        result.putString("mediaAfter", probe.describe())
                        check(outcome == BufferingRecovery.Outcome.PLAYING) { "Recovery: $outcome" }
                    }
                    if (scenario == "observePlayback") probe.observe(observeSeconds) { sample ->
                        sendStatus(1, Bundle().apply { putString("mediaSample", sample) })
                    }
                }
                "pauseTarget" -> {
                    val sessions = targetContext.getSystemService(android.media.session.MediaSessionManager::class.java)
                        .getActiveSessions(android.content.ComponentName(targetContext, AutoListener::class.java))
                        .filter { it.packageName == Target.PACKAGE }
                    check(sessions.isNotEmpty()) { "Target session missing" }
                    sessions.forEach { it.transportControls.pause() }
                    val pauseBy = SystemClock.elapsedRealtime() + 5000
                    while (sessions.any { it.playbackState?.state != android.media.session.PlaybackState.STATE_PAUSED }
                        && SystemClock.elapsedRealtime() < pauseBy) Thread.sleep(200)
                    check(sessions.all { it.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED }) {
                        "Target did not pause"
                    }
                }
                "finishSetup" -> {
                    runOnMainSync {
                        if (Store.prefs.getString("fingerprint", "").isNullOrEmpty()) {
                            Store.enabled = false
                            Store.prefs.edit().putBoolean("consumed", false).commit()
                        }
                        targetContext.getSystemService(android.app.NotificationManager::class.java).cancel(1)
                        Store.log("휴대폰 사전 검증 완료 — 실차 연결 알림 등록 대기")
                    }
                }
                "missingShizuku" -> {
                    check(!Bridge.ready()) { "This scenario requires Shizuku to be unavailable" }
                    runOnMainSync { Runner.start(true, 5000) }
                    val finishBy = SystemClock.elapsedRealtime() + 10000
                    while (Runner.running && SystemClock.elapsedRealtime() < finishBy) Thread.sleep(200)
                    check(!Runner.running) { "Missing privilege did not terminate the run" }
                    check(Store.prefs.getString("lastResult", "").orEmpty().startsWith("failed:"))
                }
                "menuSequence", "cancelInitialization" -> {
                    check(Bridge.ready() && AutoListener.instance != null) { "Permissions not ready" }
                    val displays = targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)
                    try {
                        if (scenario == "menuSequence") {
                            val activity = startActivitySync(android.content.Intent(targetContext, MainActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            runOnMainSync {
                                fun find(view: android.view.View): android.widget.Button? {
                                    if (view is android.widget.Button && view.text.toString() == "15초 후 시험 (화면을 잠그세요)") return view
                                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                                        find(view.getChildAt(index))?.let { return it }
                                    }
                                    return null
                                }
                                try { check(checkNotNull(find(activity.window.decorView)).performClick()) { "Menu click failed" } }
                                finally { activity.finish() }
                            }
                        } else {
                            runOnMainSync { Runner.start(true, 0) }
                            val displayBy = SystemClock.elapsedRealtime() + 25000
                            while (displays.displays.none { it.name == HiddenPlayback.DISPLAY_NAME } &&
                                Runner.running && SystemClock.elapsedRealtime() < displayBy) Thread.sleep(100)
                            check(displays.displays.any { it.name == HiddenPlayback.DISPLAY_NAME }) { "Temporary display was never created" }
                            runOnMainSync { Runner.cancel("가상 초기화 중 사용자 취소 시험") }
                        }
                        val finishBy = SystemClock.elapsedRealtime() + 110000
                        while (Runner.running && SystemClock.elapsedRealtime() < finishBy) Thread.sleep(100)
                        check(!Runner.running) { "Runner did not terminate" }
                        val expected = if (scenario == "menuSequence") "success" else "cancelled"
                        val actual = Store.prefs.getString("lastResult", "")
                        check(actual == expected) { "Unexpected result: $actual" }
                        val removeBy = SystemClock.elapsedRealtime() + 5000
                        while (displays.displays.any { it.name == HiddenPlayback.DISPLAY_NAME } &&
                            SystemClock.elapsedRealtime() < removeBy) Thread.sleep(100)
                        check(displays.displays.none { it.name == HiddenPlayback.DISPLAY_NAME }) { "Temporary display leaked" }
                        if (scenario == "menuSequence") PlaybackProbe(targetContext).observe(185) { sample ->
                            sendStatus(1, Bundle().apply { putString("mediaSample", sample) })
                        }
                    } finally {
                        if (Runner.running) Runner.cancel("기기 시험 종료")
                    }
                }
                "sequence", "holdPotHelper", "cancel", "disconnect", "revokeListener" -> {
                    check(Bridge.ready()) { "Shizuku permission not ready" }
                    check(AutoListener.instance != null) { "Notification listener not ready" }
                    val previousEnabled = Store.enabled
                    val potReady = java.util.concurrent.CountDownLatch(1)
                    val potConnection = object : android.content.ServiceConnection {
                        override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
                            potReady.countDown()
                        }
                        override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
                    }
                    var potBound = false
                    try {
                        if (scenario == "holdPotHelper") {
                            runOnMainSync {
                                potBound = targetContext.bindService(android.content.Intent("app.morphe.pot.helper.potokens.service.START")
                                    .setComponent(android.content.ComponentName("app.morphe.pot.helper", "app.morphe.pot.helper.potokens.PoTokenService")),
                                    potConnection, android.content.Context.BIND_AUTO_CREATE or android.content.Context.BIND_IMPORTANT)
                            }
                            check(potBound && potReady.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "PotHelper keepalive binding failed" }
                            sendStatus(1, Bundle().apply { putString("potHelper", "Binding held during test") })
                        }
                        runOnMainSync {
                            if (scenario == "disconnect") { Store.enabled = true}
                            Runner.start(true, when (scenario) {
                                "sequence", "holdPotHelper" -> 5000
                                "revokeListener" -> 60000
                                else -> 15000
                            })
                        }
                        if (scenario == "revokeListener") sendStatus(1, Bundle().apply { putString("waiting", "REVOKE_LISTENER_NOW") })
                        if (scenario == "cancel") {
                            Thread.sleep(700)
                            runOnMainSync { Runner.start(true, 15000); Runner.cancel("기기 취소 시험") }
                        }
                        if (scenario == "disconnect") {
                            Thread.sleep(700)
                            runOnMainSync {
                                Runner.cancel("알림 접근 연결 해제 모의 시험")
                            }
                        }
                        val finishBy = SystemClock.elapsedRealtime() + if (scenario == "revokeListener") 25000 else 115000
                        while (Runner.running && SystemClock.elapsedRealtime() < finishBy) Thread.sleep(250)
                        check(!Runner.running) { "Runner did not terminate" }
                        val actual = Store.prefs.getString("lastResult", "").orEmpty()
                        when (scenario) {
                            "sequence", "holdPotHelper" -> check(actual == "success") { "Unexpected result: $actual" }
                            "revokeListener" -> check(actual.startsWith("failed:") && actual.contains("알림")) { "Unexpected result: $actual" }
                            else -> check(actual == "cancelled") { "Unexpected result: $actual" }
                        }
                        if (scenario == "holdPotHelper") {
                            // Read-only observation; missing queue publication is reported explicitly.
                            PlaybackProbe(targetContext).observe(75, requireQueue = false) { sample ->
                                sendStatus(1, Bundle().apply { putString("mediaSample", sample) })
                            }
                        }
                    } finally {
                        if (potBound) runOnMainSync { targetContext.unbindService(potConnection) }
                        if (Runner.running) Runner.cancel("기기 시험 종료")
                        if (scenario == "disconnect") {
                            runOnMainSync { Store.enabled = previousEnabled}
                        }
                    }
                }
                else -> error("Unknown scenario")
            }
            result.putString("result", "PASS: $scenario")
            result.putString("automationEnabled", Store.enabled.toString())
            result.putString("notificationRegistered", (!Store.prefs.getString("fingerprint", "").isNullOrEmpty()).toString())
            result.putString("history", Store.prefs.getString("log", ""))
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("result", "FAIL: ${e.message}")
            result.putString("history", Store.prefs.getString("log", ""))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
