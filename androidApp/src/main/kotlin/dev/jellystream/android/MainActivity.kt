package dev.jellystream.android

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.PersistedSession
import dev.jellystream.shared.UserSession
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Keep content clear of status bar, cutout AND keyboard (IME):
                    // the centered login form deliberately shifts up when typing
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        JellystreamApp()
                    }
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data class Detail(val item: BaseItem) : Screen
    data class Series(val item: BaseItem) : Screen
    data object Search : Screen
}

/** App-private storage for the session blob (shared module owns the format). */
private class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("jellystream_session", Context.MODE_PRIVATE)

    fun load(): PersistedSession? =
        prefs.getString("session", null)?.let { PersistedSession.fromJson(it) }

    fun save(persisted: PersistedSession) {
        prefs.edit().putString("session", persisted.toJson()).apply()
    }

    fun clear() {
        prefs.edit().remove("session").apply()
    }
}

@Composable
private fun JellystreamApp() {
    val context = LocalContext.current
    val store = remember { SessionStore(context) }
    val persisted = remember { store.load() }
    // Jellyfin ties sessions to DeviceId — reuse the stored one so the server
    // sees the same device across launches
    val deviceId = remember { persisted?.deviceId ?: UUID.randomUUID().toString() }
    val api = remember {
        JellyfinApi(
            deviceName = Build.MODEL,
            deviceId = deviceId,
        ).also { restored -> persisted?.let { restored.restoreSession(it.session) } }
    }
    var session by remember { mutableStateOf(persisted?.session) }
    var playing by remember { mutableStateOf<BaseItem?>(null) }
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()

    fun open(item: BaseItem) {
        when {
            item.isSeries -> backStack.add(Screen.Series(item))
            item.isPlayable -> backStack.add(Screen.Detail(item))
        }
    }

    when (val s = session) {
        null -> LoginScreen(
            api,
            onLoggedIn = { logged ->
                session = logged
                store.save(PersistedSession(deviceId, logged))
            },
        )
        // The player is an overlay: the navigation stack stays composed
        // underneath so its state survives closing the player
        else -> Box {
            when (val screen = backStack.last()) {
                Screen.Home -> HomeScreen(
                    api = api,
                    session = s,
                    onOpen = ::open,
                    onSearch = { backStack.add(Screen.Search) },
                    onLogout = {
                        // Best-effort server revocation; local state clears now
                        scope.launch { runCatching { api.logout() } }
                        store.clear()
                        backStack.clear()
                        backStack.add(Screen.Home)
                        session = null
                    },
                )
                is Screen.Detail -> DetailScreen(api, screen.item, onPlay = { playing = it })
                is Screen.Series -> SeriesScreen(api, screen.item, onPlay = { playing = it })
                Screen.Search -> SearchScreen(api, onOpen = ::open)
            }

            BackHandler(enabled = backStack.size > 1 && playing == null) {
                backStack.removeAt(backStack.lastIndex)
            }

            playing?.let { item ->
                PlayerScreen(api, item, onClose = { playing = null })
            }
        }
    }
}

@Composable
private fun LoginScreen(api: JellyfinApi, onLoggedIn: (UserSession) -> Unit) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Jellystream", style = MaterialTheme.typography.headlineLarge)

        val fieldModifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            singleLine = true,
            modifier = fieldModifier,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = fieldModifier,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = fieldModifier,
        )

        Button(
            enabled = !loading && serverUrl.isNotBlank(),
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    try {
                        onLoggedIn(api.login(serverUrl, username, password))
                    } catch (e: Exception) {
                        error = e.message ?: "Connection failed"
                    } finally {
                        loading = false
                    }
                }
            },
        ) {
            Text("Connect")
        }

        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private data class LibrarySection(val title: String, val key: String, val items: List<BaseItem>)

@Composable
private fun HomeScreen(
    api: JellyfinApi,
    session: UserSession,
    onOpen: (BaseItem) -> Unit,
    onSearch: () -> Unit,
    onLogout: () -> Unit,
) {
    var sections by remember { mutableStateOf<List<LibrarySection>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        try {
            val result = mutableListOf<LibrarySection>()
            runCatching { api.getResumeItems(12) }.getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let { result.add(LibrarySection("Continue Watching", "resume", it)) }
            runCatching { api.getNextUp(12) }.getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let { result.add(LibrarySection("Next Up", "nextup", it)) }
            api.getUserViews().forEach { view ->
                // One failing view must not blank the whole home screen
                val latest = runCatching { api.getLatestItems(view.id, 12) }
                    .getOrDefault(emptyList())
                result.add(LibrarySection(view.name ?: "Library", view.id, latest))
            }
            sections = result
        } catch (e: Exception) {
            error = e.message ?: "Failed to load library"
        }
    }

    when {
        error != null -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        sections == null -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        session.serverName ?: "Jellyfin",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Row {
                        IconButton(onClick = onSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = onLogout) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Log out",
                            )
                        }
                    }
                }
            }
            items(sections!!, key = { it.key }) { section ->
                LibraryRow(api, section, onOpen)
            }
        }
    }
}

@Composable
private fun LibraryRow(api: JellyfinApi, section: LibrarySection, onOpen: (BaseItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            section.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(section.items, key = { it.id }) { item ->
                PosterCard(api, item, onOpen)
            }
        }
    }
}

@Composable
private fun PosterCard(api: JellyfinApi, item: BaseItem, onOpen: (BaseItem) -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(enabled = item.isPlayable || item.isSeries) { onOpen(item) },
    ) {
        AsyncImage(
            model = api.imageUrl(item, 400),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(120.dp)
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            item.name ?: "",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
