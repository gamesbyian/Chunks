package io.github.gamesbyian.chunks

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*

/** Actual installed MainActivity: Connect, paste, review, confirm, Transfers,
 * Stop, Start, further pieces, Remove. No injected remote or mutation callbacks. */
internal class MainActivityRealMagnetJourney(
    private val rule: AndroidComposeTestRule<*, MainActivity>,
    private val corroboratedParent: suspend (RTorrentRemote, List<RemoteTransfer>) -> String?
) {
    private fun arg(name: String) = InstrumentationRegistry.getArguments().getString(name)
    private fun decoded(name: String) = String(Base64.decode(requireNotNull(arg(name)), Base64.NO_WRAP), Charsets.UTF_8)
    private fun visible(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun scroll(text: String) = rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))
    private fun click(text: String) {
        // First find the LazyColumn item and confirm the action is enabled.
        // A large transfer Card may be taller than the viewport: scrolling to
        // its item alone does NOT expose a confirmation button near its end.
        rule.waitUntil(45_000) { runCatching {
            scroll(text)
            rule.onNodeWithText(text).assertIsEnabled()
            true
        }.getOrDefault(false) }
        rule.waitForIdle()
        // Connect is already visible; a redundant scroll there previously
        // raced its re-composing form. Only scroll within an oversized item
        // when the actual action remains offscreen.
        val shown = runCatching {
            rule.onNodeWithText(text).assertIsDisplayed()
            true
        }.getOrDefault(false)
        if (!shown) rule.onNodeWithText(text).performScrollTo()
        rule.onNodeWithText(text).assertIsDisplayed()
        // Exactly one click; never retry a possibly submitted RPC mutation.
        rule.onNodeWithText(text).performClick()
    }
    private fun type(label: String, text: String) {
        scroll(label)
        rule.onNodeWithText(label).performTextReplacement(text)
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        rule.waitForIdle()
    }
    private fun report(key: String, value: String) {
        require(key.matches(Regex("chunks_real_add_(stage|hash_(magnet2?|negative)|traffic_magnet2?_(initial|resumed)|diagnostic_magnet2?|negative|negative_stage|app_stage)")))
        require(value.length <= 160 && value.matches(Regex("[a-z0-9,=-]+")))
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })
    }
    private fun stage(label: String, phase: String) = report("chunks_real_add_stage", "$label-$phase")
    private fun traffic(label: String, phase: String, sample: RemoteTrafficSample) {
        val values = listOf(sample.receivedBytes, sample.verifiedBytes, sample.expectedBytes, sample.peers, sample.rateBytesPerSecond)
        require(values.all { it != null && it >= 0L })
        report("chunks_real_add_traffic_${label}_$phase", values.joinToString(","))
    }
    private fun reportReadOnlyStall(label: String, remote: RTorrentRemote, hash: String) {
        // The existing inspector verifies exact identity and classifies d.message
        // without returning a path, URL, private tracker or passkey to the report.
        // This is called only when real payload progress timed out. No writes.
        val value = runCatching {
            val status = runBlocking { RemoteStallInspector.inspect(remote, hash) }
            fun bit(flag: Boolean?) = when (flag) {
                true -> "1"
                false -> "0"
                null -> "x"
            }
            "state=" + bit(status.started) + ",active=" + bit(status.active) +
                ",open=" + bit(status.open) +
                ",phase=" + (status.hashingPhase?.toString() ?: "x") +
                ",hashfailed=" + bit(status.hashCheckFailed) +
                ",hashing=" + bit(status.hashChecking) +
                ",meta=" + bit(status.awaitingMetadata) +
                ",message=" + status.messageClass.name.lowercase(java.util.Locale.ROOT)
                    .replace("_", "-")
        }.getOrDefault("unavailable")
        report("chunks_real_add_diagnostic_$label", value)
    }

    private fun await(seconds: Int = 45, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + seconds * 1_000L
        while (SystemClock.elapsedRealtime() < end) {
            try { if (condition()) return } catch (fault: RpcFault) {
                if (!fault.isUnknownInfoHash()) throw fault
            }
            Thread.sleep(300)
        }
        fail("Required exact-server checkpoint missing")
    }
    private fun controller(path: String, port: Long? = null): ByteArray {
        require(path.matches(Regex("/magnet2?(\\.(torrent|magnet)|/(metadata|payload|resume))")))
        val connection = URL("http://127.0.0.1:18081$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000; connection.readTimeout = 60_000
        connection.instanceFollowRedirects = false
        try {
            if (port != null) {
                require(port in 1L..65535L)
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write("{\"port\":$port}".toByteArray()) }
                check(connection.responseCode == 204) { "Real peer phase unavailable" }
                return ByteArray(0)
            }
            check(connection.responseCode == 200) { "Real peer source unavailable" }
            return connection.inputStream.use { it.readNBytes(2_097_153) }.also { require(it.size <= 2_097_152) }
        } finally { connection.disconnect() }
    }

    fun run() {
        require(arg("chunks_real_add") == "authorized")
        report("chunks_real_add_app_stage", "runner-ready")
        val disposable = arg("chunks_disposable") == "authorized"
        val publicDiscovery = arg("chunks_public_discovery") == "authorized"
        val url = decoded("chunks_live_url_b64")
        if (disposable) require(url == "http://127.0.0.1:18080/RPC2")
        else {
            val endpoint = URI(url)
            require(endpoint.scheme == "https" && endpoint.host == "ianseeds.wood.seedhost.eu" &&
                endpoint.port == -1 && endpoint.path == "/rutorrent/" && endpoint.userInfo == null &&
                endpoint.query == null && endpoint.fragment == null)
        }
        val user = if (disposable) "" else decoded("chunks_live_user_b64")
        val password = if (disposable) "" else decoded("chunks_live_pass_b64")
        val remote = RTorrentRemote(RemoteCredentials(url, user, password))
        fun row(hash: String) = runBlocking { remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) } }
        val beforeRows = runBlocking { remote.listTransfers("main") }
        val before = beforeRows.map { it.hash.lowercase() }.toSet()
        val default = runBlocking { remote.defaultDirectory() }
        val parent = default ?: requireNotNull(runBlocking { corroboratedParent(remote, beforeRows) }) {
            "No independently corroborated server Place; no Add allowed"
        }
        val fixtures = listOf("magnet", "magnet2").associateWith { label ->
            TorrentMetainfoReader.preview(controller("/$label.torrent")).also { preview ->
                report("chunks_real_add_hash_$label", preview.infoHashV1)
                require(preview.infoHashV1 !in before && runBlocking { remote.lookupName(preview.infoHashV1) } == null)
            }
        }
        // Real connection UI; its ordinary authentication, saved profile,
        // selected environment and mutation fence remain in the call chain.
        type("Seedbox hostname, ruTorrent URL or RPC address", url)
        type("Username, if required", user)
        type("Password", password)
        report("chunks_real_add_app_stage", "connect-click-attempt")
        click("Connect to rTorrent")
        report("chunks_real_add_app_stage", "connect-submitted")
        // The compact authenticated Transfers header replaced the old welcome
        // slogan. Assert an actual connected list action, not retired copy.
        rule.waitUntil(90_000) { visible("All environments…") && !visible("Connect to rTorrent") }
        rule.onNodeWithText("Password").assertDoesNotExist()
        report("chunks_real_add_app_stage", "queue")

        fun add(hash: String, label: String? = null, onSubmit: () -> Unit = {}) {
            report("chunks_real_add_app_stage", "add-open-requested")
            click("+ Add torrent")
            rule.waitUntil(30_000) { visible("Add a download to this server") }
            report("chunks_real_add_app_stage", "add-open")
            // The real Seedhost must receive the actual publisher magnet, including
            // its original tracker list. The disposable controlled test keeps
            // the deliberate dead tracker to isolate the supplied peer.
            val link = if ((publicDiscovery || !disposable) && label != null)
                String(controller("/$label.magnet"), Charsets.UTF_8)
                else "magnet:?xt=urn:btih:$hash&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce"
            if (!disposable && label != null) require("&tr=" in link) { "Original source trackers missing" }
            require(StoppedRemoteAddPlan.fromMagnet(link, parent).expectedInfoHash == hash)
            type("Magnet link", link)
            report("chunks_real_add_app_stage", "magnet-entered")
            rule.waitUntil(30_000) {
                runCatching { scroll("Custom server folder (optional)") }
                visible("Using rTorrent default Place:") || visible("No usable default Place was reported.")
            }
            if (default == null) type("Custom server folder (optional)", parent)
            report("chunks_real_add_app_stage", "place-ready")
            click("Review Add")
            scroll("Confirm one server operation")
            assertTrue("Review lost exact public identity", visible("Infohash: $hash"))
            if (label != null) stage(label, "review")
            report("chunks_real_add_app_stage", "add-review")
            onSubmit()
            report("chunks_real_add_app_stage", "add-submitted")
            click("Confirm: add then start")
            rule.waitUntil(60_000) { runCatching { scroll("Operation outcome") }; visible("Operation outcome") }
            assertTrue("Actual app did not verify Add+Start", visible("Verified: added and started on rTorrent."))
            report("chunks_real_add_app_stage", "add-registered")
        }
        fun refreshAndFind(hash: String) {
            click("Refresh")
            type("Search names or Places", hash)
        }
        fun lifecycle(hash: String, start: Boolean, refresh: Boolean = true) {
            if (refresh) refreshAndFind(hash)
            click(if (start) "Start transfer…" else "Stop transfer…")
            click(if (start) "Confirm start" else "Confirm stop")
            await { row(hash)?.started == start && runBlocking { remote.readActivity(hash).active } == start }
        }
        fun remove(hash: String) {
            if (row(hash)?.started == true) lifecycle(hash, false)
            refreshAndFind(hash)
            click("Remove from rTorrent…"); click("Remove transfer, keep data")
            await { runBlocking { remote.lookupName(hash) } == null }
            assertEquals(before, runBlocking { remote.listTransfers("main") }.map { it.hash.lowercase() }.toSet())
        }
        for (label in listOf("magnet", "magnet2")) {
            val preview = fixtures.getValue(label)
            val hash = preview.infoHashV1
            require(hash !in before && runBlocking { remote.lookupName(hash) } == null)
            stage(label, "preflight")
            var submitted = false
            var removed = false
            try {
                add(hash, label) { submitted = true }
                stage(label, "ui-verified")
                val placeholder = requireNotNull(row(hash))
                assertTrue(placeholder.directory in setOf(parent, "$parent/${preview.name}"))
                val initialMetaFlag = (runBlocking { remote.call("d.is_meta", listOf(hash)) } as Number).toInt()
                require(initialMetaFlag == 0 || initialMetaFlag == 1) { "Invalid exact metadata state" }
                if (!publicDiscovery) {
                    // Disposable peer-isolated Add must still exercise BEP9; the
                    // live published trackers may already have resolved metadata.
                    if (disposable) assertEquals(1, initialMetaFlag)
                    if (initialMetaFlag == 0) {
                        assertEquals("Immediately resolved metadata name", preview.name, placeholder.name)
                        assertEquals("Immediately resolved metadata size", preview.sizeBytes, placeholder.sizeBytes)
                        report("chunks_real_add_app_stage", "metadata-already-resolved")
                        stage(label, "metadata-already-resolved")
                    }
                }
                assertTrue(placeholder.started)
                assertEquals(true, runBlocking { remote.readActivity(hash).active })
                stage(label, "registered")
                val source = runBlocking { remote.peerSource(hash) }
                val host = if (disposable) "127.0.0.1" else "5.79.112.219"
                require(source.boundAddress == host || (disposable && source.rpcHost == host))
                if (!publicDiscovery && initialMetaFlag == 1)
                    controller("/$label/metadata", source.listenPort)
                await(90) {
                    val exact = row(hash)
                    exact?.name == preview.name && exact.sizeBytes == preview.sizeBytes &&
                        (runBlocking { remote.call("d.is_meta", listOf(hash)) } as Number).toInt() == 0
                }
                stage(label, "metadata")
                if (!publicDiscovery) controller("/$label/payload", source.listenPort)
                try {
                    await(90) {
                        val sample = runBlocking { remote.readTrafficSample(hash) }
                        sample.active == true && ((sample.peers ?: 0L) > 0L ||
                            (publicDiscovery && sample.verifiedBytes == sample.expectedBytes)) &&
                            (sample.receivedBytes ?: 0L) > 0L && (sample.verifiedBytes ?: 0L) > 0L &&
                            sample.expectedBytes == preview.sizeBytes
                    }
                } catch (timeout: AssertionError) {
                    // Prior failed live run had zero independent uploads but
                    // discarded *all* remote counters and state on timeout.
                    // Preserve only allowlisted exact-hash numbers/categories
                    // while the owned transfer still exists, before cleanup.
                    runCatching {
                        traffic(label, "initial", runBlocking { remote.readTrafficSample(hash) })
                    }
                    reportReadOnlyStall(label, remote, hash)
                    throw timeout
                }
                traffic(label, "initial", runBlocking { remote.readTrafficSample(hash) })
                stage(label, "peer-progress"); stage(label, "bytes-verified")
                rule.waitUntil(15_000) { runCatching { scroll("Operation outcome") }
                    visible("Payload:") && (visible("Downloading payload from peers.") ||
                        (publicDiscovery && visible("Download complete. All payload pieces verified by rTorrent."))) }
                stage(label, "ui-progress")
                val payload = requireNotNull(row(hash))
                assertEquals(if (preview.fileCount > 1) "$parent/${preview.name}" else parent, payload.directory)
                assertTrue(payload.completedBytes in 1..payload.sizeBytes)
                if (!publicDiscovery) assertTrue(payload.completedBytes < payload.sizeBytes)
                click("Back to transfers")
                refreshAndFind(hash)
                scroll(preview.name)
                assertTrue("Actual Transfers lost original metadata name", visible(preview.name))
                assertFalse("Actual Transfers retained placeholder facts", visible("Payload size and progress pending metadata"))
                // Already refreshed and searched this exact hash above. A second
                // refresh before Stop creates an unnecessary pending/readback
                // window and can disable controls while a user-visible mutation
                // is still awaiting a new snapshot.
                lifecycle(hash, false, refresh = false)
                stage(label, "stopped")
                val stopped = runBlocking { remote.readTrafficSample(hash) }
                lifecycle(hash, true)
                if (!publicDiscovery) controller("/$label/resume", source.listenPort)
                val completeBeforeRestart = publicDiscovery && stopped.verifiedBytes == stopped.expectedBytes
                if (!completeBeforeRestart) await(90) {
                    val sample = runBlocking { remote.readTrafficSample(hash) }
                    (sample.receivedBytes ?: 0L) > (stopped.receivedBytes ?: 0L) &&
                        (sample.verifiedBytes ?: 0L) > (stopped.verifiedBytes ?: 0L)
                }
                traffic(label, "resumed", runBlocking { remote.readTrafficSample(hash) })
                stage(label, "restarted")
                stage(label, if (completeBeforeRestart) "completed-at-stop" else "resumed-traffic")
                remove(hash); removed = true
                stage(label, "removed")
                stage(label, "ui-controls"); stage(label, "passed")
            } finally {
                if (submitted && !removed) {
                    // Failure-only exact owned-session cleanup. A fresh exact
                    // row and metadata flag guard changes; no Add is replayed.
                    val current = row(hash)
                    if (current != null && current.directory in setOf(parent, "$parent/${preview.name}")) {
                        if (current.started) runBlocking { remote.setStarted(hash, false) }
                        await { row(hash)?.started == false && runBlocking { remote.readActivity(hash).active } == false }
                        val stopped = requireNotNull(row(hash))
                        check(runBlocking { remote.eraseFromSession(hash, stopped.name, stopped.directory) } is SessionEraseOutcome.VerifiedRemoved)
                        assertEquals(before, runBlocking { remote.listTransfers("main") }.map { it.hash.lowercase() }.toSet())
                        stage(label, "removed")
                    }
                    stage(label, "uncertain")
                }
            }
        }
        val run = requireNotNull(arg("chunks_acceptance_run"))
        require(run.matches(Regex("[0-9]{5,20}")))
        val negative = MessageDigest.getInstance("SHA-1").digest("chunks-peerless-$run".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        report("chunks_real_add_hash_negative", negative)
        report("chunks_real_add_negative_stage", "preflight")
        require(runBlocking { remote.lookupName(negative) } == null)
        var inTransfers = false
        try {
            add(negative)
            report("chunks_real_add_negative_stage", "registered")
            rule.waitUntil(15_000) { runCatching { scroll("Operation outcome") }
                visible("Fetching magnet metadata. Waiting for peers.") }
            assertFalse(visible("Downloading payload from peers."))
            assertFalse(visible("Download complete."))
            report("chunks_real_add_negative_stage", "add-ui-truthful")
            val sample = runBlocking { remote.readTrafficSample(negative) }
            assertEquals(0L, sample.receivedBytes); assertEquals(0L, sample.verifiedBytes)
            assertEquals(1, (runBlocking { remote.call("d.is_meta", listOf(negative)) } as Number).toInt())
            click("Back to transfers"); inTransfers = true
            refreshAndFind(negative)
            scroll("Fetching metadata")
            assertTrue(visible("Payload size and progress pending metadata"))
            assertFalse(visible("100% ·"))
            report("chunks_real_add_negative_stage", "transfers-ui-truthful")
        } finally {
            if (row(negative) != null) {
                if (!inTransfers) click("Back to transfers")
                remove(negative)
            }
            assertNull(runBlocking { remote.lookupName(negative) })
            report("chunks_real_add_negative_stage", "removed")
        }
        report("chunks_real_add_negative", "peerless-ui-and-cleanup-passed")
    }
}
