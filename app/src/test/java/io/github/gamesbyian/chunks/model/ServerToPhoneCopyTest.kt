package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemotePeerSource
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerToPhoneCopyTest {
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
        val plan = ServerToPhoneCopy.plan(transfer(), source(), ip("2001:db8::7", "203.0.113.9"))
        plan as ServerToPhoneCopy.Ready
        assertEquals("magnet:?xt=urn:btih:$hash&dn=Big%20Buck%20Bunny&x.pe=203.0.113.9:51413", plan.magnet)
        assertEquals("203.0.113.9:51413", plan.peer)
        assertEquals(1_000L, plan.bytes)
    }

    @Test fun ipv6OnlyServersAreBracketedAndTheAnnouncedAddressWins() {
        var asked = ""
        val plan = ServerToPhoneCopy.plan(transfer(), source(announced = "peer.example")) {
            asked = it; listOf(InetAddress.getByName("2001:db8::7"))
        }
        assertEquals("peer.example", asked)
        assertEquals("[2001:db8:0:0:0:0:0:7]:51413", (plan as ServerToPhoneCopy.Ready).peer)
    }

    @Test fun aSpecificBindAddressBeatsTheWebHostButNotTheAnnouncedAddress() {
        var asked = ""
        val resolve: (String) -> List<InetAddress> = { asked = it; listOf(InetAddress.getByName(it)) }
        val bound = ServerToPhoneCopy.plan(transfer(), source(host = "198.51.100.10", bound = "198.51.100.19"), resolve)
        assertEquals("198.51.100.19:51413", (bound as ServerToPhoneCopy.Ready).peer)
        ServerToPhoneCopy.plan(transfer(), source(announced = "198.51.100.20", bound = "198.51.100.19"), resolve)
        assertEquals("198.51.100.20", asked)
    }

    @Test fun refusesWhatTheServerCannotSend() {
        fun refused(plan: ServerToPhoneCopy.Plan) = (plan as ServerToPhoneCopy.Refused).reason
        assertTrue(refused(ServerToPhoneCopy.plan(transfer(done = 500), source(), ip("203.0.113.9"))).contains("50%"))
        assertTrue(refused(ServerToPhoneCopy.plan(transfer(done = 0, size = 0), source(), ip("203.0.113.9"))).isNotBlank())
        assertTrue(refused(ServerToPhoneCopy.plan(transfer(), source(private = true), ip("203.0.113.9"))).contains("private"))
        assertTrue(refused(ServerToPhoneCopy.plan(transfer(), source(active = false), ip("203.0.113.9"))).contains("Start it"))
        assertTrue(refused(ServerToPhoneCopy.plan(transfer(), source(port = 0), ip("203.0.113.9"))).contains("port"))
        assertTrue(refused(ServerToPhoneCopy.plan(transfer(), source(), { error("dns") })).contains("seed.example"))
        val bad = RemoteTransfer("not-a-hash", "x", 1, 1, true, "/x")
        assertTrue(refused(ServerToPhoneCopy.plan(bad, source(), ip("203.0.113.9"))).contains("v1"))
    }

    @Test fun loopbackIsOnlyAcceptedForTheLoopbackRig() {
        val remoteSeedbox = ServerToPhoneCopy.plan(transfer(), source(), ip("127.0.0.1", "0.0.0.0"))
        assertTrue(remoteSeedbox is ServerToPhoneCopy.Refused)
        val rig = ServerToPhoneCopy.plan(transfer(), source(host = "127.0.0.1", port = 6890), ip("127.0.0.1"))
        assertEquals("127.0.0.1:6890", (rig as ServerToPhoneCopy.Ready).peer)
    }

    @Test fun enteredAddressOverridesReportedOnesAndIsLabelled() {
        var asked = ""
        val resolve: (String) -> List<InetAddress> = { asked = it; listOf(InetAddress.getByName("198.51.100.4")) }
        val plan = ServerToPhoneCopy.plan(transfer(), source(announced = "wrong.example"), resolve,
            addressOverride = " peer.seed.example ")
        assertEquals("peer.seed.example", asked)
        plan as ServerToPhoneCopy.Ready
        assertEquals("198.51.100.4:51413", plan.peer)
        assertEquals(ServerToPhoneCopy.AddressSource.ENTERED, plan.addressSource)
        val bracketed = ServerToPhoneCopy.plan(transfer(), source(), ip("2001:db8::9"),
            addressOverride = "[2001:db8::9]") as ServerToPhoneCopy.Ready
        assertEquals("[2001:db8:0:0:0:0:0:9]:51413", bracketed.peer)
    }

    @Test fun enteredAddressMustBeABareHostAndUnresolvableOnesInviteCorrection() {
        for (bad in listOf("https://seed.example", "seed.example:51413", "user:pw@seed.example",
                "seed.example/path", "", "bad host")) {
            val refused = ServerToPhoneCopy.plan(transfer(), source(), ip("203.0.113.9"), addressOverride = bad)
            assertTrue(bad, refused is ServerToPhoneCopy.Refused && refused.addressProblem)
        }
        val unresolved = ServerToPhoneCopy.plan(transfer(), source(), { emptyList() })
        assertTrue(unresolved is ServerToPhoneCopy.Refused && unresolved.addressProblem)
        // A non-address refusal is not offered the address field.
        val incomplete = ServerToPhoneCopy.plan(transfer(done = 1), source(), ip("203.0.113.9"))
        assertTrue(incomplete is ServerToPhoneCopy.Refused && !incomplete.addressProblem)
    }
}
