package dev.jellystream.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadFileNameTest {

    @Test
    fun theFileIsNamedAfterTheItemIdNotTheTitle() {
        // Two libraries can both hold "Dune (2021).mkv", and a title may
        // contain characters a file system refuses
        assertEquals("abc123.mkv", DownloadedItem.fileNameFor("abc123", "mkv"))
        assertEquals("abc123.mp4", DownloadedItem.fileNameFor("abc123", "mp4"))
    }

    @Test
    fun anAbsentOrHostileContainerFallsBackToMkv() {
        assertEquals("abc.mkv", DownloadedItem.fileNameFor("abc", null))
        assertEquals("abc.mkv", DownloadedItem.fileNameFor("abc", ""))
        assertEquals("abc.mkv", DownloadedItem.fileNameFor("abc", "  "))
        // A container carrying a separator would escape the folder
        assertEquals("abc.mkv", DownloadedItem.fileNameFor("abc", "../../etc"))
        assertEquals("abc.mkv", DownloadedItem.fileNameFor("abc", "mp4;rm -rf"))
    }

    @Test
    fun theContainerIsNormalizedToLowerCase() {
        assertEquals("abc.mkv", DownloadedItem.fileNameFor("abc", "MKV"))
    }
}

class DownloadedItemTest {

    private val film = BaseItem(
        id = "film-1",
        name = "Dune",
        type = "Movie",
        productionYear = 2021,
        runTimeTicks = 9_000_000_000,
    )

    private val episode = BaseItem(
        id = "ep-1",
        name = "Sea Change",
        type = "Episode",
        seriesName = "Pioneer One",
        indexNumber = 4,
        parentIndexNumber = 1,
        userData = UserItemData(playbackPositionTicks = 600_000_000),
    )

    @Test
    fun aQueuedFilmCarriesWhatAnOfflineScreenNeeds() {
        val queued = DownloadedItem.queued(film, "mkv")

        assertEquals("Dune", queued.name)
        assertEquals("film-1.mkv", queued.fileName)
        assertEquals(DownloadState.QUEUED, queued.state)
        assertEquals("2021", queued.subtitle)
        assertFalse(queued.isPlayable)
    }

    @Test
    fun anEpisodeKeepsItsSeriesAndNumber() {
        val queued = DownloadedItem.queued(episode, "mkv")

        // Offline the library call fails, so this is the only place the
        // name can come from
        assertEquals("Pioneer One · S1 · E4", queued.subtitle)
    }

    @Test
    fun aResumePointCarriesIntoTheDownload() {
        val queued = DownloadedItem.queued(episode, "mkv")

        assertEquals(600_000_000, queued.positionTicks)
        assertEquals(60.0, queued.positionSeconds)
    }

    @Test
    fun progressIsNullUntilTheSizeIsKnown() {
        val item = DownloadedItem(itemId = "a", fileName = "a.mkv", name = "A")

        assertNull(item.progress)
        assertEquals(0.5, item.copy(totalBytes = 100, downloadedBytes = 50).progress)
        // A server that lies about the length must not produce 1.4
        assertEquals(1.0, item.copy(totalBytes = 100, downloadedBytes = 140).progress)
    }

    @Test
    fun onlyACompletedDownloadPlays() {
        val item = DownloadedItem(itemId = "a", fileName = "a.mkv", name = "A")

        assertFalse(item.copy(state = DownloadState.QUEUED).isPlayable)
        assertFalse(item.copy(state = DownloadState.DOWNLOADING).isPlayable)
        // A partial file is not half a film, it is a broken one
        assertFalse(item.copy(state = DownloadState.FAILED).isPlayable)
        assertTrue(item.copy(state = DownloadState.COMPLETE).isPlayable)
    }
}

class PersistedDownloadsTest {

    private fun item(id: String, state: DownloadState = DownloadState.COMPLETE) =
        DownloadedItem(itemId = id, fileName = "$id.mkv", name = id, state = state)

    @Test
    fun addingTheSameItemTwiceReplacesItInPlace() {
        val downloads = PersistedDownloads.empty()
            .with(item("a", DownloadState.QUEUED))
            .with(item("b"))
            .with(item("a", DownloadState.COMPLETE))

        assertEquals(2, downloads.items.size)
        // Order matters on screen: a progressing download must not jump
        assertEquals(listOf("a", "b"), downloads.items.map { it.itemId })
        assertEquals(DownloadState.COMPLETE, downloads.stateOf("a"))
    }

    @Test
    fun onlyFinishedDownloadsAreOfferedForPlayback() {
        val downloads = PersistedDownloads.empty()
            .with(item("a", DownloadState.COMPLETE))
            .with(item("b", DownloadState.DOWNLOADING))
            .with(item("c", DownloadState.FAILED))

        assertEquals(listOf("a"), downloads.playable.map { it.itemId })
    }

    @Test
    fun removingOneLeavesTheRest() {
        val downloads = PersistedDownloads.empty().with(item("a")).with(item("b"))

        assertEquals(listOf("b"), downloads.without("a").items.map { it.itemId })
        assertNull(downloads.without("a")["a"])
    }

