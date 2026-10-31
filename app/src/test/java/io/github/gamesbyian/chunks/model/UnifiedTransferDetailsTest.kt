package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteProbe
import org.junit.Assert.*
import org.junit.Test

class UnifiedTransferDetailsTest {
    private val phone = EnvironmentSnapshots.PHONE
    private val serverA = EnvironmentId("first-server")
    private val serverB = EnvironmentId("second-server")
    private val hash = "a".repeat(40)
    private val same = UnifiedTransferKey.of(serverA, hash)
    private val now = 300_000L

    private fun row(id: EnvironmentId, name: String = "Transfer") = UnifiedTransferObservation(
        key = UnifiedTransferKey.of(id, hash),
        displayName = name, originalName = name, sizeBytes = null,
        progressPpm = null, started = null,
        downloadBytesPerSecond = null, uploadBytesPerSecond = null, peers = null
    )

    private fun source(
        id: EnvironmentId,
        state: EnvironmentAvailability = EnvironmentAvailability.LIVE,
        complete: Boolean = true,
        timestamp: Long? = now - 1,
        rows: List<UnifiedTransferObservation> = listOf(row(id))
    ) = EnvironmentTransferSnapshot(id, state, complete, timestamp, rows)

    private fun probe(id: EnvironmentId, timestamp: Long = now - 2) =
        EnvironmentCapabilitySnapshot(id, "rTorrent test", timestamp,
            mapOf(EnvironmentOperation.START_STOP to CapabilityEvidence.ADVERTISED,
                EnvironmentOperation.SESSION_REMOVE to CapabilityEvidence.MISSING))

    @Test fun exactSameHashOnTwoServersNeverCrossesDetailOrCapability() {
        val rows = listOf(source(serverB, rows = listOf(row(serverB, "Other owner's job"))),
            source(serverA, rows = listOf(row(serverA, "Chosen job"))))
        val found = UnifiedTransferDetails.resolve(same, rows, probe(serverA), now)
        assertEquals("Chosen job", found.observation?.displayName)
        assertEquals(serverA, found.key.environment)
        assertEquals(TransferDetailReadState.CURRENT, found.readState)
        assertTrue(found.operations.single { it.operation == EnvironmentOperation.START_STOP }.mayOfferAttempt)

        val wrongProbe = UnifiedTransferDetails.resolve(same, rows, probe(serverB), now)
        assertFalse(wrongProbe.operations.single { it.operation == EnvironmentOperation.START_STOP }.mayOfferAttempt)
        assertEquals(CapabilityEvidence.UNKNOWN, wrongProbe.operations.single {
            it.operation == EnvironmentOperation.START_STOP }.evidence)
    }

    @Test fun spoofedPhoneBuildEvidenceCanNeverAuthorizeRemoteDetails() {
        val spoof = EnvironmentCapabilitySnapshot(serverA, "phone engine", now,
            mapOf(EnvironmentOperation.START_STOP to CapabilityEvidence.BUILT_IN))
        val detail = UnifiedTransferDetails.resolve(same, listOf(source(serverA)), spoof, now)
        val action = detail.operations.single { it.operation == EnvironmentOperation.START_STOP }
        assertEquals(TransferDetailReadState.CURRENT, detail.readState)
        assertEquals(CapabilityEvidence.UNKNOWN, action.evidence)
        assertFalse(action.mayOfferAttempt)
        assertTrue(action.explanation.contains("wrong engine type"))
    }

    @Test fun remoteAdvertisementCanNeverAuthorizePhoneDetails() {
        val phoneKey = UnifiedTransferKey.of(phone, hash)
        val spoof = EnvironmentCapabilitySnapshot(phone, "rTorrent test", now,
            mapOf(EnvironmentOperation.START_STOP to CapabilityEvidence.ADVERTISED))
        val detail = UnifiedTransferDetails.resolve(phoneKey, listOf(source(phone)), spoof, now)
        val action = detail.operations.single { it.operation == EnvironmentOperation.START_STOP }
        assertEquals(CapabilityEvidence.UNKNOWN, action.evidence)
        assertFalse(action.mayOfferAttempt)
        assertTrue(action.explanation.contains("wrong engine type"))
    }

    @Test fun completeFreshListCanReportAbsentWithoutClaimingRemoval() {
        val detail = UnifiedTransferDetails.resolve(same, listOf(source(serverA, rows = emptyList())),
            probe(serverA), now)
        assertNull(detail.observation)
        assertEquals(TransferDetailReadState.NOT_IN_CURRENT_LIST, detail.readState)
        assertTrue(detail.readState.explanation.contains("No removal is inferred"))
        assertTrue(detail.operations.none { it.mayOfferAttempt })
    }

