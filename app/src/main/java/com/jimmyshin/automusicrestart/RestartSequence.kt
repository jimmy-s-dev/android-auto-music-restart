package com.jimmyshin.automusicrestart

/** The first PLAY is a request, not a prerequisite for recovering a stuck player. */
class RestartSequence(private val port: Port) {
    data class Snapshot(val pids: Set<String>, val tokens: Set<Any>, val states: String)
    interface Port {
        fun check()
        fun waitFor(millis: Long)
        fun requestPlayback()
        fun snapshot(): Snapshot
        fun stopTarget()
        fun confirmPlayback(): Any
        fun log(message: String)
    }

    var stage = "첫 재생 요청"
        private set

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
        port.stopTarget()
        stage = "종료 후 2초 대기"
        port.waitFor(2000)
        port.check()
        val after = port.snapshot()
        val stale = after.tokens.any { it in before.tokens }
        port.log("강제 종료 후 PID=${after.pids}, 이전 세션=$stale, 상태=${after.states}")
        check(after.pids.none { it in before.pids }) { "종료 전 프로세스가 남아 있습니다" }
        check(!stale) { "이전 미디어 세션이 남아 있어 재시작을 중단했습니다" }
        stage = "최종 재생 확인"
        port.log("단계: $stage")
        port.check()
        val token = port.confirmPlayback()
        port.check()
        check(token !in before.tokens) { "새 미디어 세션 생성이 확인되지 않았습니다" }
    }
}
