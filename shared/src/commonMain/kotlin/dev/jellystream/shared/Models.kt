package dev.jellystream.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("ProductName") val productName: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class AuthenticationResult(
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("User") val user: UserDto? = null,
)

@Serializable
internal data class AuthenticateByNameRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)
