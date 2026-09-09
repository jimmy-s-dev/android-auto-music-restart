package com.jimmyshin.automusicrestart

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GuardedCallTest {
    @Test fun revokedPermissionPreventsOperation() {
        var called = false
        assertThrows(SecurityException::class.java) {
            GuardedCall.execute({ throw SecurityException() }) { called = true }
        }
        assertFalse(called)
    }
    @Test fun cancellationWhileRemoteCallWaitsReturnsToCleanup() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        assertThrows(CancellationException::class.java) {
            GuardedCall.execute({ if (started.count == 0L) throw CancellationException() }) {
                started.countDown()
                try { CountDownLatch(1).await() } finally { interrupted.countDown() }
            }
        }
        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
    }
    @Test fun remoteFailurePropagates() {
        val failure = IllegalStateException("remote failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            GuardedCall.execute({}) { throw failure }
        })
    }
}
