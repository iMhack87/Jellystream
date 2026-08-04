package dev.jellystream.shared

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Minimal Jellyfin API client. Grows with the app; anything protocol-related
 * belongs here in the shared module, never in platform code.
 */
class JellyfinApi(
    private val clientName: String = "Jellystream",
    private val clientVersion: String = "0.1.0",
    private val deviceName: String,
    private val deviceId: String,
) {
    private val http = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var session: UserSession? = null

    val currentSession: UserSession?
        get() = session

    /** Adopts a session restored from persistent storage. */
    fun restoreSession(restored: UserSession) {
        session = restored
    }

    fun logout() {
        session = null
    }

    /** Unauthenticated ping — validates the URL points at a Jellyfin server. */
    suspend fun getPublicSystemInfo(serverUrl: String): PublicSystemInfo =
        http.get("${normalizeServerUrl(serverUrl)}/System/Info/Public").body()

    /**
     * Resolves what the user typed into a working server base URL.
     * Scheme-less input is tried as https first, then http — self-hosted
     * Jellyfin servers on the LAN are frequently plain http.
     */
    suspend fun resolveServer(rawUrl: String): ResolvedServer {
        var lastError: Exception? = null
        for (candidate in candidateUrls(rawUrl)) {
            try {
                val info: PublicSystemInfo =
                    http.get("$candidate/System/Info/Public").body()
                return ResolvedServer(baseUrl = candidate, info = info)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalArgumentException("No server URL candidates for '$rawUrl'")
    }

    suspend fun authenticateByName(
        serverUrl: String,
        username: String,
        password: String,
    ): AuthenticationResult =
        http.post("${normalizeServerUrl(serverUrl)}/Users/AuthenticateByName") {
            header("Authorization", authorizationHeader(accessToken = null))
            contentType(ContentType.Application.Json)
            setBody(AuthenticateByNameRequest(username, password))
        }.body()

    /**
     * Resolves the server, authenticates, and stores the session used by all
     * subsequent authenticated calls (views, latest items, images).
     */
    suspend fun login(rawUrl: String, username: String, password: String): UserSession {
        val server = resolveServer(rawUrl)
        val auth = authenticateByName(server.baseUrl, username, password)
        val token = auth.accessToken
            ?: throw IllegalStateException("Server did not return an access token")
        val userId = auth.user?.id
            ?: throw IllegalStateException("Server did not return a user id")
        return UserSession(
            baseUrl = server.baseUrl,
            userId = userId,
            accessToken = token,
            userName = auth.user?.name,
            serverName = server.info.serverName,
        ).also { session = it }
    }

    /** The user's library views (Movies, Shows, …). Requires [login]. */
    suspend fun getUserViews(): List<BaseItem> {
        val s = requireSession()
        val result: ItemsResult = http.get("${s.baseUrl}/UserViews") {
            url.parameters.append("userId", s.userId)
            header("Authorization", authorizationHeader(s.accessToken))
        }.body()
        return result.items
    }

    /** Latest additions in one view (or across the library if [viewId] is null). */
    suspend fun getLatestItems(viewId: String?, limit: Int = 16): List<BaseItem> {
        val s = requireSession()
        return http.get("${s.baseUrl}/Users/${s.userId}/Items/Latest") {
            url.parameters.append("limit", limit.toString())
            if (viewId != null) url.parameters.append("parentId", viewId)
            header("Authorization", authorizationHeader(s.accessToken))
        }.body()
    }

    /** In-progress items — the "Continue watching" row. */
    suspend fun getResumeItems(limit: Int = 12): List<BaseItem> {
        val s = requireSession()
        return authGet<ItemsResult>(
            "Users/${s.userId}/Items/Resume",
            "limit" to limit.toString(),
            "mediaTypes" to "Video",
        ).items
    }

    /** Next unwatched episodes of followed shows — the "Next up" row. */
    suspend fun getNextUp(limit: Int = 12): List<BaseItem> {
        val s = requireSession()
        return authGet<ItemsResult>(
            "Shows/NextUp",
            "userId" to s.userId,
            "limit" to limit.toString(),
        ).items
    }

    /** Full item details (overview, runtime, genres, …). */
    suspend fun getItem(itemId: String): BaseItem {
        val s = requireSession()
        return authGet("Users/${s.userId}/Items/$itemId")
    }

    suspend fun getSeasons(seriesId: String): List<BaseItem> {
        val s = requireSession()
        return authGet<ItemsResult>(
            "Shows/$seriesId/Seasons",
            "userId" to s.userId,
        ).items
    }

    /** Episodes of a series, optionally restricted to one season. */
    suspend fun getEpisodes(seriesId: String, seasonId: String?): List<BaseItem> {
        val s = requireSession()
        val params = mutableListOf("userId" to s.userId)
        if (seasonId != null) params.add("seasonId" to seasonId)
        return authGet<ItemsResult>(
            "Shows/$seriesId/Episodes",
            *params.toTypedArray(),
        ).items
    }

    suspend fun search(query: String, limit: Int = 24): List<BaseItem> {
        val s = requireSession()
        return authGet<ItemsResult>(
            "Items",
            "userId" to s.userId,
            "searchTerm" to query,
            "recursive" to "true",
            "includeItemTypes" to "Movie,Series,Episode",
            "limit" to limit.toString(),
        ).items
    }

    private suspend inline fun <reified T> authGet(
        path: String,
        vararg params: Pair<String, String>,
    ): T {
        val s = requireSession()
        return http.get("${s.baseUrl}/$path") {
            params.forEach { url.parameters.append(it.first, it.second) }
            header("Authorization", authorizationHeader(s.accessToken))
        }.body()
    }

    /**
     * Direct-stream URL for a playable item: the original file, container and
     * all, served as-is (`static=true`) — the player does the work, not the
     * server. Transcoding fallback comes later via PlaybackInfo.
     */
    fun streamUrl(item: BaseItem): String? {
        val s = session ?: return null
        return "${s.baseUrl}/Videos/${item.id}/stream?static=true&api_key=${s.accessToken}"
    }

    /** Primary image URL for an item, or null if the item has none. */
    fun imageUrl(item: BaseItem, maxWidth: Int = 400): String? {
        val s = session ?: return null
        val tag = item.imageTags?.get("Primary") ?: return null
        return "${s.baseUrl}/Items/${item.id}/Images/Primary?maxWidth=$maxWidth&tag=$tag"
    }

    /** Tells the server playback started — enables "continue watching". */
    suspend fun reportPlaybackStart(itemId: String) =
        postPlaybackReport("Sessions/Playing", PlaybackReport(itemId))

    /** Periodic position update (Jellyfin ticks: 1 s = 10_000_000). */
    suspend fun reportPlaybackProgress(itemId: String, positionTicks: Long, isPaused: Boolean) =
        postPlaybackReport(
            "Sessions/Playing/Progress",
            PlaybackReport(itemId, positionTicks, isPaused),
        )

    /** Final position — the server stores it as the resume point. */
    suspend fun reportPlaybackStopped(itemId: String, positionTicks: Long) =
        postPlaybackReport("Sessions/Playing/Stopped", PlaybackReport(itemId, positionTicks))

    private suspend fun postPlaybackReport(path: String, report: PlaybackReport) {
        val s = requireSession()
        http.post("${s.baseUrl}/$path") {
            header("Authorization", authorizationHeader(s.accessToken))
            contentType(ContentType.Application.Json)
            setBody(report)
        }
    }

    private fun requireSession(): UserSession =
        session ?: throw IllegalStateException("Not logged in — call login() first")

    private fun authorizationHeader(accessToken: String?): String = buildString {
        append("MediaBrowser Client=\"$clientName\"")
        append(", Device=\"$deviceName\"")
        append(", DeviceId=\"$deviceId\"")
        append(", Version=\"$clientVersion\"")
        if (accessToken != null) append(", Token=\"$accessToken\"")
    }

    companion object {
        /** Jellyfin wire format: 1 tick = 100 ns. */
        const val TICKS_PER_SECOND = 10_000_000L

        fun millisecondsToTicks(milliseconds: Long): Long = milliseconds * 10_000L

        fun secondsToTicks(seconds: Double): Long = (seconds * TICKS_PER_SECOND).toLong()

        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        /** Ordered connection attempts for what the user typed. */
        fun candidateUrls(raw: String): List<String> {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                listOf(trimmed)
            } else {
                listOf("https://$trimmed", "http://$trimmed")
            }
        }
    }
}
