package io.github.gamesbyian.chunks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF39478C),
                secondary = Color(0xFF7651B0),
                background = Color(0xFFFFF8ED),
                surface = Color(0xFFFFF4E1)
            )) {
                Surface(modifier = Modifier.fillMaxSize()) { ChunksHome() }
            }
        }
    }
}

@Composable
private fun ChunksHome() {
    var endpoint by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serverVersion by remember { mutableStateOf<String?>(null) }
    var transfers by remember { mutableStateOf<List<RemoteTransfer>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("chunks.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("Your downloads have places to be.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text("rTorrent connection", style = MaterialTheme.typography.titleLarge)
            Text("Read-only developer slice. Credentials are held in memory, never stored.",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(
                value = endpoint, onValueChange = { endpoint = it },
                label = { Text("HTTPS rTorrent RPC address") },
                placeholder = { Text("https://server.example/RPC2") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("Username (if required)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password (never saved)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Button(
                onClick = {
                    connecting = true
                    error = null
                    serverVersion = null
                    transfers = emptyList()
                    scope.launch {
                        try {
                            val remote = RTorrentRemote(RemoteCredentials(endpoint, username, password))
                            val version = remote.version()
                            val list = remote.listTransfers()
                            serverVersion = version
                            transfers = list
                        } catch (failure: Exception) {
                            error = when (failure) {
                                is java.net.UnknownHostException -> "Server hostname could not be resolved."
                                is java.net.SocketTimeoutException -> "rTorrent did not respond before the timeout."
                                else -> failure.message ?: "Connection failed."
                            }
                        } finally {
                            connecting = false
                        }
                    }
                },
                enabled = !connecting && endpoint.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (connecting) "Connecting…" else "Connect and list transfers")
            }
        }
        if (error != null) item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Could not connect", fontWeight = FontWeight.Bold)
                    Text(error.orEmpty())
                }
            }
        }
        if (serverVersion != null) item {
            Text("Connected to rTorrent ${serverVersion.orEmpty()}", style = MaterialTheme.typography.titleMedium)
            Text("${transfers.size} transfers found. Viewing only; no remote changes are possible in this slice.")
        }
        items(transfers, key = { it.hash }) { torrent ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(torrent.name, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("${(torrent.fractionComplete * 100).toInt()}% downloaded")
                        Text(if (torrent.started) "Running" else "Stopped")
                    }
                    Text("Place: ${torrent.directory}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text("Next: destinations, safe remote moves, named copies and smart collections.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
