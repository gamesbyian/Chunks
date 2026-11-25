package io.github.gamesbyian.chunks

import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.model.SmartRoutingPolicyStore
import io.github.gamesbyian.chunks.model.SmartRoutingPreview
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientHashes
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.DelugeReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTorrentClients
import io.github.gamesbyian.chunks.rpc.TransmissionReadOnlyRpc
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/**
 * Installed app against one disposable Transmission, qBittorrent or Deluge
 * daemon on the CI runner. The test preloads a
 * unique stopped fixture with the real client, then drives the app's own
 * Connections form (Transmission type) → canonical Transfers → Details
 * Start → Remove (keep files), with independent client readback after each.
 * It also verifies what Add can claim after a real daemon's reply is withheld.
 */
@RunWith(AndroidJUnit4::class)
class ProtocolClientInstalledJourneyTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val args: Bundle get() = InstrumentationRegistry.getArguments()
    private fun decoded(name: String): String =
        String(Base64.decode(requireNotNull(args.getString(name)) { name }, Base64.NO_WRAP), Charsets.UTF_8)

    private fun stage(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putString("chunks_stage", name) })
    }

    private fun visible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val count = rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
            throw AssertionError("Timed out: $what ($count lazy scrollers)", failure)
        }
    }

    private fun scrollToNode(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        // Lazy lists compose rows only when scrolled to; Details is a plain
        // scrolling column. Try the lazy path, then the composed node itself.
        if (rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty())
            runCatching { rule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(matcher) }
        rule.onAllNodes(matcher).onFirst().performScrollTo()
    }

    private fun typeInto(label: String, value: String) {
        var last: Throwable? = null
        repeat(5) {
            try {
                scrollToNode(hasText(label))
                rule.waitForIdle()
                rule.onNodeWithText(label).performTextReplacement(value)
                rule.waitForIdle()
                return
            } catch (failure: AssertionError) {
                last = failure
                Thread.sleep(400)
            } catch (failure: IllegalStateException) {
                // Compose may dispose a lazy text node between scroll/lookup and
                // its focus request. No text action was dispatched; re-locate it.
                if (failure.message?.contains("visitAncestors called on an unattached node") != true)
                    throw failure
                last = failure
                Thread.sleep(400)
            }
        }
        throw AssertionError("Could not enter $label", last)
    }

    /**
     * Make "Start when added" OFF deterministically before a stopped Add: read the
     * toggle's own state, activate it once through its click action only if it is
     * on, then require it to read off. A missed coordinate tap previously left
     * Start on, so the app correctly started the job (installed runs 44428021753,
     * 44430524897: a confirmed Add, then Downloading/metaDL on readback).
     */
    private fun ensureStartWhenAddedOff() {
        val toggle = hasText("Start when added")
        fun state() = rule.onNode(toggle).fetchSemanticsNode().config
            .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState) { null }
        scrollToNode(toggle)
        rule.waitForIdle()
        if (state() == androidx.compose.ui.state.ToggleableState.On)
            rule.onNode(toggle).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Start when added is off", 10_000) {
            runCatching { state() == androidx.compose.ui.state.ToggleableState.Off }.getOrDefault(false)
        }
    }

    private fun artworkChoiceIs(expected: androidx.compose.ui.state.ToggleableState) {
        val matcher = hasTestTag(ChunksSemantics.ADD_ARTWORK_CHOICE)
        waitFor("per-Add artwork choice available") {
            runCatching {
                scrollToNode(matcher)
                true
            }.getOrDefault(false)
        }
        rule.waitForIdle()
        val state = rule.onNode(matcher).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState]
        assertEquals("per-Add artwork choice", expected, state)
    }

    private fun showArtworkChoice() {
        val matcher = hasTestTag(ChunksSemantics.ADD_ARTWORK_CHOICE)
        if (rule.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            val disclosure = hasText("Artwork & metadata")
            waitFor("artwork disclosure") {
                runCatching { scrollToNode(disclosure); true }.getOrDefault(false)
            }
            val node = rule.onNode(disclosure)
            if (node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "Collapsed")
                node.performSemanticsAction(SemanticsActions.OnClick)
        }
    }

    private val addJourney get() = InstalledUniversalAddJourney(rule)
    private fun receiptVerified() = addJourney.hasReceiptState("Verified")

    /** Bounded, credential-free panel state for a control that never became enabled (#2109). */
    private fun operationPanelState(): String {
        val status = runCatching {
            rule.onAllNodes(hasTestTag(ChunksSemantics.PROTOCOL_OPERATION_STATUS), useUnmergedTree = true)
                .fetchSemanticsNodes().joinToString(" | ") { node ->
                    node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
                        ?.joinToString(" ") { it.text }.orEmpty()
                }
        }.getOrDefault("unreadable")
        return "status=[" + status.take(240) + "] paused=" +
            visible("Commands are paused until a read-only check.")
    }

    /** One dispatch of a control, after polling only until it is enabled. */
    private fun clickOnce(tag: String, what: String, timeoutMs: Long = 30_000) {
        val matcher = hasTestTag(tag)
        try {
            waitFor("enabled $what", timeoutMs) {
                runCatching {
                    scrollToNode(matcher)
                    rule.onNode(matcher, useUnmergedTree = true).assertIsEnabled()
                    true
                }.getOrDefault(false)
            }
        } catch (timeout: AssertionError) {
            // Captured at the moment of the timeout, not before waiting.
            throw AssertionError("${timeout.message}; ${operationPanelState()}", timeout)
        }
        // A lazy row can be disposed between the check and the tap (IME,
        // insets). "No node" fails BEFORE any click is dispatched, so
        // re-locating is safe; an accepted click is never repeated.
        var last: AssertionError? = null
        repeat(5) {
            try {
                runCatching { scrollToNode(matcher) }
                rule.waitForIdle()
                rule.onNode(matcher, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick) {
                    assertTrue("$what must accept the click", it())
                }
                return
            } catch (missing: AssertionError) {
                if (missing.message?.contains("could not find any node") != true) throw missing
                last = missing
                Thread.sleep(300)
            }
        }
        throw AssertionError("Could not locate $what to click", last)
    }

    private fun connect(kind: RemoteBackendKind, appUrl: String, user: String, pass: String) {
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_CONNECTIONS).performClick()
        waitFor("Connections") {
            runCatching { scrollToNode(hasTestTag(ChunksSemantics.CONNECTION_ADD)); true }.getOrDefault(false)
        }
        rule.openConnectionFormIfNeeded()
        when (kind) {
            RemoteBackendKind.TRANSMISSION -> {
                clickOnce("connection-backend-transmission", "Transmission type")
                typeInto("Transmission RPC URL", appUrl)
                typeInto("Username, if required", user)
            }
            RemoteBackendKind.QBITTORRENT -> {
                clickOnce("connection-backend-qbittorrent", "qBittorrent type")
                typeInto("qBittorrent WebUI root URL", appUrl)
                typeInto("Username, if required", user)
            }
            RemoteBackendKind.DELUGE -> {
                clickOnce("connection-backend-deluge", "Deluge type")
                typeInto("Deluge Web JSON URL", appUrl)
            }
            else -> error("rTorrent has its own installed journeys")
        }
        typeInto("Password", pass)
        clickOnce(ChunksSemantics.CONNECTION_CONNECT, "Connect")
        stage("connect-dispatched-once")
    }

    private fun fixture(tag: String): TorrentMetainfoBuilder.Result {
        val random = SecureRandom()
        val name = "chunks-installed-$tag-" + java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36)
        val bytes = ByteArray(64 * 1024).also(random::nextBytes)
        return TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            listOf(TorrentMetainfoBuilder.Entry(listOf(name), bytes.size.toLong()) { bytes.inputStream() }),
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024))
    }

    @Test fun protocolClientConnectTransfersDetailsAddAndRemove() {
        assumeTrue("Disposable runner daemon only", args.getString("chunks_protocol_disposable") == "authorized")
        val kind = io.github.gamesbyian.chunks.model.RemoteBackendKind.valueOf(decoded("chunks_protocol_kind_b64"))
        val url = decoded("chunks_protocol_url_b64")
        val user = decoded("chunks_protocol_user_b64")
        val pass = decoded("chunks_protocol_pass_b64")
        val credentials = RemoteCredentials(url,
            if (kind == io.github.gamesbyian.chunks.model.RemoteBackendKind.DELUGE) "" else user, pass)
        val daemon = if (kind == io.github.gamesbyian.chunks.model.RemoteBackendKind.DELUGE) runBlocking {
            io.github.gamesbyian.chunks.rpc.DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds().single()
        } else null
        val client = io.github.gamesbyian.chunks.rpc.RemoteTorrentClients.create(kind, credentials, daemon)
        val witness = ClientOperations(client, 40, 500)
        // The fixture owner and the installed app establish separate Web UI
        // sessions. Always use a NEW independent authenticated connection for
        // post-app readback, not the potentially stale fixture-preload cookie.
        // This remains real-daemon evidence and never replays the app's write.
        suspend fun independentRead(hash: String) =
            io.github.gamesbyian.chunks.rpc.RemoteTorrentClients.create(kind, credentials, daemon).torrent(hash)
        stage("client-" + kind.name.lowercase())

        /** Two unique files in one folder; returns the metainfo and the shared file-name suffix. */
        fun multiFileFixture(): Pair<TorrentMetainfoBuilder.Result, String> {
            val random = SecureRandom()
            val suffix = java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36) + ".bin"
            val folder = "chunks-installed-choices-" + suffix.removeSuffix(".bin")
            val a = ByteArray(48 * 1024).also(random::nextBytes)
            val b = ByteArray(48 * 1024).also(random::nextBytes)
            return TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(folder, listOf(
                TorrentMetainfoBuilder.Entry(listOf("keep-$suffix"), a.size.toLong()) { a.inputStream() },
                TorrentMetainfoBuilder.Entry(listOf("skip-$suffix"), b.size.toLong()) { b.inputStream() }),
                trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024)) to suffix
        }
        /** Three episode files deliberately NOT in metainfo order. */
        fun episodeFixture(): TorrentMetainfoBuilder.Result {
            val random = SecureRandom()
            val folder = "chunks-installed-fio-" + java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36)
            val entries = listOf("Show S01E03.mkv", "Show S01E01.mkv", "Show S01E02.mkv").map { name ->
                val bytes = ByteArray(24 * 1024).also(random::nextBytes)
                TorrentMetainfoBuilder.Entry(listOf(name), bytes.size.toLong()) { bytes.inputStream() }
            }
            return TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(folder, entries,
                trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024))
        }
        val preload = fixture("details")
        val name = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(preload.torrent).name
        val hash = preload.infoHashV1
        val added = runBlocking {
            witness.add(ClientAddRequest(ClientAddSource.TorrentFile(preload.torrent), null, startNow = false))
        }
        assertTrue("Fixture preload: $added", added is ClientOperationOutcome.Verified)
        stage("fixture-preloaded-stopped")

        ActivityScenario.launch(MainActivity::class.java).use {
            connect(kind, url, user, pass)

            waitFor("fixture row in canonical Transfers", 90_000) {
                runCatching { scrollToNode(hasText(name, substring = true)); true }.getOrDefault(false) && visible(name)
            }
            stage("transfers-shows-fixture")

            val details = hasContentDescription("Details for $name", substring = true)
            waitFor("Details control") {
                runCatching { scrollToNode(details); true }.getOrDefault(false)
            }
            rule.onAllNodes(details).onFirst().performClick()
            stage("details-open")

            clickOnce(ChunksSemantics.PROTOCOL_START_STOP, "Start", 60_000)
            waitFor("Start confirmed", 60_000) { visible("Start: confirmed") }
            val started = runBlocking { independentRead(hash) }
            assertTrue("Independent readback: started", started != null &&
                started.runState != ClientRunState.STOPPED)
            stage("start-verified-independently")

            clickOnce(ChunksSemantics.PROTOCOL_REMOVE, "Remove…", 60_000)
            clickOnce(ChunksSemantics.PROTOCOL_REMOVE_CONFIRM, "Remove, keep files")
            waitFor("Remove confirmed", 60_000) { visible("Remove: confirmed") }
            assertNull("Independent readback: removed", runBlocking { independentRead(hash) })
            stage("remove-verified-independently")

            // Universal Add to this exact client, stopped, from a magnet.
            val profile = RememberedRemoteProtocolProfiles(InstrumentationRegistry.getInstrumentation().targetContext)
                .list().single { it.backend == kind }
            val profileId = profile.id
            val second = fixture("add")
            val magnet = requireNotNull(second.magnet)
            val routingForAdd = SmartRoutingPolicyStore(
                InstrumentationRegistry.getInstrumentation().targetContext)
            val previousRoutingForAdd = routingForAdd.read() as SmartRoutingPolicyStore.Read.Ready
            val liveRule = SmartRoutingPreview.Rule("installed-add-live-client", 1,
                SmartRoutingPreview.Kind.TV, 60, profileId)
            assertEquals(SmartRoutingPolicyStore.Save.SAVED,
                routingForAdd.save(previousRoutingForAdd.revision,
                    SmartRoutingPolicyStore.Policy(enabled = true, rules = listOf(liveRule))))
            // Details may hide the footer: go back to Transfers first.
            if (rule.onAllNodes(hasTestTag(ChunksSemantics.FOOTER_ADD)).fetchSemanticsNodes().isEmpty()) {
                it.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
            }
            waitFor("Add footer") { rule.onAllNodes(hasTestTag(ChunksSemantics.FOOTER_ADD)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
            waitFor("Add source for live Smart Routing", 30_000) {
                runCatching { scrollToNode(hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD)); true }.getOrDefault(false)
            }
            val routingMagnet = "magnet:?xt=urn:btih:${second.infoHashV1}&dn=Show.S01E02.mkv"
            rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).performTextReplacement(routingMagnet)
            val routingSuggestion = hasTestTag("chunks.add.routing.suggestion")
            waitFor("real client offers its own checked default", 60_000) {
                runCatching {
                    scrollToNode(routingSuggestion)
                    rule.onAllNodes(routingSuggestion and hasText(profile.label, substring = true))
                        .fetchSemanticsNodes().size == 1
                }.getOrDefault(false)
            }
            assertNull("Read-only routing may not Add",
                runBlocking { independentRead(second.infoHashV1) })
            clickOnce("chunks.add.routing.use", "exact-client routing suggestion")
            waitFor("routing selected the exact live protocol client", 30_000) {
                runCatching {
                    scrollToNode(hasTestTag("chunks.add.target.protocol.$profileId"))
                    rule.onNodeWithTag("chunks.add.target.protocol.$profileId").assertIsSelected()
                    true
                }.getOrDefault(false)
            }
            assertNull("Selecting a destination is only draft state",
                runBlocking { independentRead(second.infoHashV1) })
            stage("smart-routing-exact-live-default-no-write")
            val refreshedRoutingForAdd = routingForAdd.read() as SmartRoutingPolicyStore.Read.Ready
            assertEquals(SmartRoutingPolicyStore.Save.SAVED,
                routingForAdd.save(refreshedRoutingForAdd.revision, previousRoutingForAdd.policy))
            waitFor("protocol Add review") {
                runCatching { scrollToNode(hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD)); true }.getOrDefault(false)
            }
            rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).performTextReplacement(magnet)
            rule.waitForIdle()
            ensureStartWhenAddedOff()
            showArtworkChoice()
            artworkChoiceIs(androidx.compose.ui.state.ToggleableState.On)
            rule.onNodeWithTag(ChunksSemantics.ADD_ARTWORK_CHOICE).performSemanticsAction(SemanticsActions.OnClick)
            artworkChoiceIs(androidx.compose.ui.state.ToggleableState.Off)
            stage("add-artwork-opt-out-selected")
            addJourney.submit()
            stage("add-dispatched-once")
            val submitting = addJourney.hasReceiptState("Adding")
            if (submitting) {
                addJourney.activate(ChunksSemantics.ADD_BACK_TO_TRANSFERS)
                addJourney.awaitTransferReceipt("Adding", "Verified")
            }
            it.recreate()
            if (submitting) {
                addJourney.awaitTransferReceipt("Adding", "Verified")
                addJourney.activate(ChunksSemantics.TRANSFERS_ADD_REVIEW + "." + profileId)
                stage("transfer-receipt-kept-across-recreation")
            }
            waitFor("Add verified (shared receipt)", 90_000) { receiptVerified() }
            // The attempt is app-owned like Local and rTorrent: Activity recreation must
            // keep this exact receipt and must not re-enable a second Add of the same source.
            it.recreate()
            waitFor("receipt kept across recreation", 30_000) { receiptVerified() }
            stage("add-receipt-kept-across-recreation")
            // Newly registered magnets may briefly report a queue/check state while
            // the daemon settles the explicitly requested Start-off option. Never
            // accept QUEUED as STOPPED: it can become runnable without permission.
            var observed = runBlocking { independentRead(second.infoHashV1) }
            val settleUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
            while (observed?.runState != ClientRunState.STOPPED &&
                System.nanoTime() < settleUntil) {
                Thread.sleep(500)
                observed = runBlocking { independentRead(second.infoHashV1) }
            }
            assertTrue("Independent readback: added stopped ($observed)", observed != null &&
                observed.runState == ClientRunState.STOPPED)
            // A fleeting paused observation cannot prove a stable stopped Add.
            Thread.sleep(1_500)
            val settled = runBlocking { independentRead(second.infoHashV1) }
            assertTrue("Independent settled readback: remained stopped ($settled)",
                settled?.runState == ClientRunState.STOPPED)
            stage("add-stopped-verified-independently")
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val automation = io.github.gamesbyian.chunks.media.FileMediaAutomationPreferences(
                java.io.File(appContext.filesDir, "media/automation.json"))
            fun artworkExcluded(environment: io.github.gamesbyian.chunks.model.EnvironmentId) = automation.isExcluded(
                io.github.gamesbyian.chunks.media.MediaTransferKey.of(
                    io.github.gamesbyian.chunks.model.UnifiedTransferKey.of(environment, second.infoHashV1)))
            assertTrue("Opt-out persisted for the exact protocol profile and hash",
                artworkExcluded(io.github.gamesbyian.chunks.model.EnvironmentId(profileId)))
            assertTrue("The same hash on Local is unaffected by the protocol opt-out",
                !artworkExcluded(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL))
            stage("add-exact-profile-artwork-opt-out-persisted")


            // View transfer from Add must locate the exact hash on the selected
            // protocol client, rather than merely opening that client's inventory.
            addJourney.viewExactTransfer(profileId, second.infoHashV1)
            addJourney.awaitTransferReceipt("Verified")
            stage("add-view-exact-hash-navigation")

            // Start a separate review and deliberately request an existing hash.
            // This must refuse while the exact independently witnessed job stays stopped.
            addJourney.activate(ChunksSemantics.TRANSFERS_ADD_REVIEW + "." + profileId)
            addJourney.activate(ChunksSemantics.ADD_ANOTHER)
            typeInto(UniversalAddSourceText.FIELD_LABEL, magnet)
            ensureStartWhenAddedOff()
            addJourney.submit()
            waitFor("Duplicate hash refused") {
                addJourney.hasReceiptState("Not added") && visible("Already in this client")
            }
            assertEquals("Duplicate refusal must keep the existing job stopped", ClientRunState.STOPPED,
                runBlocking { independentRead(second.infoHashV1) }?.runState)
            stage("add-another-duplicate-refused")
            addJourney.activate(ChunksSemantics.FOOTER_TRANSFERS)

            // Cached remote and live Local rows may temporarily have the same
            // original name and hash. Open the exact owner before each move.
            val rowTag = ChunksSemantics.TRANSFERS_ROW_DETAILS
            val localId = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL.value
            clickOnce("$rowTag.$profileId.${second.infoHashV1}", "exact remote job Details", 90_000)
            clickOnce(ChunksSemantics.PROTOCOL_MOVE, "Copy or move", 60_000)
            clickOnce(ChunksSemantics.PROTOCOL_MOVE + "." + localId, "Copy job to This device")
            stage("move-to-device-dispatched-once")
            waitFor("job copied to This device", 90_000) { visible("Job copied to This device") }
            val local = requireNotNull(LocalEngineHolder.current()) { "Local engine not started by the Move" }
            assertTrue("Independent Local readback: job registered", local.status(second.infoHashV1) != null)
            stage("move-copy-verified-on-device")
            clickOnce(ChunksSemantics.PROTOCOL_MOVE_RETIRE, "Finish move")
            waitFor("source retired", 60_000) { runBlocking { client.torrent(second.infoHashV1) } == null }
            stage("move-source-retired-independently")
            assertTrue("Local copy kept after retiring the source", local.status(second.infoHashV1) != null)

            // And back: the Local job's own Details move-only panel → the same remote client.
            rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
            // The list may be scoped to the remote client; show This device as a person would.
            val hiddenLocal = hasContentDescription("Hidden: On this device", substring = true)
            if (rule.onAllNodes(hiddenLocal).fetchSemanticsNodes().isNotEmpty())
                rule.onAllNodes(hiddenLocal).onFirst().performClick()
            clickOnce("$rowTag.$localId.${second.infoHashV1}", "exact Local job Details", 90_000)
            clickOnce(ChunksSemantics.PROTOCOL_MOVE, "Copy or move (from this device)", 60_000)
            clickOnce(ChunksSemantics.PROTOCOL_MOVE + "." + profileId, "Copy job back to the remote client")
            stage("move-from-device-dispatched-once")
            waitFor("job copied back to the remote client", 90_000) { visible("Job copied to") && !visible("Job copied to This device") }
            assertTrue("Independent remote readback: job registered again",
                runBlocking { client.torrent(second.infoHashV1) } != null)
            stage("move-back-copy-verified-on-remote")
            clickOnce(ChunksSemantics.PROTOCOL_MOVE_RETIRE, "Finish move from this device")
            waitFor("local job retired", 60_000) { local.status(second.infoHashV1) == null }
            stage("move-back-local-retired-independently")
            runBlocking { witness.remove(second.infoHashV1, deleteData = false) }

            // Universal Add of a reviewed multi-file .torrent (Open With, as a person
            // would) to this exact client with ONE file unticked, stopped. The real
            // daemon must show that file skipped and the other one wanted.
            val choices = multiFileFixture()
            val kept = "keep-" + choices.second
            val skipped = "skip-" + choices.second
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val tree = io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider.treeUri(context)
            val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val document = requireNotNull(android.provider.DocumentsContract.createDocument(
                context.contentResolver, root, "application/x-bittorrent", "chunks-choices.torrent"))
            requireNotNull(context.contentResolver.openOutputStream(document, "wt")).use {
                out -> out.write(choices.first.torrent)
            }
            // ActivityScenario tracks its launch identity; a genuine onNewIntent replaces it.
            var launchIntent: android.content.Intent? = null
            it.onActivity { activity -> launchIntent = activity.intent }
            it.onActivity { activity ->
                activity.startActivity(android.content.Intent(activity, MainActivity::class.java).apply {
                    action = android.content.Intent.ACTION_VIEW
                    setDataAndType(document, "application/x-bittorrent")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
            }
            stage("choices-torrent-opened")
            val targetChip = hasTestTag("chunks.add.target.protocol.$profileId")
            waitFor("Add after Open With", 30_000) {
                rule.onAllNodes(targetChip).fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodes(hasTestTag(ChunksSemantics.FOOTER_ADD)).fetchSemanticsNodes().isNotEmpty()
            }
            if (rule.onAllNodes(targetChip).fetchSemanticsNodes().isEmpty())
                rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
            clickOnce("chunks.add.target.protocol.$profileId", "Add target chip (file choices)", 30_000)
            // The earlier magnet draft is still there: the app asks before replacing it
            // (neither choice adds anything). Choose the new source once, as a person would.
            val choicesName = choices.first.let {
                io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(it.torrent).name
            }
            waitFor("shared .torrent offered or adopted", 30_000) {
                visible("Use new source") || visible(choicesName)
            }
            if (visible("Use new source")) {
                scrollToNode(hasText("Use new source"))
                rule.onNodeWithText("Use new source").performClick()
                stage("choices-new-source-accepted")
            }
            // A blank source field keeps the reviewed .torrent (only text replaces it).
            waitFor("protocol Add source") {
                runCatching { scrollToNode(hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD)); true }.getOrDefault(false)
            }
            rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).performTextReplacement("")
            rule.waitForIdle()
            waitFor("reviewed .torrent in protocol Add", 30_000) { visible("Using $choicesName") }
            clickOnce(ChunksSemantics.ADD_FILE_CHOICE, "Files to download", 30_000)
            waitFor("both reviewed files listed") {
                runCatching { scrollToNode(hasText(skipped)); true }.getOrDefault(false) && visible(kept)
            }
            // The row's own toggle action, not a coordinate tap: clearing the source
            // field above can move the list while the keyboard settles (e56c116).
            rule.onNodeWithText(skipped).performSemanticsAction(SemanticsActions.OnClick)
            waitFor("one of two files chosen") { visible("Files to download (1 of 2") }
            stage("choices-one-file-unticked")
            // Add-time priority: raise the kept file to High (every client stores High).
            val keptIndex = requireNotNull(AddFileChoices.declared(
                io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(choices.first.torrent)))
                .single { f -> f.path.endsWith(kept) }.index
            val keptPriority = ChunksSemantics.ADD_FILE_PRIORITY + "." + keptIndex
            clickOnce(keptPriority, "kept file priority", 30_000)
            waitFor("kept file raised to High") {
                runCatching { rule.onNodeWithTag(keptPriority).assertTextEquals("High"); true }.getOrDefault(false)
            }
            stage("choices-kept-file-high")
            LazyComposeActions.activateOnce(rule, hasText("Change folder"))
            val customFolder = decoded("chunks_protocol_custom_folder_b64")
            typeInto("Absolute folder on ${profile.label}", customFolder)
            stage("choices-custom-folder-reviewed")
            // A Chunks-only display name for this Add: stored for this exact profile only
            // after the verified NEW registration, never sent to the client.
            val choicesAlias = "Chunks alias " + kind.name.lowercase()
            scrollToNode(hasText("Optional display name"))
            rule.onNodeWithText("Optional display name").performSemanticsAction(SemanticsActions.OnClick)
            typeInto("Display name in Chunks", choicesAlias)
            stage("choices-display-name-entered")
            ensureStartWhenAddedOff()
            showArtworkChoice()
            artworkChoiceIs(androidx.compose.ui.state.ToggleableState.On)
            stage("next-add-artwork-default-checked")
            addJourney.submit()
            stage("choices-add-dispatched-once")
            val choicesHash = choices.first.infoHashV1
            // Independent readback is the authority: the job exists, stays stopped,
            // and the daemon's own file priorities match the review exactly.
            waitFor("file choices applied on the real client", 90_000) {
                val files = runCatching {
                    runBlocking {
                        io.github.gamesbyian.chunks.rpc.RemoteTorrentClients.create(kind, credentials, daemon)
                            .files(choicesHash)
                    }
                }.getOrNull().orEmpty()
                files.size == 2 &&
                    files.single { f -> f.path.endsWith(skipped) }.priority ==
                        io.github.gamesbyian.chunks.rpc.ClientFilePriority.SKIP &&
                    files.single { f -> f.path.endsWith(kept) }.priority ==
                        io.github.gamesbyian.chunks.rpc.ClientFilePriority.HIGH
            }
            waitFor("Add receipt verified", 60_000) { receiptVerified() }
            val customJob = runBlocking { independentRead(choicesHash) }
            assertEquals("Add with file choices stayed stopped", ClientRunState.STOPPED, customJob?.runState)
            assertTrue("Exact custom folder independently verified",
                io.github.gamesbyian.chunks.rpc.ClientHashes.sameDirectory(customJob?.downloadDirectory, customFolder))
            stage("choices-skip-and-high-verified-independently")
            assertEquals("display name stored for this exact profile", choicesAlias,
                TransferAliasStore(InstrumentationRegistry.getInstrumentation().targetContext)
                    .get(ProtocolAliasScope.of(profileId), choicesHash))
            rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
            // View transfer's injected hash is cleared when a new Add begins.
            // Otherwise this second, independently verified job stays hidden.
            waitFor("previous Add navigation search cleared", 30_000) {
                rule.onAllNodes(hasText("Clear search")).fetchSemanticsNodes().isEmpty()
            }
            waitFor("canonical Transfers shows the Chunks display name", 90_000) {
                runCatching { scrollToNode(hasText(choicesAlias, substring = true)); true }.getOrDefault(false)
            }
            stage("choices-display-name-shown-in-transfers")
            runBlocking { witness.remove(choicesHash, deleteData = false) }
            // Restore only the test monitor's identity after all assertions (as the
            // Open With handoff tests do); the shared source is never redispatched.
            it.onActivity { activity -> launchIntent?.let { original -> activity.intent = original } }

            // Finish in Order from this client's Details on a real preloaded episode job:
            // review the detected order once; the real daemon must show E01 at High.
            val episodes = episodeFixture()
            val episodesName = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(episodes.torrent).name
            val episodesHash = episodes.infoHashV1
            val preloaded = runBlocking {
                witness.add(ClientAddRequest(ClientAddSource.TorrentFile(episodes.torrent), null, startNow = false))
            }
            assertTrue("Episode fixture preload: $preloaded", preloaded is ClientOperationOutcome.Verified)
            stage("fio-fixture-preloaded")
            val routing = SmartRoutingPolicyStore(context)
            val originalRouting = routing.read() as SmartRoutingPolicyStore.Read.Ready
            val episodeRule = SmartRoutingPreview.Rule("episodes", 1, SmartRoutingPreview.Kind.TV, 60, localId)
            assertEquals(SmartRoutingPolicyStore.Save.SAVED, routing.save(originalRouting.revision,
                SmartRoutingPolicyStore.Policy(enabled = true, rules = listOf(episodeRule))))
            if (rule.onAllNodes(hasTestTag(ChunksSemantics.FOOTER_TRANSFERS)).fetchSemanticsNodes().isEmpty()) {
                it.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
            }
            rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
            val episodesDetails = hasContentDescription("Details for $episodesName", substring = true)
            waitFor("episode job row", 90_000) {
                runCatching { scrollToNode(episodesDetails); true }.getOrDefault(false)
            }
            rule.onAllNodes(episodesDetails).onFirst().performClick()
            clickOnce(ChunksSemantics.PROTOCOL_MOVE, "Copy or move episode job")
            val advice = hasTestTag(ChunksSemantics.PROTOCOL_MOVE_SUGGESTION)
            waitFor("the files suggest the observed Local client") {
                rule.onAllNodes(advice and hasText("This device", substring = true))
                    .fetchSemanticsNodes().size == 1
            }
            assertNull("Advice must never register a Local job", local.status(episodesHash))
            assertEquals("Advice must keep the source stopped", ClientRunState.STOPPED,
                runBlocking { independentRead(episodesHash) }?.runState)
            stage("move-suggestion-live-exact-client-no-write")
            clickOnce(ChunksSemantics.PROTOCOL_MOVE, "Hide copy or move")
            val currentRouting = routing.read() as SmartRoutingPolicyStore.Read.Ready
            assertEquals(SmartRoutingPolicyStore.Save.SAVED, routing.save(currentRouting.revision, originalRouting.policy))
            waitFor("protocol Files control", 60_000) {
                runCatching { scrollToNode(hasText("Files")); rule.onNodeWithText("Files").assertIsEnabled(); true }
                    .getOrDefault(false)
            }
            rule.onNodeWithText("Files").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("Finish in Order entry", 60_000) {
                runCatching { scrollToNode(hasText("Finish in Order…")); true }.getOrDefault(false)
            }
            rule.onNodeWithText("Finish in Order…").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("detected episode order", 30_000) {
                runCatching { scrollToNode(hasText("Start Finish in Order")); true }.getOrDefault(false) &&
                    visible("Found an episode order")
            }
            rule.onNodeWithText("Start Finish in Order").performSemanticsAction(SemanticsActions.OnClick)
            stage("fio-start-dispatched-once")
            val firstEpisode = "Show S01E01.mkv"
            waitFor("E01 at High on the real client", 90_000) {
                val files = runCatching {
                    runBlocking {
                        io.github.gamesbyian.chunks.rpc.RemoteTorrentClients.create(kind, credentials, daemon)
                            .files(episodesHash)
                    }
                }.getOrNull().orEmpty()
                files.size == 3 && files.single { f -> f.path.endsWith(firstEpisode) }.priority ==
                    io.github.gamesbyian.chunks.rpc.ClientFilePriority.HIGH &&
                    files.filter { f -> !f.path.endsWith(firstEpisode) }
                        .none { f -> f.priority == io.github.gamesbyian.chunks.rpc.ClientFilePriority.HIGH }
            }
            assertEquals("Finish in Order never starts a stopped job", ClientRunState.STOPPED,
                runBlocking { independentRead(episodesHash) }?.runState)
            stage("fio-e01-high-verified-independently")
            runBlocking { witness.remove(episodesHash, deleteData = false) }

            // Observe the same open Move review before and after live BEP 9 metadata.
            // Run this Local-to-remote transition once in the shared five-client campaign.
            if (kind == RemoteBackendKind.TRANSMISSION) {
                val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "move-metadata-").toFile()
                val publisher = File(root, "publisher").apply { check(mkdir()) }
                val receiver = File(root, "receiver").apply { check(mkdir()) }
                val title = "Chunks Metadata Fixture S01E02.mkv"
                val payload = ByteArray(32 * 1024).also(SecureRandom()::nextBytes)
                File(publisher, title).writeBytes(payload)
                val definition = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(title,
                    listOf(TorrentMetainfoBuilder.Entry(listOf(title), payload.size.toLong()) { payload.inputStream() }),
                    trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024))
                val lateHash = definition.infoHashV1
                val savedRouting = routing.read() as SmartRoutingPolicyStore.Read.Ready
                val ruleForRemote = SmartRoutingPreview.Rule("late", 1, SmartRoutingPreview.Kind.TV, 60, profileId)
                assertEquals(SmartRoutingPolicyStore.Save.SAVED, routing.save(savedRouting.revision,
                    SmartRoutingPolicyStore.Policy(enabled = true, rules = listOf(ruleForRemote))))
                try {
                    LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
                        allowMultipleConnectionsPerIp = true, enableDht = false)).use { publisherEngine ->
                        publisherEngine.start()
                        assertTrue(publisherEngine.addStopped(definition.torrent, publisher) is LocalAddResult.AddedStopped)
                        assertEquals(LocalCommandOutcome.VERIFIED, publisherEngine.resume(lateHash).outcome)
                        waitFor("independently verified publisher seed") {
                            publisherEngine.status(lateHash)?.state == LocalTransferState.SEEDING
                        }
                        val magnetWithoutPeer = requireNotNull(definition.magnet)
                        assertTrue(local.addMagnet(magnetWithoutPeer, receiver) is LocalAddResult.FetchingMetadata)
                        addJourney.activate(ChunksSemantics.FOOTER_TRANSFERS)
                        clickOnce("$rowTag.$localId.$lateHash", "exact metadata-pending Local Details", 90_000)
                        clickOnce(ChunksSemantics.PROTOCOL_MOVE, "Open metadata-pending job Move review")
                        assertEquals(false, local.status(lateHash)?.metadataKnown)
                        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_SUGGESTION).assertDoesNotExist()
                        assertNull(runBlocking { independentRead(lateHash) })
                        stage("move-late-metadata-pending-no-advice")

                        assertTrue(local.connectPeer(lateHash, InetSocketAddress("127.0.0.1", publisherEngine.listenPort())))
                        waitFor("peer metadata and stopped Local readback") {
                            local.status(lateHash)?.let { it.metadataKnown && it.stopped } == true
                        }
                        waitFor("same open Move review suggests the Transmission destination") {
                            rule.onAllNodes(advice).fetchSemanticsNodes().size == 1
                        }
                        assertNull("Advice leaves the remote inventory unchanged", runBlocking { independentRead(lateHash) })
                        assertEquals(0L, local.status(lateHash)?.payloadDownloadedBytes)
                        stage("move-late-metadata-advice-no-write")
                    }
                } finally {
                    if (local.status(lateHash) != null) assertTrue(local.removeKeepingData(lateHash))
                    val latestRouting = routing.read() as SmartRoutingPolicyStore.Read.Ready
                    assertEquals(SmartRoutingPolicyStore.Save.SAVED, routing.save(latestRouting.revision, savedRouting.policy))
                }
            }

            // Smart Seeding through the installed UI: this client's default upload limit and its
            // explicit "Apply while Chunks is open" switch. A transfer added after activation gets
            // the client's own per-transfer cap, read back from the real daemon (no peers needed).
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val profileLabel = RememberedRemoteProtocolProfiles(targetContext).list().single { p -> p.backend == kind }.label
            val seedingEnvironment = io.github.gamesbyian.chunks.seeding.RemoteSeeding.protocolEnvironment(profileId)
            val switch = io.github.gamesbyian.chunks.seeding.RemoteSeeding.EnforcementSwitch(targetContext)
            try {
                rule.onNodeWithTag(ChunksSemantics.FOOTER_OPTIONS).performClick()
                clickOnce(OptionsCategory.SEEDING.tag, "Seeding options category")
                val seedingRules = hasText("Seeding rules…") and androidx.compose.ui.test.hasClickAction()
                waitFor("Seeding rules entry") { runCatching { scrollToNode(seedingRules); true }.getOrDefault(false) }
                rule.onNode(seedingRules).performSemanticsAction(SemanticsActions.OnClick)
                val reviewDefaults = hasText("Review defaults", substring = true)
                waitFor("Seeding rules screen") { runCatching { scrollToNode(reviewDefaults); true }.getOrDefault(false) }
                rule.onNode(reviewDefaults).performSemanticsAction(SemanticsActions.OnClick)
                if (visible("Find client defaults")) typeInto("Find client defaults", profileLabel)
                waitFor("this client's default") { runCatching { scrollToNode(hasText("Change default")); true }.getOrDefault(false) }
                assertEquals("only this client's default is shown", 1,
                    rule.onAllNodesWithText("Change default").fetchSemanticsNodes().size)
                rule.onNodeWithText("Change default").performSemanticsAction(SemanticsActions.OnClick)
                typeInto("Upload limit per transfer (blank: none), e.g. 24 KiB/s", "128 kB/s")
                scrollToNode(hasText("Save default"))
                rule.onNodeWithText("Save default").performSemanticsAction(SemanticsActions.OnClick)
                waitFor("default saved") { visible("Saved.") }
                assertEquals(false, switch.enabled(seedingEnvironment))
                clickOnce(ChunksSemantics.SEEDING_REMOTE_ENFORCEMENT, "Apply while Chunks is open")
                waitFor("switch saved on") { switch.enabled(seedingEnvironment) }
                stage("seeding-switch-on-via-ui")
                // The app's 30 s Smart Seeding loop is a LaunchedEffect delay, which under this
                // (v2) Compose rule runs on the virtual main clock: advance it explicitly, one
                // tick at a time, and let each tick's real client IO finish before the next.
                fun oneTick() {
                    rule.mainClock.advanceTimeBy(31_000)
                    rule.waitForIdle()
                    Thread.sleep(2_000)
                }
                val assignments = io.github.gamesbyian.chunks.seeding.PreferencesAssignmentStore(targetContext, seedingEnvironment)
                // The first tick records activation: transfers present then stay unmanaged.
                for (attempt in 0 until 10) { if (assignments.epoch() != null) break; oneTick() }
                assertTrue("the activation tick ran", assignments.epoch() != null)
                stage("seeding-activation-recorded")
                val capped = fixture("seeding-cap")
                assertTrue(runBlocking {
                    witness.add(ClientAddRequest(ClientAddSource.TorrentFile(capped.torrent), null, startNow = false))
                } is ClientOperationOutcome.Verified)
                fun cap() = runBlocking {
                    runCatching {
                        io.github.gamesbyian.chunks.rpc.RemoteTorrentClients.create(kind, credentials, daemon)
                            .uploadCapBytesPerSecond(capped.infoHashV1)
                    }.getOrNull()
                }
                for (attempt in 0 until 10) { if (cap() == 128_000L) break; oneTick() }
                assertEquals("the client's own per-transfer cap set by the rule", 128_000L, cap())
                stage("seeding-cap-applied-and-read-back")
                runBlocking { witness.remove(capped.infoHashV1, deleteData = false) }
            } finally {
                // Leave the disposable profile's opt-in off for any later run.
                switch.set(seedingEnvironment, false)
            }

        }
        val profiles = RememberedRemoteProtocolProfiles(InstrumentationRegistry.getInstrumentation().targetContext).list()
        assertEquals("The verified login was saved once", 1,
            profiles.count { it.credentials.endpoint == url && it.backend == kind })
        stage("profile-saved")
    }

    // ---- #2026 retained typed-Stop receipt across process death ----------
    // Phase 1 and phase 2 run as separate instrumentations from
    // tools/installed_protocol_client.sh; the first kills its own app process
    // while a real, daemon-accepted Stop reply is withheld by the relay.
    private val receiptJournal get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("chunks_protocol_receipts_v1", android.content.Context.MODE_PRIVATE)
    private fun retainedStopEntry(hash: String): JSONObject? = retainedEntry(hash, "STOP")
    private fun retainedEntry(hash: String, kind: String): JSONObject? {
        val entries = org.json.JSONArray(receiptJournal.getString("entries", "[]") ?: "[]")
        return (0 until entries.length()).map { entries.getJSONObject(it) }
            .singleOrNull { it.optString("h").equals(hash, ignoreCase = true) && it.optString("k") == kind }
    }
    private val removeWitnessFile get() =
        File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "chunks-remove-witness.json")
    // Instrumentation runs as the app UID; app data survives the kill (cleared only before phase 1).
    private val stopWitnessFile get() =
        File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "chunks-stop-witness.json")

    @Test fun typedStopAckHeldThenKillTheAppProcess() {
        assumeTrue("Disposable runner daemon only", args.getString("chunks_protocol_stop_death") == "authorized")
        val kind = RemoteBackendKind.valueOf(decoded("chunks_protocol_kind_b64"))
        assumeTrue("The relay faults only Transmission's typed Stop", kind == RemoteBackendKind.TRANSMISSION)
        val appUrl = decoded("chunks_protocol_app_url_b64")
        val user = decoded("chunks_protocol_user_b64")
        val pass = decoded("chunks_protocol_pass_b64")
        val credentials = RemoteCredentials(decoded("chunks_protocol_url_b64"), user, pass)
        suspend fun independentRead(hash: String) = RemoteTorrentClients.create(kind, credentials, null).torrent(hash)
        val relay = InstalledAddResponseFault(appUrl)

        val preload = fixture("stop-death")
        val name = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(preload.torrent).name
        val hash = preload.infoHashV1
        val added = runBlocking {
            ClientOperations(RemoteTorrentClients.create(kind, credentials, null), 40, 500)
                .add(ClientAddRequest(ClientAddSource.TorrentFile(preload.torrent), null, startNow = true))
        }
        assertTrue("Fixture preload: $added", added is ClientOperationOutcome.Verified)
        assertTrue("Fixture is started before the Stop",
            runBlocking { independentRead(hash) }?.runState != ClientRunState.STOPPED)
        stopWitnessFile.writeText(JSONObject().put("hash", hash).put("name", name).toString())
        stage("stop-death-fixture-started")

        ActivityScenario.launch(MainActivity::class.java).use {
            connect(kind, appUrl, user, pass)
            val profiles = RememberedRemoteProtocolProfiles(InstrumentationRegistry.getInstrumentation().targetContext)
            waitFor("durable exact-client profile") {
                profiles.list().any { it.backend == kind && it.credentials.endpoint == appUrl }
            }
            waitFor("fixture row in canonical Transfers", 90_000) {
                runCatching { scrollToNode(hasText(name, substring = true)); true }.getOrDefault(false) && visible(name)
            }
            val details = hasContentDescription("Details for $name", substring = true)
            waitFor("Details control") { runCatching { scrollToNode(details); true }.getOrDefault(false) }
            rule.onAllNodes(details).onFirst().performClick()
            waitFor("Stop offered for the started fixture", 60_000) {
                rule.onAllNodes(hasTestTag(ChunksSemantics.PROTOCOL_START_STOP) and hasText("Stop"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            relay.arm("hold-stop")
            clickOnce(ChunksSemantics.PROTOCOL_START_STOP, "Stop", 60_000)
            stage("stop-dispatched-once")
            // Kill well inside the app's 15 s read timeout: the journal must
            // already hold the possible write before any reply could arrive.
            rule.waitUntil(10_000) { relay.hasWithheldReply() }
            // Transmission acknowledges torrent-stop before the status flips;
            // read independently (direct port) until it settles, bounded.
            waitFor("daemon reports the executed Stop", 10_000) {
                runBlocking { independentRead(hash) }?.runState == ClientRunState.STOPPED
            }
            stage("stop-executed-by-daemon-reply-withheld")
            val entry = requireNotNull(retainedStopEntry(hash)) { "No durable Stop receipt before the kill" }
            assertTrue("Durable possible write, credential-free: $entry",
                entry.optString("s") in setOf("POSSIBLE_WRITE", "UNKNOWN") && !entry.toString().contains(pass))
            stage("stop-durable-possible-write-before-kill")
            android.util.Log.i("ChunksStopDeath", "process-death-stop-awaits-ack")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    @Test fun freshProcessKeepsStopUnknownUntilReadOnlyCheck() {
        assumeTrue("Disposable runner daemon only", args.getString("chunks_protocol_stop_death") == "authorized")
        val kind = RemoteBackendKind.valueOf(decoded("chunks_protocol_kind_b64"))
        assumeTrue("The relay faults only Transmission's typed Stop", kind == RemoteBackendKind.TRANSMISSION)
        val appUrl = decoded("chunks_protocol_app_url_b64")
        val credentials = RemoteCredentials(decoded("chunks_protocol_url_b64"),
            decoded("chunks_protocol_user_b64"), decoded("chunks_protocol_pass_b64"))
        val direct = ClientOperations(RemoteTorrentClients.create(kind, credentials, null), 40, 500)
        val relay = InstalledAddResponseFault(appUrl)
        val saved = JSONObject(stopWitnessFile.readText())
        val hash = saved.getString("hash")
        val name = saved.getString("name")
        val profiles = RememberedRemoteProtocolProfiles(InstrumentationRegistry.getInstrumentation().targetContext)
        val profileId = profiles.list().single { it.backend == kind && it.credentials.endpoint == appUrl }.id

        relay.requireOneStopAndNoReplay()
        assertTrue("The retained receipt survived process death",
            retainedStopEntry(hash)?.optString("s") in setOf("POSSIBLE_WRITE", "UNKNOWN"))
        stage("stop-receipt-survived-process-death")

        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitForIdle()
            rule.onNodeWithTag(ChunksSemantics.FOOTER_CONNECTIONS).performClick()
            // A remembered typed profile is offered on its own saved card after
            // relaunch; viewing it reconnects that exact profile.
            val viewExactProfile = hasTestTag("protocol-profile-view-$profileId") and isEnabled()
            waitFor("exact remembered typed profile is offered", 90_000) {
                runCatching { scrollToNode(hasTestTag("protocol-profile-view-$profileId")); true }
                    .getOrDefault(false) && rule.onAllNodes(viewExactProfile).fetchSemanticsNodes().isNotEmpty()
            }
            LazyComposeActions.activateOnce(rule, viewExactProfile)
            stage("stop-death-reconnected-exact-profile")
            waitFor("fixture row after relaunch", 90_000) {
                runCatching { scrollToNode(hasText(name, substring = true)); true }.getOrDefault(false) && visible(name)
            }
            val details = hasContentDescription("Details for $name", substring = true)
            waitFor("Details control") { runCatching { scrollToNode(details); true }.getOrDefault(false) }
            rule.onAllNodes(details).onFirst().performClick()

            waitFor("retained UNKNOWN Stop is shown", 60_000) {
                visible("Its result is unknown") && visible("Commands are paused until a read-only check.")
            }
            assertEquals("UNKNOWN", retainedStopEntry(hash)?.optString("s"))
            scrollToNode(hasTestTag(ChunksSemantics.PROTOCOL_START_STOP))
            rule.onNode(hasTestTag(ChunksSemantics.PROTOCOL_START_STOP), useUnmergedTree = true).assertIsNotEnabled()
            relay.requireOneStopAndNoReplay()
            stage("stop-unknown-retained-writes-blocked-no-replay")

            val check = hasText("Check this transfer without changing it")
            waitFor("read-only Check offered") {
                runCatching { scrollToNode(check); rule.onNode(check).assertIsEnabled(); true }.getOrDefault(false)
            }
            LazyComposeActions.activateOnce(rule, check)
            waitFor("read-only Check reconciles the exact receipt", 60_000) {
                visible("read-only check found the transfer still registered")
            }
            assertNull("Only this exact receipt is released", retainedStopEntry(hash))
            relay.requireOneStopAndNoReplay()
            assertEquals("Still stopped after the read-only Check; nothing replayed",
                ClientRunState.STOPPED, runBlocking { direct.client.torrent(hash) }?.runState)
            waitFor("write controls return after reconciliation") {
                runCatching {
                    scrollToNode(hasTestTag(ChunksSemantics.PROTOCOL_START_STOP))
                    rule.onNode(hasTestTag(ChunksSemantics.PROTOCOL_START_STOP), useUnmergedTree = true)
                        .assertIsEnabled(); true
                }.getOrDefault(false)
            }
            stage("stop-read-only-check-released-exact-receipt")
        }
        // Disposable fixture cleanup through the DIRECT daemon port, never the relay.
        // Job-only removal (keep files on the disposable daemon), then confirm
        // absence with an independent read; payload deletion is not claimed here.
        val removed = runBlocking { direct.remove(hash, deleteData = false) }
        assertNull("Disposable fixture job removed ($removed)", runBlocking { direct.client.torrent(hash) })
        stopWitnessFile.delete()
    }

    // ---- #2026 retained typed-Remove receipt for a job that is gone --------
    @Test fun typedRemoveAckHeldThenKillTheAppProcess() {
        assumeTrue("Disposable runner daemon only", args.getString("chunks_protocol_remove_death") == "authorized")
        val kind = RemoteBackendKind.valueOf(decoded("chunks_protocol_kind_b64"))
        assumeTrue("The relay faults only Transmission's typed Remove", kind == RemoteBackendKind.TRANSMISSION)
        val appUrl = decoded("chunks_protocol_app_url_b64")
        val user = decoded("chunks_protocol_user_b64")
        val pass = decoded("chunks_protocol_pass_b64")
        val credentials = RemoteCredentials(decoded("chunks_protocol_url_b64"), user, pass)
        suspend fun independentRead(hash: String) = RemoteTorrentClients.create(kind, credentials, null).torrent(hash)
        val relay = InstalledAddResponseFault(appUrl)

        val preload = fixture("remove-death")
        val name = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(preload.torrent).name
        val hash = preload.infoHashV1
        val added = runBlocking {
            ClientOperations(RemoteTorrentClients.create(kind, credentials, null), 40, 500)
                .add(ClientAddRequest(ClientAddSource.TorrentFile(preload.torrent), null, startNow = false))
        }
        assertTrue("Fixture preload: $added", added is ClientOperationOutcome.Verified)
        removeWitnessFile.writeText(JSONObject().put("hash", hash).put("name", name).toString())
        stage("remove-death-fixture-stopped")

        ActivityScenario.launch(MainActivity::class.java).use {
            connect(kind, appUrl, user, pass)
            val profiles = RememberedRemoteProtocolProfiles(InstrumentationRegistry.getInstrumentation().targetContext)
            waitFor("durable exact-client profile") {
                profiles.list().any { it.backend == kind && it.credentials.endpoint == appUrl }
            }
            waitFor("fixture row in canonical Transfers", 90_000) {
                runCatching { scrollToNode(hasText(name, substring = true)); true }.getOrDefault(false) && visible(name)
            }
            val details = hasContentDescription("Details for $name", substring = true)
            waitFor("Details control") { runCatching { scrollToNode(details); true }.getOrDefault(false) }
            rule.onAllNodes(details).onFirst().performClick()
            clickOnce(ChunksSemantics.PROTOCOL_REMOVE, "Remove…", 60_000)
            relay.arm("hold-remove")
            clickOnce(ChunksSemantics.PROTOCOL_REMOVE_CONFIRM, "Remove, keep files")
            stage("remove-dispatched-once")
            rule.waitUntil(10_000) { relay.hasWithheldReply() }
            waitFor("daemon no longer lists the removed job", 10_000) {
                runBlocking { independentRead(hash) } == null
            }
            stage("remove-executed-by-daemon-reply-withheld")
            val entry = requireNotNull(retainedEntry(hash, "REMOVE")) { "No durable Remove receipt before the kill" }
            assertTrue("Durable possible write, credential-free: $entry",
                entry.optString("s") in setOf("POSSIBLE_WRITE", "UNKNOWN") && !entry.toString().contains(pass))
            stage("remove-durable-possible-write-before-kill")
            android.util.Log.i("ChunksRemoveDeath", "process-death-remove-awaits-ack")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    @Test fun freshProcessReachesTheRemovedJobsReceiptFromTransfers() {
        assumeTrue("Disposable runner daemon only", args.getString("chunks_protocol_remove_death") == "authorized")
        val kind = RemoteBackendKind.valueOf(decoded("chunks_protocol_kind_b64"))
        assumeTrue("The relay faults only Transmission's typed Remove", kind == RemoteBackendKind.TRANSMISSION)
        val appUrl = decoded("chunks_protocol_app_url_b64")
        val credentials = RemoteCredentials(decoded("chunks_protocol_url_b64"),
            decoded("chunks_protocol_user_b64"), decoded("chunks_protocol_pass_b64"))
        val direct = ClientOperations(RemoteTorrentClients.create(kind, credentials, null), 40, 500)
        val relay = InstalledAddResponseFault(appUrl)
        val saved = JSONObject(removeWitnessFile.readText())
        val hash = saved.getString("hash")
        val name = saved.getString("name")
        val profileId = RememberedRemoteProtocolProfiles(InstrumentationRegistry.getInstrumentation().targetContext)
            .list().single { it.backend == kind && it.credentials.endpoint == appUrl }.id

        relay.requireOneRemoveAndNoReplay()
        assertNull("The job is really gone from the daemon", runBlocking { direct.client.torrent(hash) })
        assertTrue("The retained Remove receipt survived process death",
            retainedEntry(hash, "REMOVE")?.optString("s") in setOf("POSSIBLE_WRITE", "UNKNOWN"))
        stage("remove-receipt-survived-process-death")

        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitForIdle()
            rule.onNodeWithTag(ChunksSemantics.FOOTER_CONNECTIONS).performClick()
            val viewExactProfile = hasTestTag("protocol-profile-view-$profileId") and isEnabled()
            waitFor("exact remembered typed profile is offered", 90_000) {
                runCatching { scrollToNode(hasTestTag("protocol-profile-view-$profileId")); true }
                    .getOrDefault(false) && rule.onAllNodes(viewExactProfile).fetchSemanticsNodes().isNotEmpty()
            }
            LazyComposeActions.activateOnce(rule, viewExactProfile)
            val card = hasTestTag(ChunksSemantics.PROTOCOL_ABSENT_RECEIPTS)
            waitFor("Transfers offers the removed job's receipt", 90_000) {
                runCatching { scrollToNode(card); true }.getOrDefault(false)
            }
            assertTrue("The removed job has no row to open", !visible(name))
            assertTrue(visible("Remove: the previous request may have reached this client"))
            assertEquals("UNKNOWN", retainedEntry(hash, "REMOVE")?.optString("s"))
            relay.requireOneRemoveAndNoReplay()
            stage("remove-unknown-reachable-without-a-row-no-replay")

            val check = hasTestTag(ChunksSemantics.PROTOCOL_ABSENT_RECEIPT_CHECK + "." + hash)
            waitFor("read-only Check offered") { runCatching { scrollToNode(check); true }.getOrDefault(false) }
            LazyComposeActions.activateOnce(rule, check)
            val result = hasTestTag(ChunksSemantics.PROTOCOL_ABSENT_RECEIPT_RESULT + "." + hash)
            waitFor("read-only Check reconciles the exact receipt", 60_000) {
                runCatching { scrollToNode(result); true }.getOrDefault(false) &&
                    visible("read-only check found the transfer not registered")
            }
            assertNull("Only this exact receipt is released", retainedEntry(hash, "REMOVE"))
            relay.requireOneRemoveAndNoReplay()
            assertNull("Still absent; nothing was re-added", runBlocking { direct.client.torrent(hash) })
            stage("remove-read-only-check-released-exact-receipt")
        }
        removeWitnessFile.delete()
    }

    @Test fun installedAddAfterTheDaemonResponseIsWithheld() {
        assumeTrue("Disposable runner daemon only", args.getString("chunks_protocol_disposable") == "authorized")
        val kind = RemoteBackendKind.valueOf(decoded("chunks_protocol_kind_b64"))
        val appUrl = decoded("chunks_protocol_app_url_b64")
        val user = decoded("chunks_protocol_user_b64")
        val pass = decoded("chunks_protocol_pass_b64")
        val credentials = RemoteCredentials(decoded("chunks_protocol_url_b64"),
            if (kind == RemoteBackendKind.DELUGE) "" else user, pass)
        val daemon = if (kind == RemoteBackendKind.DELUGE) runBlocking {
            DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds().single()
        } else null
        suspend fun independentRead(hash: String) = RemoteTorrentClients.create(kind, credentials, daemon).torrent(hash)
        val witness = ClientOperations(RemoteTorrentClients.create(kind, credentials, daemon), 40, 500)
        val responseFault = InstalledAddResponseFault(appUrl)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            connect(kind, appUrl, user, pass)
            val profiles = RememberedRemoteProtocolProfiles(InstrumentationRegistry.getInstrumentation().targetContext)
            waitFor("verified exact-client profile") { profiles.list().any { it.backend == kind } }
            val profileId = profiles.list().single { it.backend == kind && it.credentials.endpoint == appUrl }.id

            addJourney.activate(ChunksSemantics.FOOTER_ADD)
            clickOnce("chunks.add.target.protocol.$profileId", "Add target chip")
            for (fault in listOf("drop", "timeout")) {
                val pending = fixture(fault)
                val pendingHash = pending.infoHashV1
                assertNull("The exact fixture must be absent before its Add", runBlocking { independentRead(pendingHash) })
                typeInto(UniversalAddSourceText.FIELD_LABEL, requireNotNull(pending.magnet))
                ensureStartWhenAddedOff()
                responseFault.arm(fault)
                addJourney.submit()
                stage("add-fault-$fault-dispatched-once")

                waitFor("exact real daemon job while the response is withheld") {
                    runBlocking { independentRead(pendingHash) }?.runState == ClientRunState.STOPPED
                }
                addJourney.activate(ChunksSemantics.FOOTER_TRANSFERS)
                addJourney.awaitTransferReceipt("Adding", "Add unverified", "Present, origin unverified")
                scenario.recreate()
                addJourney.awaitTransferReceipt("Adding", "Add unverified", "Present, origin unverified")
                addJourney.activate(ChunksSemantics.TRANSFERS_ADD_REVIEW + ".$profileId")
                waitFor("existing transfer confirmed, original registration unverified") {
                    addJourney.hasReceiptState("Present, origin unverified")
                }
                assertTrue("An exact match cannot establish who registered the job", !receiptVerified())
                addJourney.requireSubmitBlocked()
                addJourney.viewExactTransfer(profileId, pendingHash)
                addJourney.awaitTransferReceipt("Present, origin unverified")

                responseFault.requireOneWriteAndNoStart()
                assertEquals("Independent readback still finds the job stopped", ClientRunState.STOPPED,
                    runBlocking { independentRead(pendingHash) }?.runState)
                stage("add-fault-$fault-one-write-present-origin-unverified")
                addJourney.activate(ChunksSemantics.TRANSFERS_ADD_REVIEW + ".$profileId")
                addJourney.activate(ChunksSemantics.ADD_ANOTHER)
                runBlocking { witness.remove(pendingHash, deleteData = false) }
            }
            if (kind == RemoteBackendKind.TRANSMISSION) {
                val delayed = fixture("delayed-ack")
                val delayedHash = delayed.infoHashV1
                assertNull(runBlocking { independentRead(delayedHash) })
                typeInto(UniversalAddSourceText.FIELD_LABEL, requireNotNull(delayed.magnet))
                ensureStartWhenAddedOff()
                responseFault.arm("delay")
                addJourney.submit()
                waitFor("daemon accepted the delayed request", 8_000) { responseFault.hasWithheldReply() }
                assertEquals(ClientRunState.STOPPED, runBlocking { independentRead(delayedHash) }?.runState)
                addJourney.activate(ChunksSemantics.FOOTER_TRANSFERS)
                addJourney.awaitTransferReceipt("Adding")
                assertTrue("Exact inventory alone cannot settle an Add with a reply outstanding", !receiptVerified())
                responseFault.releaseDelayedResponse()
                scenario.recreate()
                addJourney.awaitTransferReceipt("Adding", "Verified")
                addJourney.activate(ChunksSemantics.TRANSFERS_ADD_REVIEW + ".$profileId")
                addJourney.awaitAddReceipt("Verified")
                addJourney.viewExactTransfer(profileId, delayedHash)
                responseFault.requireOneWriteAndNoStart()
                stage("add-delayed-valid-ack-then-exact-verified-readback")
                addJourney.activate(ChunksSemantics.TRANSFERS_ADD_REVIEW + ".$profileId")
                addJourney.activate(ChunksSemantics.ADD_ANOTHER)
                runBlocking { witness.remove(delayedHash, deleteData = false) }

                val original = runBlocking { requireNotNull(witness.session(refresh = true).defaultDownloadDirectory) }
                val changed = decoded("chunks_protocol_custom_folder_b64")
                assertTrue("The disposable folder for the policy change must differ",
                    !ClientHashes.sameDirectory(original, changed))
                val pending = fixture("changed-default")
                typeInto(UniversalAddSourceText.FIELD_LABEL, requireNotNull(pending.magnet))
                ensureStartWhenAddedOff()
                waitFor("the original default is still the reviewed Save to folder") {
                    visible("default folder ($original)")
                }
                assertNull(runBlocking { independentRead(pending.infoHashV1) })
                try {
                    runBlocking { setDisposableTransmissionDefault(credentials, changed) }
                    assertTrue(ClientHashes.sameDirectory(changed,
                        runBlocking { witness.session(refresh = true).defaultDownloadDirectory }))
                    responseFault.arm("drop")
                    addJourney.submit()
                    addJourney.awaitAddReceipt("Not added")
                    assertTrue(visible("The reviewed default folder could not be confirmed"))
                    responseFault.requireNoWrite()
                    assertNull("The exact job must still be absent", runBlocking { independentRead(pending.infoHashV1) })
                    stage("changed-default-refused-without-write")
                } finally {
                    runBlocking { setDisposableTransmissionDefault(credentials, original) }
                }
            }
        }
    }

    private suspend fun setDisposableTransmissionDefault(credentials: RemoteCredentials, directory: String) {
        require(credentials.endpoint == "http://127.0.0.1:19091/transmission/rpc")
        val endpoint = URI(credentials.endpoint)
        val authorization = "Basic " + Base64.encodeToString(
            "${credentials.username}:${credentials.password}".toByteArray(), Base64.NO_WRAP)
        val wire = TransmissionReadOnlyRpc.HttpWire
        val probe = wire.exchange(TransmissionReadOnlyRpc.Request(endpoint,
            """{"method":"session-get"}""".toByteArray(), authorization, null))
        val token = when (probe.status) {
            409 -> probe.header("X-Transmission-Session-Id").single()
            200 -> null
            else -> error("Disposable Transmission session validation failed")
        }
        val update = JSONObject().put("method", "session-set")
            .put("arguments", JSONObject().put("download-dir", directory)).toString().toByteArray()
        val reply = wire.exchange(TransmissionReadOnlyRpc.Request(endpoint, update, authorization, token))
        assertEquals(200, reply.status)
        assertEquals("success", JSONObject(reply.body).getString("result"))
    }
}
