package io.github.gamesbyian.chunks

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
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
        rule.waitUntil(45_000) { runCatching {
            scroll(text); rule.onNodeWithText(text).assertIsEnabled(); true
        }.getOrDefault(false) }
        rule.onNodeWithText(text).performScrollTo().performClick()
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
        require(key.matches(Regex("chunks_real_add_(stage|hash_(magnet2?|negative)|traffic_magnet2?_(initial|resumed)|negative|negative_stage|app_stage)")))
        require(value.length <= 160 && value.matches(Regex("[a-z0-9,=-]+")))
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })
    }
    private fun stage(label: String, phase: String) = report("chunks_real_add_stage", "$label-$phase")
    private fun traffic(label: String, phase: String, sample: RemoteTrafficSample) {
        val values = listOf(sample.receivedBytes, sample.verifiedBytes, sample.expectedBytes, sample.peers, sample.rateBytesPerSecond)
        require(values.all { it != null && it >= 0L })
        report("chunks_real_add_traffic_${label}_$phase", values.joinToString(","))
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
        require(path.matches(Regex("/magnet2?(\\.torrent|/(metadata|payload|resume))")))
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
        // Real connection UI; its ordinary authentication, saved profile,
        // selected environment and mutation fence remain in the call chain.
        type("Seedbox hostname, ruTorrent URL or RPC address", url)
        type("Username, if required", user)
        type("Password", password)
        report("chunks_real_add_app_stage", "connect-submitted")
        click("Connect to rTorrent")
        rule.waitUntil(90_000) { visible("It's all here.") }
        rule.onNodeWithText("Password").assertDoesNotExist()
        report("chunks_real_add_app_stage", "queue")

        fun add(hash: String, label: String? = null) {
            click("+ Add torrent")
            rule.waitUntil(30_000) { visible("A place for every download.") }
            type("Magnet link", "magnet:?xt=urn:btih:$hash&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce")
            rule.waitUntil(30_000) {
                runCatching { scroll("Custom server folder (optional)") }
                visible("Using rTorrent default Place:") || visible("No usable default Place was reported.")
            }
            if (default == null) type("Custom server folder (optional)", parent)
            click("Review Add")
            scroll("Confirm one server operation")
            assertTrue("Review lost exact public identity", visible("Infohash: $hash"))
            if (label != null) stage(label, "review")
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
        fun lifecycle(hash: String, start: Boolean) {
            refreshAndFind(hash)
            click(if (start) "Start transfer…" else "Stop transfer…")
            click(if (start) "Confirm start" else "Confirm stop")
            await { row(hash)?.started == start && runBlocking { remote.readActivity(hash).active } == start }
        }
        fun remove(hash: String) {
            if (row(hash)?.started == true) lifecycle(hash, false)
            refreshAndFind(hash)
            click("Remove from rTorrent…"); click("Confirm remove")
            await { runBlocking { remote.lookupName(hash) } == null }
            assertEquals(before, runBlocking { remote.listTransfers("main") }.map { it.hash.lowercase() }.toSet())
        }
        for (label in listOf("magnet", "magnet2")) {
            val preview = TorrentMetainfoReader.preview(controller("/$label.torrent"))
            val hash = preview.infoHashV1
            report("chunks_real_add_hash_$label", hash)
            require(hash !in before && runBlocking { remote.lookupName(hash) } == null)
            stage(label, "preflight")
            var submitted = false
            var removed = false
            try {
                submitted = true
                add(hash, label)
                stage(label, "ui-verified")
                val placeholder = requireNotNull(row(hash))
                assertEquals(parent, placeholder.directory)
                assertTrue(placeholder.started)
                assertEquals(true, runBlocking { remote.readActivity(hash).active })
                stage(label, "registered")
                val source = runBlocking { remote.peerSource(hash) }
                val host = if (disposable) "127.0.0.1" else "5.79.112.219"
                require(source.boundAddress == host || (disposable && source.rpcHost == host))
                controller("/$label/metadata", source.listenPort)
                await(90) {
                    val exact = row(hash)
                    exact?.name == preview.name && exact.sizeBytes == preview.sizeBytes &&
                        (runBlocking { remote.call("d.is_meta", listOf(hash)) } as Number).toInt() == 0
                }
                stage(label, "metadata")
                controller("/$label/payload", source.listenPort)
                await(90) {
                    val sample = runBlocking { remote.readTrafficSample(hash) }
                    sample.active == true && (sample.peers ?: 0L) > 0L &&
                        (sample.receivedBytes ?: 0L) > 0L && (sample.verifiedBytes ?: 0L) > 0L &&
                        sample.expectedBytes == preview.sizeBytes
                }
                traffic(label, "initial", runBlocking { remote.readTrafficSample(hash) })
                stage(label, "peer-progress"); stage(label, "bytes-verified")
                rule.waitUntil(15_000) { runCatching { scroll("Operation outcome") }
                    visible("Payload:") && visible("Downloading payload from peers.") }
                stage(label, "ui-progress")
                val payload = requireNotNull(row(hash))
                assertEquals(if (preview.fileCount > 1) "$parent/${preview.name}" else parent, payload.directory)
                assertTrue(payload.completedBytes in 1 until payload.sizeBytes)
                click("Back to transfers")
                refreshAndFind(hash)
                scroll(preview.name)
                assertTrue("Actual Transfers lost original metadata name", visible(preview.name))
                assertFalse("Actual Transfers retained placeholder facts", visible("Payload size and progress pending metadata"))
                lifecycle(hash, false)
                stage(label, "stopped")
                val stopped = runBlocking { remote.readTrafficSample(hash) }
                lifecycle(hash, true)
                controller("/$label/resume", source.listenPort)
                await(90) {
                    val sample = runBlocking { remote.readTrafficSample(hash) }
                    (sample.receivedBytes ?: 0L) > (stopped.receivedBytes ?: 0L) &&
                        (sample.verifiedBytes ?: 0L) > (stopped.verifiedBytes ?: 0L)
                }
                traffic(label, "resumed", runBlocking { remote.readTrafficSample(hash) })
                stage(label, "restarted"); stage(label, "resumed-traffic")
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
            assertFalse(visible("Complete"))
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
