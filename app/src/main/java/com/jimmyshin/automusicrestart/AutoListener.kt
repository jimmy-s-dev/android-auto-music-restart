package com.jimmyshin.automusicrestart

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AutoListener : NotificationListenerService() {
    companion object { @Volatile var instance: AutoListener? = null }
    private var monitor: PlaybackMonitor? = null
    private var noisyRegistered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                monitor?.suspendCurrent()
                Runner.cancelAutomatic("이어폰·오디오 출력 연결 해제")
            }
        }
    }
    override fun onListenerConnected() {
        instance = this
        monitor?.close()
        monitor = PlaybackMonitor().also { it.start() }
        if (!noisyRegistered) {
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_EXPORTED)
            noisyRegistered = true
        }
        Store.log("Morphe 재생 감지 서비스 준비")
    }
    override fun onListenerDisconnected() {
        cleanup()
        Runner.cancelAutomatic("알림 접근 연결이 끊겼습니다")
        requestRebind(ComponentName(this, AutoListener::class.java))
    }
    override fun onDestroy() { cleanup(); super.onDestroy() }
    private fun cleanup() {
        instance = null; monitor?.close(); monitor = null
        if (noisyRegistered) { unregisterReceiver(noisy); noisyRegistered = false }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) { if (sbn.packageName == Target.PACKAGE) refresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (sbn.packageName == Target.PACKAGE) refresh() }
    fun refresh(force: Boolean = false) { monitor?.refresh(force) }
    fun suspendCurrent() { monitor?.suspendCurrent() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Store.log("재부팅 후 Morphe 재생 감지 대기")
        NotificationListenerService.requestRebind(ComponentName(context, AutoListener::class.java))
    }
}
