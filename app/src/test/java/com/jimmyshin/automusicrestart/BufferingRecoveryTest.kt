package com.jimmyshin.automusicrestart

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BufferingRecoveryTest {
    private class Fake : BufferingRecovery.Port {
        var state = BufferingRecovery.Snapshot("track", listOf("track", "next"), true, false, true, 59000)
        val commands = mutableListOf<String>()
        var now = 0L
        var startPlaying = true
        var afterPause: (() -> Unit)? = null
        var guard: (() -> Unit)? = null
        override fun check() { guard?.invoke() }
        override fun snapshot() = state
        override fun pause() { commands.add("pause"); afterPause?.invoke() }
        override fun seekToStart() { commands.add("seek0"); state = state.copy(positionMs = 0) }
        override fun play() { commands.add("play"); if (startPlaying) state = state.copy(playing = true, buffering = false) }
        override fun waitFor(millis: Long) { now += millis }
    }
    @Test fun rewindsBufferingWithoutWaitingForPaused() {
        val fake = Fake()
        assertEquals(BufferingRecovery.Outcome.PLAYING, BufferingRecovery(fake).execute())
        assertEquals(listOf("pause", "seek0", "play"), fake.commands)
        assertEquals(0, fake.state.positionMs)
    }
    @Test fun attemptIsBoundedAndNeverRepeated() {
        val fake = Fake().apply { startPlaying = false }
        val recovery = BufferingRecovery(fake)
        assertEquals(BufferingRecovery.Outcome.TIMEOUT, recovery.execute())
        assertEquals(15000L, fake.now)
        assertEquals(BufferingRecovery.Outcome.ALREADY_ATTEMPTED, recovery.execute())
        assertEquals(3, fake.commands.size)
    }
    @Test fun playingNeedsNoRecovery() {
        val fake = Fake().apply { state = state.copy(playing = true, buffering = false) }
        assertEquals(BufferingRecovery.Outcome.NOT_BUFFERING, BufferingRecovery(fake).execute())
        assertTrue(fake.commands.isEmpty())
    }
    @Test fun missingTrackOrQueueOrSeekSupportPreventsMutation() {
        for (kind in 0..2) {
            val fake = Fake().apply { state = when (kind) {
                0 -> state.copy(track = null)
                1 -> state.copy(queue = null)
                else -> state.copy(canSeek = false)
            } }
            assertEquals(BufferingRecovery.Outcome.UNSUPPORTED, BufferingRecovery(fake).execute())
            assertTrue(fake.commands.isEmpty())
        }
    }
    @Test fun reorderedQueueStopsBeforeSeek() {
        val fake = Fake().apply { afterPause = { state = state.copy(queue = state.queue!!.reversed()) } }
        assertEquals(BufferingRecovery.Outcome.CONTENT_CHANGED, BufferingRecovery(fake).execute())
        assertEquals(listOf("pause"), fake.commands)
    }
    @Test fun changedTrackStopsBeforeSeek() {
        val fake = Fake().apply { afterPause = { state = state.copy(track = "other") } }
        assertEquals(BufferingRecovery.Outcome.CONTENT_CHANGED, BufferingRecovery(fake).execute())
        assertEquals(listOf("pause"), fake.commands)
    }
    @Test fun cancellationStopsFollowingCommands() {
        val fake = Fake().apply { afterPause = { guard = { throw CancellationException() } } }
        assertThrows(CancellationException::class.java) { BufferingRecovery(fake).execute() }
        assertEquals(listOf("pause"), fake.commands)
    }
    @Test fun permissionLossStopsFollowingCommands() {
        val fake = Fake().apply { afterPause = { guard = { throw SecurityException() } } }
        assertThrows(SecurityException::class.java) { BufferingRecovery(fake).execute() }
        assertEquals(listOf("pause"), fake.commands)
    }
}
