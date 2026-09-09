package com.jimmyshin.automusicrestart

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.security.MessageDigest

class AutoListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: AutoListener? = null
        @Volatile var connected = false
        fun label(n: StatusBarNotification): String = listOf(
            n.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            n.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()).joinToString(" / ")
        fun fingerprint(n: StatusBarNotification): String {
            val raw = listOf(n.packageName, n.id.toString(), n.tag.orEmpty(), n.notification.channelId, label(n)).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
    private lateinit var gate: ConnectionGate
    private val absent = Runnable {
        if (!hasMatch()) {
            connected = false
            gate.observe(false, Store.enabled)
            Store.prefs.edit().putBoolean("consumed", false).commit()
            Runner.cancelAutomatic("Android Auto 연결 해제")
        }
    }
    override fun onListenerConnected() {
        instance = this
        gate = ConnectionGate(Store.prefs.getBoolean("consumed", false))
        Store.log("연결 알림 감지 서비스 준비")
        refresh()
    }
    override fun onListenerDisconnected() {
        instance = null; connected = false
        Store.main.removeCallbacks(absent)
        Runner.cancelAutomatic("알림 접근 연결이 끊겼습니다")
        requestRebind(ComponentName(this, AutoListener::class.java))
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) { if (sbn.packageName == Target.AUTO) refresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (sbn.packageName == Target.AUTO) refresh() }
    fun candidates(): List<StatusBarNotification> = runCatching {
        activeNotifications.filter { it.packageName == Target.AUTO && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 && it.isOngoing }
    }.getOrDefault(emptyList())
    private fun hasMatch(): Boolean {
        val saved = Store.prefs.getString("fingerprint", "").orEmpty()
        return saved.isNotEmpty() && candidates().any { fingerprint(it) == saved }
    }
    fun refresh() {
        if (!::gate.isInitialized) return
        Store.main.removeCallbacks(absent)
        if (!hasMatch()) { Store.main.postDelayed(absent, 3000); return }
        connected = true
        if (gate.observe(true, Store.enabled)) {
            Store.prefs.edit().putBoolean("consumed", true).commit()
            Runner.start(manual = false, initialDelayMs = 5000)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Store.prefs.edit().putBoolean("consumed", false).commit()
        Store.log("재부팅 후 연결 감지 대기")
        NotificationListenerService.requestRebind(ComponentName(context, AutoListener::class.java))
    }
}
