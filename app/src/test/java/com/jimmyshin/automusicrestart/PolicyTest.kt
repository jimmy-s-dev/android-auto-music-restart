package com.jimmyshin.automusicrestart

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun notificationUpdatesDoNotRestartMusic() {
        val gate = ConnectionGate()
        assertTrue(gate.observe(true, true))
        repeat(20) { assertFalse(gate.observe(true, true)) }
    }
    @Test fun disconnectedSessionCanRunOnceAgain() {
        val gate = ConnectionGate(true)
        assertFalse(gate.observe(false, true))
        assertTrue(gate.observe(true, true))
        assertFalse(gate.observe(true, true))
    }
    @Test fun disabledAndUnregisteredStatesNeverTrigger() {
        val gate = ConnectionGate()
        assertFalse(gate.observe(true, false))
        assertFalse(gate.observe(false, true))
        assertTrue(gate.observe(true, true))
    }
    @Test fun processRestartPreservesConsumedSession() {
        assertFalse(ConnectionGate(true).observe(true, true))
    }
}
