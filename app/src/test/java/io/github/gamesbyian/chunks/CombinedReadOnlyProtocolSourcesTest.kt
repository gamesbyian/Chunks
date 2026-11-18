package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.CapabilityEvidence
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilitySnapshot
import io.github.gamesbyian.chunks.model.EnvironmentOperation
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.NewRemoteReadOnlyCapabilities
import io.github.gamesbyian.chunks.model.RemoteBackendIdentity
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CombinedReadOnlyProtocolSourcesTest {
    private val id = RemoteBackendIdentity.of(RemoteBackendKind.QBITTORRENT,
        "https://seedbox.example/tenant/qb/", "alice")
    private val hash = "a".repeat(40)
    private val startedAt = 1_000_000L

    private fun profile(
        kind: RemoteBackendKind = RemoteBackendKind.QBITTORRENT,
        environmentId: String = id.value,
        revision: Long = startedAt
    ) = CombinedReadOnlyProtocolProfile(environmentId, kind, "My test client", revision) {
        throw AssertionError("No actual network operation in a pure admission test")
    }

    private fun accepted(
        kind: RemoteBackendKind = RemoteBackendKind.QBITTORRENT,
        observedAt: Long = startedAt,
        complete: Boolean = true
    ): CombinedReadOnlyProtocolReceipt {
        val observation = UnifiedTransferObservation(
            UnifiedTransferKey.of(id, hash), "Sample", "Sample", 100L, 500_000,
            started = true, downloadBytesPerSecond = 12L, uploadBytesPerSecond = 0L,
            peers = null
        )
        return CombinedReadOnlyProtocolReceipt(kind,
            EnvironmentTransferSnapshot(id, EnvironmentAvailability.LIVE, complete,
                observedAt, listOf(observation)),
            NewRemoteReadOnlyCapabilities.forBackend(kind, id, observedAt))
    }

    @Test fun realMatchingCompleteReadOnlyEvidencePassesAndPreservesExistingTransferKey() {
        val receipt = accepted()
        val result = CombinedReadOnlyProtocolAdmission.accept(profile(), receipt, startedAt + 1_000L)
        assertEquals(id, result.environment)
        assertEquals(1, result.transfers.size)
        assertEquals(hash, result.transfers.single().key.infoHash)
        assertEquals(500_000, result.transfers.single().progressPpm)
        assertTrue(result.canClaimCompleteLiveInventory)
        assertFalse(receipt.capabilities.decide(
            EnvironmentOperation.SESSION_REMOVE, EnvironmentAvailability.LIVE).allowed)
    }

    @Test fun mismatchedBackendAndSavedProfileIdentityNeverPublishRows() {
        val receipt = accepted()
        val badProfiles = listOf(
            profile(RemoteBackendKind.TRANSMISSION),
            profile(environmentId = "b".repeat(64)),
            profile(revision = 0),
            profile(RemoteBackendKind.RTORRENT)
        )
        badProfiles.forEach { invalid ->
            try {
                CombinedReadOnlyProtocolAdmission.accept(invalid, receipt, startedAt + 2L)
                fail("Wrong backend or profile inherited live rows")
            } catch (expected: IOException) { }
        }
    }

    @Test fun unknownMutationCapabilityMustBeDeniedRatherThanTentativelyAllowed() {
        val receipt = accepted()
        val misleading = receipt.copy(capabilities = receipt.capabilities.copy(
            evidence = receipt.capabilities.evidence - EnvironmentOperation.SESSION_REMOVE
        ))
        assertEquals(CapabilityEvidence.UNKNOWN,
            misleading.capabilities.of(EnvironmentOperation.SESSION_REMOVE))
        assertTrue(misleading.capabilities.decide(
            EnvironmentOperation.SESSION_REMOVE, EnvironmentAvailability.LIVE).allowed)
        try {
            CombinedReadOnlyProtocolAdmission.accept(profile(), misleading, startedAt + 100L)
            fail("Unknown write was treated as safely unavailable")
        } catch (expected: IOException) { }
    }

    @Test fun incompleteStaleFutureAndInvalidTimestampAreNotAuthenticatedCurrentSources() {
        val bad = listOf(
            accepted(complete = false) to startedAt + 1L,
            accepted(observedAt = startedAt - 120_001L) to startedAt + 1L,
            accepted(observedAt = startedAt + 100L) to startedAt,
            accepted(observedAt = 0L) to startedAt + 1L,
            accepted().copy(snapshot = accepted().snapshot.copy(
                availability = EnvironmentAvailability.STALE)) to startedAt + 1L
        )
        for ((receipt, now) in bad) {
            try {
                CombinedReadOnlyProtocolAdmission.accept(profile(), receipt, now)
                fail("Unverified or stale source accepted")
            } catch (expected: IOException) { }
        }
    }

    @Test fun privacyScopesAreProtocolQualifiedAndNeverContainRawClientEndpoint() {
        val qb = profile()
        val transmission = profile(RemoteBackendKind.TRANSMISSION)
        val a = CombinedReadOnlyProtocolAdmission.privacyScope(qb)
        val b = CombinedReadOnlyProtocolAdmission.privacyScope(transmission)
        assertNotNull(a)
        assertNotEquals(a, b)
        assertFalse(a.orEmpty().contains("seedbox.example"))
        assertFalse(a.orEmpty().contains("alice"))
        assertNull(CombinedReadOnlyProtocolAdmission.privacyScope(
            profile(RemoteBackendKind.RTORRENT)))
        assertNull(CombinedReadOnlyProtocolAdmission.privacyScope(
            profile(environmentId = "arbitrary")))
        assertEquals(qb.identityWitness, qb.copy(label = "Renamed only").identityWitness)
        assertNotEquals(qb.identityWitness, qb.copy(verifiedAtMillis = startedAt + 1L).identityWitness)
        assertFalse(qb.toString().contains("seedbox.example"))
    }

    @Test fun automaticReadsRespectSuccessCadenceFailureBackoffAndSingleFlight() {
        val witness = profile().identityWitness
        val fresh = CombinedReadOnlyProtocolListing(
            snapshot = accepted().snapshot, witness = witness)
        val loading = fresh.copy(loading = true)
        val failed = fresh.copy(failed = true)
        assertTrue(CombinedProtocolRefreshPolicy.due(null, null, 1_000L))
        assertFalse(CombinedProtocolRefreshPolicy.due(loading, 0L, 100_000L))
        assertFalse(CombinedProtocolRefreshPolicy.due(fresh, 10L, 59_999L))
        assertTrue(CombinedProtocolRefreshPolicy.due(fresh, 10L, 60_010L))
        assertFalse(CombinedProtocolRefreshPolicy.due(failed, 10L, 299_999L))
        assertTrue(CombinedProtocolRefreshPolicy.due(failed, 10L, 300_010L))
        assertTrue(CombinedProtocolRefreshPolicy.due(fresh, 500L, 10L))
    }

    @Test fun duplicateHashesAcrossDifferentEnvironmentIdsAreIndependent() {
        val id2 = RemoteBackendIdentity.of(RemoteBackendKind.TRANSMISSION,
            "https://seedbox.example/tenant/transmission/rpc", "alice")
        val a = UnifiedTransferKey.of(id, hash)
        val b = UnifiedTransferKey.of(id2, hash)
        assertNotEquals(a, b)
        assertEquals(hash, a.infoHash)
        assertEquals(hash, b.infoHash)
        assertNotEquals(EnvironmentSnapshots.LOCAL, a.environment)
    }
}
