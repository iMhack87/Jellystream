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

    /** Primary image URL for an item, or null if the item has none. */
    fun imageUrl(item: BaseItem, maxWidth: Int = 400): String? {
        val s = session ?: return null
        val tag = item.imageTags?.get("Primary") ?: return null
        return "${s.baseUrl}/Items/${item.id}/Images/Primary?maxWidth=$maxWidth&tag=$tag"
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
