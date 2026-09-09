package com.jimmyshin.automusicrestart

enum class ActivityHistory { ABSENT, PRESENT, UNKNOWN }
data class ProcessHistory(val key: String?, val activity: ActivityHistory, val reason: String) {
    companion object {
        fun unknown(reason: String) = ProcessHistory(null, ActivityHistory.UNKNOWN, reason)
    }
}

/** Parse only complete APP blocks. Missing fields never mean no Activity. */
object ProcessHistoryParser {
    fun parse(dump: String, boot: String, activityBit: Int?): ProcessHistory {
        if (!Regex("[a-fA-F0-9-]{36}").matches(boot)) return ProcessHistory.unknown("부팅 식별값 없음")
        val blocks = mutableListOf<List<String>>()
        var block: MutableList<String>? = null
        dump.lineSequence().forEach { line ->
            if (line.startsWith("  *APP*")) {
                block = mutableListOf(line).also { blocks.add(it) }
            } else if (line.isNotBlank() && !line.startsWith("    ")) {
                block = null
            } else block?.add(line)
        }
        val records = blocks.mapNotNull { lines ->
            val match = Regex("ProcessRecord\\{\\S+ (\\d+):([^/]+)/").find(lines.first()) ?: return@mapNotNull null
            val name = match.groupValues[2]
            if (name != Target.PACKAGE && !name.startsWith(Target.PACKAGE + ":")) return@mapNotNull null
            Triple(match.groupValues[1], name, lines.joinToString("\n"))
        }
        val main = records.singleOrNull { it.second == Target.PACKAGE }
            ?: return ProcessHistory.unknown("대상 주 프로세스 식별 불가")
        val pid = Regex("(?m)^    pid=(\\d+)\\s*$").find(main.third)?.groupValues?.get(1)
        val seq = Regex("(?m)^    startSeq=(\\d+)\\s*$").find(main.third)?.groupValues?.get(1)
        if (pid != main.first || seq == null) return ProcessHistory.unknown("PID·시작 식별값 검증 실패")
        val key = "$boot:$pid:$seq"
        if (records.size != 1) return ProcessHistory(key, ActivityHistory.UNKNOWN, "복수 대상 프로세스")
        // Samsung's process profile did not update the Activity bit after a verified visible
        // MusicActivity launch. The process-lifetime UI flag is positive evidence independently.
        if (Regex("(?m)^    hasShownUi=true(?:\\s|$)").containsMatchIn(main.third))
            return ProcessHistory(key, ActivityHistory.PRESENT, "현재 프로세스의 화면 표시 이력 있음")
        if (activityBit == null || activityBit <= 0) return ProcessHistory(key, ActivityHistory.UNKNOWN, "플랫폼 Activity 상수 없음")
        val historical = Regex("historicalHostingComponentTypes=0x([0-9a-fA-F]+)")
            .find(main.third)?.groupValues?.get(1)?.toLongOrNull(16)
            ?: return ProcessHistory(key, ActivityHistory.UNKNOWN, "Activity 이력 필드 없음")
        val seen = historical and activityBit.toLong() != 0L
        return ProcessHistory(key, if (seen) ActivityHistory.PRESENT else ActivityHistory.ABSENT,
            if (seen) "현재 프로세스의 Activity 이력 있음" else "현재 프로세스의 Activity 이력 없음")
    }
}

enum class PlaybackCondition { PLAYING, BUFFERING, INACTIVE }
enum class RestartReason(val label: String) {
    MANUAL("수동 시험"), PREVENTIVE("예방 재시작"), BUFFERING("버퍼링 복구")
}

/** Event-driven debounce. Position-only callbacks do not extend a continuous buffer. */
class PlaybackGate {
    var token: Any? = null; private set
    var track: String? = null; private set
    var state = PlaybackCondition.INACTIVE; private set
    var bufferingSince: Long? = null; private set
    fun observe(newToken: Any?, newTrack: String?, newState: PlaybackCondition, now: Long) {
        if (newToken != token || newTrack != track || newState != PlaybackCondition.BUFFERING)
            bufferingSince = null
        token = newToken; track = newTrack; state = newState
        if (state == PlaybackCondition.BUFFERING && bufferingSince == null) bufferingSince = now
    }
    fun decide(history: ActivityHistory, now: Long): RestartReason? = when {
        state == PlaybackCondition.INACTIVE || history == ActivityHistory.PRESENT -> null
        history == ActivityHistory.ABSENT -> RestartReason.PREVENTIVE
        bufferingSince?.let { now - it >= 10000 } == true -> RestartReason.BUFFERING
        else -> null
    }
}

/** Stored before force-stop; recovery successors inherit the original attempt. */
class AttemptLedger(
    val attempted: MutableSet<String> = linkedSetOf(),
    var pending: String? = null,
    var quarantine: String? = null
) {
    fun allowed(key: String): Boolean {
        if (pending != null) {
            if (quarantine == null) quarantine = key
            if (quarantine == key) return false
            pending = null; quarantine = null
        }
        return key !in attempted
    }
    fun reserve(key: String) { attempted.add(key); trim() }
    fun begin(key: String) { reserve(key); pending = key; quarantine = null }
    fun finish(successor: String?) {
        if (successor != null) { attempted.add(successor); pending = null; quarantine = null; trim() }
    }
    fun resetPending() { pending = null; quarantine = null }
    private fun trim() { while (attempted.size > 64) attempted.remove(attempted.first()) }
}
