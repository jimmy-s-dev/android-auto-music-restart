package com.jimmyshin.automusicrestart

import org.junit.Assert.*
import org.junit.Test

class PreventivePolicyTest {
    private val boot = "12345678-1234-1234-1234-123456789abc"
    private fun dump(history: String = "0x3a0", pid: Int = 10, seq: Int = 20) = """
  *APP* UID 10664 ProcessRecord{abc $pid:${Target.PACKAGE}/u0a664}
    pid=$pid
    startSeq=$seq
    currentHostingComponentTypes=0x380 historicalHostingComponentTypes=$history
  Process LRU list:
    unrelated
"""
    @Test fun serviceOnlyHistoryIsAbsent() {
        val value = ProcessHistoryParser.parse(dump(), boot, 0x10)
        assertEquals(ActivityHistory.ABSENT, value.activity)
        assertEquals("$boot:10:20", value.key)
    }
    @Test fun historicalActivitySurvivesNoCurrentActivity() {
        assertEquals(ActivityHistory.PRESENT, ProcessHistoryParser.parse(dump("0x3b0"), boot, 0x10).activity)
    }
    @Test fun samsungUiHistoryOverridesMissingActivityBit() {
        val observed = dump().replace("    startSeq=20", "    startSeq=20\n    hasShownUi=true pendingUiClean=false")
        assertEquals(ActivityHistory.PRESENT, ProcessHistoryParser.parse(observed, boot, 0x10).activity)
    }
    @Test fun missingFieldIsUnknownNotAbsent() {
        val value = ProcessHistoryParser.parse(dump().replace("historicalHostingComponentTypes", "missing"), boot, 0x10)
        assertEquals(ActivityHistory.UNKNOWN, value.activity)
        assertNotNull(value.key)
    }
    @Test fun noPlatformConstantIsUnknown() {
        assertEquals(ActivityHistory.UNKNOWN, ProcessHistoryParser.parse(dump(), boot, null).activity)
    }
    @Test fun mismatchedPidInvalidatesIdentity() {
        assertNull(ProcessHistoryParser.parse(dump().replace("pid=10", "pid=11"), boot, 0x10).key)
    }
    @Test fun missingStartSequenceInvalidatesIdentity() {
        assertNull(ProcessHistoryParser.parse(dump().replace("startSeq=20", "other=20"), boot, 0x10).key)
    }
    @Test fun additionalProcessMakesHistoryUnknown() {
        val extra = dump().replace("${Target.PACKAGE}/", "${Target.PACKAGE}:worker/")
        assertEquals(ActivityHistory.UNKNOWN, ProcessHistoryParser.parse(dump() + extra, boot, 0x10).activity)
    }
    @Test fun unrelatedBlocksCannotSupplyHistory() {
        val own = dump().replace("historicalHostingComponentTypes", "missing")
        val unrelated = dump("0x3b0").replace(Target.PACKAGE, "another.app")
        assertEquals(ActivityHistory.UNKNOWN, ProcessHistoryParser.parse(own + unrelated, boot, 0x10).activity)
    }
    @Test fun malformedBootCannotCreateReusableKey() {
        assertNull(ProcessHistoryParser.parse(dump(), "", 0x10).key)
    }
    @Test fun pausedAndStoppedNeverTrigger() {
        val gate = PlaybackGate()
        gate.observe("s", "song", PlaybackCondition.INACTIVE, 0)
        ActivityHistory.entries.forEach { assertNull(gate.decide(it, 50000)) }
    }
    @Test fun coldPlayingStartsPreventiveButWarmPlayingDoesNot() {
        val gate = PlaybackGate()
        gate.observe("s", "song", PlaybackCondition.PLAYING, 0)
        assertEquals(RestartReason.PREVENTIVE, gate.decide(ActivityHistory.ABSENT, 0))
        assertNull(gate.decide(ActivityHistory.PRESENT, 0))
        assertNull(gate.decide(ActivityHistory.UNKNOWN, 50000))
    }
    @Test fun tenSecondsRequiredForUnknown() {
        val gate = PlaybackGate()
        gate.observe("s", "song", PlaybackCondition.BUFFERING, 500)
        assertNull(gate.decide(ActivityHistory.UNKNOWN, 10499))
        assertEquals(RestartReason.BUFFERING, gate.decide(ActivityHistory.UNKNOWN, 10500))
    }
    @Test fun duplicateEventsDoNotResetBufferTimer() {
        val gate = PlaybackGate()
        gate.observe("s", "song", PlaybackCondition.BUFFERING, 0)
        gate.observe("s", "song", PlaybackCondition.BUFFERING, 9000)
        assertEquals(RestartReason.BUFFERING, gate.decide(ActivityHistory.UNKNOWN, 10000))
    }
    @Test fun naturalRecoveryResetsTimer() {
        val gate = PlaybackGate()
        gate.observe("s", "song", PlaybackCondition.BUFFERING, 0)
        gate.observe("s", "song", PlaybackCondition.PLAYING, 9000)
        gate.observe("s", "song", PlaybackCondition.BUFFERING, 9500)
        assertNull(gate.decide(ActivityHistory.UNKNOWN, 10000))
    }
    @Test fun trackAndSessionChangesResetTimer() {
        val gate = PlaybackGate()
        gate.observe("s", "song1", PlaybackCondition.BUFFERING, 0)
        gate.observe("s", "song2", PlaybackCondition.BUFFERING, 9000)
        assertNull(gate.decide(ActivityHistory.UNKNOWN, 10000))
        assertEquals(RestartReason.BUFFERING, gate.decide(ActivityHistory.UNKNOWN, 19000))
        gate.observe("s2", "song2", PlaybackCondition.BUFFERING, 20000)
        assertNull(gate.decide(ActivityHistory.UNKNOWN, 21000))
    }
    @Test fun pauseResetsTimer() {
        val gate = PlaybackGate()
        gate.observe("s", "song", PlaybackCondition.BUFFERING, 0)
        gate.observe("s", "song", PlaybackCondition.INACTIVE, 9000)
        assertNull(gate.bufferingSince)
    }
    @Test fun originalAndSuccessorAreBothConsumed() {
        val ledger = AttemptLedger()
        ledger.begin("boot:10:1")
        ledger.finish("boot:11:2")
        assertFalse(ledger.allowed("boot:10:1"))
        assertFalse(ledger.allowed("boot:11:2"))
        assertTrue(ledger.allowed("boot:10:3")) // PID reuse.
        assertTrue(ledger.allowed("newboot:11:2"))
    }
    @Test fun connectionFailureBeforeForceStopCannotRetryAfterAppRestart() {
        val ledger = AttemptLedger()
        ledger.reserve("boot:10:1")
        val restored = AttemptLedger(ledger.attempted.toMutableSet(), ledger.pending, ledger.quarantine)
        assertFalse(restored.allowed("boot:10:1"))
        assertTrue(restored.allowed("boot:11:2"))
        assertNull(restored.pending)
    }
    @Test fun crashQuarantinesFirstObservedProcessUntilReplacement() {
        val ledger = AttemptLedger(linkedSetOf("old"), "old", null)
        assertFalse(ledger.allowed("unknown-successor"))
        val restored = AttemptLedger(ledger.attempted.toMutableSet(), ledger.pending, ledger.quarantine)
        assertFalse(restored.allowed("unknown-successor"))
        assertTrue(restored.allowed("later-process"))
    }
    @Test fun unidentifiedSuccessorDoesNotClearPending() {
        val ledger = AttemptLedger()
        ledger.begin("original"); ledger.finish(null)
        assertEquals("original", ledger.pending)
        assertFalse(ledger.allowed("successor"))
    }
    @Test fun manualCanClearQuarantine() {
        val ledger = AttemptLedger(linkedSetOf("original"), "original", "quarantined")
        ledger.resetPending()
        assertTrue(ledger.allowed("quarantined"))
        assertFalse(ledger.allowed("original"))
    }
}
