package io.github.gamesbyian.chunks

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.gamesbyian.chunks.external.ExternalTorrentTestProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1786: installed Android cross-package source admission, task reentry, and
 * granted URI lifetime. The source provider belongs to androidTest's distinct
 * APK/UID, not the Chunks APK, and has opaque content IDs.
 *
 * This qualifies emulator/Android package grant behavior only; it cannot
 * substitute for the owner's Pixel/Files/Drive hardware/provider acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ExternalApkTorrentHandoffInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val sourceApp get() = instrumentation.context
    private val chunksApp get() = instrumentation.targetContext

    private fun b(value: String) = "${value.toByteArray(Charsets.UTF_8).size}:$value"
    private fun torrent(name: String): ByteArray =
        ("d8:announce" + b("https://tracker.example/announce") +
            "4:infod6:lengthi5e4:name" + b(name) +
            "12:piece lengthi16384e6:pieces20:01234567890123456789ee")
            .toByteArray(Charsets.UTF_8)

    private fun create(slot: String, name: String): Pair<Uri, ByteArray> {
        val bytes = torrent(name)
        ExternalTorrentTestProvider.file(sourceApp, slot).writeBytes(bytes)
        val uri = ExternalTorrentTestProvider.uri(sourceApp, slot)
        assertEquals("The external test package must really serve this MIME",
            "application/octet-stream", sourceApp.contentResolver.getType(uri))
        assertTrue("Opaque provider IDs do not contain a filename", !uri.toString().endsWith(".torrent"))
        return uri to bytes
    }

    private fun openWith(uri: Uri, first: Boolean) = Intent(Intent.ACTION_VIEW).apply {
        setClassName(chunksApp.packageName, MainActivity::class.java.name)
        setDataAndType(uri, "application/octet-stream")
        clipData = ClipData.newUri(sourceApp.contentResolver, "External torrent", uri)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (first) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private fun send(uri: Uri) = Intent(Intent.ACTION_SEND).apply {
        setClassName(chunksApp.packageName, MainActivity::class.java.name)
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(sourceApp.contentResolver, "External torrent", uri)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // Absence is expected while Android is creating the target Activity.
    // A waitUntil probe may keep polling, rather than aborting the whole test
    // before the actual permission-granted handoff can complete.
    private fun withResumed(block: (MainActivity, LocalAddAttemptOwner) -> Unit): Boolean {
        var found = false
        instrumentation.runOnMainSync {
            val activities = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
            if (activities.size == 1) {
                found = true
                val activity = activities.single()
                block(activity, ViewModelProvider(activity)[LocalAddAttemptOwner::class.java])
            }
        }
        return found
    }

    private fun awaitSource(name: String, expected: Uri, bytes: ByteArray) {
        compose.waitUntil(60_000) {
            var reviewed = false
            val resumed = withResumed { activity, owner ->
                val file = owner.reviewedTorrent.value
                reviewed = !activity.isFinishing && file != null && file.preview.name == name &&
                    file.originalDocument == expected && file.bytes.contentEquals(bytes) &&
                    owner.session.attemptedTicket == null
            }
            resumed && reviewed && compose.onAllNodes(hasText(name, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().forEach { it.finish() }
        }
        ExternalTorrentTestProvider.file(sourceApp, "first").delete()
        ExternalTorrentTestProvider.file(sourceApp, "second").delete()
    }

    @Test fun externalProviderOpenWithThenShareRetainsExactUnsubmittedSource() {
        // An instrumented test runs inside the Chunks process, but the
        // content provider APK and its files are owned by a different UID.
        assertNotEquals(chunksApp.packageName, sourceApp.packageName)
        assertNotEquals(chunksApp.applicationInfo.uid, sourceApp.applicationInfo.uid)

        val firstName = "external-first.bin"
        val secondName = "external-second.bin"
        val (first, firstBytes) = create("first", firstName)
        sourceApp.startActivity(openWith(first, first = true))
        awaitSource(firstName, first, firstBytes)

        var originalTask = -1
        withResumed { activity, owner ->
            originalTask = activity.taskId
            assertNull("The first foreign Open With does not submit Add", owner.session.attemptedTicket)
        }

        val (second, secondBytes) = create("second", secondName)
        sourceApp.startActivity(send(second))
        compose.waitUntil(60_000) {
            var queued = false
            val resumed = withResumed { activity, owner ->
                queued = activity.taskId == originalTask && !activity.isFinishing &&
                    owner.pendingSharedDocument.value == second &&
                    owner.reviewedTorrent.value?.bytes?.contentEquals(firstBytes) == true &&
                    owner.session.attemptedTicket == null
            }
            resumed && queued && compose.onAllNodes(hasText("Use new source", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The new external source cannot silently displace the first one.
        // This is the person's one deliberate replacement choice, never Add.
        LazyComposeActions.activateOnce(compose,
            hasText("Use new source") and hasClickAction())
        awaitSource(secondName, second, secondBytes)
        withResumed { activity, owner ->
            assertEquals("Both external intents must stay in the Chunks task",
                originalTask, activity.taskId)
            assertFalse("The source app must not bounce Chunks out", activity.isFinishing)
            assertNull("Neither external intent may authorize Add or Start",
                owner.session.attemptedTicket)
            assertArrayEquals(secondBytes, requireNotNull(owner.reviewedTorrent.value).bytes)
        }
    }
}
