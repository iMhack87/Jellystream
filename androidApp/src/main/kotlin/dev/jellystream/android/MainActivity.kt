package dev.jellystream.android

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.PersistedSession
import dev.jellystream.shared.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JellystreamTheme {
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

    fun goBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
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
                    onPlay = { playing = it },
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
                is Screen.Detail -> DetailScreen(
                    api,
                    screen.item,
                    onPlay = { playing = it },
                    onBack = ::goBack,
                )
                is Screen.Series -> SeriesScreen(
                    api,
                    screen.item,
                    onPlay = { playing = it },
                    onBack = ::goBack,
                )
                Screen.Search -> SearchScreen(api, onOpen = ::open, onBack = ::goBack)
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
    var quickConnectCode by remember { mutableStateOf<String?>(null) }

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
            modifier = Modifier.dpadFocusEffect(RoundedCornerShape(10.dp)),
        ) {
            Text("Connect")
        }

        // Quick Connect: no on-screen keyboard needed for username/password
        Text(
            "Use Quick Connect",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .dpadFocusEffect(RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !loading && serverUrl.isNotBlank()) {
                    scope.launch {
                        loading = true
                        error = null
                        quickConnectCode = null
                        try {
                            val (baseUrl, initial) = api.initiateQuickConnect(serverUrl)
                            quickConnectCode = initial.code
                            var state = initial
                            // Jellyfin codes expire (~5 min); stop polling
                            // rather than hanging forever on a dead code
                            val deadline = System.currentTimeMillis() + 5 * 60_000
                            while (!state.authenticated) {
                                if (System.currentTimeMillis() > deadline) {
                                    error = "Quick Connect code expired — try again"
                                    return@launch
                                }
                                delay(3_000)
                                state = api.getQuickConnectState(baseUrl, initial.secret)
                            }
                            onLoggedIn(
                                api.authenticateWithQuickConnect(baseUrl, initial.secret)
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Quick Connect failed"
                        } finally {
                            loading = false
                            quickConnectCode = null
                        }
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        quickConnectCode?.let { code ->
            Text(
                code,
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                "Enter this code in Jellyfin on your phone or browser",
                style = MaterialTheme.typography.bodyMedium,
                color = CinemaColors.TextSecondary,
            )
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
    onPlay: (BaseItem) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            // Never strand the user on a dead server: offer a way out
            Button(
                onClick = onLogout,
                modifier = Modifier.dpadFocusEffect(RoundedCornerShape(10.dp)),
            ) {
                Text("Log out")
            }
        }
        sections == null -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        else -> {
            // Hero must be openable: first playable item or series across sections
            val hero = sections!!.asSequence()
                .flatMap { it.items }
                .firstOrNull { it.isPlayable || it.isSeries }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                item(key = "hero") {
                    HeroSection(
                        api = api,
                        item = hero,
                        onOpen = onOpen,
                        onPlay = onPlay,
                        onSearch = onSearch,
                        onLogout = onLogout,
                    )
                }
                items(sections!!, key = { it.key }) { section ->
                    if (section.key == "resume" || section.key == "nextup") {
                        ContinueRow(api, section, onOpen)
                    } else {
                        LibraryRow(api, section, onOpen)
                    }
                }
            }
        }
    }
}

/** Full-bleed backdrop with a gradient into the background — the ATV+ hero. */
@Composable
private fun HeroSection(
    api: JellyfinApi,
    item: BaseItem?,
    onOpen: (BaseItem) -> Unit,
    onPlay: (BaseItem) -> Unit,
    onSearch: () -> Unit,
    onLogout: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(460.dp),
    ) {
        if (item != null) {
            AsyncImage(
                model = api.backdropUrl(item, 1280),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onOpen(item) },
            )
        }
        // Cinematic scrim: image melts into the page background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1.0f to CinemaColors.Background,
                    )
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
            }
            IconButton(onClick = onLogout) {
                Icon(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = "Log out",
                    tint = Color.White,
                )
            }
        }

        if (item != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    item.name ?: "",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    item.episodeLabel?.let { add("${item.seriesName ?: ""} $it".trim()) }
                    item.productionYear?.let { add(it.toString()) }
                    item.runtimeMinutes?.let { add("$it min") }
                }.joinToString("  ·  ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaColors.TextSecondary,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.isPlayable) {
                        Button(
                            onClick = { onPlay(item) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.dpadFocusEffect(RoundedCornerShape(10.dp)),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (item.resumePositionSeconds > 60) "Resume" else "Play")
                        }
                    }
                    Text(
                        "Details",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier
                            .dpadFocusEffect(RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpen(item) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Landscape cards with a progress bar — Continue Watching / Next Up. */
@Composable
private fun ContinueRow(api: JellyfinApi, section: LibrarySection, onOpen: (BaseItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            section.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(section.items, key = { it.id }) { item ->
                Column(
                    modifier = Modifier
                        .width(248.dp)
                        .dpadFocusEffect()
                        .clickable(enabled = item.isPlayable || item.isSeries) { onOpen(item) },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(248.dp)
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CinemaColors.SurfaceVariant),
                    ) {
                        AsyncImage(
                            model = api.backdropUrl(item, 600),
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        item.playedFraction?.let { fraction ->
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .background(CinemaColors.ProgressTrack),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction.toFloat())
                                        .height(4.dp)
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                    Column {
                        Text(
                            item.name ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = item.episodeLabel?.let { "${item.seriesName ?: ""} $it".trim() }
                        if (sub != null) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(api: JellyfinApi, section: LibrarySection, onOpen: (BaseItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            section.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
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
            .width(132.dp)
            .dpadFocusEffect(RoundedCornerShape(10.dp))
            .clickable(enabled = item.isPlayable || item.isSeries) { onOpen(item) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(132.dp)
                .height(198.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CinemaColors.SurfaceVariant),
        ) {
            AsyncImage(
                model = api.imageUrl(item, 400),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            item.name ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = CinemaColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
