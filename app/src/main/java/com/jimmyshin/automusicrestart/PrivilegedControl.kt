package com.jimmyshin.automusicrestart

import android.content.ComponentName
import android.content.Intent
import android.view.KeyEvent
import java.util.concurrent.TimeUnit

/** Fixed target-only operations. No caller-supplied shell or package names. */
class PrivilegedControl : IControl.Stub() {
    private val hidden = HiddenPlayback { command(*it) }
    private var initializeNext = false
    private var before: HiddenPlayback.Content? = null
    private fun command(vararg args: String, allowEmpty: Boolean = false): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("명령 응답 시간 초과")
        }
        val result = process.inputStream.bufferedReader().use { it.readText().take(4096) }
        if (process.exitValue() != 0 && !(allowEmpty && result.isBlank()))
            throw IllegalStateException("명령 실패 ${process.exitValue()}: $result")
        return result.trim()
    }
    override fun inspect(): String = command("/system/bin/pidof", Target.PACKAGE, allowEmpty = true)
    override fun inspectHistory(): String = ProcessInspection.encode(ProcessInspection.inspect())
    override fun stopTarget(): String {
        before = hidden.capture()
        val result = command("/system/bin/am", "force-stop", "--user", "0", Target.PACKAGE)
        initializeNext = true
        return result
    }
    override fun preparePlayback(): String {
        if (!initializeNext) return startService()
        initializeNext = false // Exactly one initialization attempt per stop; no retry loop.
        return hidden.initialize(before, ::startService)
    }
    private fun startService(): String {
        // UserService runs as shell. Use its real package identity and a null app thread,
        // rather than a normal app Context which is invalid in a Shizuku UserService.
        val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val method = Class.forName("android.app.IActivityManager").methods.single {
            it.name == "startService" && it.parameterTypes.size == 7
        }
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(Target.PACKAGE, Target.BROWSER))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        val component = method.invoke(manager, null, intent, null, true, "com.android.shell", null, 0) as? ComponentName
        check(component?.packageName == Target.PACKAGE) { "대상 재생 서비스 시작 실패: $component" }
        return component.flattenToShortString()
    }
    override fun destroy() { kotlin.system.exitProcess(0) }
}
