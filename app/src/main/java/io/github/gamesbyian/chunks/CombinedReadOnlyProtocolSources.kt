package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.gamesbyian.chunks.model.CapabilityEvidence
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilitySnapshot
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentOperation
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** A saved typed connection, without transport secrets in the UI data fields. */
internal data class CombinedReadOnlyProtocolProfile(
    val id: String,
    val backend: RemoteBackendKind,
    val label: String,
    /** Changes whenever exact credentials are replaced and reauthenticated. */
    val verifiedAtMillis: Long,
    /**
     * A fresh write-capable session bound to the SAME exact credentials and
     * identity. Details uses it only through the shared operation workflow.
     */
    val operations: (() -> io.github.gamesbyian.chunks.rpc.ClientOperations)? = null,
    /** Must perform fresh exact-client, challenged/cookie-authenticated read. */
    val inspect: suspend () -> CombinedReadOnlyProtocolReceipt
) {
    val environment: EnvironmentId get() = EnvironmentId(id)
    val identityWitness: String get() = "chunks-readonly-protocol-v1:" +
        backend.name + ":" + id + ":" + verifiedAtMillis
    /** This exact client principal's Chunks display-alias scope (never a label or endpoint text). */
    val aliasScope: String get() = ProtocolAliasScope.of(id)
    override fun toString(): String = "Read-only remote client (<redacted>)"
}

/**
 * Receipts are deliberately separate from the display row model. A source
 * can publish its rows only when identity, backend, time and unavailable
 * mutation capabilities match the actual saved profile.
 */
internal data class CombinedReadOnlyProtocolReceipt(
    val backend: RemoteBackendKind,
    val snapshot: EnvironmentTransferSnapshot,
    val capabilities: EnvironmentCapabilitySnapshot
)

internal object CombinedReadOnlyProtocolAdmission {
    private val ID = Regex("[0-9a-f]{64}")

    fun privacyScope(profile: CombinedReadOnlyProtocolProfile): String? =
        profile.takeIf { it.backend != RemoteBackendKind.RTORRENT &&
            ID.matches(it.id) && it.verifiedAtMillis > 0 }
            ?.let { "chunks-readonly-remote-client-v1:" + it.backend.name + ":" + it.id }

    fun accept(
        profile: CombinedReadOnlyProtocolProfile,
        read: CombinedReadOnlyProtocolReceipt,
        nowMillis: Long
    ): EnvironmentTransferSnapshot {
        val environment = profile.environment
        val timestamp = read.snapshot.observedAtEpochMillis
        if (privacyScope(profile) == null || read.backend != profile.backend ||
            read.snapshot.environment != environment ||
            read.capabilities.environment != environment ||
            timestamp == null || timestamp <= 0 || timestamp > nowMillis ||
            nowMillis - timestamp > EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS ||
            !read.snapshot.canClaimCompleteLiveInventory ||
            read.capabilities.observedAtEpochMillis != timestamp ||
            read.capabilities.of(EnvironmentOperation.LIST) != CapabilityEvidence.UNKNOWN ||
            EnvironmentOperation.entries.filter { it != EnvironmentOperation.LIST }.any {
                read.capabilities.decide(it, EnvironmentAvailability.LIVE).allowed
            }) {
            throw IOException("Saved client authentication or read-only inventory could not be verified")
        }
        return read.snapshot
    }
}

internal data class CombinedReadOnlyProtocolListing(
    val snapshot: EnvironmentTransferSnapshot? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val witness: String
)

internal data class CombinedProtocolSources(
    val sources: List<EnvironmentTransferSnapshot>,
    val listings: Map<EnvironmentId, CombinedReadOnlyProtocolListing>,
    val reload: (EnvironmentId) -> Unit
)

internal object CombinedProtocolRefreshPolicy {
    fun due(
        listing: CombinedReadOnlyProtocolListing?,
        lastAttemptAt: Long?,
        nowMillis: Long
    ): Boolean {
        if (listing?.loading == true) return false
        if (lastAttemptAt == null || listing == null || nowMillis < lastAttemptAt) return true
        return nowMillis - lastAttemptAt >=
            (if (listing.failed) 300_000L else 60_000L)
    }
}

/**
 * One small feed to the canonical Combined catalog. It has no torrent
 * commands, separate list UI, stored auth tickets or private source cache.
 * Rows become invisible immediately on privacy lock/roster replacement and
 * a stale asynchronous read cannot restore them.
 */
