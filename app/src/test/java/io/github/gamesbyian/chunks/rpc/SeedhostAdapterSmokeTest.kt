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

        // A prior non-idempotent Add that returned Pending/Uncertain cannot be
        // replayed just because its session did not appear in the main view.
        // A separately dispatched READ-ONLY exact hash probe checks d.name,
        // optional main-view row and selected directory before any later
        // fixture attempt. Never print foreign torrent names or server paths.
        val auditRun = System.getenv("CHUNKS_P0_AUDIT_RUN_ID").orEmpty()
        val auditHash = System.getenv("CHUNKS_P0_AUDIT_HASH").orEmpty()
        if (auditRun.isNotEmpty() || auditHash.isNotEmpty()) {
            require(auditRun.matches(Regex("[0-9]{7,20}")) &&
                auditHash.matches(Regex("[0-9a-fA-F]{40}"))) {
                "Exact read-only cohort audit needs a valid run ID and hash"
            }
            val instance = when (url.path) {
                "/rutorrent2/" -> "2"
                "/rutorrent3/" -> "3"
                else -> error("Exact cohort audit only applies to numbered instances")
            }
            val expectedName = "Chunks-P0-shared-$auditRun.bin"
            val listed = remote.listTransfers("main")
                .firstOrNull { it.hash.equals(auditHash, ignoreCase = true) }
            val exactName = remote.lookupName(auditHash)
            require(exactName == null || exactName == expectedName) {
                "Exact hash was present with an unexpected transfer name"
            }
            val rootCorrect = if (exactName != null) runCatching {
                (remote.call("d.directory", listOf(auditHash)) as? String)
                    ?.trimEnd('/') == "/home20/ianseeds/downloads"
            }.getOrDefault(false) else null
            println("CHUNKS_P0_EXACT_READONLY: instance=$instance run=$auditRun " +
                "hash=${auditHash.lowercase(java.util.Locale.ROOT)} " +
                "main=${listed != null} exactLookup=${exactName != null} " +
                "nameMatch=${exactName == expectedName} started=${listed?.started} " +
                "reviewedParent=$rootCorrect mutation=false")
        }
    }
}
