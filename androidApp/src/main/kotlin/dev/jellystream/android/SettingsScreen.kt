package dev.jellystream.android

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    session: UserSession,
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onSwitchProfile: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
) {
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

            item(key = "playback") {
                SettingsSection("Playback") {
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
