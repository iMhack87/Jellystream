package dev.jellystream.android

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jellystream.shared.AppSettings
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.LanguageCode
import dev.jellystream.shared.SubtitleLanguages
import dev.jellystream.shared.SubtitleMode
import dev.jellystream.shared.JellyseerrApi
import dev.jellystream.shared.PersistedSession
import dev.jellystream.shared.PersistedSettings
import dev.jellystream.shared.UserSession

/**
 * App-private storage for the settings blob (the shared module owns the JSON
 * format). Separate file from the session prefs: preferences are not secrets
 * and have a different lifetime.
 */
class SettingsStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("jellystream_settings", Context.MODE_PRIVATE)

    fun load(): PersistedSettings =
        prefs.getString("settings", null)
            ?.let { PersistedSettings.fromJson(it) }
        // No settings yet, or a blob we can't read — defaults, never a
        // failure the user has to recover from
            ?: PersistedSettings.empty()

    fun save(settings: PersistedSettings) {
        prefs.edit().putString("settings", settings.toJson()).apply()
    }
}

/**
 * The active profile's settings, readable from any screen — the player needs
 * them without every intermediate screen having to forward them.
 */
val LocalAppSettings = compositionLocalOf { AppSettings.Defaults }

/** Account, playback and About — reached from the profile button. */
@Composable
fun SettingsScreen(
    api: JellyfinApi,
    seerr: JellyseerrApi,
    profile: PersistedSession,
    session: UserSession,
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onProfileChange: (PersistedSession) -> Unit,
    onOpenRequests: () -> Unit,
    onOpenDownloads: () -> Unit,
    onSwitchProfile: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
) {
    // Read once, outside the list scope: a LazyColumn's content lambda is
    // not composable, so a composable call there does not compile
    val onTelevision = isTvDevice()
    var editingServer by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    val link = profile.jellyseerr

    // Best effort: an unreachable server just leaves the row out
    var serverVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session.baseUrl) {
        serverVersion = runCatching { api.getPublicSystemInfo(session.baseUrl).version }
            .getOrNull()
    }

    // Same deal: no libraries listed is better than a screen that fails
    var libraries by remember { mutableStateOf<List<BaseItem>>(emptyList()) }
    LaunchedEffect(session.userId) {
        libraries = runCatching { api.getUserViews() }.getOrDefault(emptyList())
    }

    Box(modifier = Modifier.fillMaxSize().background(CinemaColors.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 72.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    AvatarCircle(initial = session.initial, size = 56.dp)
                    Column {
                        Text(
                            session.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = CinemaColors.TextPrimary,
                        )
                        Text(
                            session.serverLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CinemaColors.TextSecondary,
                        )
                    }
                }
            }

            item(key = "account") {
                SettingsSection("Account") {
                    // "Who's watching?" is also the only path from a
                    // single-profile install to adding a second account.
                    // It also takes initial focus on TV: without it the
                    // remote lands on the floating back button, and D-pad
                    // Down does not find its way into the list from there.
                    SettingsAction(
                        "Switch profile",
                        onClick = onSwitchProfile,
                        modifier = Modifier.tvDefaultFocus(),
                    )
                    SettingsAction(
                        "Log out",
                        onClick = onLogout,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (libraries.isNotEmpty()) {
                item(key = "libraries") {
                    // Every library the server offers is listed, including
                    // the music and photo ones this player starts with
                    // switched off — hidden is a choice here, never a
                    // library the user can no longer find.
                    SettingsSection("Home screen") {
                        libraries.forEach { view ->
                            SettingsToggle(
                                title = view.name ?: "Library",
                                subtitle = null,
                                checked = settings.showsLibrary(view),
                                onCheckedChange = {
                                    onChange(settings.withLibraryShown(view, it))
                                },
                            )
                        }
                    }
                }
            }

            if (!onTelevision) {
                item(key = "downloads") {
                    SettingsSection("Offline") {
                        SettingsAction("Downloads", onClick = onOpenDownloads)
                    }
                }
            }

            item(key = "requests") {
                SettingsSection("Requests") {
                    SettingsChoice(
                        label = "Jellyseerr server",
                        value = link?.baseUrl?.removePrefix("https://")?.removePrefix("http://")
                            ?: "Not set",
                        onClick = { editingServer = true },
                    )
                    if (link != null) {
                        SettingsChoice(
                            label = "Account",
                            value = if (link.isSignedIn) "Signed in" else "Sign in",
                            onClick = { signingIn = true },
                        )
                        SettingsAction(
                            "Browse and request",
                            onClick = onOpenRequests,
                        )
                    }
                }
                Text(
                    "Requests are made with this profile's own Jellyfin account, so "
                        + "quotas and history stay yours. Only the session is kept — "
                        + "never the password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }

            item(key = "subtitles") {
                SettingsSection("Subtitles") {
                    SettingsChoice(
                        label = "When to show",
                        value = settings.subtitleMode.label,
                        onClick = {
                            onChange(
                                settings.withSubtitleMode(
                                    SubtitleMode.Companion.next(settings.subtitleMode)
                                )
                            )
                        },
                    )
                    SettingsChoice(
                        label = "Language",
                        value = SubtitleLanguages.labelFor(settings.subtitleLanguage),
                        onClick = { onChange(settings.withSubtitleLanguage(nextLanguage(settings.subtitleLanguage))) },
                    )
                    SettingsChoice(
                        label = "Size",
                        value = scaleLabel(settings.subtitleScale),
                        onClick = { onChange(settings.withSubtitleScale(nextScale(settings.subtitleScale))) },
                        last = true,
                    )
                }
                Text(
                    "Smart turns on full subtitles when the audio is not in your "
                        + "language, and only forced ones when it is. Device language "
                        + "follows the system: ${deviceLanguageLabel()}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }

            item(key = "playback") {
                SettingsSection("Playback") {
                    SettingsToggle(
                        title = "Play next episode automatically",
                        subtitle = "When an episode ends, the next one starts after a "
                            + "ten-second countdown you can stop. Off keeps the same "
                            + "card and the same button — it just waits for you.",
                        checked = settings.autoPlayNextEpisode,
                        onCheckedChange = { onChange(settings.withAutoPlayNextEpisode(it)) },
                    )
                    SettingsToggle(
                        title = "Always transcode",
                        subtitle = "Direct Play sends the original file untouched — leave "
                            + "this off. Turn it on only if a title stutters or won't "
                            + "decode: the server will re-encode it, at the cost of CPU "
                            + "and quality.",
                        checked = settings.alwaysTranscode,
                        onCheckedChange = { onChange(settings.withAlwaysTranscode(it)) },
                    )
                }
            }

            item(key = "about") {
                SettingsSection("About") {
                    SettingsValue("Jellystream", JellyfinApi.CLIENT_VERSION)
                    SettingsValue("Server", session.serverLabel)
                    serverVersion?.let { SettingsValue("Jellyfin", it) }
                }
            }
        }

        FloatingNavButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))

        if (editingServer) {
            JellyseerrServerDialog(
                current = link?.baseUrl.orEmpty(),
                onDismiss = { editingServer = false },
                onSave = { url ->
                    editingServer = false
                    onProfileChange(profile.withJellyseerrServer(url))
                },
            )
        }
        if (signingIn && link != null) {
            JellyseerrSignInDialog(
                username = session.displayName,
                serverLabel = link.baseUrl,
                onDismiss = { signingIn = false },
                onSignIn = { password ->
                    seerr.configure(link.baseUrl, null)
                    val cookie = seerr.signIn(session.displayName, password)
                    if (cookie != null) {
                        onProfileChange(profile.withJellyseerrSession(cookie))
                    }
                    cookie != null
                },
            )
        }
    }
}

/**
 * The gradient initial circle used by the profile picker, the settings header
 * and the home button — one look for "this account", everywhere.
 */
@Composable
fun AvatarCircle(initial: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(CinemaColors.AvatarGradient))
            // Hairline edge: the gradient alone disappears into a dark
            // backdrop, and the home button sits straight on the artwork
            .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape),
    ) {
        Text(
            initial,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = CinemaColors.TextSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CinemaColors.Surface),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = CinemaColors.TextPrimary,
            )
            // A library row is its own explanation; only settings that
            // can bite carry a caption
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.dpadFocusEffect(RoundedCornerShape(20.dp)),
        )
    }
}

