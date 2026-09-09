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
    }
}

object Store {
    lateinit var context: Application
    val main = Handler(Looper.getMainLooper())
    val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).commit() }
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
        val notification = android.app.Notification.Builder(context, "result")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("음악 재시작 확인 필요")
            .setContentText(message).setContentIntent(intent).setAutoCancel(true).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(1, notification) }
    }
}
