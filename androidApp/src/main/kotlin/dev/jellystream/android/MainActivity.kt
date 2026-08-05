package dev.jellystream.android

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jellystream.shared.AppSettings
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.PersistedProfiles
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
    data object Settings : Screen
}

/** App-private storage for the profiles blob (shared module owns the format). */
private class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("jellystream_session", Context.MODE_PRIVATE)

    fun loadProfiles(): PersistedProfiles {
        prefs.getString("profiles", null)
            ?.let { PersistedProfiles.fromJson(it) }
            ?.let { return it }
        // Pre-profiles installs stored a single session — adopt it as the
        // first profile so nobody has to log in again after updating
        prefs.getString("session", null)
            ?.let { PersistedSession.fromJson(it) }
            ?.let { legacy ->
                val migrated = PersistedProfiles(listOf(legacy))
                saveProfiles(migrated)
                prefs.edit().remove("session").apply()
                return migrated
            }
        return PersistedProfiles(emptyList())
    }

    fun saveProfiles(profiles: PersistedProfiles) {
        prefs.edit().putString("profiles", profiles.toJson()).apply()
    }
}

@Composable
private fun JellystreamApp() {
    val context = LocalContext.current
    val store = remember { SessionStore(context) }
    val settingsStore = remember { SettingsStore(context) }
    var profiles by remember { mutableStateOf(store.loadProfiles()) }
    // Every profile's preferences, including the inactive ones
    var storedSettings by remember { mutableStateOf(settingsStore.load()) }
    // A single known account enters directly (pre-profiles behavior);
    // several → "who's watching" picker. After logout, active goes null
    // and the picker (or login) takes over.
    var active by remember { mutableStateOf(profiles.profiles.singleOrNull()) }
    var addingProfile by remember { mutableStateOf(false) }

    val current = active
    when {
        current != null ->
            // key() drops every screen/backstack state on profile switch
            key(current.profileKey) {
                SignedInApp(
                    profile = current,
                    settings = storedSettings.forProfile(current.profileKey),
                    onSettingsChange = {
                        storedSettings = storedSettings
                            .withSettings(current.profileKey, it)
                            .also(settingsStore::save)
                    },
                    onLoggedOut = {
                        profiles = profiles.withoutProfile(current)
                            .also(store::saveProfiles)
                        // Nothing should outlive an account the install no
                        // longer knows
                        storedSettings = storedSettings
                            .withoutProfile(current.profileKey)
                            .also(settingsStore::save)
                        addingProfile = false
                        active = null
                    },
                    // Back to the picker, keeping the account — the only
                    // path from a single-profile install to a second one
                    onSwitchProfile = {
                        addingProfile = false
                        active = null
                    },
                )
            }
        profiles.profiles.isEmpty() || addingProfile -> {
            // New accounts get a fresh DeviceId — two users sharing one
            // would revoke each other's tokens server-side
            val loginDeviceId = remember { UUID.randomUUID().toString() }
            val loginApi = remember {
                JellyfinApi(deviceName = Build.MODEL, deviceId = loginDeviceId)
            }
            LoginScreen(
                api = loginApi,
                onLoggedIn = { logged ->
                    val profile = PersistedSession(loginDeviceId, logged)
                    profiles = profiles.withProfile(profile).also(store::saveProfiles)
                    addingProfile = false
                    active = profile
                },
                onCancel = if (profiles.profiles.isEmpty()) {
                    null
                } else {
                    { addingProfile = false }
                },
            )
        }
        else -> ProfilePickerScreen(
            profiles = profiles.profiles,
            onSelect = { active = it },
            onAdd = { addingProfile = true },
        )
    }
}

