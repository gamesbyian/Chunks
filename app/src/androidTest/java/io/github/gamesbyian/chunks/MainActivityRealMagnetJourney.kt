package io.github.gamesbyian.chunks

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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
    private fun scroll(text: String) {
        val match = hasText(text, substring = true)
        // Plain scroll first (canonical Details is a non-lazy column); lazy lists (Transfers,
        // Connections) only compose nearby items, so fall back to scrolling by item.
        try { rule.onNode(match).performScrollTo(); return } catch (_: AssertionError) { }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(match)
    }
    private fun hasTag(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    /** Stable-id click for persistent roots; waits for composition, never retries a click. */
    private fun clickTag(tag: String) {
        rule.waitUntil(45_000) { hasTag(tag) }
        rule.waitForIdle()
        rule.onNode(hasTestTag(tag)).performClick()
    }
    private fun waitTransfersCatalog() = rule.waitUntil(60_000) {
        hasTag(ChunksSemantics.TRANSFERS_HEADING) && visible("Search names or hashes")
    }
    private fun click(text: String) {
        // Names the control about to be clicked so a failure reports the exact step.
        report("chunks_real_add_app_stage",
            "click-" + text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40))
        // First find the LazyColumn item and confirm the action is enabled.
        // A large transfer Card may be taller than the viewport: scrolling to
        // its item alone does NOT expose a confirmation button near its end.
        try {
            rule.waitUntil(45_000) { runCatching {
                scroll(text)
                rule.onNodeWithText(text).assertIsEnabled()
                true
            }.getOrDefault(false) }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            // Names WHY this control was unusable (bounded, lowercase; no labels from the app).
            val count = rule.onAllNodesWithText(text).fetchSemanticsNodes().size
            report("chunks_real_add_app_stage", "click-" +
                text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(26) +
                if (count == 0) "-absent" else if (count > 1) "-ambiguous" else "-unusable")
            throw timeout
        }
        rule.waitForIdle()
        // Connect is already visible; a redundant scroll there previously
        // raced its re-composing form. Only scroll within an oversized item
        // when the actual action remains offscreen.
        // The IME can asynchronously re-scroll and recycle a lazy button
        // after the first pre-dispatch scroll. Retry *only* read/scroll checks,
        // never an action that could have submitted Connect/Add/Remove.
        // A focused input may reopen the IME on the next Android frame,
        // recycling the button even after the earlier enabled-only probe.
        // Give Compose a bounded chance to settle using *read/scroll-only*
        // operations. Never attempt a click in this loop.
        rule.waitUntil(30_000) {
            runCatching {
                scroll(text)
                rule.onNodeWithText(text).performScrollTo()
                rule.waitForIdle()
                rule.onNodeWithText(text).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        // Exactly one click; never retry a possibly submitted RPC mutation.
        rule.onNodeWithText(text).performClick()
    }
    private fun type(label: String, text: String) {
        scroll(label)
        rule.onNodeWithText(label).performTextReplacement(text)
        if (label == "Magnet or torrent link" || label == "Custom server folder (optional)") {
            // Exercise the same Done IME action available to a real phone user
            // instead of leaving the URL editor focused under an unseen IME.
            // Never trigger a Connect/Confirm action from the keyboard.
            runCatching { rule.onNodeWithText(label).performImeAction() }
        }
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        rule.waitForIdle()
    }
    private fun report(key: String, value: String) {
        require(key.matches(Regex("chunks_real_add_(stage|hash_(magnet[0234]?|negative)|traffic_magnet[0234]?_(initial|resumed)|diagnostic_magnet[0234]?|negative|negative_stage|app_stage)")))
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

    private fun await(seconds: Int = 45, what: String = "exact-server checkpoint", condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + seconds * 1_000L
        while (SystemClock.elapsedRealtime() < end) {
            try { if (condition()) return } catch (fault: RpcFault) {
                if (!fault.isUnknownInfoHash()) throw fault
            }
            Thread.sleep(300)
        }
        fail("Required exact-server checkpoint missing: $what")
    }
    /**
     * The test's own READ-ONLY rTorrent checks. The emulator reaches the disposable proxy through
     * `adb reverse`, which occasionally drops a connection before nginx sees it (run 42100816764:
     * first frame RTorrentRemote.kt request write; every logged request was 200). Retry only reads,
     * a bounded few times; mutations are never retried here.
     */
    private fun <T> readOnly(block: suspend () -> T): T {
        var last: java.io.IOException? = null
        repeat(4) { attempt ->
            try { return runBlocking { block() } } catch (transient: java.io.IOException) {
                if (transient is io.github.gamesbyian.chunks.rpc.RpcReplyTooLarge) throw transient
                last = transient
                android.util.Log.i("ChunksRealMagnet", "CHUNKS_READ_RETRY attempt=${attempt + 1} type=${transient.javaClass.simpleName}")
                Thread.sleep(1_000)
            }
        }
        // After four READ-ONLY I/O attempts, preserve only a fixed failure
        // category. Do not emit RPC URLs, exception text, tracker values or
        // credentials, and never retry a mutation through this helper.
        report("chunks_real_add_app_stage", "read-only-exhausted")
        throw last!!
    }
    private fun controller(path: String, port: Long? = null): ByteArray {
        require(path.matches(Regex("/magnet[0234]?(\\.(torrent|magnet)|/(metadata|payload|resume))")))
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

    private fun controllerReady(): ByteArray {
        val connection = URL("http://127.0.0.1:18081/ready").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000; connection.readTimeout = 5_000
        try {
            check(connection.responseCode == 200)
            return connection.inputStream.use { it.readNBytes(65_537) }.also { require(it.size <= 65_536) }
        } finally { connection.disconnect() }
    }

    fun run(torrentLinks: Boolean = false) {
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
        fun row(hash: String) = readOnly { remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) } }
        val beforeRows = readOnly { remote.listTransfers("main") }
        val before = beforeRows.map { it.hash.lowercase() }.toSet()
        val skippedSessions = mutableMapOf<String, Pair<String, RemoteTransfer?>>()
        val default = readOnly { remote.defaultDirectory() }
        val parent = default ?: requireNotNull(runBlocking { corroboratedParent(remote, beforeRows) }) {
            "No independently corroborated server Place; no Add allowed"
        }
        val groups = listOf((if (disposable && !publicDiscovery) listOf("magnet0") else emptyList()) +
            listOf("magnet", "magnet3"), listOf("magnet2", "magnet4"))
        val fixtures = groups.flatten().associateWith { label ->
            TorrentMetainfoReader.preview(controller("/$label.torrent")).also { preview ->
                report("chunks_real_add_hash_$label", preview.infoHashV1)
            }
        }
        require(fixtures.values.map { it.infoHashV1 }.toSet().size == fixtures.size)
        // Enter the real Connections destination, never an implicit login.
        rule.onNodeWithText("Connections").assertExists().performClick()
        // Real connection UI; its ordinary authentication, saved profile,
        // selected environment and mutation fence remain in the call chain.
        type("Seedbox hostname, ruTorrent URL or RPC address", url)
        type("Username, if required", user)
        type("Password", password)
        report("chunks_real_add_app_stage", "connect-click-attempt")
        click("Connect to rTorrent")
        report("chunks_real_add_app_stage", "connect-submitted")
        // Verified Connect acknowledges and stays on Connections (#1515); the saved
        // profile then opens the one canonical Transfers browser. There is no legacy
        // server queue landing screen.
        rule.waitUntil(90_000) { visible("Continue to Connections") }
        // The acknowledgement is a dialog (its own window), not an item in the Connections
        // list, so it is clicked directly rather than through the list-scrolling helper.
        rule.onNodeWithText("Continue to Connections").performClick()
        rule.waitUntil(60_000) { visible("View transfers") }
        // Connections lists the current server's button AND one per saved profile; only the
        // current verified server's exact accessibility name is unambiguous.
        rule.onNode(hasContentDescription("View transfers on current verified server"))
            .performScrollTo().performClick()
        waitTransfersCatalog()
        rule.onNodeWithText("Password").assertDoesNotExist()
        report("chunks_real_add_app_stage", "queue")

        fun remoteAddShowing() = visible("Magnet or torrent link") || visible("View verified transfer in rTorrent")
        fun openRemoteAdd() {
            clickTag(ChunksSemantics.FOOTER_ADD)
            rule.waitUntil(30_000) { visible("Add a transfer") || remoteAddShowing() }
            // The footer opens the last selected target; on first use that is this device. The
            // authenticated server is an explicit, non-mutating Add-to choice (it grants no Add
            // permission). Select it only when the remote form is not already showing, e.g. when
            // returning to a retained verified result.
            if (!remoteAddShowing()) {
                rule.waitUntil(30_000) { hasTag(ChunksSemantics.ADD_TARGET_REMOTE) }
                rule.onNode(hasTestTag(ChunksSemantics.ADD_TARGET_REMOTE)).performScrollTo().performClick()
            }
            rule.waitUntil(30_000) { remoteAddShowing() }
        }
        fun add(hash: String, label: String? = null, onSubmit: () -> Unit = {}) {
            report("chunks_real_add_app_stage", "add-open-requested")
            openRemoteAdd()
            report("chunks_real_add_app_stage", "add-open")
            // A verified previous Add remains a result on return; only this
            // explicit action starts a fresh source review, without an RPC.
            if (runCatching { scroll("Start another Add") }.isSuccess) click("Start another Add")
            // The real Seedhost must receive the actual publisher magnet, including
            // its original tracker list. The disposable controlled test keeps
            // the deliberate dead tracker to isolate the supplied peer.
            val link = if ((publicDiscovery || !disposable) && label != null)
                String(controller("/$label.magnet"), Charsets.UTF_8)
                else "magnet:?xt=urn:btih:$hash&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce"
            if (!disposable && label != null) require("&tr=" in link) { "Original source trackers missing" }
            require(StoppedRemoteAddPlan.fromMagnet(link, parent).expectedInfoHash == hash)
            if (torrentLinks && label != null && label != "magnet0") {
                val url = org.json.JSONObject(String(controllerReady(), Charsets.UTF_8))
                    .getJSONObject(label).getString("torrent_url")
                require(url.startsWith("https://"))
                type("Magnet or torrent link", url)
                click("Get torrent from link")
                rule.waitUntil(45_000) { visible(fixtures.getValue(label).name) }
                assertNull("Fetch/review must not register a job", readOnly { remote.lookupName(hash) })
                if (disposable && !publicDiscovery) {
                    // An ordinary publisher HTTPS .torrent necessarily includes
                    // public trackers. Keep the genuine link *preview* and
                    // zero-write assertion, then explicitly switch this draft
                    // to the same-infohash, inert-tracker magnet before Add.
                    // Otherwise rTorrent can fetch from unobserved public peers
                    // and fake a successful controlled-source test even when
                    // the supplied local libtorrent seeder uploaded zero bytes.
                    // Changing the source must revoke the fetched .torrent
                    // selection; the actual Add still uses the production UI.
                    type("Magnet or torrent link", link)
                    assertNull("Changing source cannot implicitly add the reviewed URL",
                        readOnly { remote.lookupName(hash) })
                }
            } else type("Magnet or torrent link", link)
            report("chunks_real_add_app_stage", "magnet-entered")
            rule.waitUntil(30_000) {
                runCatching { scroll("Custom server folder (optional)") }
                visible("Using rTorrent default Place:") || visible("No usable default Place was reported.")
            }
            if (default == null) type("Custom server folder (optional)", parent)
            report("chunks_real_add_app_stage", "place-ready")
            if (disposable) {
                val artworkChoice = "Automatically identify and fetch artwork & metadata"
                report("chunks_real_add_app_stage", "artwork-heading")
                // The disclosure is saveable across Add visits. A blind click
                // can COLLAPSE an already-expanded second Add, hiding the exact
                // opt-out. Probe only semantics/scroll before choosing whether
                // the disclosure needs opening. Never retry the checkbox tap
                // (it represents the user's actual source-specific choice).
                val choiceAlreadyExpanded = runCatching {
                    scroll(artworkChoice)
                    rule.onNodeWithText(artworkChoice).assertIsDisplayed()
                }.isSuccess
                if (!choiceAlreadyExpanded) click("Artwork & metadata")
                report("chunks_real_add_app_stage", "artwork-choice")
                // This must remain present, reachable and actionable for
                // EVERY Add; do not silently omit an opt-out after navigation.
                click(artworkChoice)
            }
            // The source-first form has one explicit "Add transfer" submission action and no
            // separate review card. The exact full infohash is shown inline before Add; assert it
            // here, then verify the registered hash, tracker list and BEP9 witness after Add.
            scroll("Infohash: $hash")
            assertTrue("Add form lost exact public identity", visible("Infohash: $hash"))
            if (torrentLinks && label != null && label != "magnet0") {
                InstrumentationRegistry.getInstrumentation().sendStatus(2,
                    Bundle().apply { putString("chunks_https_review", label) })
            }
            if (label != null) stage(label, "review")
            report("chunks_real_add_app_stage", "add-review")
            onSubmit()
            report("chunks_real_add_app_stage", "add-submitted")
            click("Add transfer")
            rule.waitUntil(60_000) { runCatching { scroll("Operation outcome") }; visible("Operation outcome") }
            assertTrue("Actual app did not verify Add+Start", visible("Verified: added and started on rTorrent."))
            if (disposable) {
                val key = io.github.gamesbyian.chunks.media.MediaTransferKey.of(
                    io.github.gamesbyian.chunks.model.UnifiedTransferKey.of(
                        io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverId(url, user), hash))
                assertTrue("Remote Add must persist its own exact-scoped artwork exclusion",
                    io.github.gamesbyian.chunks.media.FileMediaAutomationPreferences(
                        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                            "media/automation.json")).isExcluded(key))
            }
            // Exercise the production app owner, not a directly composed form:
            // leaving/reopening must retain the exact verified result and latch.
            clickTag(ChunksSemantics.FOOTER_TRANSFERS)
            waitTransfersCatalog()
            openRemoteAdd()
            scroll("View verified transfer in rTorrent")
            rule.onNodeWithText("View verified transfer in rTorrent").assertExists()
            assertTrue("Returning to Add must retain the submitted latch, even outside the viewport",
                runCatching { scroll("Add transfer") }.isFailure)
            assertNotNull("Navigation must retain the independently registered exact job", row(hash))
            if (disposable && !publicDiscovery) {
                // The HTTPS preview above must NOT leak its public trackers
                // into the actual Add. Query the real rTorrent session after
                // admission, independently of the Android draft and without
                // recording or printing any tracker string.
                val trackers = readOnly {
                    remote.call("t.multicall", listOf(hash, "", "t.url=")) as? List<*>
                }
                assertTrue("No public tracker may be admitted by a controlled-peer fixture",
                    trackers != null && trackers.isNotEmpty() && trackers.all { tracker ->
                        (tracker as? List<*>)?.singleOrNull() ==
                            "http://127.0.0.1:1/announce"
                    })
            }
            report("chunks_real_add_app_stage", "add-registered")
        }
        fun refreshAndFind(hash: String) {
            // Footer Transfers is the one canonical list; searching an exact hash narrows it
            // without any write.
            clickTag(ChunksSemantics.FOOTER_TRANSFERS)
            waitTransfersCatalog()
            type("Search names or hashes", hash)
        }
        // Compact rows open full Details on tap; Start/Stop/Remove live in that one Details.
        fun openDetails(hash: String) {
            // The exact-hash search leaves one row for this job, so open it without relying on
            // the display name (a metadata-pending magnet's server name differs from its label).
            requireNotNull(row(hash)) { "exact remote job must exist" }
            val anyRow = hasContentDescription("Details for ", substring = true)
            rule.waitUntil(60_000) { rule.onAllNodes(anyRow).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("The exact-hash search must leave exactly one row", 1,
                rule.onAllNodes(anyRow).fetchSemanticsNodes().size)
            rule.onNode(anyRow).performClick()
            rule.waitUntil(30_000) { visible("Transfer details") }
        }
        fun lifecycle(hash: String, start: Boolean) {
            refreshAndFind(hash)
            openDetails(hash)
            // Canonical Details never dispatches Start/Stop itself: "Review Start / Stop" hands the
            // exact job to the Transfers batch tools with that one key selected. The owner's
            // separately confirmed review then names the target before one sequential run.
            click("Review Start / Stop")
            click(if (start) "Start…" else "Stop…")
            click(if (start) "Start selected" else "Stop selected")
            await(what = "lifecycle state readback") { row(hash)?.started == start && readOnly { remote.readActivity(hash).active } == start }
        }
        fun remove(hash: String) {
            if (row(hash)?.started == true) lifecycle(hash, false)
            refreshAndFind(hash)
            openDetails(hash)
            click("Remove from rTorrent…"); click("Remove transfer, keep data")
            await { readOnly { remote.lookupName(hash) } == null }
            assertEquals(before, readOnly { remote.listTransfers("main") }.map { it.hash.lowercase() }.toSet())
            // A follow-on Add must start from an unfiltered Transfers screen.
            // Clearing the prior exact-hash search is read-only, and prevents
            // the next fixture from inheriting an obsolete UI selection.
            clickTag(ChunksSemantics.FOOTER_TRANSFERS)
            waitTransfersCatalog()
            type("Search names or hashes", "")
            rule.waitUntil(30_000) { hasTag(ChunksSemantics.FOOTER_ADD) }
        }
        // Exercise an entire real, authenticated rTorrent Add -> Transfers ->
        // Combined -> read-only Details -> Transfers -> Stop/Start -> Remove
        // chain once per installed journey. Separate two-server coverage also
        // checks identity collisions on different authenticated accounts.
        var combinedDetailsVerified = false
        fun inspectCombinedDetails(hash: String, displayName: String, expectedPlace: String) {
            val original = requireNotNull(row(hash))
            val originalHashes = readOnly { remote.listTransfers("main").map { it.hash.lowercase() }.toSet() }
            clickTag(ChunksSemantics.FOOTER_TRANSFERS)
            waitTransfersCatalog()
            // Combined asynchronously loads its phone/remote privacy and
            // inventory. The title appearing does not prove that its exact
            // remote transfer row has been composed yet.
            val rowMatch = hasContentDescription("Details for $displayName on", substring = true)
            rule.waitUntil(45_000) {
                runCatching {
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(rowMatch)
                    true
                }.getOrDefault(false)
            }
            // First tap enters full exact authenticated Details, no second disclosure.
            rule.onNode(rowMatch).performClick()
            rule.waitUntil(30_000) {
                visible("Transfer details") && visible("Environment:")
            }
            assertFalse("Signed-in rTorrent detail opened phone instead",
                visible("Environment: On this phone"))
            rule.onNodeWithText("Show technical details").performScrollTo().performClick()
            assertTrue("Combined Details lost the exact authenticated rTorrent hash",
                visible("Exact infohash: $hash"))
            assertTrue("Combined Details changed the observed remote Place",
                visible("Save folder reported by engine: $expectedPlace"))
            // Reading a transfer never grants a Start/Stop/Remove authority,
            // changes its target, or performs any write.
            rule.onNodeWithText("Back to transfers").performScrollTo().performClick()
            waitTransfersCatalog()
            val after = requireNotNull(row(hash))
            assertTrue("Details altered authenticated source Place", after.directory == original.directory)
            assertTrue("Details altered authenticated transfer name", after.name == original.name)
            assertTrue("Details altered remote Start state", after.started == original.started)
            assertEquals("Details changed a remote session without authorization",
                originalHashes, readOnly { remote.listTransfers("main").map { it.hash.lowercase() }.toSet() })
            // This bounded receipt is emitted only AFTER the exact read-only
            // Details, return and nonmutation checks have all passed.
            report("chunks_real_add_app_stage", "combined-details")
        }
        for (group in groups) {
          var groupPassed = false
          for (label in group) {
            val preview = fixtures.getValue(label)
            val hash = preview.infoHashV1
            val existingName = readOnly { remote.lookupName(hash) }
            if (hash in before || existingName != null) {
                // A pre-existing session is never ours to stop or erase.
                skippedSessions[hash] = requireNotNull(existingName) to beforeRows.singleOrNull { it.hash.equals(hash, true) }
                stage(label, "already-present")
                continue
            }
            stage(label, "preflight")
            var submitted = false
            var verifiedAdded = false
            var removed = false
            var cleanupAttempted = false
            try {
                add(hash, label) { submitted = true }
                verifiedAdded = true
                stage(label, "ui-verified")
                val placeholder = requireNotNull(row(hash))
                assertTrue(placeholder.directory in setOf(parent, "$parent/${preview.name}"))
                val initialMetaFlag = (readOnly { remote.call("d.is_meta", listOf(hash)) } as Number).toInt()
                require(initialMetaFlag == 0 || initialMetaFlag == 1) { "Invalid exact metadata state" }
                if (!publicDiscovery) {
                    // The disposable controlled-peer Add always submits the
                    // isolated magnet, even after separately reviewing the
                    // publisher's HTTPS .torrent. Its exact rTorrent session
                    // must begin as a metadata placeholder (BEP9 still needs
                    // the independently supplied peer). The original public
                    // HTTPS URL is NEVER the submitted controlled source.
                    // Live/public-discovery tests retain independent behavior.
                    if (disposable) assertEquals(
                        "Controlled Add must await independently served BEP9 metadata",
                        1, initialMetaFlag)
                    if (initialMetaFlag == 0) {
                        assertEquals("Immediately resolved metadata name", preview.name, placeholder.name)
                        assertEquals("Immediately resolved metadata size", preview.sizeBytes, placeholder.sizeBytes)
                        report("chunks_real_add_app_stage", "metadata-already-resolved")
                        stage(label, "metadata-already-resolved")
                    }
                }
                assertTrue(placeholder.started)
                assertEquals(true, readOnly { remote.readActivity(hash).active })
                stage(label, "registered")
                val source = readOnly { remote.peerSource(hash) }
                val host = if (disposable) "127.0.0.1" else "5.79.112.219"
                require(source.boundAddress == host || (disposable && source.rpcHost == host))
                if (!publicDiscovery && initialMetaFlag == 1)
                    controller("/$label/metadata", source.listenPort)
                await(90, "metadata resolved on the exact row") {
                    val exact = row(hash)
                    exact?.name == preview.name && exact.sizeBytes == preview.sizeBytes &&
                        (readOnly { remote.call("d.is_meta", listOf(hash)) } as Number).toInt() == 0
                }
                stage(label, "metadata")
                class PrecompletedFixture : RuntimeException()
                fun rejectPrecompleted(sample: RemoteTrafficSample) {
                    if (sample.expectedBytes == preview.sizeBytes && preview.sizeBytes > 0 &&
                        sample.verifiedBytes == preview.sizeBytes && sample.receivedBytes == 0L) {
                        traffic(label, "initial", sample)
                        throw PrecompletedFixture()
                    }
                }
                try {
                    rejectPrecompleted(readOnly { remote.readTrafficSample(hash) })
                    if (label == "magnet0") await(30) {
                        rejectPrecompleted(readOnly { remote.readTrafficSample(hash) })
                        false
                    }
                } catch (_: PrecompletedFixture) {
                    // Remove only the just-created exact job via production UI.
                    // A failed or uncertain removal must stop rotation.
                    stage(label, "fixture-precompleted")
                    click("Back to transfers")
                    cleanupAttempted = true
                    remove(hash); removed = true
                    stage(label, "removed")
                    continue
                }
                if (!publicDiscovery) controller("/$label/payload", source.listenPort)
                try {
                    await(90, "rTorrent peer payload: active, peers, received and verified bytes") {
                        val sample = readOnly { remote.readTrafficSample(hash) }
                        rejectPrecompleted(sample)
                        sample.active == true && ((sample.peers ?: 0L) > 0L ||
                            (publicDiscovery && sample.verifiedBytes == sample.expectedBytes)) &&
                            (sample.receivedBytes ?: 0L) > 0L && (sample.verifiedBytes ?: 0L) > 0L &&
                            sample.expectedBytes == preview.sizeBytes
                    }
                } catch (_: PrecompletedFixture) {
                    stage(label, "fixture-precompleted")
                    click("Back to transfers")
                    cleanupAttempted = true
                    remove(hash); removed = true
                    stage(label, "removed")
                    continue
                } catch (timeout: AssertionError) {
                    // Prior failed live run had zero independent uploads but
                    // discarded *all* remote counters and state on timeout.
                    // Preserve only allowlisted exact-hash numbers/categories
                    // while the owned transfer still exists, before cleanup.
                    runCatching {
                        traffic(label, "initial", readOnly { remote.readTrafficSample(hash) })
                    }
                    reportReadOnlyStall(label, remote, hash)
                    throw timeout
                }
                traffic(label, "initial", readOnly { remote.readTrafficSample(hash) })
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
                if (!combinedDetailsVerified) {
                    inspectCombinedDetails(hash, preview.name, payload.directory)
                    combinedDetailsVerified = true
                }
                lifecycle(hash, false)
                // Stop is authorized only for this verified hash. It must
                // retain the user-reviewed remote Place and metadata name.
                val stoppedRow = requireNotNull(row(hash))
                assertEquals("Stop altered reviewed remote Place", payload.directory, stoppedRow.directory)
                assertEquals("Stop altered registered transfer name", preview.name, stoppedRow.name)
                stage(label, "stopped")
                val stopped = readOnly { remote.readTrafficSample(hash) }
                lifecycle(hash, true)
                // A successful Start readback cannot justify silently moving
                // the existing payload or replacing this exact session.
                val restartedRow = requireNotNull(row(hash))
                assertEquals("Restart altered reviewed remote Place", payload.directory, restartedRow.directory)
                assertEquals("Restart altered registered transfer name", preview.name, restartedRow.name)
                if (!publicDiscovery) controller("/$label/resume", source.listenPort)
                val completeBeforeRestart = publicDiscovery && stopped.verifiedBytes == stopped.expectedBytes
                if (!completeBeforeRestart) {
                    try {
                        await(90, "progress before restart") {
                            val sample = readOnly { remote.readTrafficSample(hash) }
                            (sample.receivedBytes ?: 0L) > (stopped.receivedBytes ?: 0L) &&
                                (sample.verifiedBytes ?: 0L) > (stopped.verifiedBytes ?: 0L)
                        }
                    } catch (timeout: AssertionError) {
                        // Unlike the initial peer-progress timeout, the resume
                        // checkpoint previously discarded the exact remote
                        // counters and status. Preserve only allowlisted
                        // numbers and no-path/no-tracker state classifications,
                        // while the originally observed exact job still exists.
                        // These READS are never grounds for a retry or success.
                        runCatching {
                            traffic(label, "resumed", readOnly { remote.readTrafficSample(hash) })
                        }
                        reportReadOnlyStall(label, remote, hash)
                        throw timeout
                    }
                }
                traffic(label, "resumed", readOnly { remote.readTrafficSample(hash) })
                stage(label, "restarted")
                stage(label, if (completeBeforeRestart) "completed-at-stop" else "resumed-traffic")
                cleanupAttempted = true
                remove(hash); removed = true
                stage(label, "removed")
                stage(label, "ui-controls"); stage(label, "passed")
                groupPassed = true
            } finally {
                if (submitted && !removed) {
                    // A timeout or other uncertain Add does not establish
                    // that this test created a matching session. Preserve it
                    // for read-only reconciliation; never adopt and erase it.
                    // A cleanup attempt is also never blindly repeated.
                    val current = if (cleanupAttempted || !verifiedAdded) null else row(hash)
                    if (current != null && current.directory in setOf(parent, "$parent/${preview.name}")) {
                        if (current.started) runBlocking { remote.setStarted(hash, false) }
                        await { row(hash)?.started == false && readOnly { remote.readActivity(hash).active } == false }
                        val stopped = requireNotNull(row(hash))
                        check(runBlocking { remote.eraseFromSession(hash, stopped.name, stopped.directory) } is SessionEraseOutcome.VerifiedRemoved)
                        assertEquals(before, readOnly { remote.listTransfers("main") }.map { it.hash.lowercase() }.toSet())
                        stage(label, "removed")
                    }
                    stage(label, "uncertain")
                }
            }
            if (groupPassed) break
          }
          assertTrue("All genuine candidates in a publisher group were unavailable or precompleted", groupPassed)
        }
        assertTrue("No real added rTorrent job completed Combined Details acceptance", combinedDetailsVerified)
        val run = requireNotNull(arg("chunks_acceptance_run"))
        require(run.matches(Regex("[0-9]{5,20}")))
        val negative = MessageDigest.getInstance("SHA-1").digest("chunks-peerless-$run".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        report("chunks_real_add_hash_negative", negative)
        report("chunks_real_add_negative_stage", "preflight")
        require(readOnly { remote.lookupName(negative) } == null)
        var inTransfers = false
        try {
            add(negative)
            report("chunks_real_add_negative_stage", "registered")
            rule.waitUntil(15_000) { runCatching { scroll("Operation outcome") }
                visible("Fetching magnet metadata. Waiting for peers.") }
            assertFalse(visible("Downloading payload from peers."))
            assertFalse(visible("Download complete."))
            report("chunks_real_add_negative_stage", "add-ui-truthful")
            val sample = readOnly { remote.readTrafficSample(negative) }
            assertEquals(0L, sample.receivedBytes); assertEquals(0L, sample.verifiedBytes)
            assertEquals(1, (readOnly { remote.call("d.is_meta", listOf(negative)) } as Number).toInt())
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
            assertNull(readOnly { remote.lookupName(negative) })
            report("chunks_real_add_negative_stage", "removed")
        }
        report("chunks_real_add_negative", "peerless-ui-and-cleanup-passed")
        for ((hash, snapshot) in skippedSessions) {
            assertEquals("Pre-existing session name changed", snapshot.first,
                readOnly { remote.lookupName(hash) })
            val previous = snapshot.second
            if (previous != null) {
                val current = requireNotNull(row(hash))
                assertEquals("Pre-existing session path changed", previous.directory, current.directory)
                assertEquals("Pre-existing session state changed", previous.started, current.started)
            }
        }
    }
}
