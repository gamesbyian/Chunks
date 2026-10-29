package io.github.gamesbyian.chunks.create

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Folder and file listing through a fake document provider, as the system picker would return. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CreateTorrentSourceTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** id -> (name, mime, size, children) */
    class Doc(val name: String, val dir: Boolean, val size: Long = 0, val children: List<String> = emptyList())

    class FakeProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val segments = uri.pathSegments
            val docId = segments[3]
            val cursor = MatrixCursor(projection)
            fun row(id: String) {
                val doc = docs.getValue(id)
                cursor.addRow(projection!!.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, OpenableColumns.DISPLAY_NAME -> doc.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE ->
                        if (doc.dir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_SIZE, OpenableColumns.SIZE -> if (doc.dir) null else doc.size
                    else -> null
                } }.toTypedArray())
            }
            if (segments.size == 5 && segments[4] == "children") docs.getValue(docId).children.forEach(::row) else row(docId)
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    companion object {
        const val AUTHORITY = "test.documents"
        val docs = mapOf(
            "root" to Doc("Holiday", true, children = listOf("b", "dir", "a")),
            "a" to Doc("a.jpg", false, 30_000),
            "b" to Doc("b.jpg", false, 20_000),
            "dir" to Doc("Clips", true, children = listOf("c")),
            "c" to Doc("c.mp4", false, 50_001)
        )
        fun bytes(id: String) = ByteArray(docs.getValue(id).size.toInt()) { (it * 31 + id.hashCode()).toByte() }
    }

    private val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")

    @Before fun provider() {
        Robolectric.setupContentProvider(FakeProvider::class.java, AUTHORITY)
        val resolver = shadowOf(context.contentResolver)
        for (id in listOf("a", "b", "c")) resolver.registerInputStreamSupplier(
            DocumentsContract.buildDocumentUriUsingTree(tree, id)) { bytes(id).inputStream() }
    }

    @Test fun folderBecomesAStableNameSortedMultiFileTorrent() {
        val source = CreateTorrentSource.fromTree(context, tree)
        assertEquals("Holiday", source.name)
        assertEquals(listOf("Clips/c.mp4", "a.jpg", "b.jpg"), source.entries.map { it.path.joinToString("/") })
        assertEquals(100_001L, source.totalBytes)
        val built = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(source.name, source.entries))
        val preview = TorrentMetainfoReader.preview(built.torrent)
        assertEquals("Holiday", preview.name)
        assertEquals(3, preview.fileCount)
        assertEquals(100_001L, preview.sizeBytes)
    }

    @Test fun singlePickedFileBecomesASingleFileTorrent() {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "c")
        val source = CreateTorrentSource.fromDocument(context, document)
        assertEquals("c.mp4", source.name)
        val built = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(source.name, source.entries))
        assertEquals(1, TorrentMetainfoReader.preview(built.torrent).fileCount)
    }

    @Test fun cancellingTheListingStopsIt() {
        try {
            CreateTorrentSource.fromTree(context, tree) { true }
            fail("expected cancellation")
        } catch (_: TorrentMetainfoBuilder.Cancelled) { }
    }

    @Test fun torrentFileNamesAreSafeForTheSaveDialog() {
        assertEquals("a_b_c.torrent", fileName("a/b:c"))
        assertEquals("shared.torrent", fileName(null))
    }
}
