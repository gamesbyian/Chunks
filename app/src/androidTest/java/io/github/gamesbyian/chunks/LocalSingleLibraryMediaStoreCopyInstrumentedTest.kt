package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/** Real in-process native completed torrent -> Compose -> new owned Downloads file. */
@RunWith(AndroidJUnit4::class)
class LocalSingleLibraryMediaStoreCopyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Test fun seedingSingleFileCopiesThroughUiWhileOriginalAndExistingCanaryStayUntouched() {
        val root = File(context.filesDir, "single-library-export-test")
        assertTrue("Disposable fixture root must be fresh", root.mkdir())
        var engine: LocalTorrentEngine? = null
        var canary: android.net.Uri? = null
        var copyUri: android.net.Uri? = null
        try {
            val source = File(root, "Source").apply { assertTrue(mkdir()) }
            val original = ByteArray(275_000).also { Random(7471).nextBytes(it) }
            val file = File(source, "notes.bin").apply { writeBytes(original) }
            val torrent = TorrentBuilder().path(source).pieceSize(16 * 1024)
                .generate().entry().bencode()
            val local = LocalTorrentEngine(
                LocalEngineConfig(listenInterfaces = "127.0.0.1:0")).also {
                    it.start(); engine = it
                }
            val added = (local.addStopped(torrent, root) as LocalAddResult.AddedStopped).status
            local.resume(added.infoHash)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (local.status(added.infoHash)?.state != LocalTransferState.SEEDING) {
                if (System.nanoTime() > deadline) fail("Native fixture never entered verified seeding")
                Thread.sleep(100)
            }
            val status = requireNotNull(local.status(added.infoHash))
            val sourceSelection = requireNotNull(LocalLibraryCopySource.of(local, status))
            assertEquals(1, sourceSelection.files.size)
            assertEquals("notes.bin", sourceSelection.files.single().outputPath)

            val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            canary = requireNotNull(resolver.insert(downloads, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "notes.bin")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Chunks Exports/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            val foreign = "unrelated existing library bytes".toByteArray()
            requireNotNull(resolver.openOutputStream(canary, "w")).use { it.write(foreign) }
            assertEquals(1, resolver.update(canary, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))

            val result = AtomicReference<LocalSingleLibraryMediaStoreCopy.Result?>()
            compose.setContent {
                MaterialTheme {
                    LocalLibraryCopyPanel(status, engineProvider = { local }, onResult = { result.set(it) })
                }
            }
            compose.onNodeWithText("Copy single file to Downloads").performClick()
            compose.waitUntil(90_000) { result.get() != null }
            val response = requireNotNull(result.get())
            assertEquals(response.message, LocalSingleLibraryMediaStoreCopy.State.COPIED, response.state)
            copyUri = requireNotNull(response.publishedUri)
            compose.onNodeWithText("A distinct verified Downloads copy was created", substring = true).assertExists()
            assertNotEquals(canary, copyUri)
            assertArrayEquals(original, requireNotNull(resolver.openInputStream(copyUri)).use { it.readBytes() })
            assertArrayEquals(foreign, requireNotNull(resolver.openInputStream(canary)).use { it.readBytes() })
            assertArrayEquals(original, file.readBytes())
            assertEquals(LocalTransferState.SEEDING, local.status(added.infoHash)?.state)
        } finally {
            // Test-owned, exact returned MediaStore IDs only.
            copyUri?.let { assertEquals(1, resolver.delete(it, null, null)) }
            canary?.let { assertEquals(1, resolver.delete(it, null, null)) }
            engine?.close()
            root.deleteRecursively()
        }
    }

    /**
     * The native transfer remains complete, but an external actor replaces
     * its source filename AFTER Chunks pins an original FD. A second path
     * open would observe the replacement. The production adapter must refuse
     * before any new MediaStore insert, without deleting either source file.
     */
    @Test fun pathnameSwapDuringPinnedLibraryReadRefusesBeforeDestinationCreation() {
        val root = File(context.filesDir, "single-library-swap-test")
        assertTrue("Disposable source-swap fixture must be fresh", root.mkdir())
        var engine: LocalTorrentEngine? = null
        try {
            val sourceDir = File(root, "Source").apply { assertTrue(mkdir()) }
            val original = ByteArray(221_000).also { Random(9101).nextBytes(it) }
            val replacement = ByteArray(221_000).also { Random(9102).nextBytes(it) }
            val sourceFile = File(sourceDir, "replace-race.bin").apply { writeBytes(original) }
            val retired = File(sourceDir, "retired-original.bin")
            val torrent = TorrentBuilder().path(sourceDir).pieceSize(16 * 1024)
                .generate().entry().bencode()
            val local = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
            engine = local
            local.start()
            val added = (local.addStopped(torrent, root) as LocalAddResult.AddedStopped).status
            local.resume(added.infoHash)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (local.status(added.infoHash)?.state != LocalTransferState.SEEDING) {
                if (System.nanoTime() > deadline) fail("Race fixture never reached verified seeding")
                Thread.sleep(100)
            }
            val wanted = requireNotNull(LocalLibraryCopySource.of(
                local, requireNotNull(local.status(added.infoHash))))
            assertEquals(1, wanted.files.size)
            assertEquals(sourceFile.canonicalPath, wanted.files.single().source.canonicalPath)

            var callbacks = 0
            val result = LocalSingleLibraryMediaStoreCopy.copyNew(context, local, added.infoHash) {
                callbacks++
                if (callbacks == 3) {
                    // The callback executes inside the FIRST pinned-FD pread
                    // pass, after original fstat/lstat identity checks.
                    assertTrue("Original source rename must be possible",
                        sourceFile.renameTo(retired))
                    sourceFile.writeBytes(replacement)
                }
                false
            }
            assertTrue("The fixture must actually replace the source during a pinned read",
                callbacks >= 3)
            assertEquals("A swapped filename must be refused before an insert",
                LocalSingleLibraryMediaStoreCopy.State.BLOCKED, result.state)
            assertNull("No destination row may be returned for a rejected source",
                result.publishedUri)
            assertArrayEquals("Original native seeding bytes must remain untouched",
                original, retired.readBytes())
            assertArrayEquals("The planted replacement must not be cleaned up or overwritten",
                replacement, sourceFile.readBytes())
        } finally {
            engine?.close()
            // Test-private fixture only, never a user's torrent download root.
            root.deleteRecursively()
        }
    }
}
