package dev.jellystream.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.JellyfinApi
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
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.safeDrawingPadding()
                    ) {
                        JellystreamApp()
                    }
                }
            }
        }
    }
}

@Composable
private fun JellystreamApp() {
    val api = remember {
        JellyfinApi(
            deviceName = Build.MODEL,
            deviceId = UUID.randomUUID().toString(),
        )
    }
    var session by remember { mutableStateOf<UserSession?>(null) }
    var playing by remember { mutableStateOf<BaseItem?>(null) }

    when (val s = session) {
        null -> LoginScreen(api, onLoggedIn = { session = it })
        // The player is an overlay: HomeScreen stays composed underneath so its
        // state (loaded sections) survives closing the player
        else -> androidx.compose.foundation.layout.Box {
            HomeScreen(api, s, onPlay = { playing = it })
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

private data class LibrarySection(val view: BaseItem, val latest: List<BaseItem>)

@Composable
private fun HomeScreen(api: JellyfinApi, session: UserSession, onPlay: (BaseItem) -> Unit) {
    var sections by remember { mutableStateOf<List<LibrarySection>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        try {
            sections = api.getUserViews().map { view ->
                // One failing view must not blank the whole home screen
                val latest = try {
                    api.getLatestItems(view.id, 12)
                } catch (e: Exception) {
                    emptyList()
                }
                LibrarySection(view, latest)
            }
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
                Text(
                    session.serverName ?: "Jellyfin",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(sections!!, key = { it.view.id }) { section ->
                LibraryRow(api, section, onPlay)
            }
        }
    }
}

@Composable
private fun LibraryRow(api: JellyfinApi, section: LibrarySection, onPlay: (BaseItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            section.view.name ?: "Library",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(section.latest, key = { it.id }) { item ->
                PosterCard(api, item, onPlay)
            }
        }
    }
}

@Composable
private fun PosterCard(api: JellyfinApi, item: BaseItem, onPlay: (BaseItem) -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(enabled = item.isPlayable) { onPlay(item) },
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
