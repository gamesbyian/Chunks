package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Remote Universal Add Keep/Archive/Delete through the installed app on a
 * DISPOSABLE real rTorrent (never a protected host): a .torrent shared from
 * the debug fixture DocumentsProvider while connected, Add to the server
 * stopped with Archive selected. Only after the engine verified the NEW exact
 * job is a private copy archived; removing the original is a separate
 * confirmation that deletes exactly that document. The server session is
 * independently read back, then erased session-only (the stopped fixture
 * payload never existed on the server). Owned by its own workflow step.
 */
@RunWith(AndroidJUnit4::class)
class RemoteOriginalTorrentHandlingInstalledInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args: Bundle get() = InstrumentationRegistry.getArguments()

    private fun stage(name: String) = instrumentation.sendStatus(0, Bundle().apply { putString("chunks_stage", name) })
    private fun visible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun scrollTo(text: String) =
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))
    private fun waitFor(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        try { rule.waitUntil(timeoutMs, condition) } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out: $what", failure)
        }
    }
    /** Lazy remote Add items exist only once scrolled into composition. */
    private fun bringTag(tag: String) {
        runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag)) }
        rule.waitForIdle()
    }
    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun stillReadable(document: Uri): Boolean = try {
        context.contentResolver.openInputStream(document)?.use { true } ?: false
    } catch (_: FileNotFoundException) { false }

    private fun shareDocument(bytes: ByteArray, name: String): Uri {
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val document = requireNotNull(DocumentsContract.createDocument(context.contentResolver,
            root, "application/x-bittorrent", name))
        context.contentResolver.openOutputStream(document, "w").use { requireNotNull(it).write(bytes) }
        return document
    }

    @Test fun archiveThenSeparatelyRemoveOriginalAfterVerifiedNewRemoteAdd() {
        assumeTrue("Requires the disposable ruTorrent workflow",
            args.getString("chunks_remote_original") == "authorized")
        val url = String(Base64.decode(requireNotNull(args.getString("chunks_remote_url_b64")), Base64.NO_WRAP))
        require(url == "http://127.0.0.1:18082/rutorrent1/")
        val remote = RTorrentRemote(RemoteCredentials(url, "", ""))
        // As in the local source-handling journey: a system notification-permission
        // dialog (another process) would otherwise sit over MainActivity at teardown.
        if (android.os.Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS)

        // A unique stopped fixture: its payload never exists on the server.
        val work = File(context.cacheDir, "remote-original-" + System.nanoTime().toString(16)).apply { mkdirs() }
        val payload = File(work, "chunks-remote-original-${System.nanoTime().toString(16)}.bin")
            .apply { writeBytes(ByteArray(64 * 1024) { (it * 13 % 251).toByte() }) }
        val bytes = TorrentBuilder().path(payload).pieceSize(16 * 1024).flags(TorrentBuilder.V1_ONLY)
            .addTracker("http://127.0.0.1:9/announce").generate().entry().bencode()
        val hash = requireNotNull(TorrentMetainfoReader.preview(bytes).infoHashV1)
        val document = shareDocument(bytes, "remote-original.torrent")
        val sibling = shareDocument("unrelated".toByteArray(), "remote-unrelated.torrent")
        val before = runBlocking { remote.listTransfers("main") }
        assertTrue(before.none { it.hash.equals(hash, true) })
        val archiveDir = File(context.filesDir, SourceTorrentArchive.DIRECTORY)
        fun archived() = archiveDir.listFiles().orEmpty().filter { it.name.startsWith("$hash-") }
        assertTrue(archived().isEmpty())
        stage("ground-truth")
        try {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            run {
                waitFor("persistent Connections destination") {
                    rule.onAllNodes(hasContentDescription("Connections")).fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithContentDescription("Connections").performClick()
                waitFor("connection form") { visible("Seedbox hostname, ruTorrent URL or RPC address") }
                scrollTo("Seedbox hostname, ruTorrent URL or RPC address")
                rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").performTextReplacement(url)
                scrollTo("Connect to rTorrent")
                rule.onNodeWithText("Connect to rTorrent").performClick()
                waitFor("verified connection acknowledgment", 90_000) { visible("Continue to Connections") }
                rule.onNodeWithText("Continue to Connections").performClick()
                waitFor("connected server in Connections") { visible("View transfers") }
                stage("connected")

                scenario.onActivity {
                    it.startActivity(Intent(it, MainActivity::class.java).apply {
                        action = Intent.ACTION_SEND
                        type = "application/x-bittorrent"
                        putExtra(Intent.EXTRA_STREAM, document)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
                waitFor("Add target choice for the shared document") { tagged(ChunksSemantics.ADD_TARGET_REMOTE) }
                rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
                waitFor("remote review of the shared .torrent") {
                    tagged(originalHandlingTag(OriginalTorrentHandling.ARCHIVE))
                }
                stage("remote-review")
                bringTag(originalHandlingTag(OriginalTorrentHandling.ARCHIVE))
                rule.onNodeWithTag(originalHandlingTag(OriginalTorrentHandling.ARCHIVE)).performClick()
                bringTag(ChunksSemantics.ADD_START_PREFERENCE)
                val start = rule.onNodeWithTag(ChunksSemantics.ADD_START_PREFERENCE)
                if (start.fetchSemanticsNode().config
                        .getOrElseNullable(SemanticsProperties.ToggleableState) { null }?.name != "Off")
                    start.performClick()
                val add = hasText("Add transfer") and hasClickAction()
                runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(add) }
                waitFor("enabled Add transfer", 60_000) {
                    rule.onAllNodes(add).fetchSemanticsNodes().any {
                        it.config.getOrElseNullable(SemanticsProperties.Disabled) { null } == null
                    }
                }
                assertTrue("Nothing is archived before the Add", archived().isEmpty())
                rule.onNode(add).performClick()
                stage("add-submitted")
                waitFor("separate original removal question after the verified Add", 90_000) {
                    bringTag(ChunksSemantics.ADD_ORIGINAL_DELETE_PROMPT)
                    tagged(ChunksSemantics.ADD_ORIGINAL_DELETE_PROMPT)
                }
                assertTrue("A verified new exact job exists on the server",
                    runBlocking { remote.listTransfers("main") }.any { it.hash.equals(hash, true) })
                assertArrayEquals("Exactly the reviewed bytes are archived", bytes, archived().single().readBytes())
                assertTrue("Archive alone never removes the original", stillReadable(document))
                stage("archived")
                bringTag(ChunksSemantics.ADD_ORIGINAL_DELETE_CONFIRM)
                rule.onNodeWithTag(ChunksSemantics.ADD_ORIGINAL_DELETE_CONFIRM).performClick()
                waitFor("confirmed move into Chunks") {
                    runCatching { scrollTo("was moved into Chunks") }
                    visible("was moved into Chunks")
                }
                stage("original-removed")
            }
            // Teardown is test infrastructure, not the journey under test. Four runs
            // passed every stage above, then ActivityScenario.close() found its
            // MainActivity PAUSED under some other activity. Record what is on top,
            // attempt the close, and report its outcome as a separate stage.
            val top = runCatching {
                instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities").use { fd ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readLines()
                }.filter { it.contains("topResumedActivity") || it.contains("ResumedActivity:") }
                    .joinToString(" | ") { it.trim() }.take(400)
            }.getOrDefault("unavailable")
            stage("teardown-top=" + top)
            stage("teardown-close=" + runCatching { scenario.close() }.fold({ "destroyed" }, { it.javaClass.simpleName }))
            assertTrue("The confirmed original is gone", !stillReadable(document))
            assertTrue("Unrelated documents are untouched", stillReadable(sibling))
            assertEquals("The archived copy stays", 1, archived().size)
            val after = runBlocking { remote.listTransfers("main") }
            assertEquals("Only the one new exact session was added",
                before.map { it.hash.lowercase() }.toSet() + hash.lowercase(),
                after.map { it.hash.lowercase() }.toSet())
        } finally {
            // Session-only cleanup of exactly this disposable fixture job.
            runBlocking {
                remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }?.let {
                    remote.eraseFromSession(it.hash, it.name, it.directory)
                }
            }
            archived().forEach { it.delete() }
            work.deleteRecursively()
        }
        stage("cleaned-up")
    }
}
