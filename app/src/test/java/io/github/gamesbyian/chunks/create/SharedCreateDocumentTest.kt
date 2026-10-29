package io.github.gamesbyian.chunks.create

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.SharedTorrentDocument
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedCreateDocumentTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val doc = Uri.parse("content://media.example/item/7")

    private fun share(alias: Boolean, type: String = "image/jpeg", stream: Uri? = doc) = Intent(Intent.ACTION_SEND).apply {
        component = ComponentName(context.packageName,
            if (alias) SharedCreateDocument.ALIAS else "io.github.gamesbyian.chunks.MainActivity")
        this.type = type
        stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
    }

    @Test fun onlyTheCreateTargetIsHandledAndATorrentShareKeepsItsAddReview() {
        assertEquals(SharedCreateDocument.Request.File(doc), SharedCreateDocument.fromIntent(share(alias = true)))
        assertNull(SharedCreateDocument.fromIntent(share(alias = false)))
        // A .torrent shared to Chunks itself is still the Add review's, not Create's.
        val torrent = share(alias = false, type = "application/x-bittorrent")
        assertNull(SharedCreateDocument.fromIntent(torrent))
        assertEquals(doc, SharedTorrentDocument.fromIntent(torrent))
    }

    @Test fun textSeveralFilesConflictsAndNonContentUrisAreExplainedNotRead() {
        assertTrue(SharedCreateDocument.fromIntent(share(alias = true, type = "text/plain", stream = null))
            is SharedCreateDocument.Request.Unusable)
        val many = share(alias = true).apply {
            clipData = ClipData.newRawUri("a", doc).apply { addItem(ClipData.Item(Uri.parse("content://media.example/item/8"))) }
        }
        assertTrue(SharedCreateDocument.fromIntent(many) is SharedCreateDocument.Request.Unusable)
        val conflicting = share(alias = true).apply { clipData = ClipData.newRawUri("a", Uri.parse("content://other/9")) }
        assertTrue(SharedCreateDocument.fromIntent(conflicting) is SharedCreateDocument.Request.Unusable)
        assertTrue(SharedCreateDocument.fromIntent(share(alias = true, stream = Uri.parse("file:///sdcard/x.jpg")))
            is SharedCreateDocument.Request.Unusable)
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            component = ComponentName(context.packageName, SharedCreateDocument.ALIAS)
        }
        assertTrue(SharedCreateDocument.fromIntent(multiple) is SharedCreateDocument.Request.Unusable)
    }

    @Test fun aSharedFileOpensTheCreateReviewListedButNotYetBuilt() {
        Robolectric.setupContentProvider(CreateTorrentSourceTest.FakeProvider::class.java, CreateTorrentSourceTest.AUTHORITY)
        val tree = DocumentsContract.buildTreeDocumentUri(CreateTorrentSourceTest.AUTHORITY, "root")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "c")
        shadowOf(context.contentResolver).registerInputStreamSupplier(document) { CreateTorrentSourceTest.bytes("c").inputStream() }
        var consumed = false
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                CreateTorrentScreen(onBack = {}, shared = SharedCreateDocument.Request.File(document),
                    onSharedConsumed = { consumed = true })
            } }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("c.mp4").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Create torrent").assertExists()
        rule.onNodeWithText("Torrent created").assertDoesNotExist()
        assertTrue(consumed)
    }
}
