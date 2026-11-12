package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemotePeerSource
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerToLocalCopyTest {
    private val hash = "b63eaa9720ee797667ef58ea35ef7d81ab5d1c16"
    private fun transfer(done: Long = 1_000, size: Long = 1_000) =
        RemoteTransfer(hash.uppercase(), "Big Buck Bunny", size, done, true, "/srv/downloads")
    private fun source(
        host: String = "seed.example", announced: String? = null, port: Long = 51413,
        private: Boolean = false, active: Boolean = true, bound: String? = null
    ) = RemotePeerSource(host, announced, port, private, active, bound)
    private fun ip(vararg literals: String): (String) -> List<InetAddress> =
        { literals.map(InetAddress::getByName) }

    @Test fun readyPlanCarriesExactHashNameAndTheServerAsDirectPeer() {
        val plan = ServerToLocalCopy.plan(transfer(), source(), ip("2001:db8::7", "203.0.113.9"))
        plan as ServerToLocalCopy.Ready
        assertEquals("magnet:?xt=urn:btih:$hash&dn=Big%20Buck%20Bunny&x.pe=203.0.113.9:51413", plan.magnet)
        assertEquals("203.0.113.9:51413", plan.peer)
        assertEquals(1_000L, plan.bytes)
    }

    @Test fun ipv6OnlyServersAreBracketedAndTheAnnouncedAddressWins() {
        var asked = ""
        val plan = ServerToLocalCopy.plan(transfer(), source(announced = "peer.example")) {
            asked = it; listOf(InetAddress.getByName("2001:db8::7"))
        }
        assertEquals("peer.example", asked)
        assertEquals("[2001:db8:0:0:0:0:0:7]:51413", (plan as ServerToLocalCopy.Ready).peer)
    }

    @Test fun aSpecificBindAddressBeatsTheWebHostButNotTheAnnouncedAddress() {
        var asked = ""
        val resolve: (String) -> List<InetAddress> = { asked = it; listOf(InetAddress.getByName(it)) }
        val bound = ServerToLocalCopy.plan(transfer(), source(host = "198.51.100.10", bound = "198.51.100.19"), resolve)
        assertEquals("198.51.100.19:51413", (bound as ServerToLocalCopy.Ready).peer)
        ServerToLocalCopy.plan(transfer(), source(announced = "198.51.100.20", bound = "198.51.100.19"), resolve)
        assertEquals("198.51.100.20", asked)
    }

    @Test fun refusesWhatTheServerCannotSend() {
        fun refused(plan: ServerToLocalCopy.Plan) = (plan as ServerToLocalCopy.Refused).reason
        assertTrue(refused(ServerToLocalCopy.plan(transfer(done = 500), source(), ip("203.0.113.9"))).contains("50%"))
        assertTrue(refused(ServerToLocalCopy.plan(transfer(done = 0, size = 0), source(), ip("203.0.113.9"))).isNotBlank())
        assertTrue(refused(ServerToLocalCopy.plan(transfer(), source(private = true), ip("203.0.113.9"))).contains("private"))
        assertTrue(refused(ServerToLocalCopy.plan(transfer(), source(active = false), ip("203.0.113.9"))).contains("Start it"))
        assertTrue(refused(ServerToLocalCopy.plan(transfer(), source(port = 0), ip("203.0.113.9"))).contains("port"))
        assertTrue(refused(ServerToLocalCopy.plan(transfer(), source(), { error("dns") })).contains("seed.example"))
        val bad = RemoteTransfer("not-a-hash", "x", 1, 1, true, "/x")
        assertTrue(refused(ServerToLocalCopy.plan(bad, source(), ip("203.0.113.9"))).contains("v1"))
    }

    @Test fun loopbackIsOnlyAcceptedForTheLoopbackRig() {
        val remoteSeedbox = ServerToLocalCopy.plan(transfer(), source(), ip("127.0.0.1", "0.0.0.0"))
        assertTrue(remoteSeedbox is ServerToLocalCopy.Refused)
        val rig = ServerToLocalCopy.plan(transfer(), source(host = "127.0.0.1", port = 6890), ip("127.0.0.1"))
        assertEquals("127.0.0.1:6890", (rig as ServerToLocalCopy.Ready).peer)
    }

    @Test fun enteredAddressOverridesReportedOnesAndIsLabelled() {
        var asked = ""
        val resolve: (String) -> List<InetAddress> = { asked = it; listOf(InetAddress.getByName("198.51.100.4")) }
        val plan = ServerToLocalCopy.plan(transfer(), source(announced = "wrong.example"), resolve,
            addressOverride = " peer.seed.example ")
        assertEquals("peer.seed.example", asked)
        plan as ServerToLocalCopy.Ready
        assertEquals("198.51.100.4:51413", plan.peer)
        assertEquals(ServerToLocalCopy.AddressSource.ENTERED, plan.addressSource)
        val bracketed = ServerToLocalCopy.plan(transfer(), source(), ip("2001:db8::9"),
            addressOverride = "[2001:db8::9]") as ServerToLocalCopy.Ready
        assertEquals("[2001:db8:0:0:0:0:0:9]:51413", bracketed.peer)
    }

    @Test fun enteredAddressMustBeABareHostAndUnresolvableOnesInviteCorrection() {
        for (bad in listOf("https://seed.example", "seed.example:51413", "user:pw@seed.example",
                "seed.example/path", "", "bad host")) {
            val refused = ServerToLocalCopy.plan(transfer(), source(), ip("203.0.113.9"), addressOverride = bad)
            assertTrue(bad, refused is ServerToLocalCopy.Refused && refused.addressProblem)
        }
        val unresolved = ServerToLocalCopy.plan(transfer(), source(), { emptyList() })
        assertTrue(unresolved is ServerToLocalCopy.Refused && unresolved.addressProblem)
        // A non-address refusal is not offered the address field.
        val incomplete = ServerToLocalCopy.plan(transfer(done = 1), source(), ip("203.0.113.9"))
        assertTrue(incomplete is ServerToLocalCopy.Refused && !incomplete.addressProblem)
    }
}
