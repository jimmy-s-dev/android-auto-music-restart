package com.jimmyshin.automusicrestart

import android.app.Instrumentation
import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import android.app.Activity

/** Separate signed test APK; no exported test or shell-command entrypoint in the shipped app. */
class DeviceChecks : Instrumentation() {
    private var scenario = "probe"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        scenario = arguments?.getString("scenario") ?: "probe"
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            runOnMainSync {
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
                "sequence", "cancel", "disconnect", "revokeListener" -> {
                    check(Bridge.ready()) { "Shizuku permission not ready" }
                    check(AutoListener.instance != null) { "Notification listener not ready" }
                    val previousEnabled = Store.enabled
                    try {
                        runOnMainSync {
                            if (scenario == "disconnect") { Store.enabled = true; AutoListener.connected = true }
                            Runner.start(scenario != "disconnect", when (scenario) {
                                "sequence" -> 5000
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
                                AutoListener.connected = false
                                Runner.cancelAutomatic("연결 해제 신호 모의 시험")
                            }
                        }
                        val finishBy = SystemClock.elapsedRealtime() + if (scenario == "revokeListener") 25000 else 115000
                        while (Runner.running && SystemClock.elapsedRealtime() < finishBy) Thread.sleep(250)
                        check(!Runner.running) { "Runner did not terminate" }
                        val actual = Store.prefs.getString("lastResult", "").orEmpty()
                        when (scenario) {
                            "sequence" -> check(actual == "success") { "Unexpected result: $actual" }
                            "revokeListener" -> check(actual.startsWith("failed:") && actual.contains("알림")) { "Unexpected result: $actual" }
                            else -> check(actual == "cancelled") { "Unexpected result: $actual" }
                        }
                    } finally {
                        if (Runner.running) Runner.cancel("기기 시험 종료")
                        if (scenario == "disconnect") {
                            runOnMainSync { Store.enabled = previousEnabled; AutoListener.connected = false }
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
