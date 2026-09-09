package com.jimmyshin.automusicrestart

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import java.security.MessageDigest

/** Diagnostic APK only. Track and queue identities never appear in clear text in output. */
class PlaybackProbe(private val context: Context) {
    private fun controllers() = context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(ComponentName(context, AutoListener::class.java)).filter { it.packageName == Target.PACKAGE }
    private fun controller(): MediaController = controllers().single()
    private fun hash(value: String?) = value?.let { text -> MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16) } ?: "missing"
    fun snapshot(): BufferingRecovery.Snapshot {
        val c = controller()
        val s = c.playbackState
        val queue = c.queue
        // YT Music publishes queue IDs and descriptions but no MEDIA_ID on this device.
        // These identities are valid within the same session; recover() also checks the session token.
        val ids = queue?.map { item ->
            val d = item.description
            if (d.mediaId.isNullOrBlank() && d.title.isNullOrBlank()) ""
            else listOf(item.queueId, d.mediaId, d.title, d.subtitle, d.description, d.mediaUri).joinToString("\u0000")
        }
        val metadata = c.metadata
        val metadataIdentity = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let {
            listOf(it, metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM), metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
                .joinToString("\u0000")
        }
        val track = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?: queue?.indexOfFirst { it.queueId == s?.activeQueueItemId }?.takeIf { it >= 0 }?.let { ids?.get(it) }
            ?: metadataIdentity
        return BufferingRecovery.Snapshot(track, ids?.takeIf { it.isNotEmpty() && it.all(String::isNotBlank) },
            (s?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L,
            s?.state == PlaybackState.STATE_PLAYING, s?.state == PlaybackState.STATE_BUFFERING, s?.position ?: -1)
    }
    fun describe(includeMetadataKeys: Boolean = true): String {
        val c = controller()
        val s = c.playbackState
        val sample = snapshot()
        val item = c.queue.orEmpty().singleOrNull { it.queueId == s?.activeQueueItemId }
        return "state=${s?.state}, position=${s?.position}, update=${s?.lastPositionUpdateTime}, " +
            "actions=${s?.actions}, track=${hash(sample.track)}, queueSize=${sample.queue?.size}, " +
            "queueHash=${hash(sample.queue?.joinToString("\u0000"))}, token=${hash(c.sessionToken.toString())}, " +
            "metadataKeys=${if (includeMetadataKeys) c.metadata?.keySet()?.sorted() else "omitted"}, " +
            "itemMediaId=${!item?.description?.mediaId.isNullOrBlank()}, itemUri=${item?.description?.mediaUri != null}, " +
            "itemExtraKeys=${item?.description?.extras?.keySet()?.sorted()}"
    }
    fun recover(): BufferingRecovery.Outcome {
        val c = controller()
        return BufferingRecovery(object : BufferingRecovery.Port {
            override fun check() {
                check(Bridge.ready()) { "Shizuku unavailable" }
                check(AutoListener.instance != null) { "Listener unavailable" }
                check(controller().sessionToken == c.sessionToken) { "Target session changed" }
            }
            override fun snapshot() = this@PlaybackProbe.snapshot()
            override fun pause() = c.transportControls.pause()
            override fun seekToStart() = c.transportControls.seekTo(0)
            override fun play() = c.transportControls.play()
            override fun waitFor(millis: Long) { Thread.sleep(millis) }
        }).execute()
    }

    /** One diagnostic reload of the already selected queue item; never selects a different item. */
    fun reloadCurrentItem(): String {
        val c = controller()
        val before = snapshot()
        val state = checkNotNull(c.playbackState)
        val itemId = state.activeQueueItemId
        check(before.buffering && !before.track.isNullOrBlank() && !before.queue.isNullOrEmpty()) {
            "Buffering content identity unavailable"
        }
        check(itemId != android.media.session.MediaSession.QueueItem.UNKNOWN_ID.toLong() &&
            c.queue.orEmpty().count { it.queueId == itemId } == 1) { "Current queue item is ambiguous" }
        check(state.actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM != 0L) { "Queue item reload unsupported" }
        fun guard() {
            check(Bridge.ready() && AutoListener.instance != null) { "Required permission unavailable" }
            check(controller().sessionToken == c.sessionToken) { "Target session changed" }
            val current = snapshot()
            check(current.track == before.track && current.queue == before.queue) { "Track or queue changed" }
        }
        guard()
        c.transportControls.skipToQueueItem(itemId)
        val until = SystemClock.elapsedRealtime() + 15000
        do {
            guard()
            if (snapshot().playing) return "PLAYING"
            Thread.sleep(250)
        } while (SystemClock.elapsedRealtime() < until)
        return "TIMEOUT"
    }

    /** Reset the existing player without force-stop, Activity launch, seeking or changing queue items. */
    fun stopAndPlay(report: (String) -> Unit): String {
        val c = controller()
        val before = snapshot()
        check(before.buffering && !before.track.isNullOrBlank() && !before.queue.isNullOrEmpty()) {
            "Buffering content identity unavailable"
        }
        val actions = c.playbackState?.actions ?: 0L
        check(actions and PlaybackState.ACTION_STOP != 0L && actions and PlaybackState.ACTION_PLAY != 0L) {
            "Stop/play unsupported"
        }
        fun guard() {
            check(Bridge.ready() && AutoListener.instance != null) { "Required permission unavailable" }
            check(controller().sessionToken == c.sessionToken) { "Target session changed" }
            val current = snapshot()
            check(current.track == before.track && current.queue == before.queue) { "Track or queue changed" }
        }
        guard()
        report("STOP requested")
        c.transportControls.stop()
        Thread.sleep(500)
        guard()
        report("After STOP: ${describe()}")
        c.transportControls.play()
        val until = SystemClock.elapsedRealtime() + 15000
        do {
            guard()
            if (snapshot().playing) return "PLAYING"
            Thread.sleep(250)
        } while (SystemClock.elapsedRealtime() < until)
        return "TIMEOUT"
    }

    /** Browse root only. Does not issue transport controls or open an Activity. */
    fun browseRoot(report: (String) -> Unit): String {
        val done = java.util.concurrent.CountDownLatch(1)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var outcome = "TIMEOUT"
        lateinit var browser: android.media.browse.MediaBrowser
        main.post {
            browser = android.media.browse.MediaBrowser(context, ComponentName(Target.PACKAGE, Target.BROWSER),
                object : android.media.browse.MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        report("Connected; root=${browser.root}; extras=${browser.extras?.keySet()?.sorted()}")
                        browser.subscribe(browser.root, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                            override fun onChildrenLoaded(parentId: String, children: MutableList<android.media.browse.MediaBrowser.MediaItem>) {
                                children.take(20).forEach { item ->
                                    report("Root item: title=${item.description.title}; id=${item.mediaId}; playable=${item.isPlayable}; browsable=${item.isBrowsable}")
                                }
                                outcome = "CONNECTED"
                                done.countDown()
                            }
                            override fun onError(parentId: String) { outcome = "ROOT_ERROR"; done.countDown() }
                        })
                    }
                    override fun onConnectionFailed() { outcome = "CONNECTION_REJECTED"; done.countDown() }
                    override fun onConnectionSuspended() { outcome = "CONNECTION_SUSPENDED"; done.countDown() }
                }, null)
            browser.connect()
        }
        try { done.await(15, java.util.concurrent.TimeUnit.SECONDS) }
        finally { main.post { runCatching { browser.disconnect() } } }
        return outcome
    }

    fun observe(seconds: Int, requireQueue: Boolean = true, report: (String) -> Unit) {
        val initialController = controller()
        val before = snapshot()
        check(!before.track.isNullOrBlank() && (!requireQueue || !before.queue.isNullOrEmpty())) { "Cannot verify content identity" }
        if (before.queue.isNullOrEmpty()) report("Queue not published; this observation verifies track and session only")
        val until = SystemClock.elapsedRealtime() + seconds * 1000L
        var stalledSince: Long? = null
        while (SystemClock.elapsedRealtime() < until) {
            val now = SystemClock.elapsedRealtime()
            val s = snapshot()
            report(describe(false))
            check(controller().sessionToken == initialController.sessionToken) { "Target session changed during observation" }
            check(s.track == before.track && s.queue == before.queue) { "Track or queue changed" }
            if (s.playing) stalledSince = null else if (stalledSince == null) stalledSince = now
            check(stalledSince == null || now - stalledSince < 5000) { "Playback stopped progressing" }
            Thread.sleep(2000)
        }
        val after = snapshot()
        // Framework-reported positions may be extrapolated. Treat this as a session-state check;
        // device verification also needs player events/audio output, not this value alone.
        check(after.playing && after.positionMs >= before.positionMs + (seconds - 10).coerceAtLeast(1) * 1000L) {
            "Insufficient reported playback progress over ${seconds}s: ${after.positionMs - before.positionMs}ms"
        }
    }

    /** Allows a natural next-track transition; requires first track >=185s and next >=90s. */
    fun observeTwoTracks(report: (String) -> Unit) {
        val initial = controller()
        val firstTrack = checkNotNull(PlaybackMonitor.track(initial))
        val start = SystemClock.elapsedRealtime()
        var secondTrack: String? = null
        var secondStart: Long? = null
        var stalled: Long? = null
        var lastReport = 0L
        var candidate: String? = null
        var candidateSince = start
        while (SystemClock.elapsedRealtime() - start < 15 * 60000) {
            val now = SystemClock.elapsedRealtime()
            val current = controller()
            check(current.sessionToken == initial.sessionToken) { "Session replaced during observation" }
            val track = checkNotNull(PlaybackMonitor.track(current))
            val playing = current.playbackState?.state == PlaybackState.STATE_PLAYING
            if (!playing || candidate != track) { candidate = if (playing) track else null; candidateSince = now }
            val stable = playing && now - candidateSince >= 3000
            if (stable && track != firstTrack && secondTrack == null) {
                check(now - start >= 185000) { "First track changed before 185s; cannot count fixed-track pass" }
                secondTrack = track; secondStart = now
            }
            if (stable && secondTrack != null) check(track == secondTrack) { "Second track changed before 90s" }
            if (playing) stalled = null else if (stalled == null) stalled = now
            check(stalled == null || now - checkNotNull(stalled) < 10000) { "Continuous playback interruption >=10s" }
            if (now - lastReport >= 10000) {
                report("elapsed=${now-start}, nextElapsed=${secondStart?.let { now-it }}, ${describe(false)}")
                lastReport = now
            }
            if (secondStart != null && now - secondStart >= 90000 && playing && track == secondTrack) return
            Thread.sleep(1000)
        }
        error("No complete first/next track observation within 15 minutes")
    }
}
