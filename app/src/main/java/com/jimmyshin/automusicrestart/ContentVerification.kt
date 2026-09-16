package com.jimmyshin.automusicrestart

import java.text.Normalizer

/** Metadata is restored asynchronously. Only a stable, different title warrants a pause. */
class ContentVerification(private val port: Port) {
    data class Track(val title: String? = null, val artist: String? = null,
        val album: String? = null, val durationMs: Long? = null)
    data class Snapshot(val track: Track?, val playing: Boolean)
    enum class Verdict { SAME, CHANGE_CANDIDATE, UNCERTAIN }
    data class Comparison(val verdict: Verdict, val reason: String)
    data class Result(val verdict: Verdict, val diagnostic: String)
    interface Port {
        /** Propagate cancellation, permission loss and invalid sessions/states; never resume playback. */
        fun check()
        fun snapshot(): Snapshot
        fun now(): Long
        fun waitFor(millis: Long)
        fun pause()
        fun log(message: String)
    }

    companion object {
        const val POLL_MS = 200L
        const val TIMEOUT_MS = 5000L
        const val STABLE_MS = 3000L
        private val whitespace = Regex("[\\s\\p{Z}]+")
        internal fun normalize(value: String?): String? = value?.let {
            whitespace.replace(Normalizer.normalize(it, Normalizer.Form.NFC), " ").trim().takeIf(String::isNotEmpty)
        }
        fun compare(before: Track?, after: Track?): Comparison {
            val titleBefore = normalize(before?.title)
            val titleAfter = normalize(after?.title)
            if (titleBefore == null || titleAfter == null) return Comparison(Verdict.UNCERTAIN, "제목 정보 부족")
            if (titleBefore != titleAfter) return Comparison(Verdict.CHANGE_CANDIDATE, "제목 불일치")
            val artistBefore = normalize(before?.artist)
            val artistAfter = normalize(after?.artist)
            if (artistBefore == null || artistAfter == null) return Comparison(Verdict.UNCERTAIN, "아티스트 정보 부족")
            return if (artistBefore == artistAfter) Comparison(Verdict.SAME, "제목·아티스트 일치")
                else Comparison(Verdict.UNCERTAIN, "동일 제목·아티스트 불일치")
        }
        private fun field(before: Any?, after: Any?): String =
            "전=${before != null}/후=${after != null}/일치=${if (before == null || after == null) "비교 불가" else (before == after).toString()}"
        fun diagnostic(before: Track?, after: Track?, reason: String, elapsed: Long): String {
            val durationBefore = before?.durationMs?.takeIf { it > 0 }
            val durationAfter = after?.durationMs?.takeIf { it > 0 }
            val delta = if (durationBefore != null && durationAfter != null) (durationAfter - durationBefore).toString() else "비교 불가"
            return "곡 확인: $reason, 경과=${elapsed}ms, " +
                "제목[${field(normalize(before?.title), normalize(after?.title))}], " +
                "아티스트[${field(normalize(before?.artist), normalize(after?.artist))}], " +
                "앨범[${field(normalize(before?.album), normalize(after?.album))}], " +
                "재생시간[${field(durationBefore, durationAfter)}], 재생시간차=${delta}ms"
        }
    }

    fun execute(before: Track?): Result {
        val started = port.now()
        var candidate: String? = null
        var candidateSince = started
        var last: Snapshot? = null
        var comparison = Comparison(Verdict.UNCERTAIN, "관측 전")
        try {
            while (true) {
                port.check()
                val sample = port.snapshot()
                last = sample
                port.check()
                val now = port.now()
                val elapsed = now - started
                comparison = compare(before, sample.track)
                val newCandidate = if (sample.playing && comparison.verdict == Verdict.CHANGE_CANDIDATE)
                    normalize(sample.track?.title) else null
                if (newCandidate == null || newCandidate != candidate) candidateSince = now
                candidate = newCandidate

                fun report(reason: String) = diagnostic(before, sample.track, reason, elapsed)
                if (sample.playing && comparison.verdict == Verdict.SAME) {
                    val detail = report("현재 곡 동일 (${comparison.reason})")
                    port.log(detail)
                    return Result(Verdict.SAME, detail)
                }
                if (candidate != null && now - candidateSince >= STABLE_MS) {
                    port.check()
                    val detail = report("곡 변경 확인: 제목 불일치 3초 유지")
                    port.log(detail)
                    port.pause()
                    error("현재 곡이 재시작 전과 달라 재생을 중단했습니다; $detail")
                }
                if (elapsed >= TIMEOUT_MS) {
                    check(sample.playing) { "곡 확인 중 재생 상태가 복구되지 않았습니다; ${report(comparison.reason)}" }
                    val detail = report("곡 정보 확인 불충분: 재생 유지 (${comparison.reason})")
                    port.log(detail)
                    return Result(Verdict.UNCERTAIN, detail)
                }
                port.waitFor(minOf(POLL_MS, TIMEOUT_MS - elapsed))
            }
        } catch (failure: Exception) {
            port.log(diagnostic(before, last?.track, "확인 중단 (${comparison.reason})", port.now() - started))
            throw failure
        }
    }
}
