package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UniversalAddAdmission
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddDisplayStage
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UniversalAddTransferReceiptTest {
    @Test fun onlyTheExactClientGetsAReceiptAndUncertaintyNeverBecomesVerified() {
        val coordinator = UniversalAddCoordinator()
        val environment = EnvironmentId("exact-profile")
        val hash = "ab".repeat(20)
        val source = UniversalAddSource.magnet("magnet:?xt=urn:btih:$hash", hash)
        val intent = UniversalAddIntent(source, UniversalAddTarget.ProtocolClient(environment),
            UniversalAddPlace.ServerDefault("/downloads"), startWhenAdded = false, allowArtwork = true)
        assertNull(coordinator.transferReceipt(environment, "Exact client", submitting = true))
        val ticket = requireNotNull(UniversalAddAdmission.authorize(coordinator, intent,
            EnvironmentCapabilities.unprobed(environment), EnvironmentAvailability.LIVE,
            acquireMutationGate = { true }, submittedAtEpochMillis = 1_000L).ticket)
        val sending = requireNotNull(coordinator.transferReceipt(environment, "Exact client", submitting = true))
        assertEquals(environment, sending.key.environment)
        assertEquals(hash, sending.key.infoHash)
        assertEquals(UniversalAddDisplayStage.SUBMITTING, sending.display.stage)
        assertNull(coordinator.transferReceipt(EnvironmentId("other-profile"), "Other client", submitting = true))
        coordinator.requireReadback(ticket)
        val uncertain = requireNotNull(coordinator.transferReceipt(environment, "Exact client", submitting = false))
        assertEquals(UniversalAddDisplayStage.UNCONFIRMED, uncertain.display.stage)
        assertFalse(uncertain.display.verifiedJobObserved)
    }
}
