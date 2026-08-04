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

/** A server URL that answered `System/Info/Public`, plus what it answered. */
data class ResolvedServer(
    val baseUrl: String,
    val info: PublicSystemInfo,
)

/** An authenticated session against one server. */
data class UserSession(
    val baseUrl: String,
    val userId: String,
    val accessToken: String,
    val userName: String?,
    val serverName: String?,
)

@Serializable
data class BaseItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<BaseItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
)

@Serializable
internal data class AuthenticateByNameRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)