@Composable
private fun SettingsAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = CinemaColors.TextPrimary,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .dpadFocusEffect(RoundedCornerShape(10.dp), scaleOnFocus = false),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

/**
 * A setting with a handful of values: the row shows the current one and a
 * click steps to the next. No dialog, no picker — one focusable target per
 * setting is what a D-pad wants, and a phone loses nothing by it.
 */
@Composable
private fun SettingsChoice(
    label: String,
    value: String,
    onClick: () -> Unit,
    last: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .dpadFocusEffect(RoundedCornerShape(10.dp), scaleOnFocus = false)
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = CinemaColors.TextPrimary,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = CinemaColors.TextSecondary,
            )
        }
        if (!last) HorizontalDivider(color = CinemaColors.SurfaceVariant)
    }
}

/** The system's language as an ISO 639-2 code, or null if it has none. */
internal fun deviceSubtitleLanguage(): String? =
    runCatching { java.util.Locale.getDefault().isO3Language }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }

private fun deviceLanguageLabel(): String =
    SubtitleLanguages.labelFor(deviceSubtitleLanguage())
        .takeIf { it != SubtitleLanguages.CHOICES.first().label }
        ?: (deviceSubtitleLanguage()?.uppercase() ?: "unknown")

private fun nextLanguage(current: String?): String? {
    val choices = SubtitleLanguages.CHOICES
    val index = choices.indexOfFirst { LanguageCode.matches(it.code, current) }
        .takeIf { it >= 0 }
        ?: choices.indexOfFirst { it.code == null }
    return choices[(index + 1) % choices.size].code
}

