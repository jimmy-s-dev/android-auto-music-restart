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
        text("차량 음악 재시작", 26f)
        text("연결 후 5초 대기 → 5초 재생 → 종료 → 2초 후 재생\nMorphe YouTube Music 전용 · PC 없이 실행")
        status = text("")
        layout.addView(Switch(this).apply {
            text = "자동화 사용"; isChecked = Store.enabled
            setOnCheckedChangeListener { _, checked ->
                if (checked && Store.prefs.getString("fingerprint", "").isNullOrBlank()) {
                    isChecked = false
                    Toast.makeText(this@MainActivity, "차량에 연결한 뒤 연결 알림을 먼저 등록하세요", Toast.LENGTH_LONG).show()
                } else {
                    Store.enabled = checked
                    if (!checked) Runner.cancel("자동화 끄기") else AutoListener.instance?.refresh()
                    update()
                }
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
        button("4. 차량 연결 알림 등록") { registerConnection() }
        button("15초 후 시험 (화면을 잠그세요)") { Runner.start(true, 15000) }
        button("시험 중단") { Runner.cancel("사용자 중단") }
        button("상태 새로고침") { update() }
        text("정차한 차량에서 Android Auto에 연결한 뒤 연결 알림을 등록하고 자동화를 켜세요. 재부팅 후에는 최초 잠금 해제와 신뢰하는 Wi-Fi 연결이 필요할 수 있습니다.")
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
        status.text = "Shizuku: ${if (Bridge.ready()) "준비됨" else "실행·권한 확인 필요"}\n알림 감지: ${if (AutoListener.instance != null) "준비됨" else "접근 허용 필요"}\n배터리 제한: ${if (battery) "해제됨" else "해제 권장"}\n연결 알림: ${Store.prefs.getString("registeredLabel", "미등록 — 실차에서 등록")}\n실행: ${if (Runner.running) "진행 중" else "대기"}"
        history.text = "최근 실행 기록\n" + Store.prefs.getString("log", "아직 실행하지 않았습니다")
    }
    private fun registerConnection() {
        val candidates = AutoListener.instance?.candidates().orEmpty()
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this).setMessage("Android Auto의 지속 알림이 없습니다. 정차한 차량에 실제로 연결한 뒤 다시 눌러 주세요.")
                .setPositiveButton("확인", null).show(); return
        }
        AlertDialog.Builder(this).setTitle("실제로 차량에 연결되었음을 나타내는 알림 선택")
            .setItems(candidates.map { AutoListener.label(it) }.toTypedArray()) { _, index ->
                val n = candidates[index]
                AlertDialog.Builder(this).setTitle("연결 완료 알림 등록")
                    .setMessage("${AutoListener.label(n)}\n\n연결 중·설정 안내 알림은 등록하지 마세요. 이 알림과 동일한 연결 완료 알림이 나타날 때만 실행합니다.")
                    .setPositiveButton("등록") { _, _ ->
                        Store.prefs.edit().putString("fingerprint", AutoListener.fingerprint(n))
                            .putString("registeredLabel", AutoListener.label(n)).commit()
                        Store.log("차량 연결 알림 등록 완료"); update()
                    }.setNegativeButton("취소", null).show()
            }.setNegativeButton("취소", null).show()
    }
}