@Composable
internal fun rememberCombinedProtocolSources(
    profiles: List<CombinedReadOnlyProtocolProfile>,
    privacyAllowed: Set<EnvironmentId>,
    nowMillis: Long
): CombinedProtocolSources {
    val policyEpoch = TransferPrivacyPolicyStore.revision
    val lifecycleEpoch = TransferPrivacyRuntime.lifecycleRevision
    val identities = profiles.map { it.id to it.identityWitness }
    val profileMap = remember(identities) {
        val byId = profiles.associateBy { it.environment }
        require(byId.size == profiles.size) { "Duplicate read-only protocol profile identity" }
        byId
    }
    val active = remember(identities, privacyAllowed) {
        profileMap.filterKeys { it in privacyAllowed }.mapValues { it.value.identityWitness }
    }
    val currentActive = rememberUpdatedState(active)
    val currentProfiles = rememberUpdatedState(profiles.associateBy { it.environment })
    val scope = rememberCoroutineScope()
    val fence = remember { SavedListingResponseFence() }
    var listings by remember { mutableStateOf<Map<EnvironmentId, CombinedReadOnlyProtocolListing>>(emptyMap()) }
    val attempts = remember { mutableMapOf<SavedProfileAutoRead.Key, Long>() }

    LaunchedEffect(active, policyEpoch, lifecycleEpoch) {
        fence.retireOutside(active)
        // Do not keep credentials, transfer names or a future read's callback
        // after a privacy lock, profile removal or credential replacement.
        listings = listings.filter { (id, value) -> active[id] == value.witness }
    }

    fun refresh(id: EnvironmentId) {
        val profile = currentProfiles.value[id] ?: return
        val witness = profile.identityWitness
        if (currentActive.value[id] != witness || listings[id]?.loading == true) return
        val epochPolicy = policyEpoch
        val epochLifecycle = lifecycleEpoch
        val ticket = fence.begin(SavedListingResponseFence.Source(id, witness))
        attempts[SavedProfileAutoRead.Key(id, witness)] = System.currentTimeMillis()
        val old = listings[id]?.takeIf { it.witness == witness }
            ?: CombinedReadOnlyProtocolListing(witness = witness)
        listings = listings + (id to old.copy(loading = true, failed = false))
        scope.launch {
            val result = try {
                val accepted = withTimeout(45_000) {
                    val receipt = withContext(Dispatchers.IO) { profile.inspect() }
                    CombinedReadOnlyProtocolAdmission.accept(profile, receipt, System.currentTimeMillis())
                }
                CombinedReadOnlyProtocolListing(accepted, witness = witness)
            } catch (cancel: CancellationException) {
                if (cancel !is kotlinx.coroutines.TimeoutCancellationException) throw cancel
                old.copy(loading = false, failed = true)
            } catch (_: Exception) {
                old.copy(loading = false, failed = true)
            }
            if (epochPolicy == TransferPrivacyPolicyStore.revision &&
                epochLifecycle == TransferPrivacyRuntime.lifecycleRevision &&
                fence.accepts(ticket, currentActive.value)) {
                listings = listings + (id to result)
            }
        }
    }
    val latestRefresh = rememberUpdatedState<(EnvironmentId) -> Unit>({ refresh(it) })
    LaunchedEffect(Unit) {
        while (true) {
            val clock = System.currentTimeMillis()
            currentProfiles.value.values.forEach { profile ->
                val id = profile.environment
                val witness = profile.identityWitness
                if (currentActive.value[id] == witness &&
                    CombinedProtocolRefreshPolicy.due(
                        listings[id]?.takeIf { it.witness == witness },
                        attempts[SavedProfileAutoRead.Key(id, witness)], clock
                    )) latestRefresh.value(id)
            }
            delay(SavedProfileAutoRead.TICK_MS)
        }
    }

    val sources = profiles.map { profile ->
        val id = profile.environment
        val listing = listings[id]?.takeIf { it.witness == profile.identityWitness }
        val unlocked = id in privacyAllowed && active[id] == profile.identityWitness &&
            policyEpoch == TransferPrivacyPolicyStore.revision &&
            lifecycleEpoch == TransferPrivacyRuntime.lifecycleRevision
        val last = listing?.snapshot
        when {
            !unlocked -> EnvironmentTransferSnapshot(id, EnvironmentAvailability.LOCKED,
                inventoryComplete = false, observedAtEpochMillis = null, transfers = emptyList())
            last == null -> EnvironmentTransferSnapshot(id, EnvironmentAvailability.OFFLINE,
                inventoryComplete = false, observedAtEpochMillis = null, transfers = emptyList())
            else -> EnvironmentTransferSnapshot(id,
                // The caller's clock ticks every few seconds; a read accepted
                // since the last tick must not look "from the future" (STALE),
                // which disabled Details controls after every refresh.
                if (listing?.loading == true || listing?.failed == true ||
                    EnvironmentSnapshots.serverInventoryIsOutOfDate(last.observedAtEpochMillis,
                        maxOf(nowMillis, System.currentTimeMillis())))
                    EnvironmentAvailability.STALE else EnvironmentAvailability.LIVE,
                inventoryComplete = last.inventoryComplete,
                observedAtEpochMillis = last.observedAtEpochMillis,
                transfers = last.transfers)
        }
    }
    return CombinedProtocolSources(sources, listings, latestRefresh.value)
}

/**
 * Chunks display aliases for a Transmission/qBittorrent/Deluge profile are
 * keyed by its exact client identity (backend, endpoint, principal, daemon:
 * RemoteBackendIdentity), so two accounts on one host never share names.
 * TransferAliasStore fingerprints the scope; nothing is sent to the client.
 */
internal object ProtocolAliasScope {
    private const val PREFIX = "chunks-protocol-alias-v1:"
    fun of(profileId: String): String {
        require(profileId.isNotBlank()) { "A protocol alias scope needs the exact client identity" }
        return PREFIX + profileId
    }
}
