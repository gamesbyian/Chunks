package io.github.gamesbyian.chunks

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/**
 * Source file handling for a user's original .torrent document (SMART-ROUTING-AND-SOURCE-LIFECYCLE):
 * Archive is the default; Delete removes only the exact reviewed document after its own approval and
 * reports deletion only from a fresh absence read. Payload files are never involved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class OriginalTorrentHandlingTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val authority = "chunks.original-handling.test"
    private val metainfo = ("d4:infod6:lengthi5e4:name4:a.mk12:piece lengthi16384e" +
        "6:pieces20:01234567890123456789ee").toByteArray()

    class Provider : ContentProvider() {
        companion object { lateinit var root: File }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/x-bittorrent"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = File(root, requireNotNull(uri.lastPathSegment))
            if (!file.exists()) throw FileNotFoundException(uri.lastPathSegment)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private val root get() = File(context.cacheDir, "original-handling").apply { mkdirs() }
    private fun uri(name: String) = Uri.parse("content://$authority/$name")
    private fun request(name: String, reviewed: ByteArray = metainfo) =
        OriginalDeletionRequest(uri(name), name, OriginalTorrentDocuments.sha256(reviewed), "Show")

    @Before fun register() {
        Provider.root = root
        val provider = Robolectric.buildContentProvider(Provider::class.java)
            .create(ProviderInfo().apply { authority = this@OriginalTorrentHandlingTest.authority }).get()
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @Test fun archiveIsTheDefaultAndAChosenDefaultPersists() {
        val store = OriginalTorrentHandlingStore(context)
        assertEquals(OriginalTorrentHandling.ARCHIVE, store.default)
        assertTrue(store.setDefault(OriginalTorrentHandling.KEEP))
        assertEquals(OriginalTorrentHandling.KEEP, OriginalTorrentHandlingStore(context).default)
    }

    @Test fun deleteRemovesOnlyAnUnchangedReviewedDocumentAndNeedsFreshAbsence() {
        val resolver = context.contentResolver
        val file = File(root, "exact.torrent").apply { writeBytes(metainfo) }
        val calls = AtomicInteger()
        assertEquals(OriginalDeletionOutcome.DELETED, OriginalTorrentDocuments.deleteReviewed(
            resolver, request("exact.torrent")) { calls.incrementAndGet(); file.delete() })
        assertEquals(1, calls.get())

        // Changed since review: never deleted.
        File(root, "changed.torrent").writeBytes(metainfo.copyOf().also { it[it.size - 3] = 'X'.code.toByte() })
        assertEquals(OriginalDeletionOutcome.CHANGED, OriginalTorrentDocuments.deleteReviewed(
            resolver, request("changed.torrent")) { calls.incrementAndGet(); true })
        assertEquals("no delete call for a changed document", 1, calls.get())
        // Unreadable: kept, not deleted.
        assertEquals(OriginalDeletionOutcome.CHANGED, OriginalTorrentDocuments.deleteReviewed(
            resolver, request("missing.torrent")) { calls.incrementAndGet(); true })
        assertEquals(1, calls.get())

        // Provider refusal and SecurityException are reported as kept.
        File(root, "refused.torrent").writeBytes(metainfo)
        assertEquals(OriginalDeletionOutcome.REFUSED, OriginalTorrentDocuments.deleteReviewed(
            resolver, request("refused.torrent")) { false })
        assertEquals(OriginalDeletionOutcome.REFUSED, OriginalTorrentDocuments.deleteReviewed(
            resolver, request("refused.torrent")) { throw SecurityException("no write grant") })
        // A claimed delete that leaves the document readable is not a deletion.
        assertEquals(OriginalDeletionOutcome.UNCONFIRMED, OriginalTorrentDocuments.deleteReviewed(
            resolver, request("refused.torrent")) { true })
        assertTrue(File(root, "refused.torrent").exists())
    }

    @Test fun choiceIsOneAccessibleSingleSelectGroupOf48dpRows() {
        var selected by mutableStateOf(OriginalTorrentHandling.ARCHIVE)
        rule.setContent { ChunksTheme { OriginalTorrentHandlingChoice("Original .torrent file", selected,
            enabled = true, onSelect = { selected = it }) } }
        for (choice in OriginalTorrentHandling.entries)
            rule.onNodeWithTag(originalHandlingTag(choice)).assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.RadioButton))
        rule.onNodeWithTag(originalHandlingTag(OriginalTorrentHandling.ARCHIVE)).assertIsSelected()
        rule.onNodeWithTag(originalHandlingTag(OriginalTorrentHandling.DELETE)).performClick()
        rule.onNodeWithTag(originalHandlingTag(OriginalTorrentHandling.DELETE)).assertIsSelected()
        rule.onNodeWithTag(originalHandlingTag(OriginalTorrentHandling.ARCHIVE)).assertIsNotSelected()
        assertEquals(OriginalTorrentHandling.DELETE, selected)
    }

    @Test fun deletePromptIsSeparateAndExplicit() {
        val deletes = AtomicInteger()
        val keeps = AtomicInteger()
        rule.setContent { ChunksTheme { OriginalDeletionPrompt(request("x.torrent"), busy = false,
            onDelete = { deletes.incrementAndGet() }, onKeep = { keeps.incrementAndGet() }) } }
        rule.onNodeWithTag(ChunksSemantics.ADD_ORIGINAL_DELETE_CONFIRM).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, deletes.get())
        assertEquals(0, keeps.get())
    }

    @Test fun archiveOutcomesNeverClaimTheOriginalWasRemoved() {
        assertTrue(archiveOutcomeMessage(SourceArchiveOutcome.ArchivedCopy("x", false))!!.contains("original was kept"))
        for (outcome in listOf(SourceArchiveOutcome.NeedsReconciliation, SourceArchiveOutcome.ArchiveFull,
            SourceArchiveOutcome.IoFailure, SourceArchiveOutcome.UnverifiedAdd))
            assertTrue(outcome.toString(), archiveOutcomeMessage(outcome)!!.contains("original was kept"))
    }

    @Test fun deleteIsOfferedOnlyWhenTheProviderSupportsIt() {
        // A plain shared-content provider is not a deletable document.
        File(root, "shared.torrent").writeBytes(metainfo)
        assertEquals(false, OriginalTorrentDocuments.supportsDelete(context, uri("shared.torrent")))
        rule.setContent { ChunksTheme { OriginalTorrentHandlingChoice("Original .torrent file",
            OriginalTorrentHandling.ARCHIVE, enabled = true, deleteAvailable = false, onSelect = {}) } }
        rule.onNodeWithTag(originalHandlingTag(OriginalTorrentHandling.DELETE)).assertIsNotEnabled()
        rule.onNodeWithTag(originalHandlingTag(OriginalTorrentHandling.KEEP)).assertIsEnabled()
    }

    @Test fun aRefusedDeletePreconditionIsReportedAsKeptNotAsArchive() {
        val text = archiveOutcomeMessage(SourceArchiveOutcome.IdentityMismatch, OriginalTorrentHandling.DELETE)!!
        assertTrue(text, text.contains("original .torrent file was kept"))
        assertTrue(text, !text.contains("archive"))
    }

    @Test fun archiveAsMoveAsksToRemoveAndReportsTheMoveOnlyOnVerifiedAbsence() {
        val moved = OriginalDeletionRequest(uri("m.torrent"), "m.torrent",
            OriginalTorrentDocuments.sha256(metainfo), "Show", archived = true)
        rule.setContent { ChunksTheme { OriginalDeletionPrompt(moved, busy = false, onDelete = {}, onKeep = {}) } }
        rule.onNodeWithTag(ChunksSemantics.ADD_ORIGINAL_DELETE_CONFIRM).assertHeightIsAtLeast(48.dp)
        rule.onNode(androidx.compose.ui.test.hasText("Remove original .torrent")).assertExists()
        assertTrue(originalDeletionMessage(OriginalDeletionOutcome.DELETED, archived = true).contains("moved into Chunks"))
        for (other in listOf(OriginalDeletionOutcome.CHANGED, OriginalDeletionOutcome.REFUSED,
                OriginalDeletionOutcome.UNCONFIRMED))
            assertTrue(!originalDeletionMessage(other, archived = true).contains("moved"))
    }
}
