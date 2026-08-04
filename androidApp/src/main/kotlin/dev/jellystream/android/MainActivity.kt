package dev.jellystream.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.jellystream.shared.JellyfinApi
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConnectScreen()
                }
            }
        }
    }
}

private sealed interface ConnectState {
    data object Idle : ConnectState
    data object Loading : ConnectState
    data class Success(val message: String) : ConnectState
    data class Error(val message: String) : ConnectState
}

@Composable
private fun ConnectScreen() {
    val api = remember {
        JellyfinApi(
            deviceName = Build.MODEL,
            deviceId = UUID.randomUUID().toString(),
        )
    }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ConnectState>(ConnectState.Idle) }

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
            enabled = state != ConnectState.Loading && serverUrl.isNotBlank(),
            onClick = {
                scope.launch {
                    state = ConnectState.Loading
                    state = try {
                        val info = api.getPublicSystemInfo(serverUrl)
                        val auth = api.authenticateByName(serverUrl, username, password)
                        ConnectState.Success(
                            "Connected to ${info.serverName ?: "Jellyfin"} " +
                                "(v${info.version ?: "?"}) as ${auth.user?.name ?: username}"
                        )
                    } catch (e: Exception) {
                        ConnectState.Error(e.message ?: "Connection failed")
                    }
                }
            },
        ) {
            Text("Connect")
        }

        when (val s = state) {
            is ConnectState.Loading -> CircularProgressIndicator()
            is ConnectState.Success -> Text(s.message, color = MaterialTheme.colorScheme.primary)
            is ConnectState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
            ConnectState.Idle -> {}
        }
    }
}
