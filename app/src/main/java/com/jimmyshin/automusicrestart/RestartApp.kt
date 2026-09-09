package com.jimmyshin.automusicrestart

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RestartApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.context = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("result", "실행 실패", NotificationManager.IMPORTANCE_DEFAULT))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("recovery_status", "자동 복구 상태", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false)
            })
    }
}

object Store {
    lateinit var context: Application
    val main = Handler(Looper.getMainLooper())
    val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).commit() }
    private fun ledger(): AttemptLedger {
        val keys = linkedSetOf<String>()
        val ordered = prefs.getString("attemptedProcessOrder", null)
        if (ordered != null) {
            val array = org.json.JSONArray(ordered)
            for (index in 0 until array.length()) keys.add(array.getString(index))
        } else keys.addAll(prefs.getStringSet("attemptedProcesses", emptySet()).orEmpty())
        return AttemptLedger(keys, prefs.getString("pendingProcess", null), prefs.getString("quarantineProcess", null))
    }
    private fun saveLedger(value: AttemptLedger) {
        check(prefs.edit().putString("attemptedProcessOrder", org.json.JSONArray(value.attempted.toList()).toString())
            .remove("attemptedProcesses")
            .putString("pendingProcess", value.pending).putString("quarantineProcess", value.quarantine).commit()) {
            "복구 시도 기록 저장 실패"
        }
    }
    @Synchronized fun processAllowed(key: String): Boolean {
        val value = ledger()
        val before = value.pending to value.quarantine
        val allowed = value.allowed(key)
        if (before != (value.pending to value.quarantine)) saveLedger(value)
        return allowed
    }
    @Synchronized fun beginAttempt(key: String) { val value = ledger(); value.begin(key); saveLedger(value) }
    @Synchronized fun reserveAttempt(key: String) { val value = ledger(); value.reserve(key); saveLedger(value) }
    @Synchronized fun finishAttempt(successor: String?) { val value = ledger(); value.finish(successor); saveLedger(value) }
    @Synchronized fun clearPendingAttempt() { val value = ledger(); value.resetPending(); saveLedger(value) }
    @Synchronized fun log(message: String) {
        val line = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date()) + "  " + message
        Log.i("AutoMusicRestart", line)
        prefs.edit().putString("log", (prefs.getString("log", "") + "\n" + line).takeLast(16000)).apply()
        context.sendBroadcast(android.content.Intent("${context.packageName}.REFRESH").setPackage(context.packageName))
    }
    fun fail(message: String) {
        log(message)
        val intent = android.app.PendingIntent.getActivity(context, 0,
            android.content.Intent(context, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(context, "recovery_status")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("음악 재시작 확인 필요")
            .setContentText(message).setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(1, notification) }
    }
}
