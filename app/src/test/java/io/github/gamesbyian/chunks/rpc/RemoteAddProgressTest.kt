package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteAddProgressTest {
    private val hash = "ab".repeat(20)
    private fun traffic(size: Long = 1L, verified: Long = 1L, peers: Long = 0L,
        rate: Long = 0L) = RemoteTrafficSample(hash, 1L, 100L, verified, size, true, peers, rate)

    @Test fun completedMetadataPlaceholderNeverBecomesCompletedPayload() {
        val progress = RemoteAddProgress(true, true, true, traffic(rate = 10_000))
        assertNull(progress.payloadSizeBytes)
        assertNull(progress.payloadVerifiedBytes)
        assertEquals("Fetching magnet metadata. Waiting for peers.", progress.summary())
        assertEquals("Fetching magnet metadata from peers.",
            progress.copy(traffic = traffic(peers = 1)).summary())
    }

    @Test fun unknownMetadataAndStartedFlagsCannotProveDownloading() {
        assertEquals("Torrent registered. Metadata status is unavailable on this server.",
            RemoteAddProgress(true, null, true, traffic(rate = 10_000)).summary())
        assertEquals("Metadata resolved. Waiting for peers.",
            RemoteAddProgress(true, false, true, traffic(1_000L, 0L)).summary())
        assertEquals("Stopped. Magnet metadata is still pending.",
            RemoteAddProgress(true, true, false, traffic()).summary())
    }

    @Test fun resolvedPayloadDistinguishesTrafficFromVerifiedCompletion() {
        assertEquals("Downloading payload from peers.",
            RemoteAddProgress(true, false, true, traffic(1_000L, 100L, 1L, 10L)).summary())
        assertEquals("Download complete. All payload pieces verified by rTorrent.",
            RemoteAddProgress(true, false, true, traffic(1_000L, 1_000L)).summary())
    }

    @Test fun realGetterReadbackMasksMetadataBytesAndNeverMutates() = runBlocking {
        val calls = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)!!.groupValues[1]
            calls += method
            val answer = when (method) {
                "d.name" -> "<string>metadata.meta</string>"
                "d.hash" -> "<string>$hash</string>"
                "d.directory" -> "<string>/downloads</string>"
                "d.is_multi_file", "d.peers_connected", "d.down.rate" -> "<i8>0</i8>"
                "d.is_meta", "d.state", "d.is_active", "d.size_bytes", "d.completed_bytes", "d.down.total" -> "<i8>1</i8>"
                else -> error("Unexpected RPC")
            }
            val response = "<methodResponse><params><param><value>$answer</value></param></params></methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val progress = remote.readAddProgress(StoppedRemoteAddPlan.fromMagnet("magnet:?xt=urn:btih:$hash", "/downloads"))
            assertTrue(progress.registered)
            assertEquals(true, progress.metadataPending)
            assertNull(progress.payloadSizeBytes)
            assertNull(progress.payloadVerifiedBytes)
            assertEquals("Fetching magnet metadata. Waiting for peers.", progress.summary())
            assertTrue(calls.all { it in setOf("d.name", "d.hash", "d.directory", "d.is_multi_file",
                "d.peers_connected", "d.down.rate", "d.is_meta", "d.state", "d.is_active", "d.size_bytes",
                "d.completed_bytes", "d.down.total") })
        } finally { server.stop(0) }
    }

    @Test fun freshInventoryUsesMetadataFlagInsteadOfCompletedPlaceholderSize() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val commands = mutableListOf<String>()
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)!!.groupValues[1]
            commands += method
            check(method == "d.multicall2")
            fun str(v: String) = "<value><string>$v</string></value>"
            fun num(v: Long) = "<value><i8>$v</i8></value>"
            fun array(v: String) = "<value><array><data>$v</data></array></value>"
            val row = if (xml.contains("<string>d.is_meta=</string>")) str(hash) + num(1)
                else str(hash) + str("$hash.meta") + num(1) + num(1) + num(1) + str("/downloads") + num(0) + num(0) + num(0)
            val response = "<methodResponse><params><param>${array(array(row))}</param></params></methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val placeholder = remote.listTransfersWithMetadata().single()
            assertEquals(true, placeholder.metadataPending)
            assertEquals(0f, placeholder.fractionComplete)
            val common = io.github.gamesbyian.chunks.model.UnifiedTransferObservations.fromRTorrent(
                io.github.gamesbyian.chunks.model.EnvironmentId("test-server"), placeholder)
            assertNull(common.sizeBytes)
            assertNull(common.progressPpm)
            assertEquals(true, common.metadataPending)
            assertEquals(listOf("d.multicall2", "d.multicall2"), commands)
        } finally { server.stop(0) }
    }
}
