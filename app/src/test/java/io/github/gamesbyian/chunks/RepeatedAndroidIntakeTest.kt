package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import io.github.gamesbyian.chunks.create.CreateTorrentSourceTest
import io.github.gamesbyian.chunks.create.SharedCreateDocument
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner report (12 Nov): a first Android Open With `.torrent` could be added; a second Open
 * With reached Add with no usable Add action. Drives the real MainActivity, the real
 * `ACTION_VIEW` → `onNewIntent()` intake and the real remote Add adapter against a stateful
 * loopback rTorrent double. JVM evidence only: no peers or pieces.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class RepeatedAndroidIntakeTest {
    @get:Rule val rule = createComposeRule()
    private val server = FakeRTorrent()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val owner = LocalAddAttemptOwner()

    @org.junit.Before fun launch() {
        rule.setContent {
            ChunksTheme {
                ChunksApp(owner)
            }
        }
    }

    @After fun stop() {
        owner.addAttemptScope.cancel()
        server.close()
    }

    private fun b(text: String) = "${text.toByteArray().size}:$text"
    private fun torrent(name: String) = ("d8:announce" + b("https://tracker.example/announce") +
        "4:infod6:lengthi5e4:name" + b(name) + "12:piece lengthi16384e6:pieces20:01234567890123456789ee")
        .toByteArray()

    private fun list(): SemanticsNodeInteraction = rule.onNode(hasScrollToIndexAction())
    // Only composed nodes are found: after the first Add the list sits at its
    // outcome/submit end, so a new source's row near the top may be off-screen
    // (CI foundation 43779794967 timed out here). Scroll toward it read-only
    // while waiting, and name what was visible on timeout (synthetic fixture
    // names only).
    private fun waitForText(text: String, timeout: Long = 30_000) {
        try {
            rule.waitUntil(timeout) {
                runCatching { list().performScrollToNode(hasText(text, substring = true)) }
                rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val visible = runCatching {
                rule.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
                    .flatMap { n -> n.config.getOrElseNullable(
                        androidx.compose.ui.semantics.SemanticsProperties.Text) { null }.orEmpty().map { it.text } }
                    .filter { it.length < 140 }
            }.getOrDefault(emptyList())
            throw AssertionError("Timed out waiting for \"$text\"; visible: " + visible.joinToString(" | "), failure)
        }
    }

    private fun connect() {
        rule.openConnectionFormIfNeeded()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.openConnectionFormIfNeeded(allowConnected = false)
        list().performScrollToNode(hasText("Seedbox hostname, ruTorrent URL or RPC address"))
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").performTextInput(server.endpoint)
        list().performScrollToNode(hasText("Connect to rTorrent"))
        rule.onNodeWithText("Connect to rTorrent").performClick()
        waitForText("Continue to Connections")
        rule.onNodeWithText("Continue to Connections").performClick()
        rule.waitForIdle()
    }

    @Test fun connectedCreateShareAdoptsTheRetainedSource() {
        connect()
        val authority = CreateTorrentSourceTest.AUTHORITY
        Robolectric.setupContentProvider(CreateTorrentSourceTest.FakeProvider::class.java, authority)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val file = DocumentsContract.buildDocumentUriUsingTree(tree, "c")
        shadowOf(context.contentResolver).registerInputStreamSupplier(file) {
            CreateTorrentSourceTest.bytes("c").inputStream()
        }
        rule.runOnIdle { owner.offerCreate(SharedCreateDocument.Request.File(file)) }
        waitForText("c.mp4")
        rule.runOnIdle {
            assertTrue(owner.createSource.value != null)
            assertEquals(null, owner.createReviewRequest.value)
        }
        rule.onNodeWithText("Torrent created").assertDoesNotExist()
    }

    /**
     * The installed delete-data / two-server journeys enter canonical Transfers this way:
     * Connect, Continue to Connections, View transfers. The server-scoped Transfers search
     * must appear (it sits in a lazy list, so reveal it before asserting).
     */
    @Test fun connectThenViewTransfersOpensCanonicalServerScopedTransfers() {
        connect()
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasTestTag(ChunksSemantics.CONNECTION_VIEW_TRANSFERS)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_VIEW_TRANSFERS).performScrollTo().performClick()
        rule.waitUntil(30_000) {
            runCatching {
                list().performScrollToNode(hasTestTag(ChunksSemantics.TRANSFERS_SEARCH))
                rule.onNodeWithTag(ChunksSemantics.TRANSFERS_SEARCH).fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
    }

    /** A second Open With while Chunks is already showing Add. */
    private fun openWith(name: String): String {
        val bytes = torrent(name)
        val uri = Uri.parse("content://chunks.test.documents/$name.torrent")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        // The same VIEW intent MainActivity receives is what selects this document.
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-bittorrent")
        val accepted = requireNotNull(SharedTorrentDocument.fromIntent(intent)) { "Not an accepted Open With" }
        rule.runOnIdle { owner.offerAdd(ExternalAddSource.TorrentDocument(accepted)) }
        rule.waitForIdle()
        return TorrentMetainfoReader.preview(bytes).infoHashV1.lowercase()
    }

    private fun submitEnabled() {
        list().performScrollToNode(hasTestTag(ChunksSemantics.ADD_SUBMIT))
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsEnabled()
    }

    @Test fun secondOpenWithDuringFirstProviderReadDoesNotSilentlyReplaceIt() {
        val firstUri = Uri.parse("content://chunks.test.documents/inflight-first.torrent")
        val secondUri = Uri.parse("content://chunks.test.documents/inflight-second.torrent")
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val firstBytes = torrent("inflight-first.mkv")
        val firstStream = object : java.io.FilterInputStream(firstBytes.inputStream()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                started.countDown()
                check(release.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    "The first document's test reader was never released"
                }
                return super.read(buffer, offset, length)
            }
        }
        shadowOf(context.contentResolver).registerInputStream(firstUri, firstStream)
        shadowOf(context.contentResolver).registerInputStream(secondUri,
            torrent("inflight-second.mkv").inputStream())
        try {
            rule.runOnIdle { owner.offerAdd(ExternalAddSource.TorrentDocument(firstUri)) }
            rule.waitUntil(15_000) { started.count == 0L }
            // The previous source is selected, but its parsed review cannot
            // yet be shown. The second handoff must be held for a choice.
            rule.runOnIdle { owner.offerAdd(ExternalAddSource.TorrentDocument(secondUri)) }
            waitForText("Use new source")
            release.countDown()
            waitForText("inflight-first.mkv")
            // Local Add is a vertical ScrollState, not the remote LazyColumn.
            // Inspect the actual, still-visible replacement control without
            // inventing a list-index action or authorizing an Add mutation.
            rule.onNodeWithText("Use new source").performScrollTo().performClick()
            waitForText("inflight-second.mkv")
        } finally {
            release.countDown()
        }
    }

    @Test fun secondOpenWithAfterAVerifiedAddGetsAUsableAddForTheNewTorrent() {
        connect()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()

        val first = openWith("first-torrent.mkv")
        waitForText("first-torrent.mkv")
        submitEnabled()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        waitForText("Verified: added")
        assertTrue(server.jobs.containsKey(first))

        val second = openWith("second-torrent.mkv")
        // A finished, verified Add is not an occupied draft: no "Use new source" detour.
        waitForText("second-torrent.mkv")
        assertTrue("A verified finished source must not hold the new one as pending",
            rule.onAllNodesWithText("Use new source").fetchSemanticsNodes().isEmpty())
        // The defect: the finished attempt hid the new source and the Add action.
        submitEnabled()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        rule.waitUntil(30_000) { server.jobs.containsKey(second) }
        waitForText("Verified: added")
        assertEquals("Exactly one load per explicit Add, never a replay of the first",
            2, server.loads().size)
        assertEquals(setOf(first, second), server.jobs.keys)
    }

    /**
     * #1786: an Open With that lands while the previous remote Add is still settling is held
     * (an unfinished attempt is never replaced) and must surface once that Add verifies,
     * instead of vanishing behind the finished review. Exactly one load per explicit Add.
     */
    @Test fun openWithDuringASettlingAddIsOfferedAfterItVerifies() {
        connect()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()
        val first = openWith("settling-first.mkv")
        waitForText("settling-first.mkv")
        submitEnabled()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        // Deliver the second handoff before the first attempt can settle.
        val bytes = torrent("settling-second.mkv")
        val uri = Uri.parse("content://chunks.test.documents/settling-second.mkv.torrent")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        val accepted = requireNotNull(SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-bittorrent")))
        rule.runOnUiThread { owner.offerAdd(ExternalAddSource.TorrentDocument(accepted)) }
        rule.mainClock.autoAdvance = true
        rule.waitUntil(30_000) { server.jobs.containsKey(first) }
        waitForText("settling-second.mkv")
        submitEnabled()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        val second = TorrentMetainfoReader.preview(bytes).infoHashV1.lowercase()
        rule.waitUntil(30_000) { server.jobs.containsKey(second) }
        assertEquals("Exactly one load per explicit Add", 2, server.loads().size)
    }

    @Test fun anUnsubmittedDraftIsReplacedOnlyByExplicitChoiceAndTheAddStaysUsable() {
        connect()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitForIdle()
        openWith("draft-a.mkv")
        waitForText("draft-a.mkv")
        val replacement = openWith("draft-b.mkv")
        // An occupied, never-submitted draft is not silently replaced.
        waitForText("Use new source")
        rule.onNodeWithText("Use new source").performScrollTo().performClick()
        waitForText("draft-b.mkv")
        submitEnabled()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        rule.waitUntil(30_000) { server.jobs.containsKey(replacement) }
        assertEquals("Only the explicitly chosen replacement is submitted", setOf(replacement), server.jobs.keys)
    }

    @Test fun detailsEnvironmentLinkReturnsToTheCanonicalBrowserWithoutRemoteWrites() {
        val hash = "8".repeat(40)
        val name = "scope-regression.txt"
        server.seed(hash, name)
        connect()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
        val row = hasContentDescription("Details for $name on", substring = true)
        // The remote inventory loads asynchronously after Connect; wait for the row
        // (a read-only observation) rather than racing it in a loaded full suite.
        rule.waitUntil(15_000) { runCatching { list().performScrollToNode(row) }.isSuccess }
        rule.onNode(row).performClick()
        rule.onNodeWithTag(ChunksSemantics.DETAILS_SCREEN).assertExists()
        val before = server.methods.size
        // Details' owning-list action names the short client origin, not "Server · …".
        val ownerList = androidx.compose.ui.test.SemanticsMatcher("Open <client> transfers") { node ->
            node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
                .orEmpty().any { it.text.startsWith("Open ") && it.text.endsWith(" transfers") &&
                    !it.text.contains("Server ·") }
        }
        rule.onNode(ownerList).performScrollTo().performClick()
        waitForText("Search names or hashes")
        rule.onNodeWithText("+ Add torrent").assertDoesNotExist()
        val owner = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverIdOfAliasScope(
            RemoteVerifiedUiIdentity.aliasScopeOf(io.github.gamesbyian.chunks.rpc.RemoteCredentials(server.endpoint, "", "")))
        assertEquals(io.github.gamesbyian.chunks.model.EnvironmentViewScope.Only(setOf(requireNotNull(owner))),
            CombinedViewPreferences(context).load().scope)
        val writes = Regex("(^load\\.|\\.(start|stop|erase|close|open|pause|resume|check_hash)$|\\.set$|\\.set\\.)")
        assertTrue("Browsing cannot issue a remote write", server.methods.drop(before).none { writes.containsMatchIn(it) })
    }
}
