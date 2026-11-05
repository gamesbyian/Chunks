package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.artwork.UserImageUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/** DNS-rebinding defence: the actually connected peer must also be public for user image links. */
class PeerCheckingSocketTest {
    private val policy = UserImageUrlPolicy.hostPolicy { emptyList() }

    @Test fun layeredSocketToLoopbackPeerIsRefusedBeforeTls() {
        ServerSocket(0).use { server ->
            val plain = Socket("127.0.0.1", server.localPort)
            val f = PeerCheckingSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory, policy)
            val e = assertThrows(MediaHttpException::class.java) { f.createSocket(plain, "images.example.com", 443, true) }
            assertEquals(MediaHttpFailure.BLOCKED_HOST, e.failure)
            assertEquals(true, plain.isClosed)
        }
    }

    @Test fun unconnectedSocketConnectToPrivateAddressIsRefused() {
        val f = PeerCheckingSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory, policy)
        val s = f.createSocket()
        listOf("127.0.0.1", "192.168.1.1", "10.0.0.1", "169.254.169.254").forEach { ip ->
            val e = assertThrows(MediaHttpException::class.java) { s.connect(InetSocketAddress(ip, 443), 1_000) }
            assertEquals(MediaHttpFailure.BLOCKED_HOST, e.failure)
        }
        s.close()
    }

    @Test fun ordinaryProviderPoliciesDoNotRestrictPeers() {
        assertEquals(false, mediaHostPolicy(listOf("api.tvmaze.com")).restrictsPeers)
        assertEquals(true, policy.restrictsPeers)
    }
}
