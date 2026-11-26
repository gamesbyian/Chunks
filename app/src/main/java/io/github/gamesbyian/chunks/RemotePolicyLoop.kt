package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyScope
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.RemoteWriteOwnerFence
import io.github.gamesbyian.chunks.seeding.RemoteSeeding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The live composition as the rTorrent policy loop sees it (A2611-02). Every member
 * reads CURRENT state at the moment it is asked, never a value captured when the loop
 * started: the loop's owner-currency test depends on that.
 */
internal interface RemotePolicyHost {
    val currentServer: RTorrentRemote?
    val currentAliasScope: String
    val isLoading: Boolean
    var isStale: Boolean
    val currentTransfers: List<RemoteTransfer>
    /** A Connect is running: no policy may act against a half-established session. */
    val connectInFlight: Boolean
    val mutationGate: RemoteMutationReadbackGate
    /** The app is started and no credential change is pending. */
    fun mayRun(): Boolean
    /** A write settled: a fresh read is required before another write. */
    fun requestReadback()
    fun refresh()
}

/**
 * One authenticated rTorrent session's 30-second policy loop (Smart Seeding, Finish in
 * Order). Behavior is exactly the loop that lived in `ChunksApp`: it shares the manual
 * mutation gate, acts only while its [ownerCurrent] test holds (same live session and
 * account, same credential generation, same privacy lifecycle, no Connect running), settles the
 * gate only for this exact session, and never retries a write. It ends only by cancellation
 * (a new session, disconnect or composition disposal).
 */
internal suspend fun runRemotePolicyLoop(
    context: Context,
    host: RemotePolicyHost,
    connectedRemote: RTorrentRemote,
    expectedScope: String,
    privacyScope: String
): Nothing {
    val privacyStore = TransferPrivacyPolicyStore(context)
    val store = FinishInOrderStore(context, expectedScope)
    val parentJob = currentCoroutineContext()[Job]
    while (true) {
        delay(30_000L)
        if (!host.mayRun()) continue
        val generation = RemoteCredentialLock.capture()
        val lifecycle = TransferPrivacyRuntime.lifecycleRevision
        val privacy = TransferPrivacyPolicyStore.revision
        val privacySession = TransferPrivacyRuntime.session
        if (!withContext(Dispatchers.IO) { privacyStore.loadInto(privacySession, privacyScope) }) continue
        val ownerCurrent: () -> Boolean = {
            parentJob?.isActive == true && host.mayRun() && !host.connectInFlight &&
                RemoteCredentialLock.current(generation) &&
                lifecycle == TransferPrivacyRuntime.lifecycleRevision &&
                privacy == TransferPrivacyPolicyStore.revision &&
                privacySession.mayAct(privacyScope) &&
                host.currentServer === connectedRemote && host.currentAliasScope == expectedScope
        }
        if (!ownerCurrent() || host.isLoading || host.isStale) continue
        val seedingEnabled = withContext(Dispatchers.IO) { RemoteSeeding.enabledFor(context, connectedRemote) }
        if (seedingEnabled && ownerCurrent() && !host.isLoading && !host.isStale && host.mutationGate.tryStart()) {
            var tick: RemoteSeeding.Tick? = null
            try {
                tick = withContext(Dispatchers.IO) {
                    RemoteSeeding.tick(context, connectedRemote, ownerCurrent = ownerCurrent)
                }
            } catch (cancel: CancellationException) {
                if (parentJob?.isActive != true || ownerCurrent()) throw cancel
            } finally {
                // The gate is shared with any later session: only this exact
                // session's admission may be settled (a reconnect reset it).
                if (host.currentServer === connectedRemote) {
                    if (tick == RemoteSeeding.Tick.NO_WRITE || tick == RemoteSeeding.Tick.NOT_RUN)
                        host.mutationGate.abandonKnownNoWrite()
                    else {
                        host.mutationGate.settled()
                        host.requestReadback()
                        host.isStale = true
                        host.refresh()
                    }
                }
            }
        }
        if (!ownerCurrent() || host.isLoading || host.isStale) continue
        val observedTransfers = host.currentTransfers
        val activeTransfers = withContext(Dispatchers.IO) {
            val policies = observedTransfers.mapNotNull { transfer ->
                store.get(transfer.hash)?.let { transfer to it }
            }
            store.rememberOwners(policies.map { it.first.hash }) { ownerCurrent() && !host.isLoading && !host.isStale }
            policies.filter { (transfer, record) -> transfer.started && record.isScheduled() }
                .map { it.first }
        }
        for (transfer in activeTransfers) {
            if (!ownerCurrent() || host.isLoading || host.isStale || !host.mutationGate.tryStart()) break
            var noWrite = false
            try {
                val step = withContext(Dispatchers.IO + RemoteWriteOwnerFence(ownerCurrent)) {
                    stepRemoteFinishInOrder(connectedRemote, store, transfer.hash, ownerCurrent)
                }
                noWrite = step is FinishInOrderRunner.Step.Idle
            } catch (cancel: CancellationException) {
                if (parentJob?.isActive != true || ownerCurrent()) throw cancel
            } catch (_: Exception) {
                // A partial write retains the readback fence.
            } finally {
                // The gate is shared with any later session: only this exact
                // session's admission may be settled (a reconnect reset it).
                if (host.currentServer === connectedRemote) {
                    if (noWrite) host.mutationGate.abandonKnownNoWrite()
                    else {
                        host.mutationGate.settled()
                        host.requestReadback()
                        host.isStale = true
                        host.refresh()
                    }
                }
            }
        }
    }
}
