package io.github.gamesbyian.chunks

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Production source admission only. The unsupported host storage probe stays refused; no engine Add. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class PhoneDocumentReplacementTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val authority = "chunks.source-replacement.test"
    private val root get() = File(context.cacheDir, "source-replacement-test")
    private val source = mutableStateOf<Uri?>(null)
    private val shown = mutableStateOf(true)
    private val draft = mutableStateOf<PendingLocalAdd?>(null)
    private val consumed = CopyOnWriteArrayList<Uri>()
    private val prepared = AtomicReference<ByteArray?>()
    private var fetchLink: (TorrentLinkCheck.Fetchable) -> TorrentLinkResult =
        { TorrentLinkResult.Refused(TorrentLinkResult.OFFLINE) }
    private fun uri(name: String) = Uri.parse("content://$authority/$name")
    private fun metainfo(name: String) = ("d4:infod6:lengthi5e4:name${name.length}:$name" +
        "12:piece lengthi16384e6:pieces20:01234567890123456789ee").toByteArray()

    class SourceProvider : ContentProvider() {
        companion object {
            lateinit var root: File
            var opened = CountDownLatch(1)
            var release = CountDownLatch(1)
            var returned = CountDownLatch(1)
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/x-bittorrent"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val requested = requireNotNull(uri.lastPathSegment)
            if (requested.startsWith("slow")) {
                opened.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "Controlled provider was not released" }
            }
            val name = when (requested) { "slow" -> "first"; "slow-second" -> "second"; else -> requested }
            return ParcelFileDescriptor.open(File(root, name), ParcelFileDescriptor.MODE_READ_ONLY).also {
                if (requested.startsWith("slow")) returned.countDown()
            }
        }
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Before fun prepare() {
        fetchLink = { TorrentLinkResult.Refused(TorrentLinkResult.OFFLINE) }
        root.mkdirs()
        File(root, "first").writeBytes(metainfo("kept.txt"))
        File(root, "second").writeBytes(metainfo("next.txt"))
        File(root, "invalid").writeText("not metainfo")
        SourceProvider.root = root
        SourceProvider.opened = CountDownLatch(1)
        SourceProvider.release = CountDownLatch(1)
        SourceProvider.returned = CountDownLatch(1)
        val info = ProviderInfo().apply { authority = this@PhoneDocumentReplacementTest.authority }
        val provider = Robolectric.buildContentProvider(SourceProvider::class.java).create(info).get()
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    @After fun cleanup() {
        SourceProvider.release.countDown()
        rule.runOnIdle { shown.value = false }
        root.deleteRecursively()
    }

    private fun show() = rule.setContent { ChunksTheme { Surface {
        if (shown.value) LocalEngineScreen(pane = PhonePane.ADD,
            onBack = {}, initialTorrentUri = source.value,
            pendingAddState = draft,
            canReviewOnConnectedServer = true, onPrepareRemoteTorrent = { prepared.set(it) },
            onAcknowledgedAdd = { error("Source review must never authorize engine Add") },
            fetchTorrentDocument = { fetchLink(it) },
            onTorrentConsumed = { uri -> consumed += uri; if (source.value == uri) source.value = null })
    } } }
    private fun waitFor(text: String) = rule.waitUntil(15_000) {
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun handoff() {
        rule.onNodeWithText("Review .torrent on connected server").performScrollTo().assertIsEnabled().performClick()
    }
    private fun openPicker(): ShadowActivity.IntentForResult {
        rule.onNodeWithText("Open .torrent").performScrollTo().assertIsEnabled().performClick()
        return requireNotNull(Shadows.shadowOf(rule.activity).nextStartedActivityForResult).also {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, it.intent.action)
        }
    }
    private fun deliverPicker(request: ShadowActivity.IntentForResult, uri: Uri) = rule.runOnUiThread {
        Shadows.shadowOf(rule.activity).receiveResult(request.intent, Activity.RESULT_OK,
            Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    @Test fun refusedReplacementPreservesThePreviouslyReviewedTorrentAndExactBytes() {
        source.value = uri("first")
        show()
        waitFor("kept.txt")
        rule.runOnIdle { source.value = uri("invalid") }
        waitFor("The shared file was unavailable")
        rule.onNodeWithText("kept.txt", substring = true).performScrollTo().assertExists()
        assertArrayEquals(metainfo("kept.txt"), draft.value?.bytes)
        handoff()
        assertArrayEquals(metainfo("kept.txt"), prepared.get())
    }

    @Test fun cancelledOldShareReadDoesNotConsumeTheNewerSource() {
        source.value = uri("slow")
        show()
        rule.waitUntil(15_000) { SourceProvider.opened.count == 0L }
        rule.runOnIdle { source.value = uri("second") }
        waitFor("next.txt")
        SourceProvider.release.countDown()
        rule.waitUntil(15_000) { SourceProvider.returned.count == 0L }
        handoff()
        assertArrayEquals(metainfo("next.txt"), prepared.get())
        assertEquals(listOf(uri("second")), consumed.toList())
    }

    @Test fun validatedReviewStaysVisibleButCannotBeSubmittedWhileReplacementReads() {
        source.value = uri("first")
        show()
        waitFor("kept.txt")
        rule.runOnIdle { source.value = uri("slow-second") }
        rule.waitUntil(15_000) { SourceProvider.opened.count == 0L }
        rule.onNodeWithText("kept.txt", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Review .torrent on connected server").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Open .torrent").performScrollTo().assertIsNotEnabled()
        SourceProvider.release.countDown()
        waitFor("next.txt")
        handoff()
        assertArrayEquals(metainfo("next.txt"), prepared.get())
    }

    @Test fun refusedFirstSourceNeverCreatesAReview() {
        source.value = uri("invalid")
        show()
        waitFor("The shared file was unavailable")
        assertNull(draft.value)
        rule.onNodeWithText("Review .torrent on connected server").assertDoesNotExist()
        rule.onNodeWithText("Add and start downloading").assertDoesNotExist()
        assertEquals(listOf(uri("invalid")), consumed.toList())
    }

    @Test fun newerShareCancelsASlowPickerResult() {
        show()
        val request = openPicker()
        deliverPicker(request, uri("slow"))
        rule.waitUntil(15_000) { SourceProvider.opened.count == 0L }
        rule.runOnIdle { source.value = uri("second") }
        waitFor("next.txt")
        SourceProvider.release.countDown()
        rule.waitUntil(15_000) { SourceProvider.returned.count == 0L }
        handoff()
        assertArrayEquals(metainfo("next.txt"), prepared.get())
        assertEquals(listOf(uri("second")), consumed.toList())
    }

    @Test fun newerPickerResultCancelsASlowShareAndConsumesOnlyItsSupersededUri() {
        show()
        val request = openPicker()
        rule.runOnIdle { source.value = uri("slow") }
        rule.waitUntil(15_000) { SourceProvider.opened.count == 0L }
        deliverPicker(request, uri("second"))
        waitFor("next.txt")
        SourceProvider.release.countDown()
        rule.waitUntil(15_000) { SourceProvider.returned.count == 0L }
        handoff()
        assertArrayEquals(metainfo("next.txt"), prepared.get())
        assertEquals(listOf(uri("slow")), consumed.toList())
    }

    @Test fun leavingDuringAReadKeepsItsUriForAReadOnlyRetryOnReturn() {
        source.value = uri("slow")
        show()
        rule.waitUntil(15_000) { SourceProvider.opened.count == 0L }
        rule.runOnIdle { shown.value = false }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Open .torrent").fetchSemanticsNodes().isEmpty()
        }
        SourceProvider.release.countDown()
        rule.waitUntil(15_000) { SourceProvider.returned.count == 0L }
        rule.waitForIdle()
        assertTrue(consumed.isEmpty())
        assertEquals(uri("slow"), source.value)
        rule.runOnIdle { shown.value = true }
        waitFor("kept.txt")
        handoff()
        assertArrayEquals(metainfo("kept.txt"), prepared.get())
        assertEquals(listOf(uri("slow")), consumed.toList())
    }


    private fun requestHttpsReplacement(url: String) {
        rule.onNodeWithText("Magnet or HTTP(S) torrent URL").performScrollTo()
            .performTextInput(url)
        rule.onNodeWithText("Get torrent from link").performScrollTo().assertIsEnabled()
            .performClick()
    }

    @Test fun refusedHttpsReplacementKeepsThePreviousReviewedDocumentAvailable() {
        source.value = uri("first")
        show()
        waitFor("kept.txt")
        // Previously the fetch button was disabled whenever a valid .torrent
        // was under review, forcing the user to discard A before trying B.
        requestHttpsReplacement("https://example.org/not-available.torrent?private=token")
        waitFor(TorrentLinkResult.OFFLINE)
        assertArrayEquals(metainfo("kept.txt"), draft.value?.bytes)
        rule.onNodeWithText("kept.txt", substring = true).performScrollTo().assertExists()
        handoff()
        assertArrayEquals(metainfo("kept.txt"), prepared.get())
    }

    @Test fun validHttpsReplacementSupersedesTheOldReviewOnlyAfterValidation() {
        source.value = uri("first")
        val replacement = metainfo("next.txt")
        fetchLink = { link ->
            assertEquals("example.org", link.uri.host)
            TorrentLinkResult.Fetched(replacement, TorrentMetainfoReader.preview(replacement), link.display)
        }
        show()
        waitFor("kept.txt")
        requestHttpsReplacement("https://example.org/next.torrent?private=token")
        waitFor("Torrent from example.org")
        assertArrayEquals(replacement, draft.value?.bytes)
        handoff()
        assertArrayEquals(replacement, prepared.get())
    }
}
