package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.RemotePolicyWriteFence
import io.github.gamesbyian.chunks.seeding.RemoteSeeding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One tick for the authenticated profile. A caller owns the session's generation. */
internal suspend fun tickProtocolRemotePolicies(
    context: Context,
    profileId: String,
    policies: FinishInOrderStore.ScheduledPolicies,
    ownerCurrent: () -> Boolean,
    operations: () -> ClientOperations
) = withContext(Dispatchers.IO + RemotePolicyWriteFence(ownerCurrent)) {
    val scope = FinishInOrderScopes.protocol(profileId)
    val finishInOrder = policies.mayHaveWork(scope)
    val smartSeeding = RemoteSeeding.EnforcementSwitch(context)
        .enabled(RemoteSeeding.protocolEnvironment(profileId))
    if ((!finishInOrder && !smartSeeding) || !ownerCurrent()) return@withContext

    val ops = operations()
    val session = if (finishInOrder) ops.session()
        .takeIf { ClientCapability.FILE_PRIORITIES in it.capabilities } else null
    if (!ownerCurrent()) return@withContext
    val inventory = if (session != null) ops.client.torrents() else null
    if (smartSeeding && ownerCurrent()) {
        val step = RemoteSeeding.tickProtocol(context, profileId, ops, inventory = inventory,
            ownerCurrent = ownerCurrent)
        // A cap or Stop may have changed the client since this observation.
        if (step == RemoteSeeding.Tick.STOP_ATTEMPTED) return@withContext
    }
    if (session == null || inventory == null) return@withContext
    val store = FinishInOrderStore(context, scope)
    for (transfer in inventory) {
        if (!ownerCurrent()) return@withContext
        val record = store.get(transfer.hash) ?: continue
        if (policies.unattributed) store.rememberOwner(transfer.hash)
        if (transfer.runState.started != true || !record.isScheduled()) continue
        stepClientFinishInOrder(ops, session.filePriorities, store, transfer.hash, ownerCurrent)
    }
}
