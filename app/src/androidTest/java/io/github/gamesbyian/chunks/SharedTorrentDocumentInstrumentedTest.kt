package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedTorrentDocumentInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = FixtureDocumentsProvider.baseDir(context)
    private val goodBytes: ByteArray get() =
        ("d8:announce14:http://ignored4:info" +
        "d6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789ee").toByteArray(Charsets.UTF_8)

    @Before fun setUp() { base.deleteRecursively(); base.mkdirs() }
    @After fun tearDown() { base.deleteRecursively() }

    private fun document(bytes: ByteArray): Uri {
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = requireNotNull(DocumentsContract.createDocument(
            context.contentResolver, root, "application/x-bittorrent", "sample.torrent"))
        context.contentResolver.openOutputStream(uri, "wt").use {
            requireNotNull(it).write(bytes)
        }
        return uri
    }

    @Test fun androidShareChoosesExactlyOneSourceWhenTorrentAndTextBothExist() {
        val uri = document(goodBytes)
        val magnet = "magnet:?xt=urn:btih:" + "b".repeat(40) + "&dn=Shared"
        val both = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-bittorrent"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, magnet)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chosen = ExternalAddSource.fromIntent(both)
        assertTrue("An explicit content document must outrank incidental shared text",
            chosen is ExternalAddSource.TorrentDocument)
        assertEquals(uri, (chosen as ExternalAddSource.TorrentDocument).uri)
        val onlyMagnet = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, magnet)
        }
        assertEquals(ExternalAddSource.Magnet(magnet),
            ExternalAddSource.fromIntent(onlyMagnet))
        assertNull(ExternalAddSource.fromIntent(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "not a torrent")
        }))
    }

    @Test fun androidViewsAndSharesAcceptOnlyExplicitTorrentContent() {
        val good = document(goodBytes)
        assertEquals(good, SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(good, "application/x-bittorrent")
            }))
        assertEquals(good, SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-bittorrent"
                putExtra(Intent.EXTRA_STREAM, good)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }))
        assertEquals(good, SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-torrent"
                clipData = android.content.ClipData.newUri(context.contentResolver, "torrent", good)
            }))
        assertNull(SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_VIEW).apply { setDataAndType(good, "application/pdf") }))
        assertNull(SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("file:///sdcard/Download/sample.torrent"),
                    "application/x-bittorrent")
            }))
        assertNull(SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("https://example.test/sample.torrent"),
                    "application/x-bittorrent")
            }))
        assertNull(SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "application/x-bittorrent" }))
    }

    @Test fun malformedStreamExtraCannotCrashOrSubstituteAClipDocumentOrMagnet() {
        val valid = document(goodBytes)
        val magnet = "magnet:?xt=urn:btih:" + "b".repeat(40)
        // Both the typed Parcelable cast and the fail-closed source gate
        // matter: modern Android may throw for a bad type or return null.
        for (wrong in listOf<Any>("content://fixture.documents/other.torrent", 42)) {
            val shared = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-bittorrent"
                when (wrong) {
                    is String -> putExtra(Intent.EXTRA_STREAM, wrong)
                    is Int -> putExtra(Intent.EXTRA_STREAM, wrong)
                }
                clipData = android.content.ClipData.newUri(
                    context.contentResolver, "valid alternate document", valid)
                putExtra(Intent.EXTRA_TEXT, magnet)
            }
            assertNull("Invalid explicit EXTRA_STREAM may not choose a different ClipData URI",
                SharedTorrentDocument.fromIntent(shared))
            assertNull("Invalid document must not silently fall through to magnet",
                ExternalAddSource.fromIntent(shared))
        }
        // A correctly typed EXTRA_STREAM retains its original exact URI
        // identity even with an incidental magnet string present.
        assertEquals(ExternalAddSource.TorrentDocument(valid),
            ExternalAddSource.fromIntent(Intent(Intent.ACTION_SEND).apply {
                type = "application/x-bittorrent"
                putExtra(Intent.EXTRA_STREAM, valid)
                putExtra(Intent.EXTRA_TEXT, magnet)
            }))
    }

    @Test fun conflictingOrMultipleSharedUrisNeverPickAnArbitraryTorrent() {
        val first = document(goodBytes)
        val second = Uri.parse("content://fixture.documents/download/other.torrent")
        val matching = android.content.ClipData.newUri(
            context.contentResolver, "torrent", first)
        assertEquals(first, SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-bittorrent"
                putExtra(Intent.EXTRA_STREAM, first)
                clipData = matching
            }))
        // EXTRA_STREAM alone and one valid ClipData URI remain supported.
        assertEquals(first, SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-bittorrent"
                putExtra(Intent.EXTRA_STREAM, first)
            }))
        assertNull(SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-bittorrent"
                putExtra(Intent.EXTRA_STREAM, first)
                clipData = android.content.ClipData.newUri(
                    context.contentResolver, "different torrent", second)
            }))
        val multiple = android.content.ClipData.newUri(
            context.contentResolver, "first torrent", first).apply {
            addItem(android.content.ClipData.Item(second))
        }
        assertNull(SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-bittorrent"
                putExtra(Intent.EXTRA_STREAM, first)
                clipData = multiple
            }))
        assertNull(SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-bittorrent"
                clipData = multiple
            }))
    }

    @Test fun invalidDocumentSharesCannotSilentlyBecomeIncidentalMagnetDrafts() {
        val first = document(goodBytes)
        val second = Uri.parse("content://fixture.documents/download/another.torrent")
        val magnet = "magnet:?xt=urn:btih:" + "b".repeat(40)
        val conflicting = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-bittorrent"
            putExtra(Intent.EXTRA_STREAM, first)
            clipData = android.content.ClipData.newUri(
                context.contentResolver, "other torrent", second)
            putExtra(Intent.EXTRA_TEXT, magnet)
        }
        assertNull("Conflicting documents must not fall through to shared text",
            ExternalAddSource.fromIntent(conflicting))

        val multiple = android.content.ClipData.newUri(
            context.contentResolver, "first torrent", first).apply {
            addItem(android.content.ClipData.Item(second))
        }
        assertNull(ExternalAddSource.fromIntent(Intent(Intent.ACTION_SEND).apply {
            type = "application/x-bittorrent"
            clipData = multiple
            putExtra(Intent.EXTRA_TEXT, magnet)
        }))

        assertNull("An explicitly shared document with the wrong MIME is refused",
            ExternalAddSource.fromIntent(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, first)
                putExtra(Intent.EXTRA_TEXT, magnet)
            }))

        // A genuine text share must still work when Android also supplies
        // text-only ClipData; there is no competing document URI.
        assertEquals(ExternalAddSource.Magnet(magnet),
            ExternalAddSource.fromIntent(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                clipData = android.content.ClipData.newPlainText("magnet", magnet)
                putExtra(Intent.EXTRA_TEXT, magnet)
            }))
    }

    @Test fun grantedDocumentUsesExistingStrictV1MetainfoParserOnly() {
        val uri = document(goodBytes)
        val result = SharedTorrentDocument.read(context.contentResolver, uri)
        assertArrayEquals(goodBytes, result.bytes)
        assertEquals("test.txt", result.preview.name)
        assertEquals(5L, result.preview.sizeBytes)
        assertEquals(TorrentMetainfoReader.preview(goodBytes).infoHashV1,
            result.preview.infoHashV1)
    }

    @Test fun sharedMetainfoStreamParserRefusesProvidersThatMakeNoProgress() {
        val zeros = object : java.io.InputStream() {
            var reads = 0
            override fun read(): Int = -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                return 0
            }
        }
        try {
            SharedTorrentDocument.readStream(zeros)
            fail("Zero-byte read loop must be bounded, including in remote Add picker")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("no torrent bytes"))
        }
        assertEquals(17, zeros.reads)

        // Both VIEW/SEND and remote picker use the same strict v1 parser.
        val reviewed = SharedTorrentDocument.readStream(
            java.io.ByteArrayInputStream(goodBytes))
        assertArrayEquals(goodBytes, reviewed.bytes)
        assertEquals(TorrentMetainfoReader.preview(goodBytes).infoHashV1,
            reviewed.preview.infoHashV1)
    }

    @Test fun invalidAndOversizedContentCannotProduceReviewedTorrent() {
        val bad = document("not bencoded torrent".toByteArray())
        try {
            SharedTorrentDocument.read(context.contentResolver, bad)
            fail("Invalid metainfo must be rejected before local Add review")
        } catch (_: RuntimeException) { }
        val huge = document(ByteArray(TorrentMetainfoReader.MAX_BYTES + 1) { 0x41 })
        try {
            SharedTorrentDocument.read(context.contentResolver, huge)
            fail("Oversize external torrent must fail without engine access")
        } catch (_: java.io.IOException) { }
        try {
            SharedTorrentDocument.read(context.contentResolver,
                Uri.parse("file:///sdcard/Download/file.torrent"))
            fail("Direct file paths must not be accepted from external intents")
        } catch (_: IllegalArgumentException) { }
    }
}
