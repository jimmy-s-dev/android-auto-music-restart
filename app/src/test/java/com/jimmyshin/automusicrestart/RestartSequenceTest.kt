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
        var readCost = 0L
        var failRead = -1
        val snapshots = mutableListOf<RestartSequence.Snapshot>()
        val readTimes = mutableListOf<Long>()
        val messages = mutableListOf<String>()
        override fun now() = now
        override fun check() { if (now >= failAt) waitFailure?.let { throw it } }
        override fun waitFor(millis: Long) { now += millis; check() }
        override fun requestPlayback() {
            actions.add("request@$now")
            check(!requestFailure) { "service start failed" }
        }
        override fun snapshot(): RestartSequence.Snapshot {
            readTimes.add(now)
            check(reads != failRead) { "Snapshot unavailable" }
            if (reads++ == 0) return before
            now += readCost
            return if (snapshots.isNotEmpty()) snapshots.removeAt(0) else after
        }
        override fun stopTarget() { actions.add("stop@$now") }
        override fun confirmPlayback(): Any {
            actions.add("confirm@$now")
            check(!finalFailure) { "final playback timed out" }
            return finalToken
        }
        override fun log(message: String) { messages.add(message) }
    }

    @Test fun bufferingStillRestartsAtFiveSeconds() {
        val fake = FakePort()
        RestartSequence(fake).execute()
        assertEquals(listOf("request@0", "stop@5000", "confirm@5750"), fake.actions)
        assertEquals(listOf(5000L, 5500L, 5750L), fake.readTimes)
    }
    @Test fun noInitialSessionStillRestartsAtFiveSeconds() {
        val fake = FakePort().apply { before = RestartSequence.Snapshot(emptySet(), emptySet(), "세션 없음") }
        RestartSequence(fake).execute()
        assertEquals(listOf("request@0", "stop@5000", "confirm@5750"), fake.actions)
    }
    @Test fun playingDoesNotAddAnotherPlaybackWait() {
        val fake = FakePort().apply { before = before.copy(states = "PLAYING") }
        RestartSequence(fake).execute()
        assertEquals(5750L, fake.now)
    }
    @Test fun finalTimeoutFailsWithoutRepeatingStop() {
        val fake = FakePort().apply { finalFailure = true }
        assertThrows(IllegalStateException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0", "stop@5000", "confirm@5750"), fake.actions)
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
        val fake = FakePort().apply { failAt = 5600; waitFailure = CancellationException("disconnected") }
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

    @Test fun processAndSessionMustBothDisappearTwice() {
        val fake = FakePort().apply {
            snapshots.add(after.copy(pids = setOf("10")))
            snapshots.add(after.copy(tokens = setOf("old")))
        }
        RestartSequence(fake).execute()
        assertEquals("confirm@6250", fake.actions.last())
    }
    @Test fun reappearanceResetsConsecutiveEmptyEvidence() {
        val fake = FakePort().apply {
            snapshots.add(after)
            snapshots.add(after.copy(pids = setOf("11")))
            snapshots.add(after)
        }
        RestartSequence(fake).execute()
        assertEquals("confirm@6250", fake.actions.last())
    }
    @Test fun oneEmptySampleAtDeadlineUsesLegacyCheck() {
        val fake = FakePort().apply {
            repeat(6) { snapshots.add(after.copy(tokens = setOf("old"))) }
        }
        RestartSequence(fake).execute()
        assertEquals("confirm@7000", fake.actions.last())
        assertEquals(8, fake.readTimes.size) // One initial and seven bounded post-stop reads.
    }
    @Test fun newSessionOnlyDoesNotAllowEarlyInitialization() {
        val fake = FakePort().apply { after = after.copy(tokens = setOf("new")) }
        RestartSequence(fake).execute()
        assertEquals("confirm@7000", fake.actions.last())
    }
    @Test fun failedSecondReadIsNotAbsence() {
        val fake = FakePort().apply { failRead = 2 }
        assertThrows(IllegalStateException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0", "stop@5000"), fake.actions)
    }
    @Test fun queryTimeCountsTowardDeadline() {
        val fake = FakePort().apply { readCost = 400 }
        RestartSequence(fake).execute()
        assertEquals(listOf(5000L, 5500L, 6150L), fake.readTimes)
        assertEquals("confirm@6550", fake.actions.last())
    }
    @Test fun slowQueryCrossingDeadlineDoesNotStartAnotherQuery() {
        val fake = FakePort().apply { readCost = 1600 }
        RestartSequence(fake).execute()
        assertEquals(listOf(5000L, 5500L), fake.readTimes)
        assertEquals("confirm@7100", fake.actions.last())
    }
    @Test fun slowQueryWithOldProcessStillFails() {
        val fake = FakePort().apply { readCost = 1600; after = before }
        assertThrows(IllegalStateException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0", "stop@5000"), fake.actions)
    }
    @Test fun permissionLossAfterStopDoesNotInitialize() {
        val fake = FakePort().apply { failAt = 5600; waitFailure = SecurityException("revoked") }
        assertThrows(SecurityException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0", "stop@5000"), fake.actions)
    }
    @Test fun cancellationDuringQueryIsCheckedBeforeInitialization() {
        val fake = FakePort().apply { readCost = 400; failAt = 5800; waitFailure = CancellationException("cancel") }
        assertThrows(CancellationException::class.java) { RestartSequence(fake).execute() }
        assertEquals(listOf("request@0", "stop@5000"), fake.actions)
    }
    @Test fun timingMarkersUseMonotonicClockAndReportReturnToInitialization() {
        val fake = FakePort()
        RestartSequence(fake).execute()
        assertEquals(5, fake.messages.count { it.startsWith("시간 측정:") })
        assertTrue(fake.messages.any { it == "시간 측정: 초기화 시작 +750ms, 반환 후 750ms" })
    }
}

