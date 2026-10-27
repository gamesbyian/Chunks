package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Read-only reconciliation of a ONE-TIME, exact-name development synthetic
 * fixture following a failed hosted acceptance run. Never adds/stops/erases.
 * No credentials, endpoint, traceback or foreign torrent names in output.
 */
class SeedhostReadOnlyReconcileTest {
    @Test fun inspectFailedAcceptanceFixtureWithoutMutation() = runBlocking {
        val runId = System.getenv("CHUNKS_RECONCILE_RUN_ID").orEmpty()
        assumeTrue("Only protected, explicitly scoped acceptance workflow", runId.isNotBlank())
        require(runId.matches(Regex("[0-9]{6,20}"))) { "Invalid scoped run identity" }
        var stage = "validate protected host"
        try {
            val rawUrl = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
            val uri = URI(rawUrl)
            require(uri.scheme.equals("https", true) &&
                uri.host?.endsWith(".seedhost.eu", true) == true &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.orEmpty().trimEnd('/').endsWith("/rutorrent")) {
                "Protected Seedhost only"
            }
            val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
            val password = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
            require(user.isNotEmpty() && password.isNotEmpty()) { "Protected credentials unavailable" }

            val name = "chunks-acceptance-$runId.txt"
            val payload = "Chunks acceptance payload\n".toByteArray()
            val digest = MessageDigest.getInstance("SHA-1").digest(payload)
            val announce = "http://127.0.0.1:1/announce"
            val prefix = "d8:announce${announce.length}:${announce}4:infod6:lengthi${payload.size}e4:name${name.length}:${name}12:piece lengthi16384e6:pieces20:"
            val metainfo = prefix.toByteArray(Charsets.ISO_8859_1) + digest + "ee".toByteArray()
            val hash = TorrentMetainfoReader.preview(metainfo).infoHashV1
            stage = "create protected read-only client"
            val remote = RTorrentRemote(RemoteCredentials(rawUrl, user, password))
            stage = "read main transfer view"
            val matches = remote.listTransfers("main").filter {
                it.hash.equals(hash, true) || it.name == name
            }
            assertTrue("Synthetic fixture must not have conflicting identities",
                matches.all { it.hash.equals(hash, true) && it.name == name } && matches.size <= 1)
            stage = "lookup exact synthetic hash"
            val sessionName = remote.lookupName(hash)
            assertTrue("Exact hash lookup inconsistent with fixture identity",
                sessionName == null || sessionName == name)
            val activity = if (matches.isNotEmpty()) {
                stage = "read exact synthetic activity"
                remote.readActivity(hash)
            } else null
            println(
                "CHUNKS_RECONCILE: phase=complete source_run=$runId hash=$hash " +
                "main_matches=${matches.size} exact_lookup=${sessionName != null} " +
                "state_set=${matches.firstOrNull()?.started} " +
                "engine_active=${activity?.active} " +
                "no_mutations=true"
            )
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            println("CHUNKS_RECONCILE: phase=$stage result=failed no_mutations=true")
            throw AssertionError("Read-only Seedhost reconciliation failed in stage: $stage")
        }
    }
}