@Composable
private fun SignedInApp(
    profile: PersistedSession,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onLoggedOut: () -> Unit,
    onSwitchProfile: () -> Unit,
) {
    val api = remember {
        JellyfinApi(
            deviceName = Build.MODEL,
            deviceId = profile.deviceId,
        ).also { it.restoreSession(profile.session) }
    }
    var playing by remember { mutableStateOf<BaseItem?>(null) }
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()

    // The server rejected our token: the session is dead and every call
    // will 401. Route back to sign-in (the callback may fire off-main).
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(api) {
        api.onUnauthorized = { mainHandler.post(onLoggedOut) }
        onDispose { api.onUnauthorized = null }
    }

    fun open(item: BaseItem) {
        when {
            item.isSeries -> backStack.add(Screen.Series(item))
            item.isPlayable -> backStack.add(Screen.Detail(item))
        }
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun logout() {
        // Best-effort server revocation; local state clears now
        scope.launch { runCatching { api.logout() } }
        onLoggedOut()
    }

    // The player is an overlay: the navigation stack stays composed
    // underneath so its state survives closing the player
    CompositionLocalProvider(LocalAppSettings provides settings) {
        Box {
            when (val screen = backStack.last()) {
                Screen.Home -> HomeScreen(
                    api = api,
                    session = profile.session,
                    onOpen = ::open,
                    onPlay = { playing = it },
                    onSearch = { backStack.add(Screen.Search) },
                    onSettings = { backStack.add(Screen.Settings) },
                    onLogout = ::logout,
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
                Screen.Settings -> SettingsScreen(
                    api = api,
                    session = profile.session,
                    settings = settings,
                    onChange = onSettingsChange,
                    onSwitchProfile = onSwitchProfile,
                    onLogout = ::logout,
                    onBack = ::goBack,
                )
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

/**
 * ATV+-style "who's watching?" — shown at launch when several accounts are
 * known and none is active. Selecting enters the profile; the last circle
 * adds another account without touching the existing ones.
 */
@Composable
private fun ProfilePickerScreen(
    profiles: List<PersistedSession>,
    onSelect: (PersistedSession) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Who's watching?", style = MaterialTheme.typography.headlineLarge)

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.Top,
        ) {
            profiles.forEach { profile ->
                ProfileAvatar(
                    initial = profile.initial,
                    title = profile.displayName,
                    subtitle = profile.serverLabel,
                    onClick = { onSelect(profile) },
                )
            }
            ProfileAvatar(
                initial = null,
                title = "Add Profile",
                subtitle = null,
                onClick = onAdd,
            )
        }
    }
}

/** [initial] letter avatar, or a "+" ring when null (the add button). */
@Composable
private fun ProfileAvatar(
    initial: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(132.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .dpadFocusEffect(CircleShape)
                .clip(CircleShape)
                .then(
                    if (initial != null) {
                        Modifier.background(
                            Brush.linearGradient(CinemaColors.AvatarGradient)
                        )
                    } else {
                        Modifier.border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    }
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            if (initial != null) {
                Text(
                    initial,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                )
            } else {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CinemaColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LoginScreen(
    api: JellyfinApi,
    onLoggedIn: (UserSession) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quickConnectCode by remember { mutableStateOf<String?>(null) }
    // Resolved-to-http URL waiting for the user's go-ahead (true = QC path)
    var insecurePending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    suspend fun doPasswordLogin(baseUrl: String) {
        onLoggedIn(api.login(baseUrl, username, password))
    }

    suspend fun doQuickConnect(resolvedUrl: String) {
        quickConnectCode = null
        try {
            val (baseUrl, initial) = api.initiateQuickConnect(resolvedUrl)
            quickConnectCode = initial.code
            var state = initial
            // Jellyfin codes expire (~5 min); stop polling rather than
            // hanging forever on a dead code
            val deadline = System.currentTimeMillis() + 5 * 60_000
            while (!state.authenticated) {
                if (System.currentTimeMillis() > deadline) {
                    error = "Quick Connect code expired — try again"
                    return
                }
                delay(3_000)
                state = api.getQuickConnectState(baseUrl, initial.secret)
            }
            onLoggedIn(api.authenticateWithQuickConnect(baseUrl, initial.secret))
        } finally {
            quickConnectCode = null
        }
    }

    /**
     * Resolves BEFORE sending anything sensitive: a scheme-less input that
     * only answers over plain http needs the user's explicit go-ahead.
     */
    fun connect(quickConnect: Boolean) {
        scope.launch {
            loading = true
            error = null
            try {
                val server = api.resolveServer(serverUrl)
                if (JellyfinApi.isInsecureDowngrade(serverUrl, server.baseUrl)) {
                    insecurePending = server.baseUrl to quickConnect
                } else if (quickConnect) {
                    doQuickConnect(server.baseUrl)
                } else {
                    doPasswordLogin(server.baseUrl)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Connection failed"
            } finally {
                loading = false
            }
        }
    }

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
            onClick = { connect(quickConnect = false) },
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
                    connect(quickConnect = true)
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

    // Adding a profile from the picker must always offer a way back
    if (onCancel != null) {
        BackHandler(onBack = onCancel)
        Box(modifier = Modifier.fillMaxSize()) {
            FloatingNavButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }

    // Shown BEFORE any credential leaves the device: the server only
    // answered over plain http for a scheme-less input
    insecurePending?.let { (resolvedUrl, quickConnect) ->
        AlertDialog(
            onDismissRequest = { insecurePending = null },
            title = { Text("Unencrypted connection") },
            text = {
                Text(
                    "This server is only reachable over plain HTTP. Your " +
                        "password and streams would travel unencrypted on " +
                        "the network."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        insecurePending = null
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                // Explicit http URL: single candidate, no
                                // second downgrade prompt
                                if (quickConnect) {
                                    doQuickConnect(resolvedUrl)
                                } else {
                                    doPasswordLogin(resolvedUrl)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Connection failed"
                            } finally {
                                loading = false
                            }
                        }
                    },
                    modifier = Modifier.dpadFocusEffect(RoundedCornerShape(10.dp)),
                ) {
                    Text("Connect Anyway")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { insecurePending = null },
                    modifier = Modifier.dpadFocusEffect(RoundedCornerShape(10.dp)),
                ) {
                    Text("Cancel")
                }
            },
        )
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
    onSettings: () -> Unit,
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
            // The way back down for the remote: the account bar overlays the
            // list instead of living in it, and the focus engine finds no
            // geometric path out of an overlay — Down from the avatar left
            // the user stuck up there. Named target, no guessing.
            val listFocus = remember { FocusRequester() }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusGroup()
                        .focusRequester(listFocus),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    item(key = "hero") {
                        HeroSection(
                            api = api,
                            item = hero,
                            onOpen = onOpen,
                            onPlay = onPlay,
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
                // Sibling of the list, not a hero child: inside the hero's
                // LazyColumn item these controls stayed unpainted until the
                // D-pad reached them (verified on the Android TV emulator).
                // It is also where a top bar belongs — it must not scroll away.
                AccountBar(
                    session = session,
                    onSearch = onSearch,
                    onSettings = onSettings,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .focusProperties { down = listFocus },
                )
            }
        }
    }
}

/**
 * Search + profile, pinned top-right over the artwork. Switching profile
 * and signing out live one step deeper, in Settings — a logout icon one
 * D-pad press from the hero is a TV footgun.
 */
@Composable
private fun AccountBar(
    session: UserSession,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // Scrim pill: white glyphs on a bright backdrop are otherwise
            // invisible, and this is the only way into settings
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = onSearch,
            modifier = Modifier.dpadFocusEffect(CircleShape),
        ) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
        }
        Box(
            modifier = Modifier
                .dpadFocusEffect(CircleShape)
                .clip(CircleShape)
                .clickable { onSettings() }
                .padding(6.dp),
        ) {
            AvatarCircle(initial = session.initial, size = 32.dp)
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
                            modifier = Modifier
                                .tvDefaultFocus()
                                .dpadFocusEffect(RoundedCornerShape(10.dp)),
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

/**
 * Apple TV store-style poster: caption inside the card over a bottom
 * scrim, hairline border so dark posters keep an edge on black.
 * (Continue/Next Up rows keep their text below the artwork.)
 */
@Composable
private fun PosterCard(api: JellyfinApi, item: BaseItem, onOpen: (BaseItem) -> Unit) {
    Box(
        modifier = Modifier
            .width(132.dp)
            .height(198.dp)
            .dpadFocusEffect(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(CinemaColors.SurfaceVariant)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .clickable(enabled = item.isPlayable || item.isSeries) { onOpen(item) },
    ) {
        AsyncImage(
            model = api.imageUrl(item, 400),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(60.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.8f),
                    )
                ),
        )
        Text(
            item.name ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        )
    }
}
