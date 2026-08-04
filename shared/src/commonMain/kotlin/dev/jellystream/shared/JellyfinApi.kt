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

    /** Unauthenticated ping — validates the URL points at a Jellyfin server. */
    suspend fun getPublicSystemInfo(serverUrl: String): PublicSystemInfo =
        http.get("${normalizeServerUrl(serverUrl)}/System/Info/Public").body()

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
    }
}
