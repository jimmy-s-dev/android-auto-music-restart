package com.jimmyshin.automusicrestart

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var history: TextView
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { update() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 12, 0, 12); layout.addView(this)
        }
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener {
                runCatching(action).onFailure { Store.log("설정 실패: ${it.message}"); Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
                update()
            } })
        }
        text("Morphe 음악 자동 복구", 26f)
        text("화면 초기화 없이 재생하면 예방 재시작\n이력 판별 불가 시 10초 연속 버퍼링을 확인해 복구\nMorphe YouTube Music 전용 · PC 없이 실행")
        status = text("")
        layout.addView(Switch(this).apply {
            text = "자동화 사용"; isChecked = Store.enabled
            setOnCheckedChangeListener { _, checked ->
                Store.enabled = checked
                if (!checked) Runner.cancel("자동화 끄기")
                AutoListener.instance?.refresh(true)
                update()
            }
        })
        button("1. Shizuku 권한 허용") {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(10)
            else startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") ?: error("Shizuku를 설치하세요"))
        }
        button("2. 알림 접근 허용") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        button("3. 배터리 제한 해제") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        button("15초 후 시험 (화면을 잠그세요)") { Runner.start(true, 15000) }
        button("시험 중단") { Runner.cancel("사용자 중단") }
        button("상태 새로고침") { update() }
        text("차량·위젯·이어폰 재생에 자동 적용합니다. 같은 프로세스에서는 한 번만 복구합니다. 재부팅 후 최초 잠금 해제와 신뢰하는 Wi-Fi 연결이 필요할 수 있습니다.")
        history = text("", 13f)
        setContentView(ScrollView(this).apply {
            addView(layout)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        registerReceiver(receiver, IntentFilter("$packageName.REFRESH"), RECEIVER_NOT_EXPORTED)
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled())
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
        update()
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) update() }
    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }
    private fun update() {
        if (!::history.isInitialized) return
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        status.text = "Shizuku: ${if (Bridge.ready()) "준비됨" else "실행·권한 확인 필요"}\n알림 감지: ${if (AutoListener.instance != null) "준비됨" else "접근 허용 필요"}\n배터리 제한: ${if (battery) "해제됨" else "해제 권장"}\n판별: ${Store.prefs.getString("monitorStatus", "준비 중")}\n실행: ${if (Runner.running) "진행 중" else "대기"}"
        history.text = "최근 실행 기록\n" + Store.prefs.getString("log", "아직 실행하지 않았습니다")
    }
}
