package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Run ONLY by the manually dispatched trusted-main workflow, with secret env
 * variables. Normal public pull-request Gradle runs skip this integration test.
 *
 * Read-only: version and d.multicall2(main); no torrent Add or mutation.
 */
class SeedhostAdapterSmokeTest {
    @Test fun actualKotlinAdapterListsOwnerApprovedDevSeedbox() = runBlocking {
        val rawUrl = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        if (rawUrl.isBlank()) return@runBlocking
        val url = URI(rawUrl)
        require(url.scheme.equals("https", true) &&
            url.host?.endsWith(".seedhost.eu", true) == true &&
            url.userInfo == null && url.query == null && url.fragment == null &&
            (url.port == -1 || url.port == 443)) {
            "Only credential-free, HTTPS Seedhost URL is accepted in this integration test"
        }
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val password = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        require(user.isNotBlank() && password.isNotEmpty()) {
            "Expected trusted workflow secret environment"
        }
        val remote = RTorrentRemote(RemoteCredentials(rawUrl, user, password))
        val version = remote.version()
        assertTrue(version.isNotBlank() && version.length <= 120)
        val rows = remote.listTransfers("main")
        assertTrue("Main view unexpected size", rows.size <= 12000)
        val hashes = rows.map { it.hash.lowercase() }
        assertEquals("Main view has duplicate hash identities", hashes.size, hashes.distinct().size)
        for (row in rows) {
            assertTrue("Invalid rTorrent transfer identity",
                Regex("(?i)[a-f0-9]{40}([a-f0-9]{24})?").matches(row.hash))
            assertTrue("Invalid announced torrent size", row.sizeBytes >= 0)
            assertTrue("Invalid completed bytes", row.completedBytes >= 0)
            assertTrue("Missing remote torrent name", row.name.isNotBlank())
        }
        println("CHUNKS_KOTLIN_SEEDHOST_READONLY: authenticated version and main-view list passed; items=" + rows.size)
    }
}
