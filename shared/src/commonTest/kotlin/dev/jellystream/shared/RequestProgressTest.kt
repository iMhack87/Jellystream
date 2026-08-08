package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun grab(
    size: Double,
    sizeLeft: Double,
    timeLeft: String? = null,
    status: String? = "downloading",
) = JellyseerrDownload(size = size, sizeLeft = sizeLeft, timeLeft = timeLeft, status = status)

class RequestProgressTest {

    @Test
    fun nothingDownloadingIsNoBarAtAll() {
        assertNull(RequestProgress.of(emptyList()))
        // A queued grab Sonarr has not sized yet would otherwise read as 0%
        assertNull(RequestProgress.of(listOf(grab(size = 0.0, sizeLeft = 0.0))))
    }

    @Test
    fun oneFilmIsItsOwnBytes() {
        val progress = RequestProgress.of(listOf(grab(size = 1000.0, sizeLeft = 250.0)))!!

        assertEquals(0.75, progress.fraction)
        assertEquals("75%", progress.percentLabel)
        assertEquals(1, progress.fileCount)
    }

    @Test
    fun aSeasonIsOneBarAcrossEveryEpisode() {
        val progress = RequestProgress.of(
            listOf(
                grab(size = 1000.0, sizeLeft = 0.0),
                grab(size = 1000.0, sizeLeft = 1000.0),
                grab(size = 2000.0, sizeLeft = 1000.0),
            ),
        )!!

        // 4000 bytes wanted, 2000 still to come
        assertEquals(0.5, progress.fraction)
        assertEquals(3, progress.fileCount)
    }

    @Test
    fun anUnsizedGrabIsIgnoredRatherThanCountedAsZero() {
        // Sonarr reports size 0 for an item it has only just queued.
        // Averaging it in would drag a finished season back to half.
        val progress = RequestProgress.of(
            listOf(
                grab(size = 1000.0, sizeLeft = 100.0),
                grab(size = 0.0, sizeLeft = 0.0),
            ),
        )!!

        assertEquals(0.9, progress.fraction)
        assertEquals(1, progress.fileCount)
    }

    @Test
    fun aSizeLeftLargerThanTheFileCannotPushTheBarBackwards() {
        // Seen on a re-grab: sizeLeft briefly exceeds size
        val progress = RequestProgress.of(listOf(grab(size = 1000.0, sizeLeft = 4000.0)))!!

        assertEquals(0.0, progress.fraction)
    }

    @Test
    fun theSlowestFileDecidesWhenTheSeasonIsDone() {
        val progress = RequestProgress.of(
            listOf(
                grab(size = 100.0, sizeLeft = 10.0, timeLeft = "00:02:00"),
                grab(size = 100.0, sizeLeft = 90.0, timeLeft = "00:41:00"),
            ),
        )!!

        assertEquals(41 * 60L, progress.remainingSeconds)
        assertEquals("41 min left", progress.remainingLabel)
    }

    @Test
    fun aStalledDownloadKeepsItsBarAndLosesItsEstimate() {
        val progress = RequestProgress.of(
            listOf(grab(size = 100.0, sizeLeft = 40.0, timeLeft = null, status = "paused")),
        )!!

        assertEquals(0.6, progress.fraction)
        assertNull(progress.remainingSeconds)
        assertNull(progress.remainingLabel)
        assertTrue(progress.isStalled)
        assertEquals("60%", progress.summary)
    }

    @Test
    fun bytesInDoesNotMeanDoneAndTheLabelSaysSo() {
        // Sonarr keeps the queue item while it imports and renames;
        // "100% · 0 min left" reads as a stuck download
        val progress = RequestProgress.of(
            listOf(grab(size = 100.0, sizeLeft = 0.0, timeLeft = "00:00:00")),
        )!!

        assertTrue(progress.isFinishing)
        assertEquals("100% · Finishing up", progress.summary)
    }

    @Test
    fun aRunningDownloadReadsAsPercentThenTime() {
        val progress = RequestProgress.of(
            listOf(grab(size = 100.0, sizeLeft = 38.0, timeLeft = "00:12:34")),
        )!!

        assertFalse(progress.isStalled)
        assertEquals("62% · 13 min left", progress.summary)
    }
}

class TimeLeftTest {

    @Test
    fun theCommonShapeIsHoursMinutesSeconds() {
        assertEquals(754L, RequestProgress.parseTimeLeft("00:12:34"))
        assertEquals(3600L, RequestProgress.parseTimeLeft("01:00:00"))
        assertEquals(3600L, RequestProgress.parseTimeLeft("1:00:00"))
    }

    @Test
    fun aDayPartIsADotBeforeTheFirstColon() {
        // .NET writes a day as "1.03:00:00", which is 27 hours
        assertEquals(27 * 3600L, RequestProgress.parseTimeLeft("1.03:00:00"))
    }

    @Test
    fun fractionalSecondsAreADotAfterTheFirstColonAndAreDropped() {
        assertEquals(754L, RequestProgress.parseTimeLeft("00:12:34.5670000"))
        assertEquals(27 * 3600L, RequestProgress.parseTimeLeft("1.03:00:00.0000000"))
    }

    @Test
    fun anythingUnrecognisedMeansNoEstimateNotAnException() {
        // Sonarr simply omits the field when a download stalls, and the
        // format is a passthrough we do not control — a request screen
        // that throws on a stalled torrent is worse than a missing label
        assertNull(RequestProgress.parseTimeLeft(null))
        assertNull(RequestProgress.parseTimeLeft(""))
        assertNull(RequestProgress.parseTimeLeft("   "))
        assertNull(RequestProgress.parseTimeLeft("soon"))
        assertNull(RequestProgress.parseTimeLeft("12:34"))
        assertNull(RequestProgress.parseTimeLeft("00:99:00"))
        assertNull(RequestProgress.parseTimeLeft("-1:00:00"))
    }
}

class RemainingLabelTest {

    @Test
    fun secondsAreNotWorthACountdown() {
        assertEquals("Any moment now", RequestProgress.formatRemaining(0))
        assertEquals("Under a minute left", RequestProgress.formatRemaining(40))
    }

    @Test
    fun minutesRoundUpSoNothingEverReadsAsZero() {
        assertEquals("1 min left", RequestProgress.formatRemaining(60))
        // A partial minute counts as a whole one: "2 min" is a promise the
        // download can keep, "1 min" for 61 seconds is not
        assertEquals("2 min left", RequestProgress.formatRemaining(61))
        assertEquals("13 min left", RequestProgress.formatRemaining(754))
    }

    @Test
    fun theCarryIsDoneOnceOrYouShipAnHourAndSixtyMinutes() {
        // 1 h 59 min 59 s: rounding minutes and hours separately gives
        // "1 h 60 min left"
        assertEquals("2 h left", RequestProgress.formatRemaining(7199))
        assertEquals("1 h 1 min left", RequestProgress.formatRemaining(3660))
        assertEquals("1 h left", RequestProgress.formatRemaining(3599))
    }

    @Test
    fun pastADayTheEstimateIsGuessworkAndReadsLikeIt() {
        assertEquals("About a day left", RequestProgress.formatRemaining(24 * 3600))
        // 25 hours must not read as two days
        assertEquals("About a day left", RequestProgress.formatRemaining(25 * 3600))
        assertEquals("About 2 days left", RequestProgress.formatRemaining(47 * 3600))
    }
}
