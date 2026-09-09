package com.jimmyshin.automusicrestart

/** The first PLAY is a request, not a prerequisite for recovering a stuck player. */
class RestartSequence(private val port: Port) {
    data class Snapshot(val pids: Set<String>, val tokens: Set<Any>, val states: String)
    interface Port {
        fun check()
        fun now(): Long
        fun waitFor(millis: Long)
        fun requestPlayback()
        fun snapshot(): Snapshot
        fun stopTarget()
        fun confirmPlayback(): Any
        fun log(message: String)
    }

    var stage = "첫 재생 요청"
        private set

    /** Only two fully empty snapshots permit early initialization. Queries never overlap. */
    private fun awaitExit(stoppedAt: Long): Snapshot {
        var emptyCount = 0
        port.waitFor(500)
        while (true) {
            port.check()
            val snapshot = port.snapshot()
            port.check()
            val elapsed = port.now() - stoppedAt
            emptyCount = if (snapshot.pids.isEmpty() && snapshot.tokens.isEmpty()) emptyCount + 1 else 0
            if (elapsed >= 2000 || emptyCount >= 2) return snapshot
            port.waitFor(minOf(250L, 2000L - elapsed))
        }
    }

    fun execute() {
        port.check()
        port.log("단계: $stage")
        port.requestPlayback()
        stage = "재생 요청 후 5초 대기"
        port.log("단계: $stage")
        port.waitFor(5000)
        port.check()
        val before = port.snapshot()
        port.log("강제 종료 전 PID=${before.pids}, 세션=${before.tokens.size}, 상태=${before.states}")
        stage = "강제 종료"
        port.check()
        val requestedAt = port.now()
        port.log("시간 측정: 종료 요청 +0ms")
        port.stopTarget()
        val stoppedAt = port.now()
        port.log("시간 측정: 종료 반환 +${stoppedAt - requestedAt}ms")
        stage = "종료 확인 대기"
        val after = awaitExit(stoppedAt)
        val stale = after.tokens.any { it in before.tokens }
        port.log("강제 종료 후 PID=${after.pids}, 이전 세션=$stale, 상태=${after.states}")
        check(after.pids.none { it in before.pids }) { "종료 전 프로세스가 남아 있습니다" }
        check(!stale) { "이전 미디어 세션이 남아 있어 재시작을 중단했습니다" }
        port.log("시간 측정: 종료 확인 +${port.now() - requestedAt}ms, 반환 후 ${port.now() - stoppedAt}ms")
        stage = "최종 재생 확인"
        port.log("단계: $stage")
        port.check()
        port.log("시간 측정: 초기화 시작 +${port.now() - requestedAt}ms, 반환 후 ${port.now() - stoppedAt}ms")
        val token = port.confirmPlayback()
        port.log("시간 측정: PLAYING 확인 +${port.now() - requestedAt}ms, 반환 후 ${port.now() - stoppedAt}ms")
        port.check()
        check(token !in before.tokens) { "새 미디어 세션 생성이 확인되지 않았습니다" }
    }
}
