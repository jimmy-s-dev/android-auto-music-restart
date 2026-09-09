package com.jimmyshin.automusicrestart

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RestartSequenceTest {
    private class FakePort : RestartSequence.Port {
        var now = 0L
        val actions = mutableListOf<String>()
        var before = RestartSequence.Snapshot(setOf("10"), setOf("old", "secondary"), "BUFFERING")
        var after = RestartSequence.Snapshot(emptySet(), emptySet(), "세션 없음")
        var finalToken = "new"
        var finalFailure = false
        var requestFailure = false
        var waitFailure: RuntimeException? = null
        var failAt = 5000L
        var reads = 0
        override fun check() { if (now >= failAt) waitFailure?.let { throw it } }
        override fun waitFor(millis: Long) { now += millis; check() }
        override fun requestPlayback() {
            actions.add("request@$now")
            check(!requestFailure) { "service start failed" }
        }
        override fun snapshot() = if (reads++ == 0) before else after
        override fun stopTarget() { actions.add("stop@$now") }
        override fun confirmPlayback(): Any {
            actions.add("confirm@$now")
            check(!finalFailure) { "final playback timed out" }
            return finalToken
        }
        override fun log(message: String) = Unit
    }

    @Test fun bufferingStillRestartsAtFiveSeconds() {
        val fake = FakePort()
        RestartSequence(fake).execute()
        assertEquals(listOf("request@0", "stop@5000", "confirm@7000"), fake.actions)
    }
    @Test fun noInitialSessionStillRestartsAtFiveSeconds() {
        val fake = FakePort().apply { before = RestartSequence.Snapshot(emptySet(), emptySet(), "세션 없음") }
        RestartSequence(fake).execute()
        assertEquals(listOf("request@0", "stop@5000", "confirm@7000"), fake.actions)
    }
    @Test fun playingDoesNotAddAnotherPlaybackWait() {
        val fake = FakePort().apply { before = before.copy(states = "PLAYING") }
        RestartSequence(fake).execute()
        assertEquals(7000L, fake.now)
    }
    @Test fun finalTimeoutFailsWithoutRepeatingStop() {
        val fake = FakePort().apply { finalFailure = true }
        assertThrows(IllegalStateException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0", "stop@5000", "confirm@7000"), fake.actions)
    }
    @Test fun cancellationPreventsStop() {
        val fake = FakePort().apply { waitFailure = CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0"), fake.actions)
    }
    @Test fun permissionLossPreventsStop() {
        val fake = FakePort().apply { waitFailure = SecurityException("permission revoked") }
        assertThrows(SecurityException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0"), fake.actions)
    }
    @Test fun cancellationAfterStopPreventsRestart() {
        val fake = FakePort().apply { failAt = 7000; waitFailure = CancellationException("disconnected") }
        assertThrows(CancellationException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0", "stop@5000"), fake.actions)
    }
    @Test fun serviceErrorDoesNotForceStop() {
        val fake = FakePort().apply { requestFailure = true }
        assertThrows(IllegalStateException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0"), fake.actions)
    }
    @Test fun oldProcessPreventsRestart() {
        val fake = FakePort().apply { after = after.copy(pids = setOf("10", "11")) }
        assertThrows(IllegalStateException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0", "stop@5000"), fake.actions)
    }
    @Test fun anyOldSessionPreventsRestart() {
        val fake = FakePort().apply { after = after.copy(tokens = setOf("secondary")) }
        assertThrows(IllegalStateException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0", "stop@5000"), fake.actions)
    }
    @Test fun externallyRestartedNewProcessIsAllowed() {
        val fake = FakePort().apply { after = after.copy(pids = setOf("11"), tokens = setOf("new")) }
        RestartSequence(fake).execute()
        assertEquals("confirm@7000", fake.actions.last())
    }
    @Test fun finalSessionCannotReuseOldToken() {
        val fake = FakePort().apply { finalToken = "secondary" }
        assertThrows(IllegalStateException::class.java) { RestartSequence(fake).execute() }
    }
}

