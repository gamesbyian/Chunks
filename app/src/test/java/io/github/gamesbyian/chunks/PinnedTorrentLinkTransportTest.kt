package io.github.gamesbyian.chunks

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual production route selector and TCP guard without WAN traffic. */
class PinnedTorrentLinkTransportTest {
    private val publicIp = InetAddress.getByName("207.241.224.2")
    private val otherPublic = InetAddress.getByName("104.21.33.39")
    private val privateIp = InetAddress.getByName("127.0.0.1")
    private val uri = URI("https://torrent.example:8443/get?token=privateFixture")

    @Test fun actualOkHttpConnectionUsesTheSingleValidatedDnsSnapshot() {
        val attempted = mutableListOf<InetSocketAddress>()
        var resolutions = 0
        val transport = PinnedTorrentLinkTransport(proxies = { listOf(Proxy.NO_PROXY) },
            beforeConnect = {
                attempted += it
                // Stop BEFORE the actual TCP connect; no public WAN traffic.
                throw IOException("offline test witness")
            })
        val fetcher = TorrentLinkFetcher(resolve = {
            resolutions++
            if (resolutions == 1) listOf(publicIp) else listOf(privateIp)
        }, transport = transport)
        val result = fetcher.fetch(TorrentLinkCheck.inspect(uri.toString()) as TorrentLinkCheck.Fetchable)
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.OFFLINE), result)
        assertEquals("No second DNS lookup can replace the validated answer", 1, resolutions)
        assertEquals(listOf(InetSocketAddress(publicIp, 8443)), attempted)
    }

    @Test fun correctFrozenPortReachesGuardedConnectWhileSocketPortIsStillZero() {
        val attempted = mutableListOf<InetSocketAddress>()
        val endpoint = InetSocketAddress(publicIp, 8443)
        PinnedTorrentSocketFactory(setOf(publicIp), endpoint.port) {
            attempted += it
            throw IOException("offline test witness")
        }.createSocket().use { socket ->
            assertEquals(0, socket.port)
            val error = assertThrows(IOException::class.java) { socket.connect(endpoint, 500) }
            assertEquals("offline test witness", error.message)
            assertFalse(socket.isConnected)
        }
        assertEquals(listOf(endpoint), attempted)
    }

    @Test fun socketRejectsUnresolvedUnpinnedWrongPortAndPrivateEndpointsBeforeConnect() {
        val attempted = mutableListOf<InetSocketAddress>()
        ServerSocket(0, 1, privateIp).use { canary ->
            canary.soTimeout = 100
            val factory = PinnedTorrentSocketFactory(setOf(publicIp, privateIp), canary.localPort) {
                attempted += it
                throw IOException("offline test witness")
            }
            val refused = listOf(
                InetSocketAddress.createUnresolved("torrent.example", canary.localPort),
                InetSocketAddress(otherPublic, canary.localPort),
                InetSocketAddress(publicIp, if (canary.localPort == 443) 444 else 443),
                InetSocketAddress(privateIp, canary.localPort)
            )
            refused.forEach { endpoint ->
                factory.createSocket().use { socket ->
                    val error = assertThrows(TorrentLinkTransportRefusal::class.java) {
                        socket.connect(endpoint, 500)
                    }
                    assertEquals(TorrentLinkResult.LOCAL_NETWORK, error.reason)
                    assertFalse(socket.isConnected)
                }
            }
            assertTrue(attempted.isEmpty())
            assertThrows(SocketTimeoutException::class.java) { canary.accept().close() }
        }
    }

    @Test fun configuredProxyAndMixedDnsAreRefusedBeforeAnyConnection() {
        var connects = 0
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(privateIp, 8888))
        val transport = PinnedTorrentLinkTransport(proxies = { listOf(proxy, Proxy.NO_PROXY) },
            beforeConnect = { connects++ })
        val link = TorrentLinkCheck.inspect(uri.toString()) as TorrentLinkCheck.Fetchable
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.PROXY),
            TorrentLinkFetcher(resolve = { listOf(publicIp) }, transport = transport).fetch(link))
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.LOCAL_NETWORK),
            TorrentLinkFetcher(resolve = { listOf(publicIp, privateIp) }, transport = transport).fetch(link))
        assertEquals(0, connects)
    }

    @Test fun signedPathNormalizationIsRefusedBeforeTcp() {
        val transport = PinnedTorrentLinkTransport(proxies = { listOf(Proxy.NO_PROXY) },
            beforeConnect = { fail("No socket before exact path admission") })
        val changed = URI("https://torrent.example/signed/../get?token=privateFixture")
        val error = assertThrows(TorrentLinkTransportRefusal::class.java) {
            transport.get(changed, listOf(publicIp), 500).close()
        }
        assertEquals(TorrentLinkResult.UNEXPECTED, error.reason)
        assertFalse(error.message.orEmpty().contains("privateFixture"))
    }
}
