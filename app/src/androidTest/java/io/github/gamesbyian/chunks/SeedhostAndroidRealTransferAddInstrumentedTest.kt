package io.github.gamesbyian.chunks

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed production Compose Add UI -> actual owner development rTorrent ->
 * actual Internet peers and piece-verified bytes. Neither an active flag,
 * mocked RPC, absent magnet metadata nor a prepared local payload can pass.
 *
 * Protected trusted-main manual workflow only. A red peer test may be a
 * tracker/network problem, but is never counted as a successful APK.
 * This mounts the production VerifiedAddScreen directly; the separate installed
 * MainActivity/Connect journey is tested by SeedhostAndroidAddUiInstrumentedTest.
 */
@RunWith(AndroidJUnit4::class)
class SeedhostAndroidRealTransferAddInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun argument(key: String) =
        InstrumentationRegistry.getArguments().getString(key)
    private fun secret(key: String) = String(
        Base64.decode(requireNotNull(argument(key)), Base64.NO_WRAP), Charsets.UTF_8)

    private fun report(stage: String) {
        require(stage.matches(Regex("(file|magnet|magnet2)-(preflight|review|ui-verified|metadata|peer-progress|bytes-verified|restarted|passed|uncertain)")))
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2, Bundle().apply { putString("chunks_real_add_stage", stage) })
    }

    private fun sourceBytes(file: Boolean, alternative: Boolean = false): ByteArray {
        // Real upstream Ubuntu v1 metainfo; different infohashes so the
        // second Add cannot inherit the first torrent's registered identity.
        val filename = if (alternative) "ubuntu-25.10-desktop-amd64.iso.torrent"
                       else if (file) "ubuntu-26.04.1-live-server-amd64.iso.torrent"
                       else "ubuntu-25.10-live-server-amd64.iso.torrent"
        val address = URI("https://releases.ubuntu.com/${if (file) "26.04" else "25.10"}/$filename")
        require(address.scheme == "https" && address.host == "releases.ubuntu.com")
        val connection = URL(address.toASCIIString()).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) {
                "Official metainfo unavailable; release gate inconclusive"
            }
            val maximum = 1_048_576
            val bytes = connection.inputStream.use { it.readNBytes(maximum + 1) }
            require(bytes.isNotEmpty() && bytes.size <= maximum) {
                "Official torrent metainfo is empty or too large"
            }
            TorrentMetainfoReader.preview(bytes) // Real v1 metainfo required.
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun announce(bytes: ByteArray): String {
        // Extract the top-level bencoded 'announce' byte string. This trusted
        // official source supplies peers for metadata-only magnet load; never
        // submit torrent bytes through the magnet route.
        val marker = "8:announce".toByteArray(Charsets.US_ASCII)
        val start = bytes.indices.firstOrNull { i ->
            i + marker.size < bytes.size && marker.indices.all { j -> bytes[i + j] == marker[j] }
        } ?: error("Official torrent has no announce tracker")
        var offset = start + marker.size
        var count = 0
        while (offset < bytes.size && bytes[offset] in '0'.code.toByte()..'9'.code.toByte()) {
            count = count * 10 + (bytes[offset].toInt() - '0'.code)
            offset++
            require(count <= 4096)
        }
        require(offset < bytes.size && bytes[offset] == ':'.code.toByte())
        offset++
        require(count > 0 && offset + count <= bytes.size)
        val tracker = String(bytes, offset, count, Charsets.UTF_8)
        require(URI(tracker).scheme in setOf("http", "https"))
        return tracker
    }

    private fun remote(): RTorrentRemote {
        assumeTrue("Only explicitly armed trusted-main Android real-transfer acceptance",
            argument("chunks_real_add") == "authorized")
        val url = secret("chunks_live_url_b64")
        val endpoint = URI(url)
        require(endpoint.scheme == "https" &&
            endpoint.host == "ianseeds.wood.seedhost.eu" &&
            endpoint.port == -1 && endpoint.path == "/rutorrent/" &&
            endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null)
        return RTorrentRemote(RemoteCredentials(url, secret("chunks_live_user_b64"),
            secret("chunks_live_pass_b64")))
    }

    private suspend fun reviewedParent(remote: RTorrentRemote, original: List<RemoteTransfer>): String {
        remote.defaultDirectory()?.let { return it }
        // Same independent existing-single-file parent corroboration as the
        // original protected installed Add test. Do not guess a server path.
        val choices = original.filter { it.sizeBytes > 0 && it.completedBytes == it.sizeBytes }
            .take(48).mapNotNull { transfer ->
                val fresh = runCatching {
                    remote.call("d.directory", listOf(transfer.hash)) as? String
                }.getOrNull()
                val multiple = runCatching {
                    (remote.call("d.is_multi_file", listOf(transfer.hash)) as? Number)?.toInt()
                }.getOrNull()
                transfer.directory.takeIf {
                    it.startsWith("/") && it.count { c -> c == '/' } >= 2 &&
                        it == fresh && multiple == 0
                }
            }
        return requireNotNull(choices.groupingBy { it }.eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }
                .thenByDescending { it.key })?.key) {
            "No independently corroborated existing single-file download parent"
        }
    }

    private fun visible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun scroll(text: String) =
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))

    private fun journey(file: Boolean, alternative: Boolean = false) {
        val stage = if (file) "file" else if (alternative) "magnet2" else "magnet"
        val remote = remote()
        val metainfo = sourceBytes(file, alternative)
        val preview = TorrentMetainfoReader.preview(metainfo)
        val hash = preview.infoHashV1
        val magnet = if (file) "" else
            "magnet:?xt=urn:btih:$hash&tr=" +
                URLEncoder.encode(announce(metainfo), "UTF-8")
        val before = runBlocking { remote.listTransfers("main") }
        val identities = before.map { it.hash.lowercase() }.toSet()
        val parent = runBlocking { reviewedParent(remote, before) }
        require(hash.lowercase() !in identities &&
            runBlocking { remote.lookupName(hash) } == null) {
            "Real upstream fixture already registered; refusing duplicate"
        }

        report("$stage-preflight")
        var magnetField by mutableStateOf(magnet)
        var folderField by mutableStateOf(parent)
        var preparedFile by mutableStateOf(if (file) metainfo else null)
        var submitted = false
        var success = false
        try {
            rule.setContent {
                MaterialTheme {
                    VerifiedAddScreen(
                        remote = remote, magnet = magnetField, destination = folderField,
                        onMagnet = { magnetField = it },
                        onDestination = { folderField = it },
                        onBack = {}, onComplete = {},
                        onMutationStarted = { true }, onMutationSettled = {},
                        initialTorrent = preparedFile,
                        onInitialTorrentConsumed = { preparedFile = null },
                        finishInOrderScope = null, environmentLabel = "Approved development Seedhost"
                    )
                }
            }
            if (file) {
                rule.waitUntil(30_000) {
                    runCatching { scroll("Infohash: $hash") }
                    visible("Infohash: $hash")
                }
            }
            rule.waitUntil(30_000) {
                runCatching { scroll("Custom server folder (optional)") }
                visible("Using custom Place.")
            }
            scroll("Review Add")
            rule.onNodeWithText("Review Add").performClick()
            scroll("Confirm: add then start")
            assertTrue("Review must show exact public fixture hash", visible("Infohash: $hash"))
            report("$stage-review")
            submitted = true
            rule.onNodeWithText("Confirm: add then start").performClick()
            rule.waitUntil(120_000) {
                runCatching { scroll("Operation outcome") }
                visible("Operation outcome")
            }
            scroll("Operation outcome")
            assertTrue("Installed app did not positively verify Add+Start",
                visible("Verified: added and started on rTorrent."))
            report("$stage-ui-verified")

            // Independent RPC readback, NOT a UI state echo. A magnet must
            // resolve its metadata; both sources must receive and verify pieces.
            var observedProgress = false
            var metadata = false
            var peers = false
            val deadline = SystemClock.elapsedRealtime() + 180_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val result = runCatching { runBlocking {
                    val transfer = remote.listTransfers("main")
                        .singleOrNull { it.hash.equals(hash, true) }
                    val traffic = transfer?.let { remote.readTrafficSample(hash) }
                    Pair(transfer, traffic)
                } }.getOrNull()
                val transfer = result?.first
                val traffic = result?.second
                if (transfer?.started == true && transfer.directory == parent &&
                    transfer.name == preview.name && transfer.sizeBytes == preview.sizeBytes &&
                    traffic != null && traffic.hash.equals(hash, true) &&
                    traffic.expectedBytes == preview.sizeBytes) {
                    if (!metadata) { metadata = true; report("$stage-metadata") }
                    if ((traffic.peers ?: 0L) > 0L && (traffic.receivedBytes ?: 0L) > 0L) {
                        if (!peers) { peers = true; report("$stage-peer-progress") }
                    }
                    if ((traffic.receivedBytes ?: 0L) > 0L &&
                        (traffic.verifiedBytes ?: 0L) > 0L && peers) {
                        observedProgress = true
                        break
                    }
                }
                Thread.sleep(2_000L)
            }
            assertTrue("No real metadata + exact-hash received AND verified peer-byte progress",
                observedProgress)
            report("$stage-bytes-verified")

            // A Chunks-created transfer must also leave the stopped state and
            // restart normally. Never retry a failed or ambiguous write.
            val stop = runBlocking { remote.setStarted(hash, false) }
            assertFalse("Explicit Stop was uncertain; do not replay",
                stop is LifecycleOutcome.Uncertain)
            val stopDeadline = SystemClock.elapsedRealtime() + 25_000L
            var observedStopped = false
            while (SystemClock.elapsedRealtime() < stopDeadline) {
                observedStopped = runCatching { runBlocking {
                    val exact = remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
                    exact?.started == false && remote.readActivity(hash).active == false
                } }.getOrDefault(false)
                if (observedStopped) break
                Thread.sleep(500)
            }
            assertTrue("Actual rTorrent job did not become stopped", observedStopped)
            val start = runBlocking { remote.setStarted(hash, true) }
            assertFalse("Explicit Start was uncertain; do not replay",
                start is LifecycleOutcome.Uncertain)
            val startDeadline = SystemClock.elapsedRealtime() + 30_000L
            var observedRestarted = false
            while (SystemClock.elapsedRealtime() < startDeadline) {
                observedRestarted = runCatching { runBlocking {
                    val exact = remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
                    exact?.started == true && remote.readActivity(hash).active == true
                } }.getOrDefault(false)
                if (observedRestarted) break
                Thread.sleep(500)
            }
            assertTrue("Actual Chunks-created rTorrent job could not restart", observedRestarted)
            report("$stage-restarted")
            success = true
        } finally {
            // Exact new hash/name/place only; session removal preserves data.
            // Never replay uncertain Add/Stop/Erase. An incomplete cleanup
            // MUST fail the test and leave the run ID for manual reconciliation.
            if (submitted) {
                val item = runBlocking {
                    remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
                }
                if (item != null) {
                    assertEquals("Refuse cleanup on unexpected torrent identity", preview.name, item.name)
                    assertEquals("Refuse cleanup on unexpected server Place", parent, item.directory)
                    if (item.started) {
                        val stopped = runBlocking { remote.setStarted(hash, false) }
                        assertFalse("Stop uncertain: no automatic retry",
                            stopped is LifecycleOutcome.Uncertain)
                    }
                    val cleanupDeadline = SystemClock.elapsedRealtime() + 25_000L
                    var safeToErase = false
                    while (SystemClock.elapsedRealtime() < cleanupDeadline) {
                        safeToErase = runCatching { runBlocking {
                            val stopped = remote.listTransfers("main").singleOrNull {
                                it.hash.equals(hash, true)
                            }
                            stopped != null && !stopped.started &&
                                stopped.name == preview.name && stopped.directory == parent &&
                                remote.readActivity(hash).active == false
                        } }.getOrDefault(false)
                        if (safeToErase) break
                        Thread.sleep(500)
                    }
                    check(safeToErase) { "Exact stopped/inactive session not proven" }
                    val erased = runBlocking {
                        remote.eraseFromSession(hash, preview.name, parent)
                    }
                    assertTrue("Exact session removal not verified: reconcile manually",
                        erased is SessionEraseOutcome.VerifiedRemoved)
                } else {
                    // A lost Add acknowledgment can mean never registered OR
                    // disappeared from main; cannot prove session absent.
                    check(!submitted || success) { "Missing post-Add hash: reconcile manually" }
                }
            }
            if (success) {
                val after = runBlocking { remote.listTransfers("main") }
                assertEquals("Unrelated inventory changed", identities,
                    after.map { it.hash.lowercase() }.toSet())
                report("$stage-passed")
            } else report("$stage-uncertain")
        }
    }

    @Test fun installedTorrentFileAddReceivesVerifiedPeerBytes() = journey(true)
    @Test fun installedMagnetAddResolvesMetadataAndReceivesVerifiedPeerBytes() = journey(false)
    @Test fun installedAlternateMagnetResolvesMetadataAndReceivesVerifiedPeerBytes() =
        journey(false, alternative = true)
}
