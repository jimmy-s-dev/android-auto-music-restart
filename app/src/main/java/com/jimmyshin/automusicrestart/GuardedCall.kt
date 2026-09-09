package com.jimmyshin.automusicrestart

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Let the caller keep checking cancellation/permissions during a blocking Binder call. */
object GuardedCall {
    fun <T> execute(check: () -> Unit, operation: () -> T): T {
        check()
        val task = FutureTask(operation)
        Thread(task, "music-control-call").apply { isDaemon = true; start() }
        try {
            while (true) {
                check()
                try {
                    val result = task.get(100, TimeUnit.MILLISECONDS)
                    check()
                    return result
                } catch (_: TimeoutException) {
                    // The owner closes its Shizuku bridge on cancellation, terminating the
                    // remote process and removing its display even if Binder ignores interrupt.
                } catch (failure: ExecutionException) {
                    throw failure.cause ?: failure
                }
            }
        } finally {
            task.cancel(true)
        }
    }
}