/** The sizes worth offering; anything finer is fiddling, not a setting. */
private val SCALES = listOf(0.75, 1.0, 1.25, 1.5, 2.0)

private fun nextScale(current: Double): Double {
    val index = SCALES.indexOfFirst { kotlin.math.abs(it - current) < 0.01 }
    return SCALES[(index + 1).mod(SCALES.size)]
}

private fun scaleLabel(scale: Double): String = when {
    kotlin.math.abs(scale - 1.0) < 0.01 -> "Normal"
    else -> "${(scale * 100).toInt()}%"
}

@Composable
private fun SettingsValue(label: String, value: String) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = CinemaColors.TextPrimary,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = CinemaColors.TextSecondary,
            )
        }
        HorizontalDivider(color = CinemaColors.SurfaceVariant)
    }
}

/** Where the Jellyseerr lives. Same shape as the Jellyfin server field. */
@Composable
private fun JellyseerrServerDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var url by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jellyseerr server") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                label = { Text("Address") },
                placeholder = { Text("seerr.example.com") },
                modifier = Modifier.fillMaxWidth().tvDefaultFocus(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(url) }) { Text("Save") } },
        dismissButton = {
            Row {
                // Clearing the field is how a profile stops using Jellyseerr
                if (current.isNotEmpty()) {
                    TextButton(onClick = { onSave(null) }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Signs the profile in to Jellyseerr with its own Jellyfin account.
 *
 * The username is already known; only the password is asked for, and it
 * leaves this dialog for the network and nowhere else.
 */
@Composable
private fun JellyseerrSignInDialog(
    username: String,
    serverLabel: String,
    onDismiss: () -> Unit,
    onSignIn: suspend (String) -> Boolean,
) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sign in to Jellyseerr") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "$username on $serverLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinemaColors.TextSecondary,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; failed = false },
                    singleLine = true,
                    label = { Text("Jellyfin password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().tvDefaultFocus(),
                )
                if (failed) {
                    Text(
                        "Jellyseerr refused those credentials.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.isNotEmpty(),
                onClick = {
                    busy = true
                    scope.launch {
                        val ok = onSignIn(password)
                        busy = false
                        if (ok) onDismiss() else failed = true
                    }
                },
            ) { Text(if (busy) "Signing in…" else "Sign in") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
