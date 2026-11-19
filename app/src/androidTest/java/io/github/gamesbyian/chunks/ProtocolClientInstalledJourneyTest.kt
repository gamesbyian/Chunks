package io.github.gamesbyian.chunks

import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed app against ONE disposable real Transmission daemon on the CI
 * runner (adb reverse loopback; never a protected host). The test preloads a
 * unique stopped fixture with the real client, then drives the app's own
 * Connections form (Transmission type) → canonical Transfers → Details
 * Start → Remove (keep files), with independent client readback after each.
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
            // Prefer the protocol controls when present: the whole root is too long.
            val ui = runCatching {
                rule.onNodeWithTag(ChunksSemantics.PROTOCOL_OPERATIONS, useUnmergedTree = true)
                    .printToString().take(6_000)
            }.recoverCatching { rule.onRoot(useUnmergedTree = true).printToString().take(6_000) }
                .getOrDefault("semantics unavailable")
            throw AssertionError("Timed out: $what\n$ui", failure)
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

    /** One dispatch of a control, after polling only until it is enabled. */
    private fun clickOnce(tag: String, what: String, timeoutMs: Long = 30_000) {
        val matcher = hasTestTag(tag)
        waitFor("enabled $what", timeoutMs) {
            runCatching {
                scrollToNode(matcher)
                rule.onNode(matcher, useUnmergedTree = true).assertIsEnabled()
                true
            }.getOrDefault(false)
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

        fun fixture(tag: String): TorrentMetainfoBuilder.Result {
            val random = SecureRandom()
            val name = "chunks-installed-$tag-" + java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36)
            val bytes = ByteArray(64 * 1024).also(random::nextBytes)
            return TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
                listOf(TorrentMetainfoBuilder.Entry(listOf(name), bytes.size.toLong()) { bytes.inputStream() }),
                trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024))
        }
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
            val entries = listOf("Show S01E03.bin", "Show S01E01.bin", "Show S01E02.bin").map { name ->
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
            rule.waitForIdle()
            rule.onNodeWithTag(ChunksSemantics.FOOTER_CONNECTIONS).performClick()
            waitFor("Connections") {
                runCatching { scrollToNode(hasTestTag(ChunksSemantics.CONNECTION_ADD)); true }.getOrDefault(false)
            }
            rule.openConnectionFormIfNeeded()
            when (kind) {
                io.github.gamesbyian.chunks.model.RemoteBackendKind.TRANSMISSION -> {
                    clickOnce("connection-backend-transmission", "Transmission type")
                    typeInto("Transmission RPC URL", url)
                    typeInto("Username, if required", user)
                }
                io.github.gamesbyian.chunks.model.RemoteBackendKind.QBITTORRENT -> {
                    clickOnce("connection-backend-qbittorrent", "qBittorrent type")
                    typeInto("qBittorrent WebUI root URL", url)
                    typeInto("Username, if required", user)
                }
                io.github.gamesbyian.chunks.model.RemoteBackendKind.DELUGE -> {
                    clickOnce("connection-backend-deluge", "Deluge type")
                    typeInto("Deluge Web JSON URL", url)
                }
                else -> error("rTorrent has its own installed journeys")
            }
            typeInto("Password", pass)
            clickOnce(ChunksSemantics.CONNECTION_CONNECT, "Connect")
            stage("connect-dispatched-once")

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
            val profileId = RememberedRemoteProtocolProfiles(InstrumentationRegistry.getInstrumentation().targetContext)
                .list().single { it.backend == kind }.id
            val second = fixture("add")
            val magnet = requireNotNull(second.magnet)
            // Details may hide the footer: go back to Transfers first.
            if (rule.onAllNodes(hasTestTag(ChunksSemantics.FOOTER_ADD)).fetchSemanticsNodes().isEmpty()) {
                it.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
            }
            waitFor("Add footer") { rule.onAllNodes(hasTestTag(ChunksSemantics.FOOTER_ADD)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
            clickOnce("chunks.add.target.protocol.$profileId", "Add target chip", 30_000)
            waitFor("protocol Add review") {
                runCatching { scrollToNode(hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD)); true }.getOrDefault(false)
            }
            rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).performTextReplacement(magnet)
            rule.waitForIdle()
            ensureStartWhenAddedOff()
            clickOnce(ChunksSemantics.ADD_SUBMIT, "Add", 30_000)
            stage("add-dispatched-once")
            waitFor("Add confirmed", 90_000) { visible("Add: confirmed") }
            // The attempt is app-owned like Local and rTorrent: Activity recreation must
            // keep this exact receipt and must not re-enable a second Add of the same source.
            it.recreate()
            waitFor("receipt kept across recreation", 30_000) { visible("Add: confirmed") }
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

            // Job-only Move of that exact job to This device (Local engine).
            val secondName = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(second.torrent).name
            rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
            val secondDetails = hasContentDescription("Details for $secondName", substring = true)
            waitFor("added job row", 90_000) {
                runCatching { scrollToNode(secondDetails); true }.getOrDefault(false)
            }
            rule.onAllNodes(secondDetails).onFirst().performClick()
            clickOnce(ChunksSemantics.PROTOCOL_MOVE, "Copy or move", 60_000)
            val localId = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL.value
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
            waitFor("local job row", 90_000) {
                runCatching { scrollToNode(secondDetails); true }.getOrDefault(false)
            }
            rule.onAllNodes(secondDetails).onFirst().performClick()
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
            rule.onNodeWithText(skipped).performClick()
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
            ensureStartWhenAddedOff()
            clickOnce(ChunksSemantics.ADD_SUBMIT, "Add with file choices", 30_000)
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
            waitFor("Add receipt", 60_000) { visible("Add: confirmed") }
            assertEquals("Add with file choices stayed stopped", ClientRunState.STOPPED,
                runBlocking { independentRead(choicesHash) }?.runState)
            stage("choices-skip-and-high-verified-independently")
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
            if (rule.onAllNodes(hasTestTag(ChunksSemantics.FOOTER_TRANSFERS)).fetchSemanticsNodes().isEmpty()) {
                it.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
            }
            rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
            val episodesDetails = hasContentDescription("Details for $episodesName", substring = true)
            waitFor("episode job row", 90_000) {
                runCatching { scrollToNode(episodesDetails); true }.getOrDefault(false)
            }
            rule.onAllNodes(episodesDetails).onFirst().performClick()
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
            val firstEpisode = "Show S01E01.bin"
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
        }
        val profiles = RememberedRemoteProtocolProfiles(InstrumentationRegistry.getInstrumentation().targetContext).list()
        assertEquals("The verified login was saved once", 1,
            profiles.count { it.credentials.endpoint == credentials.endpoint && it.backend == kind })
        stage("profile-saved")
    }
}
