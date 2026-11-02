package io.github.gamesbyian.chunks

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.key
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.rpc.RpcFault
import io.github.gamesbyian.chunks.rpc.isUnknownInfoHash
import java.security.MessageDigest
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
        require(stage.matches(Regex("(file|magnet|magnet2)-(preflight|review|ui-verified|registered|readback-error|metadata|peer-progress|bytes-verified|ui-progress|stopped|restarted|resumed-traffic|ui-controls|passed|uncertain)")))
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2, Bundle().apply { putString("chunks_real_add_stage", stage) })
    }

    private fun reportExactPublicHash(label: String, hash: String) {
        require(label in setOf("file", "magnet", "magnet2"))
        val exact = hash.lowercase(java.util.Locale.ROOT)
        require(exact.matches(Regex("[a-f0-9]{40}")))
        // This is strictly the official, reusable public-test fixture hash.
        // Never emit URL/tracker, host name, credentials or server-side paths.
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2, Bundle().apply { putString("chunks_real_add_hash_$label", exact) })
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
        if (argument("chunks_disposable") == "authorized") {
            require(argument("chunks_controlled_peer") == "authorized" &&
                url == "http://127.0.0.1:18080/RPC2")
        } else require(endpoint.scheme == "https" &&
            endpoint.host == "ianseeds.wood.seedhost.eu" &&
            endpoint.port == -1 && endpoint.path == "/rutorrent/" &&
            endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null)
        val disposable = argument("chunks_disposable") == "authorized"
        return RTorrentRemote(RemoteCredentials(url,
            if (disposable) "" else secret("chunks_live_user_b64"),
            if (disposable) "" else secret("chunks_live_pass_b64")))
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
        reportExactPublicHash(stage, hash)
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
            var registered = false
            var readbackError = false
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
                if (result == null && !readbackError) {
                    readbackError = true
                    report("$stage-readback-error")
                }
                val transfer = result?.first
                val traffic = result?.second
                if (transfer != null && !registered) {
                    registered = true
                    report("$stage-registered")
                }
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

    private fun reportTraffic(label: String, phase: String, traffic: io.github.gamesbyian.chunks.rpc.RemoteTrafficSample) {
        require(label in setOf("magnet", "magnet2") && phase in setOf("initial", "resumed"))
        val values = listOf(traffic.receivedBytes, traffic.verifiedBytes, traffic.expectedBytes,
            traffic.peers, traffic.rateBytesPerSecond)
        require(values.all { it != null && it >= 0L })
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("chunks_real_add_traffic_${label}_$phase", values.joinToString(","))
        })
    }

    private fun controller(path: String, port: Long? = null): ByteArray {
        require(path.matches(Regex("/(magnet2?|ready)(\\.torrent|/(metadata|payload|resume))?")))
        val connection = URL("http://127.0.0.1:18081$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = false
        try {
            if (port != null) {
                require(port in 1L..65535L)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write("{\"port\":$port}".toByteArray()) }
                check(connection.responseCode == 204) { "Real peer phase unavailable" }
                return ByteArray(0)
            }
            check(connection.responseCode == 200) { "Real peer source unavailable" }
            return connection.inputStream.use { it.readNBytes(2_097_153) }.also {
                require(it.size <= 2_097_152)
            }
        } finally { connection.disconnect() }
    }

    private fun checkpoint(seconds: Int = 45, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (predicate()) return } catch (fault: RpcFault) {
                if (!fault.isUnknownInfoHash()) throw fault
            }
            Thread.sleep(300)
        }
        fail("Required real transfer checkpoint missing")
    }

    /** Same compiled production Add/lifecycle/Remove composables and adapter,
     * with real publicly sourced metainfo served by a known reachable peer. */
    private fun controlledJourney(alternative: Boolean) {
        val label = if (alternative) "magnet2" else "magnet"
        val remote = remote()
        val metainfo = controller("/$label.torrent")
        val preview = TorrentMetainfoReader.preview(metainfo)
        val hash = preview.infoHashV1
        reportExactPublicHash(label, hash)
        val before = runBlocking { remote.listTransfers("main") }
        val identities = before.map { it.hash.lowercase() }.toSet()
        val parent = runBlocking { reviewedParent(remote, before) }
        require(hash !in identities && runBlocking { remote.lookupName(hash) } == null)
        report("$label-preflight")
        var link by mutableStateOf("magnet:?xt=urn:btih:$hash&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce")
        var folder by mutableStateOf(parent)
        var presentation by mutableStateOf(0)
        var controlRow by mutableStateOf<RemoteTransfer?>(null)
        var revision by mutableStateOf(0L)
        var settled by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                key(presentation) {
                    val row = controlRow
                    if (row == null) VerifiedAddScreen(remote, link, folder,
                        onMagnet = { link = it }, onDestination = { folder = it },
                        onBack = {}, onComplete = {}, onMutationStarted = { true }, onMutationSettled = {},
                        finishInOrderScope = null, environmentLabel = "rTorrent transfer acceptance")
                    else LazyColumn {
                        item { TransferLifecycleControl(remote, row, false, { true },
                            { settled = true }, snapshotRevision = revision) }
                        item { TransferSessionRemoveControl(remote, row, false, true, { true },
                            { settled = true }) }
                    }
                }
            }
        }
        fun click(text: String) {
            runCatching { scroll(text) }
            rule.onNodeWithText(text).performScrollTo().performClick()
        }
        fun row(target: String) = runBlocking {
            remote.listTransfers("main").singleOrNull { it.hash.equals(target, true) }
        }
        fun refreshControls(target: String) {
            val fresh = requireNotNull(row(target))
            rule.runOnIdle { controlRow = fresh; revision++; settled = false }
        }
        fun lifecycle(target: String, start: Boolean) {
            refreshControls(target)
            click(if (start) "Start transfer…" else "Stop transfer…")
            click(if (start) "Confirm start" else "Confirm stop")
            rule.waitUntil(45_000) { settled }
            checkpoint { row(target)?.started == start &&
                runBlocking { remote.readActivity(target).active } == start }
        }
        fun remove(target: String) {
            if (row(target)?.started == true) lifecycle(target, false)
            refreshControls(target)
            click("Remove from rTorrent…")
            click("Confirm remove")
            rule.waitUntil(45_000) { settled }
            assertTrue("Production Remove did not independently verify session absence",
                visible("Verified: this exact infohash is absent from rTorrent's session."))
            assertNull(runBlocking { remote.lookupName(target) })
        }
        fun add(target: String) {
            rule.waitUntil(30_000) {
                runCatching { scroll("Custom server folder (optional)") }
                visible("Using custom Place.")
            }
            scroll("Review Add"); click("Review Add")
            scroll("Confirm: add then start")
            assertTrue(visible("Infohash: $target"))
            click("Confirm: add then start")
            rule.waitUntil(45_000) {
                runCatching { scroll("Operation outcome") }; visible("Operation outcome")
            }
            assertTrue(visible("Verified: added and started on rTorrent."))
        }
        var submitted = false
        var removed = false
        var placeholderName: String? = null
        try {
            report("$label-review")
            submitted = true
            add(hash)
            report("$label-ui-verified")
            val placeholder = requireNotNull(row(hash))
            placeholderName = placeholder.name
            assertEquals(parent, placeholder.directory)
            assertTrue(placeholder.started)
            assertEquals(true, runBlocking { remote.readActivity(hash).active })
            report("$label-registered")
            val peer = runBlocking { remote.peerSource(hash) }
            val expectedHost = if (argument("chunks_disposable") == "authorized") "127.0.0.1" else "5.79.112.219"
            require(peer.boundAddress == expectedHost ||
                (expectedHost == "127.0.0.1" && peer.rpcHost == expectedHost))
            controller("/$label/metadata", peer.listenPort)
            checkpoint(90) {
                val observed = row(hash)
                observed?.name == preview.name && observed.sizeBytes == preview.sizeBytes &&
                    (runBlocking { remote.call("d.is_meta", listOf(hash)) } as? Number)?.toInt() == 0
            }
            report("$label-metadata")
            controller("/$label/payload", peer.listenPort)
            checkpoint(90) {
                val traffic = runBlocking { remote.readTrafficSample(hash) }
                traffic.active == true && (traffic.peers ?: 0L) > 0L &&
                    (traffic.receivedBytes ?: 0L) > 0L && (traffic.verifiedBytes ?: 0L) > 0L &&
                    traffic.expectedBytes == preview.sizeBytes
            }
            reportTraffic(label, "initial", runBlocking { remote.readTrafficSample(hash) })
            report("$label-peer-progress"); report("$label-bytes-verified")
            rule.waitUntil(15_000) {
                runCatching { scroll("Operation outcome") }
                visible("Payload:") && visible("Downloading payload from peers.")
            }
            report("$label-ui-progress")
            val full = requireNotNull(row(hash))
            assertEquals(if (preview.fileCount > 1) "$parent/${preview.name}" else parent, full.directory)
            assertTrue(full.completedBytes in 1 until full.sizeBytes)
            lifecycle(hash, false)
            report("$label-stopped")
            val stopped = runBlocking { remote.readTrafficSample(hash) }
            lifecycle(hash, true)
            controller("/$label/resume", peer.listenPort)
            checkpoint(90) {
                val resumed = runBlocking { remote.readTrafficSample(hash) }
                (resumed.receivedBytes ?: 0L) > (stopped.receivedBytes ?: 0L) &&
                    (resumed.verifiedBytes ?: 0L) > (stopped.verifiedBytes ?: 0L)
            }
            reportTraffic(label, "resumed", runBlocking { remote.readTrafficSample(hash) })
            report("$label-restarted"); report("$label-resumed-traffic")
            remove(hash)
            removed = true
            assertEquals(identities, runBlocking { remote.listTransfers("main") }.map { it.hash.lowercase() }.toSet())
            report("$label-ui-controls"); report("$label-passed")
        } finally {
            if (submitted && !removed) {
                // Error-only recovery remains exact-hash scoped. Never depend
                // on the original name while rTorrent still holds a .meta job.
                val current = row(hash)
                if (current != null && current.name in setOf(preview.name, placeholderName) &&
                    current.directory in setOf(parent, "$parent/${preview.name}")) {
                    if (current.started) runBlocking { remote.setStarted(hash, false) }
                    checkpoint { row(hash)?.started == false && runBlocking { remote.readActivity(hash).active } == false }
                    val fresh = requireNotNull(row(hash))
                    check(runBlocking { remote.eraseFromSession(hash, fresh.name, fresh.directory) } is SessionEraseOutcome.VerifiedRemoved)
                }
                report("$label-uncertain")
            }
        }
        if (alternative) {
            // Negative real-server control in the same installed test, keeping
            // the full native-suite identities and intentional skip baseline.
            val run = requireNotNull(argument("chunks_acceptance_run"))
            require(run.matches(Regex("[0-9]{5,20}")))
            val negative = MessageDigest.getInstance("SHA-1").digest("chunks-peerless-$run".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            require(runBlocking { remote.lookupName(negative) } == null)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("chunks_real_add_hash_negative", negative) })
            rule.runOnIdle {
                link = "magnet:?xt=urn:btih:$negative&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce"
                controlRow = null; presentation++
            }
            try {
                add(negative)
                rule.waitUntil(15_000) {
                    runCatching { scroll("Operation outcome") }
                    visible("Fetching magnet metadata. Waiting for peers.")
                }
                assertFalse(visible("Downloading payload from peers."))
                assertFalse(visible("Download complete."))
                val traffic = runBlocking { remote.readTrafficSample(negative) }
                assertEquals(0L, traffic.receivedBytes)
                assertEquals(0L, traffic.verifiedBytes)
                assertEquals(1, (runBlocking { remote.call("d.is_meta", listOf(negative)) } as Number).toInt())
            } finally {
                if (row(negative) != null) remove(negative)
                assertNull(runBlocking { remote.lookupName(negative) })
            }
            assertEquals(identities, runBlocking { remote.listTransfers("main") }.map { it.hash.lowercase() }.toSet())
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("chunks_real_add_negative", "peerless-ui-and-cleanup-passed") })
        }
    }

    @Test fun installedTorrentFileAddReceivesVerifiedPeerBytes() = journey(true)
    @Test fun installedMagnetAddResolvesMetadataAndReceivesVerifiedPeerBytes() {
        if (argument("chunks_controlled_peer") == "authorized") controlledJourney(false)
        else journey(false)
    }
    @Test fun installedAlternateMagnetResolvesMetadataAndReceivesVerifiedPeerBytes() {
        if (argument("chunks_controlled_peer") == "authorized") controlledJourney(true)
        else journey(false, alternative = true)
    }
}
