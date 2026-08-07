package dev.jellystream.shared

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Minimal Jellyfin API client. Grows with the app; anything protocol-related
 * belongs here in the shared module, never in platform code.
 */
class JellyfinApi(
    private val clientName: String = CLIENT_NAME,
    private val clientVersion: String = CLIENT_VERSION,
    private val deviceName: String,
    private val deviceId: String,
) {
    private val http = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // Non-2xx must throw so a revoked token surfaces as a 401 instead
        // of a JSON parse error on the error page
        expectSuccess = true
        HttpResponseValidator {
            handleResponseExceptionWithRequest { exception, _ ->
                if (exception is ClientRequestException &&
                    exception.response.status == HttpStatusCode.Unauthorized &&
                    session != null
                ) {
                    // Only an established session can expire — a wrong
                    // password during login is a plain failure, not this
                    onUnauthorized?.invoke()
                }
            }
        }
    }

    /**
     * Invoked (possibly off the main thread) when the server rejects the
     * current session's token: it was revoked or expired, and every further
     * call will fail. Platforms route back to sign-in. The original
     * exception still propagates to the caller.
     */
    var onUnauthorized: (() -> Unit)? = null

    private var session: UserSession? = null

    val currentSession: UserSession?
        get() = session

    /** Adopts a session restored from persistent storage. */
    fun restoreSession(restored: UserSession) {
        session = restored
    }

    /**
     * Best-effort server-side revocation, then drops the local session.
     * Network failure is swallowed: the local state must clear regardless.
     */
    suspend fun logout() {
        val s = session
        session = null
        if (s != null) {
            try {
                http.post("${s.baseUrl}/Sessions/Logout") {
                    header("Authorization", authorizationHeader(s.accessToken))
                }
            } catch (e: Exception) {
                // Token stays valid server-side; nothing more we can do offline
            }
        }
    }

    /** Authorization header value for platform players (stream requests). */
    fun streamAuthorizationHeader(): String? =
        session?.let { authorizationHeader(it.accessToken) }

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

    /**
     * Quick Connect: the TV shows a code, the user approves it from a phone
     * or the web UI, and the session lands here — no on-screen keyboard.
     * Returns the initial state (code to display + secret to poll with).
     */
    suspend fun initiateQuickConnect(rawUrl: String): Pair<String, QuickConnectState> {
        val server = resolveServer(rawUrl)
        val state: QuickConnectState = http.post("${server.baseUrl}/QuickConnect/Initiate") {
            header("Authorization", authorizationHeader(accessToken = null))
        }.body()
        return server.baseUrl to state
    }

    suspend fun getQuickConnectState(baseUrl: String, secret: String): QuickConnectState =
        try {
            http.get("$baseUrl/QuickConnect/Connect") {
                url.parameters.append("secret", secret)
                header("Authorization", authorizationHeader(accessToken = null))
            }.body()
        } catch (e: ClientRequestException) {
            // The secret rides in the query string (protocol requirement)
            // and ktor embeds the full URL in its message — never let it
            // reach an on-screen error
            throw IllegalStateException(
                "Quick Connect failed (HTTP ${e.response.status.value})"
            )
        }

    /** Exchanges an approved Quick Connect secret for a real session. */
    suspend fun authenticateWithQuickConnect(baseUrl: String, secret: String): UserSession {
        val auth: AuthenticationResult =
            http.post("$baseUrl/Users/AuthenticateWithQuickConnect") {
                header("Authorization", authorizationHeader(accessToken = null))
                contentType(ContentType.Application.Json)
                setBody(QuickConnectAuthRequest(secret))
            }.body()
        val token = auth.accessToken
            ?: throw IllegalStateException("Server did not return an access token")
        val userId = auth.user?.id
            ?: throw IllegalStateException("Server did not return a user id")
        val info = runCatching { getPublicSystemInfo(baseUrl) }.getOrNull()
        return UserSession(
            baseUrl = baseUrl,
            userId = userId,
            accessToken = token,
            userName = auth.user?.name,
            serverName = info?.serverName,
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
        val params = mutableListOf(
            "userId" to s.userId,
            // Synopsis and air date feed the ATV-style episode cards;
            // neither is in the default episode DTO
            "fields" to "Overview,PremiereDate",
        )
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

    /**
     * Intro/outro/etc. markers for one item (Jellyfin 10.10+). Empty when
     * the server has no segment provider (e.g. no Intro Skipper plugin) —
     * and on any failure: segments are progressive enhancement, they must
     * never break playback.
     */
    suspend fun getMediaSegments(itemId: String): List<MediaSegment> =
        try {
            authGet<MediaSegmentsResult>("MediaSegments/$itemId").items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
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
     * server. No token in the URL (it would leak into proxy/player logs):
     * players must send [streamAuthorizationHeader] as the Authorization
     * header.
     */
    fun streamUrl(item: BaseItem): String? {
        val s = session ?: return null
        return "${s.baseUrl}/Videos/${item.id}/stream?static=true"
    }

    /**
     * Negotiates playback with the server: Direct Play when the device
     * profile allows it, otherwise the server's HLS transcode; also surfaces
     * external text subtitles the player must side-load.
     */
    suspend fun getPlaybackPlan(item: BaseItem, forceTranscode: Boolean = false): PlaybackPlan {
        val s = requireSession()
        val fallback = PlaybackPlan(
            url = "${s.baseUrl}/Videos/${item.id}/stream?static=true",
            isTranscode = false,
            externalSubtitles = emptyList(),
        )
        val startTicks = (item.resumePositionSeconds * TICKS_PER_SECOND).toLong()
        val info: PlaybackInfoResponse = try {
            http.post("${s.baseUrl}/Items/${item.id}/PlaybackInfo") {
                header("Authorization", authorizationHeader(s.accessToken))
                contentType(ContentType.Application.Json)
                setBody(playbackInfoBody(s.userId, forceTranscode, startTicks))
            }.body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A server that can't negotiate must not block playback
            return fallback
        }
        val source = info.mediaSources.firstOrNull() ?: return fallback

        val subtitles = source.mediaStreams.orEmpty()
            .filter { it.type == "Subtitle" && it.isExternal && it.deliveryUrl != null }
            .map {
                ExternalSubtitle(
                    url = joinUrl(s.baseUrl, it.deliveryUrl!!),
                    language = it.language,
                    title = it.displayTitle,
                    codec = it.codec,
                )
            }

        val subtitleStreams = source.mediaStreams.orEmpty().filter { it.isSubtitle }
        // The audio the server will actually play: its default track, else
        // the first one. Which language it is decides whether the smart
        // default puts full subtitles on.
        val audioStreams = source.mediaStreams.orEmpty().filter { it.isAudio }
        val audioLanguage = (audioStreams.firstOrNull { it.isDefault } ?: audioStreams.firstOrNull())
            ?.language

        val transcodingUrl = source.transcodingUrl
        return if ((!source.supportsDirectPlay || forceTranscode) && transcodingUrl != null) {
            PlaybackPlan(
                url = joinUrl(s.baseUrl, transcodingUrl),
                isTranscode = true,
                externalSubtitles = subtitles,
                playSessionId = info.playSessionId,
                startOffsetSeconds = startTicks / TICKS_PER_SECOND.toDouble(),
                subtitleStreams = subtitleStreams,
                audioLanguage = audioLanguage,
                mediaSourceId = source.id,
                container = source.container,
            )
        } else {
            val mediaSourceParam = source.id?.let { "&mediaSourceId=$it" } ?: ""
            PlaybackPlan(
                url = "${s.baseUrl}/Videos/${item.id}/stream?static=true$mediaSourceParam",
                isTranscode = false,
                externalSubtitles = subtitles,
                playSessionId = info.playSessionId,
                subtitleStreams = subtitleStreams,
                audioLanguage = audioLanguage,
                mediaSourceId = source.id,
                container = source.container,
            )
        }
    }

    /**
     * One subtitle track as WebVTT, already parsed.
     *
     * Jellyfin converts any text track on request, embedded ones included,
     * which is what lets a player own the cue list. Android needs that to
     * shift subtitle timing at all: Media3 hands a cue over at the moment
     * it starts, so it can be held back but never brought forward.
     *
     * Never throws — a track that will not download costs the resync, not
     * the film.
     */
    suspend fun getSubtitleCues(
        itemId: String,
        mediaSourceId: String,
        streamIndex: Int,
    ): List<SubtitleCue> {
        val s = requireSession()
        return try {
            val body: String = http.get(
                "${s.baseUrl}/Videos/$itemId/$mediaSourceId/Subtitles/$streamIndex/0/Stream.vtt"
            ) {
                header("Authorization", authorizationHeader(s.accessToken))
            }.body()
            VttParser.parse(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Whether this account may download original files.
     *
     * Null when the server would not say — the caller treats that as
     * allowed, since the download itself either works or comes back 401,
     * whereas a hidden button on a permissive server has no way back.
     */
    suspend fun canDownload(): Boolean? {
        val s = session ?: return null
        return try {
            val user: UserDto = authGet("Users/${s.userId}")
            user.policy?.enableContentDownloading
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The original file, untouched — the same bytes Direct Play streams.
     * Null without a session.
     */
    fun downloadUrl(itemId: String): String? {
        val s = session ?: return null
        return "${s.baseUrl}/Items/$itemId/Download"
    }

    /** Container of the file that would be downloaded, for its extension. */
    suspend fun containerOf(item: BaseItem): String? = try {
        getPlaybackPlan(item, forceTranscode = false).container
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun joinUrl(baseUrl: String, path: String): String =
        if (path.startsWith("/")) "$baseUrl$path" else "$baseUrl/$path"

    private fun playbackInfoBody(
        userId: String,
        forceTranscode: Boolean,
        startTimeTicks: Long,
    ): JsonObject = buildJsonObject {
        put("UserId", userId)
        put("AutoOpenLiveStream", false)
        // Lets the server start a transcode at the resume point
        if (startTimeTicks > 0) put("StartTimeTicks", startTimeTicks)
        if (forceTranscode) {
            put("EnableDirectPlay", false)
            put("EnableDirectStream", false)
        }
        put("DeviceProfile", deviceProfile())
    }

    /**
     * What our players can Direct Play. Both engines are FFmpeg-backed, so
     * the profile is broad; the server only transcodes what falls outside it.
     */
    private fun deviceProfile(): JsonObject = buildJsonObject {
        putJsonArray("DirectPlayProfiles") {
            add(
                buildJsonObject {
                    put("Type", "Video")
                    put("Container", "mkv,mp4,m4v,webm,mov,ts,avi")
                    put("VideoCodec", "h264,hevc,vp9,av1,mpeg4,mpeg2video,vc1")
                    put(
                        "AudioCodec",
                        "aac,mp3,ac3,eac3,opus,flac,vorbis,dts,truehd,pcm_s16le,pcm_s24le",
                    )
                }
            )
        }
        putJsonArray("TranscodingProfiles") {
            add(
                buildJsonObject {
                    put("Type", "Video")
                    put("Container", "ts")
                    put("Protocol", "hls")
                    put("VideoCodec", "h264")
                    put("AudioCodec", "aac")
                    put("Context", "Streaming")
                }
            )
        }
        putJsonArray("SubtitleProfiles") {
            for (format in listOf("srt", "subrip", "vtt", "webvtt")) {
                add(
                    buildJsonObject {
                        put("Format", format)
                        put("Method", "External")
                    }
                )
            }
            for (format in listOf("ass", "ssa", "pgssub", "dvdsub", "subrip")) {
                add(
                    buildJsonObject {
                        put("Format", format)
                        put("Method", "Embed")
                    }
                )
            }
        }
    }

    /**
     * Wide backdrop image for heroes/detail headers: the item's own backdrop,
     * else its series' backdrop (episodes), else the primary image.
     */
    fun backdropUrl(item: BaseItem, maxWidth: Int = 1280): String? {
        val s = session ?: return null
        item.backdropImageTags?.firstOrNull()?.let { tag ->
            return "${s.baseUrl}/Items/${item.id}/Images/Backdrop?maxWidth=$maxWidth&tag=$tag"
        }
        val parentId = item.parentBackdropItemId
        item.parentBackdropImageTags?.firstOrNull()?.let { tag ->
            if (parentId != null) {
                return "${s.baseUrl}/Items/$parentId/Images/Backdrop?maxWidth=$maxWidth&tag=$tag"
            }
        }
        return imageUrl(item, maxWidth)
    }

    /** Primary image URL for an item, or null if the item has none. */
    fun imageUrl(item: BaseItem, maxWidth: Int = 400): String? {
        val s = session ?: return null
        val tag = item.imageTags?.get("Primary") ?: return null
        return "${s.baseUrl}/Items/${item.id}/Images/Primary?maxWidth=$maxWidth&tag=$tag"
    }

    /** Tells the server playback started — enables "continue watching". */
    suspend fun reportPlaybackStart(itemId: String, playSessionId: String? = null) =
        postPlaybackReport(
            "Sessions/Playing",
            PlaybackReport(itemId, playSessionId = playSessionId),
        )

    /** Periodic position update (Jellyfin ticks: 1 s = 10_000_000). */
    suspend fun reportPlaybackProgress(
        itemId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playSessionId: String? = null,
    ) = postPlaybackReport(
        "Sessions/Playing/Progress",
        PlaybackReport(itemId, positionTicks, isPaused, playSessionId = playSessionId),
    )

    /**
     * Final position — the server stores it as the resume point, and uses
     * PlaySessionId to terminate any transcode job still running for it.
     */
    suspend fun reportPlaybackStopped(
        itemId: String,
        positionTicks: Long,
        playSessionId: String? = null,
    ) = postPlaybackReport(
        "Sessions/Playing/Stopped",
        PlaybackReport(itemId, positionTicks, playSessionId = playSessionId),
    )

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
        /**
         * How this client identifies itself to Jellyfin — and what the
         * About section shows. One constant so the two apps can never drift
         * apart in the `MediaBrowser` header the server logs per device.
         */
        const val CLIENT_NAME = "Jellystream"
        const val CLIENT_VERSION = "0.1.0"

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

        /**
         * True when a scheme-less input could only be reached over plain
         * http — credentials and streams would travel unencrypted, so the
         * user must confirm BEFORE anything sensitive is sent. Explicit
         * "http://" input is the user's own choice and doesn't trip this.
         */
        fun isInsecureDowngrade(rawInput: String, resolvedBaseUrl: String): Boolean =
            !rawInput.trim().startsWith("http://") &&
                resolvedBaseUrl.startsWith("http://")

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