    @Test fun incompleteViewDoesNotTurnAnAbsentRowIntoEvidenceOfRemoval() {
        val detail = UnifiedTransferDetails.resolve(same, listOf(
            source(serverA, complete = false, rows = emptyList())), probe(serverA), now)
        assertEquals(TransferDetailReadState.INCOMPLETE, detail.readState)
        assertTrue(detail.operations.none { it.mayOfferAttempt })
    }

    @Test fun offlineStaleFutureAndOldSourcesCanNeverOfferCommands() {
        val cases = listOf(
            source(serverA, EnvironmentAvailability.OFFLINE),
            source(serverA, EnvironmentAvailability.STALE),
            source(serverA, timestamp = now + 1),
            source(serverA, timestamp = now - EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS - 1)
        )
        for (snapshot in cases) {
            val detail = UnifiedTransferDetails.resolve(same, listOf(snapshot), probe(serverA), now)
            assertTrue(detail.operations.none { it.mayOfferAttempt })
            assertNotEquals(TransferDetailReadState.CURRENT, detail.readState)
        }
    }

    @Test fun lockedEnvironmentAlwaysRedactsCachedTransfer() {
        val row = row(serverA, "PRIVATE TRANSFER")
        val detail = UnifiedTransferDetails.resolve(same, listOf(
            source(serverA, EnvironmentAvailability.LOCKED, rows = listOf(row))),
            probe(serverA), now)
        assertEquals(TransferDetailReadState.LOCKED, detail.readState)
        assertNull(detail.observation)
        assertNull(detail.observedAtEpochMillis)
        assertTrue(detail.operations.none { it.mayOfferAttempt })
    }

    @Test fun expiredUnprobedAndMissingCapabilitiesAreNotExecutablePromises() {
        val rows = listOf(source(serverA))
        val old = UnifiedTransferDetails.resolve(same, rows,
            probe(serverA, now - EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS - 1), now)
        val absent = UnifiedTransferDetails.resolve(same, rows, null, now)
        val fresh = UnifiedTransferDetails.resolve(same, rows, probe(serverA), now)
        for (unproven in listOf(old, absent)) {
            assertEquals(CapabilityEvidence.UNKNOWN, unproven.operations.single {
                it.operation == EnvironmentOperation.START_STOP }.evidence)
            assertFalse(unproven.operations.any { it.mayOfferAttempt })
        }
        assertTrue(fresh.operations.single { it.operation == EnvironmentOperation.START_STOP }.mayOfferAttempt)
        assertFalse(fresh.operations.single { it.operation == EnvironmentOperation.SESSION_REMOVE }.mayOfferAttempt)
        assertTrue(fresh.operations.single { it.operation == EnvironmentOperation.START_STOP }
            .explanation.contains("unverified"))
    }

    @Test fun bundledPhoneCapabilityNeedsExactCurrentPhoneObservationToo() {
        val key = UnifiedTransferKey.of(phone, hash)
        val ready = UnifiedTransferDetails.resolve(key, listOf(source(phone)),
            EnvironmentCapabilities.phone(), now)
        assertTrue(ready.operations.single { it.operation == EnvironmentOperation.FILE_PRIORITIES }.mayOfferAttempt)
        assertFalse(ready.operations.single { it.operation == EnvironmentOperation.COPY_TO_PHONE }.mayOfferAttempt)
        assertEquals(CapabilityEvidence.NOT_APPLICABLE,
            ready.operations.single { it.operation == EnvironmentOperation.COPY_TO_PHONE }.evidence)
        val missingPhone = UnifiedTransferDetails.resolve(key, emptyList(),
            EnvironmentCapabilities.phone(), now)
        assertEquals(TransferDetailReadState.ENVIRONMENT_MISSING, missingPhone.readState)
        assertTrue(missingPhone.operations.none { it.mayOfferAttempt })
    }

    @Test fun timestampLessOrDuplicateSourcesFailClosed() {
        val unconfirmed = UnifiedTransferDetails.resolve(same,
            listOf(source(serverA, timestamp = null)), probe(serverA), now)
        assertNotEquals(TransferDetailReadState.CURRENT, unconfirmed.readState)
        assertTrue(unconfirmed.operations.none { it.mayOfferAttempt })
        try {
            UnifiedTransferDetails.resolve(same,
                listOf(source(serverA), source(serverA)), probe(serverA), now)
            fail("Duplicate source IDs must be refused")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun sourceCapabilityReportMayBeUnknownWithoutRemoteProbe() {
        val remote = EnvironmentCapabilities.fromProbe(serverA, RemoteProbe("unknown", null), now)
        val detail = UnifiedTransferDetails.resolve(same, listOf(source(serverA)), remote, now)
        assertEquals(CapabilityEvidence.UNKNOWN, detail.operations.single {
            it.operation == EnvironmentOperation.START_STOP }.evidence)
        assertTrue(detail.operations.none { it.mayOfferAttempt })
    }
}
