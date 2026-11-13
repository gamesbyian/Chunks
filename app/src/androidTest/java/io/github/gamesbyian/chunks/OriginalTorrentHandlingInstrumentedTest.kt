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
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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

    /**
     * WORK-QUEUE R3 installed UI: exact-path partial data already in the reviewed Place is
     * verified straight after a stopped Add, which stays stopped and downloads nothing.
     * The fixture writes only its own uniquely named file and deletes only that file.
     */
    @Test fun stoppedAddOverExistingPartialDataShowsVerifiedShareAndStaysStopped() {
        val source = File(work, "partial-src").apply { mkdirs() }
        val name = "chunks-stopped-verify-${System.nanoTime().toString(16)}.bin"
        val payload = ByteArray(16 * 16 * 1024).also { Random(17).nextBytes(it) }
        File(source, name).writeBytes(payload)
        val bytes = TorrentBuilder().path(File(source, name)).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val hash = requireNotNull(TorrentMetainfoReader.preview(bytes).infoHashV1)
        hashes += hash
        val document = shareDocument(bytes, "partial.torrent")
        var placed: File? = null
        try {
            ActivityScenario.launch<MainActivity>(intent(document)).use {
                waitForText("Original .torrent file")
                // The review states the exact folder this Add writes into.
                val saves = rule.onAllNodes(hasText("Saves into:", substring = true)).fetchSemanticsNodes()
                    .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
                    .map { it.text }.first { it.startsWith("Saves into:") }
                val folder = File(saves.substringAfterLast("(").substringBeforeLast(")"))
                assertTrue("reviewed Place exists: $saves", folder.isDirectory)
                val target = File(folder, name)
                assertTrue("fixture name is unique", !target.exists())
                // Six whole valid pieces at the exact torrent path; nothing else.
                target.writeBytes(payload.copyOf(6 * 16 * 1024))
                placed = target
                addStoppedFromShare(document, OriginalTorrentHandling.KEEP)
                waitForText("Checking the files already in this folder")
                waitFor(60_000) {
                    LocalEngineHolder.current()?.status(hash)?.let {
                        it.stopped && !it.stopsAfterCheck && it.doneBytes == 6L * 16 * 1024
                    } == true
                }
                Thread.sleep(1_500)
                val idle = requireNotNull(LocalEngineHolder.current()?.status(hash))
                assertTrue("still stopped: $idle", idle.stopped)
                assertEquals("nothing downloaded while stopped", 0L, idle.payloadDownloadedBytes)
                assertArrayEquals("verification never writes payload", payload.copyOf(6 * 16 * 1024), target.readBytes())
                assertTrue("Keep leaves the original", stillReadable(document))
            }
        } finally {
            placed?.delete()
        }
    }
}
