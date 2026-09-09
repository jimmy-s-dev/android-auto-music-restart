package com.jimmyshin.automusicrestart

import org.junit.Assert.*
import org.junit.Test

class HiddenInitSequenceTest {
    private class Port(private val fail: String? = null) : HiddenInitSequence.Port {
        val events = mutableListOf<String>()
        private fun event(name: String) { events += name; check(name != fail) { name } }
        override fun create() = event("create")
        override fun launch() = event("launch")
        override fun waitFor(millis: Long) = event("wait$millis")
        override fun verifyPlacement() = event("verify")
        override fun playAndConfirm() = event("play")
        override fun release() = event("release")
    }
    @Test fun releasesOnlyAfterInitializedPlaybackAndPlacementCheck() {
        val port = Port()
        HiddenInitSequence(port).execute()
        assertEquals(listOf("create", "launch", "wait1000", "verify", "wait5000", "verify", "play", "verify", "release"), port.events)
    }
    @Test fun releasesOnPartialCreationFailureWithoutLaunch() {
        val port = Port("create")
        assertThrows(IllegalStateException::class.java) { HiddenInitSequence(port).execute() }
        assertEquals(listOf("create", "release"), port.events)
    }
    @Test fun misplacedActivityIsNotFollowedByPlayback() {
        val port = Port("verify")
        assertThrows(IllegalStateException::class.java) { HiddenInitSequence(port).execute() }
        assertFalse("play" in port.events)
        assertEquals("release", port.events.last())
    }
    @Test fun finalPlaybackFailureReleasesWithoutRetry() {
        val port = Port("play")
        assertThrows(IllegalStateException::class.java) { HiddenInitSequence(port).execute() }
        assertEquals(1, port.events.count { it == "launch" })
        assertEquals(1, port.events.count { it == "play" })
        assertEquals("release", port.events.last())
    }
}
