package io.github.gamesbyian.chunks.rpc

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1779 on the device runtime itself: `InputStream.readNBytes(int)` exists on Android
 * only from API 33, so on API 29-32 the remote directory listing, File Manager,
 * capability probe and delete-data acceptance threw NoSuchMethodError. This runs in
 * the API 29 Android 10 workflow (and the API 35 native Add shards) against a loopback
 * ruTorrent `_getdir` fixture; no real server, credentials or network.
 */
@RunWith(AndroidJUnit4::class)
class RemoteBoundedReadsInstrumentedTest {
    @Test fun boundedReadHelperRunsOnThisAndroidRuntime() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(150_001, ByteArrayInputStream(bytes).readAtMost(150_001).size)
        assertEquals(10, ByteArrayInputStream(bytes, 0, 10).readAtMost(16_385).size)
    }

    @Test fun remoteDirectoryListingReadsItsResponseOnThisAndroidRuntime() = runBlocking {
        val body = """{"path":"/srv/alice/","directories":["Movies","TV",".."],"files":["private.bin"]}"""
            .toByteArray(Charsets.UTF_8)
        val listings = AtomicInteger()
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val thread = Thread {
                while (!server.isClosed) try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val requestLine = reader.readLine().orEmpty()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val out = socket.getOutputStream()
                        if (requestLine.startsWith("GET /tenant/rutorrent2/plugins/_getdir/listdir.php")) {
                            listings.incrementAndGet()
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            out.write(body)
                        } else {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            val rpc = "http://127.0.0.1:${server.localPort}/tenant/rutorrent2/plugins/httprpc/action.php"
            val listing = RTorrentRemote(RemoteCredentials(rpc, "alice", "secret")).browseDirectories("")
            assertTrue("Listing must be read on this runtime: $listing", listing is RemoteDirectoryLookup.Ready)
            assertEquals(listOf("Movies", "TV"), (listing as RemoteDirectoryLookup.Ready).page.folders)
            assertEquals(1, listings.get())
            server.close()
            thread.join(2_000)
        }
    }
}
