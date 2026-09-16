package com.jimmyshin.automusicrestart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ServiceIdentityTest {
    private val expected = "a".repeat(64)
    @Test fun matchingImplementationPermitsSubsequentControl() {
        val logs = mutableListOf<String>()
        var controlled = false
        ServiceIdentity.verify(expected, { expected }, {}, logs::add)
        controlled = true
        assertTrue(controlled)
        assertTrue(logs.single().contains("일치, 기대=$expected, 실제=$expected"))
    }
    @Test fun mismatchInvalidReplyAndMissingMethodBlockControl() {
        val readers: List<() -> String?> = listOf({ "b".repeat(64) }, { null }, { "invalid" },
            { throw UnsupportedOperationException("Method unimplemented") })
        readers.forEach { reader ->
            var controlled = false
            val logs = mutableListOf<String>()
            assertThrows(IllegalStateException::class.java) {
                ServiceIdentity.verify(expected, reader, {}, logs::add)
                controlled = true
            }
            assertFalse(controlled)
            assertTrue(logs.single().contains("제어 코드 확인: 실패"))
        }
    }
    @Test fun queryFailureIsNotAcceptedAsIdentity() {
        val cause = IllegalStateException("Binder disconnected")
        val error = assertThrows(IllegalStateException::class.java) {
            ServiceIdentity.verify(expected, { throw cause }, {}, {})
        }
        assertSame(cause, error.cause)
    }
    @Test fun hangingQueryTimesOutAndIsInterrupted() {
        val stopped = CountDownLatch(1)
        val started = CountDownLatch(1)
        val error = assertThrows(IllegalStateException::class.java) {
            ServiceIdentity.verify(expected, {
                started.countDown()
                try { CountDownLatch(1).await(); expected } finally { stopped.countDown() }
            }, {}, {}, timeoutMs = 100)
        }
        assertTrue(error.cause is java.util.concurrent.TimeoutException)
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
    }
    @Test fun lostPermissionBeforeQueryPreventsAnyCall() {
        var called = false
        assertThrows(IllegalStateException::class.java) {
            ServiceIdentity.verify(expected, { called = true; expected }, { throw SecurityException() }, {})
        }
        assertFalse(called)
    }
    @Test fun disconnectAfterReplyRejectsEvenMatchingIdentity() {
        val replied = CountDownLatch(1)
        assertThrows(IllegalStateException::class.java) {
            ServiceIdentity.verify(expected, { replied.countDown(); expected }, {
                check(replied.count != 0L) { "Connection lost" }
            }, {})
        }
    }
    @Test fun cancellationWhileWaitingInterruptsQuery() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val failure = assertThrows(IllegalStateException::class.java) {
            ServiceIdentity.verify(expected, {
                started.countDown()
                try { CountDownLatch(1).await(); expected } finally { stopped.countDown() }
            }, { if (started.count == 0L) throw CancellationException() }, {})
        }
        assertTrue(failure.cause is CancellationException)
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
    }
    @Test fun buildIdAndShizukuVersionUseSameGeneratedInput() {
        assertTrue(BuildConfig.CONTROL_BUILD_ID.matches(Regex("[0-9a-f]{64}")))
        assertEquals(BuildConfig.CONTROL_BUILD_ID.take(7).toInt(16) + 4, BuildConfig.CONTROL_SERVICE_VERSION)
        assertTrue(BuildConfig.CONTROL_SERVICE_VERSION >= 4)
    }
}
