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
import androidx.compose.foundation.focusable
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
import dev.jellystream.shared.AnnouncedArrivals
import dev.jellystream.shared.AppSettings
import dev.jellystream.shared.Arrivals
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.DownloadAvailability
import dev.jellystream.shared.DownloadedItem
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.JellyseerrApi
import dev.jellystream.shared.PersistedProfiles
import dev.jellystream.shared.PersistedSession
import dev.jellystream.shared.RequestedTitle
import dev.jellystream.shared.UserSession
import dev.jellystream.shared.Watchlist
import dev.jellystream.shared.WatchlistEntry
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
                    // Owned here, above everything: the app drops its whole
                    // signed-in subtree on a profile switch, and a notice
                    // living in there would go with it
                    val arrivals = remember { ArrivalCenter() }
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Keep content clear of status bar, cutout AND keyboard (IME):
                        // the centered login form deliberately shifts up when typing
                        Box(modifier = Modifier.safeDrawingPadding()) {
                            JellystreamApp(arrivals)
                        }
                        // Sibling of the padded box, not a child: that
                        // padding includes the IME inset, and a notice
                        // inside it jumps up the screen when a keyboard
                        // opens. Last child, so it sits over the player.
                        // Top-right, not bottom: the bottom of the player
                        // is its transport bar, and a notice landing on
                        // the subtitle and settings buttons hides exactly
                        // what someone reaches for mid-episode. Matches
                        // where the Apple twin puts it.
                        ArrivalToastHost(
                            center = arrivals,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
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
    data object Requests : Screen
    data object Downloads : Screen
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
private fun JellystreamApp(arrivals: ArrivalCenter) {
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
                    arrivals = arrivals,
                    settings = storedSettings.forProfile(current.profileKey),
                    onSettingsChange = {
                        storedSettings = storedSettings
                            .withSettings(current.profileKey, it)
                            .also(settingsStore::save)
                    },
                    // The Jellyseerr link lives in the profile blob, not in
                    // the settings one: a session cookie is a credential
                    onProfileChange = { updated ->
                        profiles = profiles.withProfile(updated).also(store::saveProfiles)
                        active = updated
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
    arrivals: ArrivalCenter,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onProfileChange: (PersistedSession) -> Unit,
    onLoggedOut: () -> Unit,
    onSwitchProfile: () -> Unit,
) {
    val api = remember {
        JellyfinApi(
            deviceName = Build.MODEL,
            deviceId = profile.deviceId,
        ).also { it.restoreSession(profile.session) }
    }
    val context = LocalContext.current
    val downloadStore = remember { DownloadStore(context, profile.profileKey) }
    var downloads by remember { mutableStateOf(downloadStore.load()) }
    val downloader = remember {
        Downloader(context, api, profile.profileKey, downloadStore)
    }
    // Can this account download at all? demo.jellyfin.org says no.
    var downloadingAllowed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(profile.profileKey) { downloadingAllowed = api.canDownload() }

    // One list per profile, held here rather than by a screen: search,
    // detail and the home row all read and write the same one
    val watchlistStore = remember { WatchlistStore(context, profile.profileKey) }
    var watchlist by remember { mutableStateOf(watchlistStore.load()) }
    fun changeWatchlist(updated: Watchlist) {
        watchlist = updated.also(watchlistStore::save)
    }

    val seerr = remember { JellyseerrApi() }
    // Re-read on every change: pointing at another server or signing in
    // must take effect without restarting the app
    LaunchedEffect(profile.jellyseerr) {
        seerr.configure(profile.jellyseerr?.baseUrl, profile.jellyseerr?.sessionCookie)
    }

    // Declared AFTER the configure effect on purpose: effects start in
    // declaration order and configure() does not suspend, so the first
    // poll already knows which server to ask.
    val arrivalStore = remember { ArrivalStore(context, profile.profileKey) }
    LaunchedEffect(profile.profileKey, profile.jellyseerr) {
        // The centre lives above the whole app so a notice can paint over
        // the player, which means it does NOT go away with the profile.
        // Emptying it here is what stops one account's titles being
        // announced to the next, and its requests appearing on their home
        // screen — the same reason the effect is keyed on the profile.
        arrivals.reset()
        // Nothing stored means this profile has never been polled. Every
        // title already available then was not waited for by anyone, so
        // the first look records them silently.
        var firstLook = arrivalStore.load() == null
        while (true) {
            if (seerr.isConfigured) {
                try {
                    val requests = seerr.myRequestsDetailed(REQUEST_PAGE)
                    // Null is "could not reach the request server", never
                    // "you have no requests" — taking it for the latter
                    // would forget every announced id and re-announce the
                    // lot on the next tick that does answer.
                    if (requests != null) {
                        val announced = arrivalStore.load() ?: AnnouncedArrivals()
                        arrivals.announce(Arrivals.landed(requests, announced, firstLook))
                        arrivalStore.save(Arrivals.seen(requests, announced))
                        firstLook = false
                        // The home row reads this rather than fetching its
                        // own copy: one poll, one truth. Otherwise the
                        // notice says a title has arrived while the row
                        // under it still says 0% — which is what shipped
                        // before this line existed.
                        arrivals.requests = requests
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // One unreachable tick is not worth ending the poll
                    // over; the next one asks again
                }
            }
            delay(ARRIVAL_POLL_MS)
        }
    }

    var playing by remember { mutableStateOf<BaseItem?>(null) }
    var playingOffline by remember { mutableStateOf<DownloadedItem?>(null) }
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()

    // The server rejected our token: the session is dead and every call
    // will 401. Route back to sign-in (the callback may fire off-main).
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(api) {
        api.onUnauthorized = { mainHandler.post(onLoggedOut) }
        onDispose { api.onUnauthorized = null }
    }

    // The downloader runs off the main thread; its updates land here
    DisposableEffect(downloader) {
        downloader.onChange = { updated -> mainHandler.post { downloads = updated } }
        onDispose { downloader.onChange = null }
    }
    // Whatever was watched offline goes back to the server on the way in
    LaunchedEffect(Unit) { downloader.syncPositions() }


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
                    seerr = seerr,
                    arrivals = arrivals,
                    session = profile.session,
                    watchlist = watchlist,
                    onWatchlistChange = ::changeWatchlist,
                    onOpen = ::open,
                    onPlay = { playing = it },
                    onSearch = { backStack.add(Screen.Search) },
                    onSettings = { backStack.add(Screen.Settings) },
                    onLogout = ::logout,
                    downloadCount = downloads.playable.size,
                    onOpenDownloads = { backStack.add(Screen.Downloads) },
                )
                is Screen.Detail -> DetailScreen(
                    api,
                    screen.item,
                    onPlay = { playing = it },
                    onBack = ::goBack,
                    watchlist = watchlist,
                    onWatchlistChange = ::changeWatchlist,
                    download = DownloadAvailability.of(screen.item, downloadingAllowed),
                    downloadState = downloads.stateOf(screen.item.id),
                    onDownload = {
                        scope.launch {
                            downloader.start(screen.item, api.containerOf(screen.item))
                        }
                    },
                )
                is Screen.Series -> SeriesScreen(
                    api,
                    screen.item,
                    onPlay = { playing = it },
                    onBack = ::goBack,
                    watchlist = watchlist,
                    onWatchlistChange = ::changeWatchlist,
                )
                Screen.Search -> SearchScreen(
                    api = api,
                    seerr = seerr,
                    watchlist = watchlist,
                    onWatchlistChange = ::changeWatchlist,
                    onOpen = ::open,
                    onBack = ::goBack,
                )
                Screen.Requests -> RequestsScreen(seerr, onBack = ::goBack)
                Screen.Downloads -> DownloadsScreen(
                    downloads = downloads,
                    onPlay = { item ->
                        downloads[item.itemId]?.let { playingOffline = it }
                    },
                    onRemove = { downloader.remove(it.itemId) },
                    onBack = ::goBack,
                )
                Screen.Settings -> SettingsScreen(
                    api = api,
                    seerr = seerr,
                    profile = profile,
                    session = profile.session,
                    settings = settings,
                    onChange = onSettingsChange,
                    onProfileChange = onProfileChange,
                    onOpenRequests = { backStack.add(Screen.Requests) },
                    onOpenDownloads = { backStack.add(Screen.Downloads) },
                    onSwitchProfile = onSwitchProfile,
                    onLogout = ::logout,
                    onBack = ::goBack,
                )
            }

            BackHandler(enabled = backStack.size > 1 && playing == null) {
                backStack.removeAt(backStack.lastIndex)
            }

            // Offline playback is its own overlay: no plan, no reports
            playingOffline?.let { offline ->
                PlayerScreen@ Box(modifier = Modifier.fillMaxSize()) {
                    OfflinePlayerScreen(
                        file = downloadedFile(context, profile.profileKey, offline),
                        item = offline,
                        onPosition = { ticks ->
                            downloads = downloadStore.load()
                                .markPosition(offline.itemId, ticks)
                                .also(downloadStore::save)
                            downloader.syncPositions()
                        },
                        onClose = { playingOffline = null },
                    )
                }
            }

            playing?.let { item ->
                // The player needs Jellyseerr too: finishing the last
                // episode a season has is where asking for the next one
                // actually occurs to someone
                PlayerScreen(api, seerr, item, onClose = { playing = null })
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
    seerr: JellyseerrApi,
    arrivals: ArrivalCenter,
    session: UserSession,
    watchlist: Watchlist,
    onWatchlistChange: (Watchlist) -> Unit,
    onOpen: (BaseItem) -> Unit,
    onPlay: (BaseItem) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    downloadCount: Int = 0,
    onOpenDownloads: () -> Unit = {},
) {
    var sections by remember { mutableStateOf<List<LibrarySection>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val settings = LocalAppSettings.current

    // Keyed on the library choices too: turning one on in Settings must
    // show its row on the way back, not on the next cold start
    LaunchedEffect(session, settings.libraryOverrides) {
        try {
            val result = mutableListOf<LibrarySection>()
            runCatching { api.getResumeItems(12) }.getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let { result.add(LibrarySection("Continue Watching", "resume", it)) }
            runCatching { api.getNextUp(12) }.getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let { result.add(LibrarySection("Next Up", "nextup", it)) }
            settings.visibleLibraries(api.getUserViews()).forEach { view ->
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

    // The three rows below load OUTSIDE that effect, each with its own
    // state. The load above assigns `sections` only at the very end, so
    // folding a Jellyseerr call into it would make the whole home screen
    // — hero included — wait on the request server, and block on its
    // timeout every time the NAS is off.
    // Read from the app-wide arrival poll rather than fetched again here.
    // Two fetches meant two answers, and the one that showed was the older:
    // the notice announced a title while the row under it still said 0%.
    // This also keeps the row moving without a second poll of its own.
    var arriving by remember { mutableStateOf<List<RequestedTitle>>(emptyList()) }
    LaunchedEffect(arrivals.requests) {
        if (!seerr.isConfigured) return@LaunchedEffect
        if (arrivals.requests.isNotEmpty()) {
            arriving = arrivals.requests.filter { it.isSettling }
            return@LaunchedEffect
        }
        // Nothing polled yet — the poll runs every minute and the home
        // screen is usually the first thing seen, so ask once rather than
        // show an empty row for up to a minute.
        try {
            seerr.myRequestsDetailed(REQUEST_PAGE)
                ?.let { rows -> arriving = rows.filter { it.isSettling } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No row is better than a home screen that says it failed
        }
    }

    var favorites by remember { mutableStateOf<List<BaseItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        favorites = try {
            api.getFavorites(24)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // A watchlist entry carries no Jellyfin image tag, and without one
    // there is no poster URL to build — so the ones already on the server
    // are fetched. It is also what makes the card openable at all.
    var onServer by remember { mutableStateOf<Map<String, BaseItem>>(emptyMap()) }
    LaunchedEffect(watchlist) {
        val resolved = mutableMapOf<String, BaseItem>()
        for (entry in watchlist.entries) {
            val id = entry.itemId ?: continue
            try {
                resolved[id] = api.getItem(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One title the server will not describe costs its
                // poster, not the row
            }
        }
        onServer = resolved
    }

    // An entry added from search knows only a TMDb id, and nothing here
    // can open one of those. Reconcile against everything already loaded
    // so it picks up an item id the moment the download lands.
    LaunchedEffect(sections, favorites, watchlist) {
        val known = sections.orEmpty().flatMap { it.items } + favorites
        if (known.isEmpty()) return@LaunchedEffect
        val reconciled = watchlist.reconciled(known)
        if (reconciled != watchlist) onWatchlistChange(reconciled)
    }

    when {
        error != null -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Downloads exist precisely for this moment. Offering only
            // "Log out" here would send someone on a train to the one
            // button that deletes the films they downloaded for it.
            if (downloadCount > 0) {
                Text(
                    "Can't reach the server.",
                    color = CinemaColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "$downloadCount downloaded ${if (downloadCount == 1) "title is" else "titles are"} still on this device.",
                    color = CinemaColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onOpenDownloads,
                    modifier = Modifier
                        .tvDefaultFocus()
                        .dpadFocusEffect(RoundedCornerShape(10.dp)),
                ) {
                    Text("Go to downloads")
                }
                TextButton(onClick = onLogout) { Text("Log out") }
            } else {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                // Never strand the user on a dead server: offer a way out
                Button(
                    onClick = onLogout,
                    modifier = Modifier.dpadFocusEffect(RoundedCornerShape(10.dp)),
                ) {
                    Text("Log out")
                }
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
                    // Split rather than dispatched on the key: the three
                    // rows below have to land BETWEEN Next Up and the
                    // libraries, and none of them holds BaseItems only.
                    items(
                        sections!!.filter { it.key == "resume" || it.key == "nextup" },
                        key = { it.key },
                    ) { section ->
                        ContinueRow(api, section, onOpen)
                    }
                    if (arriving.isNotEmpty()) {
                        item(key = "arriving") { ArrivingRow(arriving) }
                    }
                    if (watchlist.entries.isNotEmpty()) {
                        item(key = "watchlist") {
                            WatchlistRow(
                                api = api,
                                entries = watchlist.entries,
                                onServer = onServer,
                                onOpen = onOpen,
                            )
                        }
                    }
                    if (favorites.isNotEmpty()) {
                        item(key = "favorites") {
                            LibraryRow(
                                api,
                                LibrarySection("Favourites", "favorites", favorites),
                                onOpen,
                            )
                        }
                    }
                    items(
                        sections!!.filter { it.key != "resume" && it.key != "nextup" },
                        key = { it.key },
                    ) { section ->
                        LibraryRow(api, section, onOpen)
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

/**
 * What has been asked for and is not here yet.
 *
 * Not a [LibrarySection]: a request is a [RequestedTitle], it has a state
 * and a progress bar and no Jellyfin item behind it at all. There is
 * nothing to open either — the cards are focusable only so the D-pad can
 * reach the row and read it, never clickable.
 */
@Composable
private fun ArrivingRow(requests: List<RequestedTitle>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Requested & on the way",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(requests, key = { "arriving-${it.request.id}" }) { row ->
                Column(
                    modifier = Modifier
                        .width(132.dp)
                        .dpadFocusEffect(RoundedCornerShape(10.dp))
                        .focusable(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PosterThumb(row.posterPath, width = 132.dp, height = 198.dp)
                    Text(
                        row.displayTitle,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        row.state.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = CinemaColors.TextSecondary,
                    )
                    row.progress?.let { ProgressStrip(it) }
                }
            }
        }
    }
}

/**
 * Things meant for later, whether or not the server has them.
 *
 * An entry with no item id is shown and not playable — that is the whole
 * point of the list, and hiding it until the download lands would mean
 * the row forgets what it was asked to remember.
 */
@Composable
private fun WatchlistRow(
    api: JellyfinApi,
    entries: List<WatchlistEntry>,
    onServer: Map<String, BaseItem>,
    onOpen: (BaseItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Watchlist",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                entries,
                key = { it.itemId ?: "tmdb-${it.tmdbId}" },
            ) { entry ->
                val item = entry.itemId?.let { onServer[it] }
                Column(
                    modifier = Modifier
                        .width(132.dp)
                        .dpadFocusEffect(RoundedCornerShape(10.dp))
                        .then(
                            if (item != null) {
                                Modifier.clickable { onOpen(item) }
                            } else {
                                Modifier.focusable()
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PosterTile(
                        item?.let { api.imageUrl(it, 342) }
                            ?: JellyseerrApi.posterUrl(entry.posterPath, 342),
                        width = 132.dp,
                        height = 198.dp,
                    )
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // Says why a card does nothing when pressed,
                        // instead of leaving it looking broken
                        if (item != null) entry.year.orEmpty() else "Not on the server yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = CinemaColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
