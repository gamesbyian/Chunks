package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.model.FileZipExtractionJournal
import io.github.gamesbyian.chunks.model.ZipExtractionStatus
import io.github.gamesbyian.chunks.model.ZipExtractor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android ContentResolver + DocumentsContract extraction into a debug-only,
 * disposable DocumentsProvider tree. Not proof for every vendor provider or SD card.
 */
@RunWith(AndroidJUnit4::class)
class LocalZipExtractionInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = FixtureDocumentsProvider.baseDir(context)
    private val tree get() = FixtureDocumentsProvider.treeUri(context)
    private val journals get() = File(context.filesDir, "zip-extractions")

    @Before fun clean() {
        base.deleteRecursively(); base.mkdirs()
        journals.deleteRecursively()
    }

    @After fun tidy() {
        base.deleteRecursively()
        journals.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun <T> withDocument(bytes: ByteArray, block: (android.net.Uri) -> T): T {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "chunks-extract-test.zip")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ChunksTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            resolver.openOutputStream(uri, "w").use { requireNotNull(it).write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return block(uri)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { stream ->
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, requireNotNull(stream)))
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    private fun stagedCopies(): Set<String> = context.cacheDir.listFiles().orEmpty().map { it.name }
        .filter { it.startsWith("chunks-zip-review-") }.toSet()

    @Test fun extractsThroughDocumentsContractIntoANewFolderAndKeepsExistingData() {
        val episode = ByteArray(90_000) { (it * 31 % 251).toByte() }
        File(base, "chunks-extract-test").mkdirs()
        File(base, "chunks-extract-test/notes.txt").writeText("user's own notes")
        val before = stagedCopies()
        withDocument(zip("Season 1/Episode 01.bin" to episode, "notes.txt" to "archive".toByteArray())) { source ->
            val result = LocalZipExtraction.extract(context, source, tree, "chunks-extract-test.zip",
                allowUnknownFreeSpace = true) { false }
            assertEquals(result.reason, ZipExtractionStatus.EXTRACTED, result.status)
            assertEquals("chunks-extract-test (2)", result.folderName)
            assertArrayEquals(episode, File(base, "chunks-extract-test (2)/Season 1/Episode 01.bin").readBytes())
            assertEquals("archive", File(base, "chunks-extract-test (2)/notes.txt").readText())
            assertEquals("user's own notes", File(base, "chunks-extract-test/notes.txt").readText())
            // The source document is still readable and unchanged in length.
            context.contentResolver.openInputStream(source).use { assertTrue(requireNotNull(it).readBytes().isNotEmpty()) }
        }
        assertFalse(base.list().orEmpty().any { it.startsWith(ZipExtractor.PARTIAL_PREFIX) })
        assertEquals(before, stagedCopies())
        assertTrue(journals.list().orEmpty().isEmpty())
    }

    @Test fun unknownFreeSpaceAndHostileArchivesWriteNothing() {
        withDocument(zip("a.txt" to "a".toByteArray())) { source ->
            val refused = LocalZipExtraction.extract(context, source, tree, "a.zip", false) { false }
            assertEquals(ZipExtractionStatus.BLOCKED, refused.status)
        }
        withDocument(zip("../escape.txt" to "x".toByteArray())) { source ->
            val refused = LocalZipExtraction.extract(context, source, tree, "bad.zip", true) { false }
            assertEquals(ZipExtractionStatus.BLOCKED, refused.status)
        }
        assertTrue(base.list().orEmpty().isEmpty())
        assertFalse(File(base.parentFile, "escape.txt").exists())
    }

    @Test fun cancellationRemovesPartialOutputThroughTheProvider() {
        val random = java.util.Random(3)
        val payload = ByteArray(400_000).also { random.nextBytes(it) }
        var checks = 0
        withDocument(zip("big.bin" to payload, "second.bin" to payload)) { source ->
            val result = LocalZipExtraction.extract(context, source, tree, "big.zip", true) { ++checks > 6 }
            assertEquals(ZipExtractionStatus.CANCELLED, result.status)
        }
        assertTrue(base.list().orEmpty().isEmpty())
    }

    @Test fun safTargetRefusesExistingNamesWithoutLeavingProviderSubstitutes() {
        File(base, "taken").mkdirs()
        File(base, "file.txt").writeText("keep")
        val target = SafZipExtractionTarget(context.contentResolver, tree)
        assertNull(target.createDirectory(target.root, "taken"))
        assertNull(target.createFile(target.root, "file.txt"))
        val dir = requireNotNull(target.createDirectory(target.root, "fresh"))
        requireNotNull(target.createFile(dir, "inside.txt"))
        assertFalse("Non-empty folders must not be deleted", target.delete(dir))
        assertNull(target.rename(dir, target.root, "taken"))
        assertEquals(setOf("taken", "file.txt", "fresh"), base.list().orEmpty().toSet())
        assertEquals("keep", File(base, "file.txt").readText())
    }

    @Test fun interruptedJournalIsRolledBackOnNextVisitWithoutReextracting() {
        val operation = "0123456789abcdef0123456789abcdef"
        val partial = File(base, ZipExtractor.PARTIAL_PREFIX + operation).apply { mkdirs() }
        File(partial, "d").mkdirs()
        File(partial, "d/half.bin").writeBytes(ByteArray(10))
        journals.mkdirs()
        val journal = FileZipExtractionJournal(File(journals, "$operation.log"))
        journal.append(listOf("BEGIN", operation, tree.toString(), partial.name, "Pack"))
        journal.append(listOf("DIR", "d"))
        journal.append(listOf("FILE", "d/half.bin"))

        val results = LocalZipExtraction.reconcilePending(context)
        assertEquals(1, results.size)
        assertEquals(ZipExtractionStatus.FAILED_ROLLED_BACK, results.single().status)
        assertTrue(base.list().orEmpty().isEmpty())
        assertTrue(journals.list().orEmpty().isEmpty())
    }

    @Test fun confirmationRequiresAcknowledgingUnknownSpaceAndRendersNatively() {
        var confirmed: Boolean? = null
        rule.setContent {
            MaterialTheme {
                LocalZipExtractionConfirm(
                    LocalZipExtractionPlan("Show pack.zip", "Downloads", "Show pack", 52_428_800, 12, null),
                    onConfirm = { confirmed = it }, onDismiss = {})
            }
        }
        rule.onNodeWithText("Extract to new folder").assertIsNotEnabled()
        capture("zip-extract-confirm-unknown-space-real-compose")
        rule.onNodeWithText("can't report free space", substring = true).performClick()
        rule.onNodeWithText("Extract to new folder").assertIsEnabled().performClick()
        assertEquals(true, confirmed)
    }

    @Test fun insufficientSpaceBlocksConfirmation() {
        rule.setContent {
            MaterialTheme {
                LocalZipExtractionConfirm(
                    LocalZipExtractionPlan("Show pack.zip", "Downloads", "Show pack", 52_428_800, 12, 20_000_000),
                    onConfirm = {}, onDismiss = {})
            }
        }
        rule.onNodeWithText("Not enough free space", substring = true).assertExists()
        rule.onNodeWithText("Extract to new folder").assertIsNotEnabled()
    }

    @Test fun outcomeExplainsResultAndKeptArchive() {
        withDocument(zip("a.txt" to "a".toByteArray())) { source ->
            val result = LocalZipExtraction.extract(context, source, tree, "Show pack.zip", true) { false }
            rule.setContent {
                MaterialTheme {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp)) {
                        LocalZipExtractionOutcome(result, "Show pack.zip")
                    }
                }
            }
            rule.onNodeWithText("into the new folder \"Show pack\". The ZIP was kept.", substring = true).assertExists()
            capture("zip-extract-done-real-compose")
        }
    }
}
