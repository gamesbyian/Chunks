package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import org.junit.Assert.*
import org.junit.Test

class RemoteCapabilityProbeSessionGateTest {
    private val server2 = EnvironmentId("server-rutorrent2")
    private val server3 = EnvironmentId("server-rutorrent3")
    private val sameHash2 = UnifiedTransferKey.of(server2, "a".repeat(40))
    private val sameHash3 = UnifiedTransferKey.of(server3, "a".repeat(40))
    private val otherHash2 = UnifiedTransferKey.of(server2, "b".repeat(40))

    // Profiles can be value-equal and nevertheless represent different live
    // authenticated transport sessions. Never let equals() stand in for ===.
    private class Session(val profile: String) {
        override fun equals(other: Any?): Boolean =
            other is Session && other.profile == profile
        override fun hashCode(): Int = profile.hashCode()
    }

    @Test fun cannotProbeSavedProfileWithoutLiveSessionAndExactFocus() {
        val fence = RemoteCapabilityProbeSessionGate()
        assertNull(fence.begin())
        fence.bind(null, server2, sameHash2)
        assertNull(fence.begin())
        fence.bind(Session("two"), server2, sameHash3)
        assertNull("Wrong-environment key never arms probe", fence.begin())
        fence.bind(Session("two"), server2, null)
        assertNull(fence.begin())
    }

    @Test fun reconnectingSameProfileDiscardsLateReadOnlyEvidence() {
        val gate = RemoteCapabilityProbeSessionGate()
        val oldSession = Session("same-URL-and-account")
        val newSession = Session("same-URL-and-account")
        assertEquals(oldSession, newSession)
        assertFalse(oldSession === newSession)
        gate.bind(oldSession, server2, sameHash2)
        val old = requireNotNull(gate.begin())
        assertTrue(gate.current(old, oldSession, server2, sameHash2))
        gate.bind(newSession, server2, sameHash2)
        assertFalse("Old result must not cross reconnection", gate.current(old,
            newSession, server2, sameHash2))
        val fresh = requireNotNull(gate.begin())
        assertTrue(gate.current(fresh, newSession, server2, sameHash2))
        assertFalse(gate.current(fresh, oldSession, server2, sameHash2))
    }

    @Test fun sameInfohashOnIndependentNumberedServerCannotReuseProbe() {
        val gate = RemoteCapabilityProbeSessionGate()
        val s2 = Session("rutorrent2")
        val s3 = Session("rutorrent3")
        gate.bind(s2, server2, sameHash2)
        val ticket = requireNotNull(gate.begin())
        gate.bind(s3, server3, sameHash3)
        assertFalse(gate.current(ticket, s3, server3, sameHash3))
        assertFalse(gate.current(ticket, s2, server2, sameHash2))
        val fresh = requireNotNull(gate.begin())
        assertTrue(gate.current(fresh, s3, server3, sameHash3))
    }

    @Test fun switchingOrClosingDetailsInvalidatesPendingResult() {
        val gate = RemoteCapabilityProbeSessionGate()
        val session = Session("two")
        gate.bind(session, server2, sameHash2)
        val old = requireNotNull(gate.begin())
        gate.bind(session, server2, otherHash2)
        assertFalse(gate.current(old, session, server2, sameHash2))
        val second = requireNotNull(gate.begin())
        gate.bind(session, server2, null)
        assertFalse(gate.current(second, session, server2, otherHash2))
        assertNull(gate.begin())
    }

    @Test fun newRequestAndDisconnectBothCancelEarlierEvidence() {
        val gate = RemoteCapabilityProbeSessionGate()
        val session = Session("two")
        gate.bind(session, server2, sameHash2)
        val first = requireNotNull(gate.begin())
        val second = requireNotNull(gate.begin())
        assertFalse(gate.current(first, session, server2, sameHash2))
        assertTrue(gate.current(second, session, server2, sameHash2))
        gate.invalidate()
        assertFalse(gate.current(second, session, server2, sameHash2))
        assertNull(gate.begin())
    }
}
