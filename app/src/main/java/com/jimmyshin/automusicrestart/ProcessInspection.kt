package com.jimmyshin.automusicrestart

import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ProcessInspection {
    fun encode(value: ProcessHistory): String = JSONObject().apply {
        put("key", value.key ?: JSONObject.NULL); put("activity", value.activity.name); put("reason", value.reason)
    }.toString()
    fun decode(value: String): ProcessHistory = runCatching {
        val json = JSONObject(value)
        ProcessHistory(if (json.isNull("key")) null else json.getString("key"),
            ActivityHistory.valueOf(json.getString("activity")), json.getString("reason"))
    }.getOrElse { ProcessHistory.unknown("이력 조회 응답 해석 실패") }

    /** Shell identity only; bounded read, no raw dump returned to the app or log. */
    fun inspect(): ProcessHistory = runCatching {
        val bit = runCatching { Class.forName("android.app.ProcessMemoryState")
            .getField("HOSTING_COMPONENT_TYPE_ACTIVITY").getInt(null) }.getOrNull()
        val boot = File("/proc/sys/kernel/random/boot_id").readText().trim()
        val process = ProcessBuilder("/system/bin/dumpsys", "activity", "processes", Target.PACKAGE)
            .redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val output = reader.submit<String> {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    val text = StringBuilder()
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(text.length + count <= 1024 * 1024) { "Process dump too large" }
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }
            }
            val dump = output.get(8, TimeUnit.SECONDS)
            check(process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0)
            ProcessHistoryParser.parse(dump, boot, bit)
        } finally { process.destroyForcibly(); reader.shutdownNow() }
    }.getOrElse { ProcessHistory.unknown("시스템 프로세스 이력 조회 실패") }
}
