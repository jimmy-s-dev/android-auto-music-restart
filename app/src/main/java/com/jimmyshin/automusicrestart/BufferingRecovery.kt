package com.jimmyshin.automusicrestart

/** Experimental target-only recovery. Not enabled in the automatic sequence until device validation passes. */
class BufferingRecovery(private val port: Port) {
    data class Snapshot(val track: String?, val queue: List<String>?, val canSeek: Boolean,
        val playing: Boolean, val buffering: Boolean, val positionMs: Long)
    enum class Outcome { PLAYING, NOT_BUFFERING, UNSUPPORTED, CONTENT_CHANGED, TIMEOUT, ALREADY_ATTEMPTED }
    interface Port {
        fun check()
        fun snapshot(): Snapshot
        fun pause()
        fun seekToStart()
        fun play()
        fun waitFor(millis: Long)
    }
    private var attempted = false

    fun execute(): Outcome {
        if (attempted) return Outcome.ALREADY_ATTEMPTED
        attempted = true
        port.check()
        val before = port.snapshot()
        if (!before.buffering) return Outcome.NOT_BUFFERING
        if (!before.canSeek || before.track.isNullOrBlank() || before.queue.isNullOrEmpty())
            return Outcome.UNSUPPORTED
        fun unchanged() = port.snapshot().let { it.track == before.track && it.queue == before.queue }
        port.check()
        port.pause()
        port.check()
        if (!unchanged()) return Outcome.CONTENT_CHANGED
        port.seekToStart()
        port.check()
        if (!unchanged()) return Outcome.CONTENT_CHANGED
        port.play()
        repeat(60) {
            port.check()
            val state = port.snapshot()
            if (state.track != before.track || state.queue != before.queue) return Outcome.CONTENT_CHANGED
            if (state.playing) return Outcome.PLAYING
            port.waitFor(250)
        }
        port.check()
        val after = port.snapshot()
        if (after.track != before.track || after.queue != before.queue) return Outcome.CONTENT_CHANGED
        return if (after.playing) Outcome.PLAYING else Outcome.TIMEOUT
    }
}
