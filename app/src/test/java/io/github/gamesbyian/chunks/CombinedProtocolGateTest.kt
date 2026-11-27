package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which combined view is a typed client's own (gated) view. */
class CombinedProtocolGateTest {
    private fun profile(id: String, backend: RemoteBackendKind = RemoteBackendKind.DELUGE) =
        CombinedReadOnlyProtocolProfile(id, backend, "Box", 1L, inspect = { error("unused") })

    private val a = profile("a")
    private val b = profile("b", RemoteBackendKind.QBITTORRENT)
    private val scopes = mapOf(a.environment to "scope-a", b.environment to "scope-b")

    @Test fun exactlyOneTypedClientSelectedIsItsOwnGatedView() {
        assertEquals(CombinedProtocolGate.Gate("scope-a", "Deluge"),
            CombinedProtocolGate.forView(EnvironmentViewScope.Only(setOf(a.environment)), listOf(a, b), scopes))
        assertEquals("qBittorrent", CombinedProtocolGate.forView(
            EnvironmentViewScope.Only(setOf(b.environment)), listOf(a, b), scopes)?.clientKind)
    }

    @Test fun allOrSeveralOrNonTypedViewsAreNotGatedByAClientScope() {
        assertNull(CombinedProtocolGate.forView(EnvironmentViewScope.All, listOf(a, b), scopes))
        assertNull(CombinedProtocolGate.forView(
            EnvironmentViewScope.Only(setOf(a.environment, b.environment)), listOf(a, b), scopes))
        assertNull("Local or rTorrent have their own gates", CombinedProtocolGate.forView(
            EnvironmentViewScope.Only(setOf(EnvironmentId("local"))), listOf(a, b), scopes))
        assertNull(CombinedProtocolGate.forView(EnvironmentViewScope.Only(emptySet()), listOf(a, b), scopes))
    }

    @Test fun aClientWithoutAVerifiablePrivacyScopeIsNeverGatedWithABorrowedOne() {
        assertNull(CombinedProtocolGate.forView(EnvironmentViewScope.Only(setOf(a.environment)),
            listOf(a, b), mapOf(a.environment to null, b.environment to "scope-b")))
    }
}
