package io.github.gamesbyian.chunks

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.snapshots.SnapshotDocumentWriterV1
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses Android's real ContentResolver and our disposable debug-only SAF
 * DocumentsProvider. Does not touch torrent backing bytes or remote sessions.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotDocumentWriterV1InstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = FixtureDocumentsProvider.baseDir(context)

    @Before fun clean() {
        base.deleteRecursively()
        base.mkdirs()
    }

    @After fun tidy() {
        base.deleteRecursively()
    }

    @Test fun directWriterRefusesBeforeChangingAnExistingProviderDocument() {
        val resolver = context.contentResolver
        val tree = FixtureDocumentsProvider.treeUri(context)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val file = requireNotNull(DocumentsContract.createDocument(
            resolver, parent, "application/octet-stream", "chunks-inventory.chunksnap"
        ))
        val foreign = base.resolve("chunks-inventory.chunksnap")
        val original = "foreign existing snapshot bytes".toByteArray()
        foreign.writeBytes(original)
        try {
            SnapshotDocumentWriterV1.writeAndVerify(resolver, file,
                "replacement contents".toByteArray())
            throw AssertionError("Unproven document URI must be refused")
        } catch (_: java.io.IOException) { }
        assertArrayEquals(original, foreign.readBytes())
    }

    @Test fun refusesEmptyAndOversizedContentBeforeCreatingOrWritingAnything() {
        val resolver = context.contentResolver
        val tree = FixtureDocumentsProvider.treeUri(context)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val file = requireNotNull(DocumentsContract.createDocument(
            resolver, parent, "application/octet-stream", "empty.chunksnap"
        ))
        try {
            SnapshotDocumentWriterV1.writeAndVerify(resolver, file, byteArrayOf())
            throw AssertionError("Empty snapshots must be refused")
        } catch (_: IllegalArgumentException) { }
        assertFalse(base.resolve("empty.chunksnap").length() > 0L)
        try {
            SnapshotDocumentWriterV1.writeAndVerify(resolver, file, ByteArray(64 * 1024 * 1024 + 1))
            throw AssertionError("Oversized snapshots must be refused")
        } catch (_: IllegalArgumentException) { }
        assertFalse(base.resolve("empty.chunksnap").length() > 0L)
    }
}
