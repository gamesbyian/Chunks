package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RuTorrentDeleteData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Read-only capability evidence owned by one Add visit and roster identity. */
@Composable
internal fun rememberAddRoutingSessions(
    enabled: Boolean,
    profiles: List<CombinedReadOnlyProtocolProfile>
): Map<String, ClientSessionInfo> {
    val readable = profiles.filter { it.operations != null }
    val identities = readable.map { it.identityWitness }
    // Reset in composition so an old positive read cannot participate in even
    // one frame of preselection while the new account's check is outstanding.
    val sessions = remember(enabled, identities) { mutableStateMapOf<String, ClientSessionInfo>() }
    LaunchedEffect(enabled, identities) {
        if (!enabled) return@LaunchedEffect
        readable.forEach { profile ->
            launch {
                val session = readRoutingTarget {
                    requireNotNull(profile.operations).invoke().session()
                }
                if (session != null) sessions[profile.id] = session
            }
        }
    }
    // A slow or unavailable client cannot hold up any other client's result.
    return sessions.toMap()
}

/** The selected session goes stale independently of a saved profile or label. */
@Composable
internal fun rememberRTorrentRoutingDefault(
    enabled: Boolean,
    remote: RTorrentRemote?
): String? {
    val directory = remember(enabled, remote) { mutableStateOf<String?>(null) }
    LaunchedEffect(enabled, remote) {
        if (!enabled || remote == null) return@LaunchedEffect
        directory.value = readRoutingTarget {
            remote.defaultDirectory()?.takeIf {
                RuTorrentDeleteData.safeDirectory(it, allowRoot = true)
            }
        }
    }
    return directory.value
}

private suspend fun <T> readRoutingTarget(read: suspend () -> T): T? {
    return try {
        withTimeoutOrNull(20_000) { withContext(Dispatchers.IO) { read() } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
