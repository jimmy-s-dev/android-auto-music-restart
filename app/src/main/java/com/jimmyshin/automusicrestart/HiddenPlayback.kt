package com.jimmyshin.automusicrestart

import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/** Shell UserService only. No surface is attached to the phone, PC, or vehicle display. */
class HiddenPlayback(private val command: (Array<String>) -> String) {
    companion object {
        const val DISPLAY_NAME = "AutoMusicRestart-initialization"
    }
    private val context: Context by lazy {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            ?: threadClass.getMethod("systemMain").invoke(null)
        val system = threadClass.getMethod("getSystemContext").invoke(thread) as Context
        system.createPackageContext("com.android.shell", 0)
    }
    private val sessions: MediaSessionManager by lazy {
        // app_process does not initialize this mainline-module service manager itself.
        val initializer = Class.forName("android.media.MediaFrameworkPlatformInitializer")
        if (initializer.getMethod("getMediaServiceManager").invoke(null) == null) {
            val manager = Class.forName("android.media.MediaServiceManager")
            initializer.getMethod("setMediaServiceManager", manager)
                .invoke(null, manager.getConstructor().newInstance())
        }
        context.getSystemService(MediaSessionManager::class.java)
    }
    private fun controllers() = sessions.getActiveSessions(null).filter { it.packageName == Target.PACKAGE }
    data class Content(val track: List<String?>, val queue: List<List<String?>>?)
    private fun content(controller: MediaController): Content? {
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val queue = controller.queue?.takeIf { it.isNotEmpty() }?.map {
            val item = it.description
            // Queue IDs are session-local and change on a normal Activity restart.
            listOf(item.mediaId, item.title?.toString(), item.subtitle?.toString(),
                item.description?.toString(), item.mediaUri?.toString())
        }
        return Content(listOf(title, metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).toString()), queue)
    }
    fun capture(): Content? {
        val list = controllers()
        check(list.size <= 1) { "대상 세션이 여러 개여서 곡을 안전하게 확인할 수 없습니다" }
        return list.singleOrNull()?.let(::content)
    }
    private fun verifyContent(controller: MediaController, before: Content?): String {
        if (before == null) return "이전 곡 정보 없음: 곡·목록 비교 불가"
        val after = content(controller)
        check(after != null && before.track == after.track) {
            "현재 곡이 재시작 전과 달라 재생을 중단했습니다"
        }
        // User explicitly prioritizes the current song and permits Morphe to rebuild its upcoming queue.
        return "현재 곡 동일, 목록=${if (before.queue == null) "비교 불가" else if (before.queue == after.queue) "동일" else "재구성 허용"}, 개수=${after.queue?.size}"
    }
    private fun verifyPlacement(expected: Int) {
        val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val tasks = Class.forName("android.app.IActivityTaskManager")
            .getMethod("getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(service, 100, false, false, -1) as List<*>
        val targetDisplays = tasks.filterNotNull().mapNotNull { task ->
            val top = task.javaClass.getField("topActivity").get(task) as? ComponentName
            val base = task.javaClass.getField("baseActivity").get(task) as? ComponentName
            if (top?.packageName == Target.PACKAGE || base?.packageName == Target.PACKAGE)
                task.javaClass.getField("displayId").getInt(task) else null
        }
        if (targetDisplays.any { it != expected }) {
            command(arrayOf("/system/bin/am", "force-stop", "--user", "0", Target.PACKAGE))
            error("음악 앱이 지정한 가상 화면 밖으로 이동해 중단했습니다")
        }
        check(expected in targetDisplays) { "가상 화면에서 음악 앱 초기화를 확인하지 못했습니다" }
    }
    fun initialize(before: Content?, startService: () -> String): String {
        var display: VirtualDisplay? = null
        var reader: ImageReader? = null
        var frames: HandlerThread? = null
        var displayId = -1
        var description = ""
        var contentDescription = ""
        HiddenInitSequence(object : HiddenInitSequence.Port {
            // Executed only in Shizuku's shell app_process, where hidden-API access is allowed.
            // Unsupported OS signatures fail before Activity launch; never retry on the default display.
            @android.annotation.SuppressLint("BlockedPrivateApi", "SoonBlockedPrivateApi")
            override fun create() {
                // Require all flags before allocating or launching anything. Never fall back to display 0.
                val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager::class.java.getDeclaredField("VIRTUAL_DISPLAY_FLAG_TRUSTED").getInt(null) or
                    DisplayManager::class.java.getDeclaredField("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL").getInt(null)
                frames = HandlerThread("music-init-frame-drain").also { it.start() }
                reader = ImageReader.newInstance(480, 800, PixelFormat.RGBA_8888, 2).also { imageReader ->
                    imageReader.setOnImageAvailableListener({ source ->
                        // close() can race with an already queued callback during teardown.
                        runCatching { source.acquireLatestImage()?.close() }
                    }, Handler(checkNotNull(frames).looper))
                }
                display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                    DISPLAY_NAME, 480, 800, 160, checkNotNull(reader).surface, flags)
                displayId = checkNotNull(display) { "가상 화면 생성 실패" }.display.displayId
                check(displayId > 0) { "잘못된 가상 화면 ID" }
            }
            override fun launch() {
                command(arrayOf("/system/bin/am", "start", "--user", "0", "--display", displayId.toString(),
                    "-n", "${Target.PACKAGE}/${Target.ACTIVITY}"))
            }
            override fun waitFor(millis: Long) = Thread.sleep(millis)
            override fun verifyPlacement() = this@HiddenPlayback.verifyPlacement(displayId)
            override fun playAndConfirm() {
                description = startService()
                val sessionDeadline = SystemClock.elapsedRealtime() + 15000
                var list = controllers()
                while (list.isEmpty() && SystemClock.elapsedRealtime() < sessionDeadline) {
                    Thread.sleep(200); list = controllers()
                }
                check(list.size == 1) { "가상 초기화 후 대상 세션 생성 실패(15초): ${list.size}" }
                val controller = list.single()
                controller.transportControls.play()
                val playDeadline = SystemClock.elapsedRealtime() + 15000
                while (controller.playbackState?.state != PlaybackState.STATE_PLAYING && SystemClock.elapsedRealtime() < playDeadline)
                    Thread.sleep(200)
                check(controller.playbackState?.state == PlaybackState.STATE_PLAYING) { "가상 초기화 후 재생 확인 실패(15초)" }
                try {
                    contentDescription = verifyContent(controller, before)
                } catch (failure: IllegalStateException) {
                    controller.transportControls.pause()
                    throw failure
                }
            }
            override fun release() {
                try { display?.release() }
                finally {
                    try { reader?.close() }
                    finally { frames?.quitSafely() }
                }
            }
        }).execute()
        return "가상 화면=$displayId 초기화·제거 완료, $contentDescription, $description"
    }
}
