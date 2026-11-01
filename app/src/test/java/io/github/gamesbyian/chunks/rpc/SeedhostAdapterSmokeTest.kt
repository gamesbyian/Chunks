package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
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
        assumeTrue("Live acceptance requires the trusted-main secret environment", rawUrl.isNotBlank())
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
        val count = verifySeedhostReadOnlyAcceptance(remote)
        println("CHUNKS_KOTLIN_SEEDHOST_READONLY: authenticated version and main-view list passed; items=$count")
    }
}
