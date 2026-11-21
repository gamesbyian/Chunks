package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val sessions = remember(enabled, identities) { mutableStateOf(emptyMap<String, ClientSessionInfo>()) }
    LaunchedEffect(enabled, identities) {
        if (!enabled) return@LaunchedEffect
        sessions.value = withContext(Dispatchers.IO) {
            readable.mapNotNull { profile ->
                val session = try {
                    withTimeoutOrNull(20_000) { requireNotNull(profile.operations).invoke().session() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                session?.let { profile.id to it }
            }.toMap()
        }
    }
    return sessions.value
}
