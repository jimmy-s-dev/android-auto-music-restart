package com.jimmyshin.automusicrestart

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ContentVerificationTest {
    private val original = ContentVerification.Track("노래 (Live)", "가수", "앨범", 200000)
    private val other = original.copy(title = "다른 곡")
    private class Fake : ContentVerification.Port {
        var time = 0L
        var pauses = 0
        val waits = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        var sampleAt: (Long) -> ContentVerification.Snapshot = { ContentVerification.Snapshot(null, true) }
        var checkAt: (Long) -> Unit = {}
        override fun check() = checkAt(time)
        override fun snapshot() = sampleAt(time)
        override fun now() = time
        override fun waitFor(millis: Long) { waits += millis; time += millis }
        override fun pause() { pauses++ }
        override fun log(message: String) { logs += message }
    }
    private fun run(fake: Fake, before: ContentVerification.Track? = original) = ContentVerification(fake).execute(before)
    private fun sample(track: ContentVerification.Track?, playing: Boolean = true) = ContentVerification.Snapshot(track, playing)
    private fun assertSameImmediately(track: ContentVerification.Track) {
        val fake = Fake().apply { sampleAt = { sample(track) } }
        assertEquals(ContentVerification.Verdict.SAME, run(fake).verdict)
        assertEquals(0L, fake.time)
        assertEquals(0, fake.pauses)
    }

    @Test fun identicalMetadataNeedsNoWait() = assertSameImmediately(original)
    @Test fun albumChangesOrDisappearsDoNotPause() {
        assertSameImmediately(original.copy(album = "다른 앨범"))
        assertSameImmediately(original.copy(album = null))
    }
    @Test fun durationChangesOrDisappearsDoNotPause() {
        listOf(null, 0L, -1L, 201247L).forEach { assertSameImmediately(original.copy(durationMs = it)) }
    }
    @Test fun whitespaceAndNfcAreNormalized() {
        assertSameImmediately(original.copy(title = "\u00a0노래\t (Live)\n", artist = "\u1100\u1161수 "))
    }
    @Test fun liveAndRemixLabelsArePreserved() {
        listOf("노래", "노래 (Remix)").forEach {
            assertEquals(ContentVerification.Verdict.CHANGE_CANDIDATE,
                ContentVerification.compare(original, original.copy(title = it)).verdict)
        }
    }
    @Test fun sameTitleWithDifferentArtistIsUncertain() {
        val fake = Fake().apply { sampleAt = { sample(original.copy(artist = "다른 가수")) } }
        assertEquals(ContentVerification.Verdict.UNCERTAIN, run(fake).verdict)
        assertEquals(5000L, fake.time)
        assertEquals(0, fake.pauses)
    }
    @Test fun missingArtistsCannotEstablishSameSong() {
        listOf(null, "", " \u00a0").forEach {
            assertEquals(ContentVerification.Verdict.UNCERTAIN,
                ContentVerification.compare(original, original.copy(artist = it)).verdict)
            assertEquals(ContentVerification.Verdict.UNCERTAIN,
                ContentVerification.compare(original.copy(artist = it), original).verdict)
        }
    }
    @Test fun coldProcessMetadataRestorationKeepsPlayback() {
        val fake = Fake().apply {
            sampleAt = { time -> sample(when {
                time < 600 -> null
                time < 1200 -> original.copy(title = null, artist = null, durationMs = 0)
                time < 1800 -> original.copy(artist = null, album = null)
                else -> original
            }) }
        }
        assertEquals(ContentVerification.Verdict.SAME, run(fake).verdict)
        assertEquals(1800L, fake.time)
        assertEquals(0, fake.pauses)
    }
    @Test fun absentBeforeMetadataStaysUncertainEvenAfterRestoration() {
        val fake = Fake().apply { sampleAt = { sample(original) } }
        assertEquals(ContentVerification.Verdict.UNCERTAIN, run(fake, null).verdict)
        assertEquals(5000L, fake.time)
        assertEquals(0, fake.pauses)
    }
    @Test fun permanentlyMissingMetadataUsesExactlyFiveSeconds() {
        val fake = Fake()
        val result = run(fake)
        assertEquals(ContentVerification.Verdict.UNCERTAIN, result.verdict)
        assertTrue(result.diagnostic.contains("재생 유지"))
        assertEquals(List(25) { 200L }, fake.waits)
        assertEquals(0, fake.pauses)
    }
    @Test fun transientWrongTitleDoesNotPause() {
        val fake = Fake().apply { sampleAt = { sample(if (it < 2800) other else original) } }
        assertEquals(ContentVerification.Verdict.SAME, run(fake).verdict)
        assertEquals(2800L, fake.time)
        assertEquals(0, fake.pauses)
    }
    @Test fun stableDifferentTitlePausesOnceAtThreeSecondsAndReportsFailure() {
        val fake = Fake().apply { sampleAt = { sample(other) } }
        val failure = assertThrows(IllegalStateException::class.java) { run(fake) }
        assertEquals(3000L, fake.time)
        assertEquals(List(15) { 200L }, fake.waits)
        assertEquals(1, fake.pauses)
        assertTrue(failure.message!!.contains("현재 곡이 재시작 전과 달라"))
        assertTrue(failure.message!!.contains("경과=3000ms"))
    }
    @Test fun aNewCandidateRestartsStabilityTimer() {
        val fake = Fake().apply {
            sampleAt = { sample(if (it < 2400) other else other.copy(title = "세 번째 곡")) }
        }
        assertEquals(ContentVerification.Verdict.UNCERTAIN, run(fake).verdict)
        assertEquals(5000L, fake.time)
        assertEquals(0, fake.pauses)
    }
    @Test fun missingMetadataResetsStabilityTimer() {
        val fake = Fake().apply { sampleAt = { sample(if (it == 2400L) null else other) } }
        assertEquals(ContentVerification.Verdict.UNCERTAIN, run(fake).verdict)
        assertEquals(0, fake.pauses)
    }
    @Test fun bufferingResetsStabilityTimer() {
        val fake = Fake().apply { sampleAt = { sample(other, it != 2400L) } }
        assertEquals(ContentVerification.Verdict.UNCERTAIN, run(fake).verdict)
        assertEquals(0, fake.pauses)
    }
    @Test fun ancillaryMetadataUpdatesDoNotResetDifferentTitleTimer() {
        val fake = Fake().apply { sampleAt = { sample(other.copy(album = "album$it", durationMs = it + 100000)) } }
        assertThrows(IllegalStateException::class.java) { run(fake) }
        assertEquals(3000L, fake.time)
        assertEquals(1, fake.pauses)
    }
    @Test fun sameSongArrivingAtDeadlinePasses() {
        val fake = Fake().apply { sampleAt = { sample(if (it < 5000) null else original) } }
        assertEquals(ContentVerification.Verdict.SAME, run(fake).verdict)
        assertEquals(5000L, fake.time)
        assertEquals(0, fake.pauses)
    }
    @Test fun changeBecomingStableAtDeadlinePauses() {
        val fake = Fake().apply { sampleAt = { sample(if (it < 2000) null else other) } }
        assertThrows(IllegalStateException::class.java) { run(fake) }
        assertEquals(5000L, fake.time)
        assertEquals(1, fake.pauses)
    }
    @Test fun nonPlayingTimeoutIsNotReportedAsSuccessfulUncertainty() {
        val fake = Fake().apply { sampleAt = { sample(original, false) } }
        val failure = assertThrows(IllegalStateException::class.java) { run(fake) }
        assertTrue(failure.message!!.contains("재생 상태가 복구되지"))
        assertEquals(5000L, fake.time)
        assertEquals(0, fake.pauses)
    }
    @Test fun cancellationPermissionsAndSessionOrUserStopFailuresPropagate() {
        listOf(CancellationException("취소"), SecurityException("권한 상실"),
            IllegalStateException("세션 소멸"), IllegalStateException("사용자 일시정지"),
            IllegalStateException("사용자 정지")).forEach { expected ->
            val fake = Fake().apply {
                sampleAt = { sample(other) }
                checkAt = { if (it >= 1200) throw expected }
            }
            assertSame(expected, assertThrows(expected.javaClass) { run(fake) })
            assertEquals(1200L, fake.time)
            assertEquals(0, fake.pauses)
            assertTrue(fake.logs.last().contains("확인 중단"))
        }
    }
    @Test fun guardFailureAfterSnapshotPreventsSuccessOrPause() {
        val expected = SecurityException("권한 상실")
        val fake = Fake().apply {
            sampleAt = { checkAt = { throw expected }; sample(original) }
        }
        assertSame(expected, assertThrows(SecurityException::class.java) { run(fake) })
        assertEquals(0, fake.pauses)
    }
    @Test fun metadataQueryErrorsAreNotMissingMetadata() {
        val expected = IllegalStateException("조회 실패")
        val fake = Fake().apply { sampleAt = { throw expected } }
        assertSame(expected, assertThrows(IllegalStateException::class.java) { run(fake) })
        assertEquals(0L, fake.time)
        assertEquals(0, fake.pauses)
    }
    @Test fun diagnosticsContainFieldStatusAndDeltaWithoutSongText() {
        val fake = Fake().apply { sampleAt = { sample(original.copy(album = null, durationMs = 201234)) } }
        val detail = run(fake).diagnostic
        assertTrue(detail.contains("제목[전=true/후=true/일치=true]"))
        assertTrue(detail.contains("앨범[전=true/후=false/일치=비교 불가]"))
        assertTrue(detail.contains("재생시간차=1234ms"))
        assertFalse(detail.contains(original.title!!))
        assertFalse(detail.contains("가수"))
        val changed = Fake().apply { sampleAt = { sample(other) } }
        val failure = assertThrows(IllegalStateException::class.java) { run(changed) }
        (changed.logs + failure.message!!).forEach {
            assertFalse(it.contains(other.title!!))
            assertFalse(it.contains(original.title!!))
        }
    }
}
