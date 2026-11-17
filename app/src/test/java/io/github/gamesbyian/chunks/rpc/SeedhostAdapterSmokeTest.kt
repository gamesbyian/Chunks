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
    /**
     * Trusted-main, strictly read-only real Seedhost compatibility witness.
     * No tracker URL, torrent name, infohash, counter value or throttle name
     * is emitted. A public/unverified account read cannot authorize writes;
     * the existing challenge-authenticated inventory gate runs separately.
     */
    @Test fun realPrimarySeedhostReportsExactScopedReadOnlySeedingEvidence() = runBlocking {
        val rawUrl = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        assumeTrue("Needs trusted-main live account secrets", rawUrl.isNotBlank())
        val url = URI(rawUrl)
        require(url.scheme.equals("https", true) &&
            url.host.equals("ianseeds.wood.seedhost.eu", true) &&
            url.userInfo == null && url.query == null && url.fragment == null &&
            (url.port == -1 || url.port == 443) &&
            url.path == "/rutorrent/") {
            "Real seeding evidence is limited to the exact approved Seedhost account"
        }
        val username = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val password = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        require(username.isNotBlank() && password.isNotEmpty()) { "Missing trusted credentials" }
        val remote = RTorrentRemote(RemoteCredentials(rawUrl, username, password))
        val subject = remote.listTransfers("main")
            .firstOrNull { it.hash.matches(Regex("[0-9a-fA-F]{40}")) }
        assumeTrue("A v1 torrent is required for live seeding getter acceptance", subject != null)
        val exact = requireNotNull(subject).hash
        val observed = try {
            remote.readSeedingEvidence(exact)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            // Do not print raw gateway exception text: it could include
            // account, hash, source paths, or third-party RPC diagnostics.
            val category = when (failure) {
                is RpcHttpStatusException -> "http"
                is RpcFault -> "rpc-fault"
                is java.net.SocketTimeoutException -> "timeout"
                is IllegalArgumentException -> "invalid-value"
                is IllegalStateException -> "wrong-identity"
                else -> "unclassified"
            }
            throw AssertionError("CHUNKS_SEEDING_READONLY: failed category=$category")
        }
        check(observed.accountScope == remote.addAccountScope()) {
            "Read-only evidence was attributed to a different configured principal"
        }
        check(observed.infoHashV1.equals(exact, ignoreCase = true)) {
            "Read-only evidence was attributed to a different transfer"
        }
        check(observed.uploadedBytes != null && observed.downloadedBytes != null) {
            "Real rTorrent lacks required cumulative byte readback for automated ratio decisions"
        }
        // Optional flags may be unsupported/unknown. A completed timestamp is
        // NOT cumulative active seeding time; a throttle name is NOT proof
        // of an independent per-job rate cap.
        println("CHUNKS_KOTLIN_SEEDHOST_SEEDING_READONLY: " +
            "scope=true bytes=true complete=${observed.complete != null} " +
            "active=${observed.active != null} " +
            "finished_timestamp=${observed.finishedAtEpochSeconds != null} " +
            "assigned_throttle=${observed.throttleName != null} " +
            "mutation=false")
    }

}
