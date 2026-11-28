package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.RTorrentClientAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only admission for the existing canonical selection. The caller supplies
 * its exact privacy/session epoch and only the currently visible rows; there is
 * no saved-profile sign-in, engine startup, or implicit inventory scan.
 */
@Composable
internal fun CombinedSelectedFileSearch(
    selected: Set<UnifiedTransferKey>,
    visible: List<UnifiedTransferObservation>,
    sources: Map<EnvironmentId, EnvironmentTransferSnapshot>,
    labels: Map<EnvironmentId, String>,
    server: CombinedServerSource?,
    serverId: EnvironmentId?,
    localAllowed: Boolean,
    serverAllowed: Boolean,
    protocolProfiles: List<CombinedReadOnlyProtocolProfile>,
    protocolAllowed: Set<EnvironmentId>,
    protocolOwnerCurrent: (CombinedReadOnlyProtocolProfile) -> Boolean,
    viewWitness: Any,
    viewStillCurrent: () -> Boolean,
    onOpen: (UnifiedTransferKey) -> Unit
) {
    if (selected.isEmpty()) return
    if (selected.size > SelectedFileSearch.MAX_TRANSFERS) {
        androidx.compose.material3.Text("Find files: select at most " +
            SelectedFileSearch.MAX_TRANSFERS + " transfers for one search.")
        return
    }
    val app = LocalContext.current.applicationContext
    val visibleByKey = visible.filter { it.key in selected }.associateBy { it.key }
    // Do not silently narrow a previously selected batch after filters change.
    if (visibleByKey.keys != selected) {
        androidx.compose.material3.Text("Some selected transfers are hidden. Clear the selection before finding files.")
        return
    }
    val localEngine = LocalEngineHolder.current()
    val remote = verifiedCombinedLifecycleOwner(server, serverId)
    val localNow = rememberUpdatedState(localAllowed)
    val serverNow = rememberUpdatedState(serverAllowed)
    val remoteNow = rememberUpdatedState(remote)
    val profilesNow = rememberUpdatedState(protocolProfiles.associateBy { it.environment })
    val protocolAllowedNow = rememberUpdatedState(protocolAllowed)
    val ownerNow = rememberUpdatedState(protocolOwnerCurrent)
    val sourceNow = rememberUpdatedState(sources)
    val visibleNow = rememberUpdatedState(visibleByKey.keys)
    val viewNow = rememberUpdatedState(viewStillCurrent)

    val ordered = visibleByKey.values.toList()
    val targets = ordered.map { row ->
        val exact = row.key
        val env = exact.environment
        val profile = protocolProfiles.singleOrNull { it.environment == env }
        val currentSource = sources[env]?.canClaimCompleteLiveInventory == true
        val v1 = exact.infoHash.length == 40
        val ownerCheck: () -> Boolean = when {
            env == EnvironmentSnapshots.LOCAL -> {
                { localNow.value && localEngine != null && LocalEngineHolder.current() === localEngine }
            }
            env == serverId -> {
                { serverNow.value && remote != null && remoteNow.value === remote }
            }
            profile != null -> {
                { env in protocolAllowedNow.value &&
                    profilesNow.value[env]?.identityWitness == profile.identityWitness &&
                    ownerNow.value(profile) }
            }
            else -> { { false } }
        }
        val read: (suspend () -> List<io.github.gamesbyian.chunks.rpc.ClientFile>)? =
            if (!currentSource || !v1) null else when {
                env == EnvironmentSnapshots.LOCAL && localAllowed && localEngine != null -> {
                    { withContext(Dispatchers.IO) {
                        ClientOperations(LocalTorrentClient(
                            engine = { localEngine.takeIf { LocalEngineHolder.current() === it } },
                            places = LocalClientPlaces.forApp(app)
                        )).files(exact.infoHash)
                    } }
                }
                env == serverId && serverAllowed && remote != null &&
                    authenticatedExactRemovalRow(exact, server, serverId) != null -> {
                    { withContext(Dispatchers.IO) {
                        ClientOperations(RTorrentClientAdapter(remote)).files(exact.infoHash)
                    } }
                }
                profile != null && profile.operations != null && env in protocolAllowed -> {
                    { withContext(Dispatchers.IO) { requireNotNull(profile.operations).invoke().files(exact.infoHash) } }
                }
                else -> null
            }
        SelectedFileSearch.Target(
            key = exact,
            clientLabel = labels[env] ?: "Client",
            transferName = row.displayName,
            metadataPending = row.metadataPending,
            read = read,
            authorized = {
                viewNow.value() && exact in visibleNow.value &&
                    sourceNow.value[env]?.canClaimCompleteLiveInventory == true && ownerCheck()
            }
        )
    }
    // A live source becoming stale, an engine replacement or an owner change
    // drops the completed result even if the selected hash remains identical.
    val readWitness = listOf(viewWitness, localEngine, remote, targets.map {
        Triple(it.key, it.read != null, it.metadataPending)
    })
    SelectedFileSearchSection(
        targets = targets,
        viewWitness = readWitness,
        viewCurrent = {
            viewNow.value() && visibleNow.value == selected &&
                targets.all { it.read == null || it.authorized() }
        },
        onOpen = onOpen
    )
}
