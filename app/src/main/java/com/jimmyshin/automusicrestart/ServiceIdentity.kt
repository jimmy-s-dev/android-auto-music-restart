package com.jimmyshin.automusicrestart

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Read-only handshake. The caller must discard the connection on any failure. */
object ServiceIdentity {
    const val TIMEOUT_MS = 5000L
    fun verify(expected: String, read: () -> String?, check: () -> Unit, log: (String) -> Unit,
        timeoutMs: Long = TIMEOUT_MS) {
        val task = FutureTask(read)
        var actual = "미확인"
        try {
            check()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            Thread(task, "music-control-identity").apply { isDaemon = true; start() }
            while (true) {
                check()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw TimeoutException("제어 서비스 코드 확인 시간 초과")
                val value = try {
                    task.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) { continue }
                actual = value?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: "잘못된 응답"
                check()
                check(actual == expected) { "제어 서비스 코드가 설치본과 다릅니다" }
                log("제어 코드 확인: 일치, 기대=$expected, 실제=$actual")
                return
            }
        } catch (error: Exception) {
            val cause = if (error is ExecutionException) error.cause ?: error else error
            log("제어 코드 확인: 실패, 기대=$expected, 실제=$actual, 원인=${cause.javaClass.simpleName}")
            if (cause is InterruptedException) Thread.currentThread().interrupt()
            throw IllegalStateException("제어 서비스 코드 확인 실패: ${cause.message ?: cause.javaClass.simpleName}", cause)
        } finally {
            task.cancel(true)
        }
    }
}
