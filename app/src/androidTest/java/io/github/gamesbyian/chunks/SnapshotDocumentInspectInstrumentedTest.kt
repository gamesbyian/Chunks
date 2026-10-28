package io.github.gamesbyian.chunks

import android.provider.DocumentsContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.snapshots.SnapshotDocumentInspectScreen
import io.github.gamesbyian.chunks.snapshots.SnapshotDocumentReaderV1
import io.github.gamesbyian.chunks.snapshots.SnapshotManifestV1
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotDocumentInspectInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = FixtureDocumentsProvider.baseDir(context)
    private val hash = "7".repeat(40)

    @Before fun clean() {
        base.deleteRecursively()
        base.mkdirs()
    }
    @After fun tidy() { base.deleteRecursively() }

    private fun doc(bytes: ByteArray): android.net.Uri {
        val resolver = context.contentResolver
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = requireNotNull(DocumentsContract.createDocument(
            resolver, root, "application/octet-stream", "fixture.chunksnap"))
        resolver.openOutputStream(uri, "wt").use { requireNotNull(it).write(bytes) }
        return uri
    }

    @Test fun readsRealAndroidDocumentAndVerifiesInventoryOnlyCapabilities() {
        val manifest = SnapshotManifestV1.Manifest(
            producer = "chunks-android",
            createdAtUtc = "2026-10-27T20:00:00Z",
            source = SnapshotManifestV1.Source.RTORRENT,
            items = listOf(
                SnapshotManifestV1.Item(hash, "Public fixture",
                    SnapshotManifestV1.Recovery.INVENTORY))
        )
        val uri = doc(SnapshotManifestV1.encode(manifest, emptyMap()))
        val checked = SnapshotDocumentReaderV1.inspect(context.contentResolver, uri)
        assertEquals(manifest, checked.manifest)
        assertEquals(0, checked.restorableCount)
        assertEquals(1, checked.inventoryOnlyCount)
        assertTrue(checked.metainfoByHash.isEmpty())
    }

    @Test fun corruptedDocumentIsRefusedBeforeAnyTorrentMutation() {
        val uri = doc("not a snapshot".toByteArray(Charsets.UTF_8))
        try {
            SnapshotDocumentReaderV1.inspect(context.contentResolver, uri)
            fail("Malformed archive must fail closed")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun screenClearlySaysReadOnlyAndUnimplementedRestore() {
        compose.setContent {
            MaterialTheme { SnapshotDocumentInspectScreen(onBack = {}) }
        }
        compose.onNodeWithText("Inspect transfer snapshot").assertExists()
        compose.onNodeWithText("Choose .chunksnap file").assertExists()
        // The read-only guarantee survives UI copy changes for the separate,
        // user-reviewed verified original .torrent document export.
        compose.onNodeWithText(
            "No transfer is added, started, stopped, deleted or altered.",
            substring = true
        ).assertExists()
    }
}
