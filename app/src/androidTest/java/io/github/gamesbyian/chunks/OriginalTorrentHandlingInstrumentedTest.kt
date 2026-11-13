package io.github.gamesbyian.chunks

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.io.FileNotFoundException
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Installed Universal Add (Local Device, stopped) from a shared .torrent document held by
 * the debug fixture DocumentsProvider. Archive keeps a private copy and the original;
 * Delete needs its separate confirmation, then removes only that document. The stopped
 * Add writes no payload, so cleanup is an exact-hash session-only Remove. Emulator tier.
 */
@RunWith(AndroidJUnit4::class)
class OriginalTorrentHandlingInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val hashes = ArrayList<String>()
    private val work get() = File(context.cacheDir, "original-handling-test")

    @After fun cleanup() {
        hashes.forEach { LocalEngineHolder.current()?.removeKeepingData(it) }
        work.deleteRecursively()
        OriginalTorrentHandlingStore(context).setDefault(OriginalTorrentHandling.ARCHIVE)
    }

    private fun waitFor(ms: Long = 30_000, condition: () -> Boolean) = rule.waitUntil(ms, condition)
    private fun waitForText(text: String) = waitFor {
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /** A unique single-file v1 torrent; its payload never exists, so the Add verifies nothing. */
    private fun torrent(): Pair<ByteArray, String> {
        val source = File(work, "src-" + System.nanoTime().toString(16)).apply { mkdirs() }
        val payload = File(source, "chunks-original-handling-${System.nanoTime().toString(16)}.bin")
        payload.writeBytes(ByteArray(64 * 1024).also { Random(3).nextBytes(it) })
        val bytes = TorrentBuilder().path(payload).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        return bytes to requireNotNull(TorrentMetainfoReader.preview(bytes).infoHashV1)
    }

    private fun shareDocument(bytes: ByteArray, name: String): Uri {
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val document = requireNotNull(DocumentsContract.createDocument(context.contentResolver,
            root, "application/x-bittorrent", name))
        context.contentResolver.openOutputStream(document, "w").use { requireNotNull(it).write(bytes) }
        return document
    }

    private fun addStoppedFromShare(document: Uri, handling: OriginalTorrentHandling) {
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT == 29) LocalDownloadPlaces.ANDROID_10_STORAGE_PERMISSIONS.forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        waitForText("Original .torrent file")
        rule.onNodeWithTag(originalHandlingTag(handling)).performScrollTo().performClick()
        val start = hasText("Start when added") and hasClickAction()
        if (rule.onAllNodes(start).fetchSemanticsNodes().isNotEmpty() &&
            rule.onNode(start).fetchSemanticsNode().config
                .getOrElseNullable(SemanticsProperties.ToggleableState) { null }?.name == "On")
            rule.onNode(start).performScrollTo().performClick()
        val add = hasText("Add transfer") and hasClickAction()
        waitFor { rule.onAllNodes(add).fetchSemanticsNodes().any {
            it.config.getOrElseNullable(SemanticsProperties.Disabled) { null } == null } }
        rule.onNode(add).performScrollTo().performClick()
        assertTrue(document.toString().isNotEmpty())
    }

    private fun intent(document: Uri) = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_SEND
        type = "application/x-bittorrent"
        putExtra(Intent.EXTRA_STREAM, document)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun stillReadable(document: Uri): Boolean = try {
        context.contentResolver.openInputStream(document)?.use { true } ?: false
    } catch (_: FileNotFoundException) { false }

    private fun waitForExactStoppedJob(hash: String) = waitFor(45_000) {
        LocalEngineHolder.current()?.status(hash)?.stopped == true
    }

    @Test fun archiveKeepsThePrivateCopyAndTheOriginal() {
        val (bytes, hash) = torrent()
        hashes += hash
        val document = shareDocument(bytes, "archive-me.torrent")
        ActivityScenario.launch<MainActivity>(intent(document)).use {
            addStoppedFromShare(document, OriginalTorrentHandling.ARCHIVE)
            waitForExactStoppedJob(hash)
            waitForText("archived in Chunks; the original was kept")
        }
        val archived = File(context.filesDir, SourceTorrentArchive.DIRECTORY).listFiles().orEmpty()
            .filter { it.name.startsWith("$hash-") && it.name.endsWith(".torrent") }
        assertEquals("one exact private archive copy", 1, archived.size)
        assertArrayEquals(bytes, archived.single().readBytes())
        assertTrue("Archive never removes the original", stillReadable(document))
    }

    @Test fun deleteNeedsItsOwnConfirmationAndRemovesOnlyTheOriginal() {
        val (bytes, hash) = torrent()
        hashes += hash
        val document = shareDocument(bytes, "delete-me.torrent")
        val sibling = shareDocument("unrelated".toByteArray(), "unrelated.torrent")
        ActivityScenario.launch<MainActivity>(intent(document)).use {
            addStoppedFromShare(document, OriginalTorrentHandling.DELETE)
            waitForExactStoppedJob(hash)
            waitFor { rule.onAllNodes(hasTestTag(ChunksSemantics.ADD_ORIGINAL_DELETE_PROMPT))
                .fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Nothing is deleted before the separate confirmation", stillReadable(document))
            rule.onNodeWithTag(ChunksSemantics.ADD_ORIGINAL_DELETE_CONFIRM).performScrollTo().performClick()
            waitForText("The original .torrent file was deleted")
        }
        if (stillReadable(document)) fail("The confirmed original must be gone")
        assertTrue("Unrelated documents are untouched", stillReadable(sibling))
        assertTrue("The verified transfer stays", LocalEngineHolder.current()?.status(hash) != null)
    }
}
