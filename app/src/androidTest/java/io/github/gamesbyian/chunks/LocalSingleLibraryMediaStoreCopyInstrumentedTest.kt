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
}
