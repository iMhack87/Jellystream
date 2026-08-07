package dev.jellystream.shared

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a request could not be made, in words a screen can show. */
sealed class RequestOutcome {
    data object Sent : RequestOutcome()

    /** Jellyseerr already knows about it — someone got there first. */
    data object AlreadyRequested : RequestOutcome()

    /** The session died; the user has to sign in to Jellyseerr again. */
    data object NotSignedIn : RequestOutcome()

    data class Failed(val message: String) : RequestOutcome()
}

/**
 * Jellyseerr client: search for what the library does not have, ask for it,
 * and follow what you asked for.
 *
 * Authentication is the user's own Jellyfin account, on purpose. Jellyseerr
 * also accepts an admin API key, but then every profile on the television
 * requests as the same person — quotas stop meaning anything and the
 * history cannot say who asked. The cost is one password entry per profile,
 * and only the session cookie is kept.
 */
class JellyseerrApi {

    private val http = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // Errors are read from the status code here rather than thrown:
        // "already requested" is a 409 and is not a failure worth an
        // exception, it is an answer.
        expectSuccess = false
    }

    private var baseUrl: String? = null
    private var cookie: String? = null

    val isConfigured: Boolean
        get() = baseUrl != null

    val isSignedIn: Boolean
        get() = baseUrl != null && cookie != null

    /** Adopts a server and, if there is one, a session restored from storage. */
    fun configure(serverUrl: String?, sessionCookie: String?) {
        baseUrl = serverUrl?.let { JellyfinApi.normalizeServerUrl(it) }
        cookie = sessionCookie
    }

    /**
     * Signs in with the Jellyfin account this profile already uses.
     *
     * Returns the session cookie to persist, or null when the server said
     * no. The password is never stored, here or by the caller.
     */
    suspend fun signIn(username: String, password: String): String? {
        val base = baseUrl ?: return null
        return try {
            val response: HttpResponse = http.post("$base/api/v1/auth/jellyfin") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("username", username)
                        put("password", password)
                    }
                )
            }
            if (!response.status.isSuccess()) return null
            // Jellyseerr answers with a session cookie and nothing else of use
            val issued = response.headers.getAll("Set-Cookie")
                ?.firstOrNull { it.startsWith(SESSION_COOKIE) }
                ?.substringBefore(';')
            issued?.also { cookie = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun signOut() {
        cookie = null
    }

    /**
     * Films and shows matching [query]; people are dropped, since the
     * screen's only verb is "request".
     *
     * An unreachable server yields an empty list: a search box that goes
     * quiet is better than one that throws into the UI.
     */
    suspend fun search(query: String): List<JellyseerrResult> {
        val base = baseUrl ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return try {
            val response: HttpResponse = http.get("$base/api/v1/search") {
                url.parameters.append("query", query)
                authorize()
            }
            if (!response.status.isSuccess()) return emptyList()
            response.body<JellyseerrSearchResponse>().results
                .filter { it.isRequestableKind }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Asks for a title. Series are requested whole — picking seasons is a
     * screen of its own, and "all of it" is what nearly everyone means.
     */
    suspend fun request(tmdbId: Int, isSeries: Boolean): RequestOutcome {
        val base = baseUrl ?: return RequestOutcome.Failed("No Jellyseerr server set")
        if (cookie == null) return RequestOutcome.NotSignedIn
        return try {
            val response: HttpResponse = http.post("$base/api/v1/request") {
                contentType(ContentType.Application.Json)
                authorize()
                setBody(requestBody(tmdbId, isSeries))
            }
            when {
                response.status.isSuccess() -> RequestOutcome.Sent
                response.status == HttpStatusCode.Conflict -> RequestOutcome.AlreadyRequested
                response.status == HttpStatusCode.Unauthorized ||
                    response.status == HttpStatusCode.Forbidden -> {
                    cookie = null
                    RequestOutcome.NotSignedIn
                }
                else -> RequestOutcome.Failed("Jellyseerr said ${response.status.value}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RequestOutcome.Failed(e.message ?: "Could not reach Jellyseerr")
        }
    }

    /** What this account has asked for, newest first. */
    suspend fun myRequests(limit: Int = 30): List<JellyseerrRequest> {
        val base = baseUrl ?: return emptyList()
        if (cookie == null) return emptyList()
        return try {
            val response: HttpResponse = http.get("$base/api/v1/request") {
                url.parameters.append("take", limit.toString())
                url.parameters.append("sort", "added")
                // The endpoint defaults to everyone's requests for an admin
                url.parameters.append("requestedBy", "me")
                authorize()
            }
            if (!response.status.isSuccess()) return emptyList()
            response.body<JellyseerrRequestPage>().results
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        cookie?.let { header("Cookie", it) }
    }

    internal fun requestBody(tmdbId: Int, isSeries: Boolean): JsonObject = buildJsonObject {
        put("mediaType", if (isSeries) "tv" else "movie")
        put("mediaId", tmdbId)
        if (isSeries) put("seasons", "all")
    }

    companion object {
        private const val SESSION_COOKIE = "connect.sid="

        /**
         * Jellyseerr proxies TMDb artwork; the path it returns is relative
         * to TMDb's own image host, not to the Jellyseerr server.
         */
        fun posterUrl(posterPath: String?, width: Int = 342): String? {
            val path = posterPath?.takeIf { it.isNotBlank() } ?: return null
            val leading = if (path.startsWith("/")) path else "/$path"
            return "https://image.tmdb.org/t/p/w$width$leading"
        }
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