    @Test
    fun watchingOfflineMarksThePositionUnsynced() {
        val downloads = PersistedDownloads.empty()
            .with(item("a"))
            .markPosition("a", ticks = 1_200_000_000)

        assertEquals(1_200_000_000, downloads["a"]?.positionTicks)
        assertEquals(listOf("a"), downloads.unsyncedPositions.map { it.itemId })
    }

    @Test
    fun theServerAcceptingThePositionClearsIt() {
        val downloads = PersistedDownloads.empty()
            .with(item("a"))
            .markPosition("a", ticks = 1_200_000_000)
            .markSynced("a")

        assertTrue(downloads.unsyncedPositions.isEmpty())
        // The position itself stays: it is the offline resume point
        assertEquals(1_200_000_000, downloads["a"]?.positionTicks)
    }

    @Test
    fun aPositionOfZeroIsNothingToReport() {
        // Opening a film and closing it immediately must not rewind the
        // server's own resume point
        val downloads = PersistedDownloads.empty()
            .with(item("a"))
            .markPosition("a", ticks = 0)

        assertTrue(downloads.unsyncedPositions.isEmpty())
    }

    @Test
    fun markingAnUnknownItemChangesNothing() {
        val downloads = PersistedDownloads.empty().with(item("a"))

        assertEquals(downloads, downloads.markPosition("nope", 500))
        assertEquals(downloads, downloads.markSynced("nope"))
    }

    @Test
    fun theListSurvivesTheJsonRoundTrip() {
        val downloads = PersistedDownloads.empty()
            .with(item("a").copy(totalBytes = 100, downloadedBytes = 100))
            .markPosition("a", 42)

        val decoded = PersistedDownloads.fromJson(downloads.toJson())!!

        assertEquals(1, decoded.items.size)
        assertEquals(42, decoded["a"]?.positionTicks)
        assertFalse(decoded["a"]!!.positionSynced)
    }

    @Test
    fun aCorruptBlobCostsTheListNotTheLaunch() {
        assertNull(PersistedDownloads.fromJson("not json"))
        assertTrue(PersistedDownloads.empty().items.isEmpty())
    }

    @Test
    fun aBlobFromANewerBuildStillDecodes() {
        val fromTheFuture =
            """{"items":[{"itemId":"a","fileName":"a.mkv","name":"A","somethingNew":true}]}"""

        assertEquals("A", PersistedDownloads.fromJson(fromTheFuture)?.get("a")?.name)
    }
}

class DownloadAvailabilityTest {

    private val film = BaseItem(id = "a", type = "Movie")
    private val series = BaseItem(id = "b", type = "Series")

    @Test
    fun aFilmOnAPermittedAccountCanBeDownloaded() {
        assertTrue(DownloadAvailability.of(film, downloadingEnabled = true).canDownload)
    }

    @Test
    fun theServerCanForbidItAndTheReasonIsSayable() {
        // demo.jellyfin.org is exactly this case
        val availability = DownloadAvailability.of(film, downloadingEnabled = false)

        assertFalse(availability.canDownload)
        assertEquals(DownloadAvailability.FORBIDDEN_BY_SERVER, availability)
        assertTrue(availability.explanation!!.contains("not allowed"))
    }

    @Test
    fun anUnknownPolicyIsTreatedAsAllowed() {
        // The call either works or comes back 401, which is recoverable.
        // Hiding the button on a server that would have said yes is not.
        assertTrue(DownloadAvailability.of(film, downloadingEnabled = null).canDownload)
    }

    @Test
    fun thereIsNothingToDownloadForASeries() {
        val availability = DownloadAvailability.of(series, downloadingEnabled = true)

        assertEquals(DownloadAvailability.NOT_A_FILE, availability)
        assertFalse(availability.canDownload)
        // Not an error to explain — the button simply does not belong here
        assertNull(availability.explanation)
    }
}

class DownloadUrlTest {

    @Test
    fun thereIsNoDownloadUrlWithoutASession() {
        val api = JellyfinApi(deviceName = "test", deviceId = "test-id")

        assertNull(api.downloadUrl("abc"))
    }

    @Test
    fun theUrlAsksForTheOriginalFile() {
        val api = JellyfinApi(deviceName = "test", deviceId = "test-id")
        api.restoreSession(
            UserSession(
                baseUrl = "https://jf.example.com",
                userId = "u1",
                accessToken = "token",
                userName = "alice",
                serverName = "Home",
            )
        )

        // /Download is the untouched file; /stream would re-negotiate and
        // could hand back a transcode
        assertEquals("https://jf.example.com/Items/abc/Download", api.downloadUrl("abc"))
    }

    @Test
    fun theTokenNeverTravelsInTheUrl() {
        val api = JellyfinApi(deviceName = "test", deviceId = "test-id")
        api.restoreSession(
            UserSession(
                baseUrl = "https://jf.example.com",
                userId = "u1",
                accessToken = "secret-token",
                userName = "alice",
                serverName = "Home",
            )
        )

        // Proxy and player logs keep URLs; the header is the only place
        assertFalse(api.downloadUrl("abc")!!.contains("secret-token"))
    }
}
