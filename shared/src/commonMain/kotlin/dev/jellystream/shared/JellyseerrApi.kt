package dev.jellystream.shared

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
        val moved = baseUrl != serverUrl?.let { JellyfinApi.normalizeServerUrl(it) }
        baseUrl = serverUrl?.let { JellyfinApi.normalizeServerUrl(it) }
        cookie = sessionCookie
        // A different server is a different catalogue; keeping titles
        // resolved against the old one would put wrong names on rows.
        if (moved) titleCache.clear()
    }

    /**
     * Signs in with the Jellyfin account this profile already uses.
     *
     * Returns the session cookie to persist, or null when the server said
     * no. The password is never stored, here or by the caller.
     */
    @Throws(Throwable::class)
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
        // Titles were fetched with that session; a different account may
        // not be allowed to see the same ones.
        titleCache.clear()
    }

    /**
     * Films and shows matching [query]; people are dropped, since the
     * screen's only verb is "request".
     *
     * An unreachable server yields an empty list: a search box that goes
     * quiet is better than one that throws into the UI.
     */
    @Throws(Throwable::class)
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
     * Asks for a whole title: a film, or every season of a show.
     *
     * Season 0 is excluded server-side, so "all" never means the specials.
     */
    @Throws(Throwable::class)
    suspend fun request(tmdbId: Int, isSeries: Boolean): RequestOutcome =
        post(requestBody(tmdbId, isSeries, null))

    /**
     * Asks for named seasons of a show.
     *
     * Kept apart from [request] rather than folded in behind a default
     * argument: Kotlin defaults are not exported, so an extra parameter
     * would break every existing Swift call site.
     *
     * Jellyseerr drops any season it already has and only fails when
     * *nothing* is left — which is an answer ([RequestOutcome.AlreadyRequested]),
     * not an error worth showing as one.
     */
    @Throws(Throwable::class)
    suspend fun requestSeasons(tmdbId: Int, seasons: List<Int>): RequestOutcome {
        // Season 0 is the specials bucket, which "all" excludes anyway;
        // letting it through alone would silently ask for the whole show.
        val picked = seasons.filter { it > 0 }.distinct().sorted()
        if (picked.isEmpty()) return RequestOutcome.Failed("Pick at least one season")
        return post(requestBody(tmdbId, isSeries = true, seasons = picked))
    }

    private suspend fun post(body: JsonObject): RequestOutcome {
        val base = baseUrl ?: return RequestOutcome.Failed("No Jellyseerr server set")
        if (cookie == null) return RequestOutcome.NotSignedIn
        return try {
            val response: HttpResponse = http.post("$base/api/v1/request") {
                contentType(ContentType.Application.Json)
                authorize()
                setBody(body)
            }
            when {
                response.status.isSuccess() -> RequestOutcome.Sent
                response.status == HttpStatusCode.Conflict -> RequestOutcome.AlreadyRequested
                response.status == HttpStatusCode.Unauthorized ||
                    response.status == HttpStatusCode.Forbidden -> {
                    cookie = null
                    RequestOutcome.NotSignedIn
                }
                else -> {
                    val message = response.errorMessage()
                    // Asking for seasons that are all spoken for comes back
                    // as a plain failure with this text; it means the same
                    // thing a 409 does on a whole title.
                    if (message != null && message.contains("no seasons available", ignoreCase = true)) {
                        RequestOutcome.AlreadyRequested
                    } else {
                        RequestOutcome.Failed(message ?: "Jellyseerr said ${response.status.value}")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RequestOutcome.Failed(e.message ?: "Could not reach Jellyseerr")
        }
    }

    /**
     * A show's seasons, and what Jellyseerr makes of each one.
     *
     * Null on any failure, like every other read here: a season picker
     * that cannot load shows "couldn't reach Jellyseerr", never a crash.
     */
    @Throws(Throwable::class)
    suspend fun tvDetails(tmdbId: Int): JellyseerrTvDetails? = getOrNull("tv/$tmdbId")

    /** A film's detail page — fetched only to put a name on a request row. */
    @Throws(Throwable::class)
    suspend fun movieDetails(tmdbId: Int): JellyseerrMovieDetails? = getOrNull("movie/$tmdbId")

    private suspend inline fun <reified T> getOrNull(path: String): T? {
        val base = baseUrl ?: return null
        return try {
            val response: HttpResponse = http.get("$base/api/v1/$path") { authorize() }
            if (!response.status.isSuccess()) return null
            response.body<T>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** What this account has asked for, newest first. */
    @Throws(Throwable::class)
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

    /**
     * "My requests", each with a title, a poster and a progress bar.
     *
     * The list endpoint answers with TMDb ids and no names, so one detail
     * page is fetched per distinct title — the same thing Jellyseerr's own
     * web client does. They go out in small batches rather than all at
     * once: thirty simultaneous connections is a rude thing to do to a
     * server running on someone's NAS.
     *
     * A detail endpoint that will not answer costs the titles, not the
     * list — the rows still render, just as "Series request".
     */
    @Throws(Throwable::class)
    suspend fun myRequestsDetailed(limit: Int): List<RequestedTitle> {
        val rows = myRequests(limit)
        if (rows.isEmpty()) return emptyList()
        return try {
            coroutineScope {
                val keys = rows
                    .mapNotNull { row -> row.media?.tmdbId?.let { TitleKey(it, row.isSeries) } }
                    .distinct()

                // Only what we have never resolved. The screen polls every
                // few seconds while a download runs, and a name is not
                // going to change between two of those — without this the
                // progress bar is a load test against someone's NAS.
                // Reads and writes both happen out here, in the one
                // coroutine, so the map is never touched concurrently.
                val missing = keys.filter { it !in titleCache }
                val fetched = missing
                    .chunked(CONCURRENT_LOOKUPS)
                    .flatMap { batch -> batch.map { key -> async { key to lookUp(key) } }.awaitAll() }
                for ((key, card) in fetched) {
                    // Only successes are kept, so a server that was down
                    // for one poll is asked again on the next.
                    if (card != null) titleCache[key] = card
                }

                rows.map { row ->
                    val card = row.media?.tmdbId?.let { titleCache[TitleKey(it, row.isSeries)] }
                    RequestedTitle(row, card?.title, card?.posterPath, card?.year)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rows.map { RequestedTitle(it) }
        }
    }

    private data class TitleKey(val tmdbId: Int, val isSeries: Boolean)

    private data class TitleCard(val title: String?, val posterPath: String?, val year: String?)

    private val titleCache = mutableMapOf<TitleKey, TitleCard>()

    private suspend fun lookUp(key: TitleKey): TitleCard? =
        if (key.isSeries) {
            tvDetails(key.tmdbId)?.let { TitleCard(it.name, it.posterPath, it.year) }
        } else {
            movieDetails(key.tmdbId)?.let { TitleCard(it.title, it.posterPath, it.year) }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        cookie?.let { header("Cookie", it) }
    }

    internal fun requestBody(tmdbId: Int, isSeries: Boolean, seasons: List<Int>?): JsonObject =
        buildJsonObject {
            put("mediaType", if (isSeries) "tv" else "movie")
            put("mediaId", tmdbId)
            // "seasons" on a film is what Jellyseerr answers with a 500
            if (isSeries) {
                if (seasons.isNullOrEmpty()) {
                    put("seasons", "all")
                } else {
                    putJsonArray("seasons") { seasons.forEach { add(it) } }
                }
            }
        }

    companion object {
        private const val SESSION_COOKIE = "connect.sid="

        /** Detail lookups in flight at once while filling in the request list. */
        private const val CONCURRENT_LOOKUPS = 6

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

/**
 * Jellyseerr explains itself in a `{"message": ...}` body. Showing that
 * beats "Jellyseerr said 500", which tells the user nothing they can act
 * on — and a body that will not parse simply falls back to the code.
 */
private suspend fun HttpResponse.errorMessage(): String? = try {
    Json.parseToJsonElement(bodyAsText())
        .jsonObject["message"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
