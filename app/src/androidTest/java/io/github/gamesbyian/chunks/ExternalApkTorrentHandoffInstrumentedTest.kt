package io.github.gamesbyian.chunks

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

    private fun fixture(slot: String): Pair<Uri, ByteArray> {
        val name = if (slot == "first") "external-first.bin" else "external-second.bin"
        val uri = ExternalTorrentTestProvider.uri(sourceApp, slot)
        assertFalse("An opaque provider URI must not expose a .torrent filename",
            uri.toString().endsWith(".torrent"))
        return uri to ExternalTorrentTestProvider.torrent(name)
    }

    private fun dispatch(mode: String) {
        val intent = Intent().apply {
            setClassName(sourceApp.packageName,
                "io.github.gamesbyian.chunks.external.ExternalSourceSenderActivity")
            putExtra("fixture_mode", mode)
            putExtra("fixture_target", chunksApp.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Only this IPC launches the external sender. Source bytes and the
        // outbound grant originate inside the other package/UID, not from the
        // instrumentation running in Chunks' process.
        sourceApp.startActivity(intent)
    }

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
        // Failure evidence is boolean-only. Never disclose URIs, names, or metainfo.
        var lastState = "activityResumed=false"
        try {
            compose.waitUntil(60_000) {
                var reviewed = false
                val resumed = withResumed { activity, owner ->
                    val file = owner.reviewedTorrent.value
                    val alive = !activity.isFinishing
                    val nameMatches = file?.preview?.name == name
                    val uriMatches = file?.originalDocument == expected
                    val bytesMatch = file?.bytes?.contentEquals(bytes) == true
                    val noDispatch = owner.session.attemptedTicket == null
                    lastState = "activityResumed=true alive=$alive loaded=${file != null} " +
                        "nameMatches=$nameMatches uriMatches=$uriMatches " +
                        "bytesMatch=$bytesMatch noDispatch=$noDispatch"
                    reviewed = alive && nameMatches && uriMatches && bytesMatch && noDispatch
                }
                resumed && reviewed && compose.onAllNodes(hasText(name, substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: AssertionError) {
            throw AssertionError("Foreign provider Add review failed: $lastState", failure)
        }
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().forEach { it.finish() }
        }
        // The source documents belong to the separate installed androidTest
        // APK's private storage. Another Chunks-initiated cleanup Activity
        // would seize foreground/task focus from unrelated instrumented UI
        // cases. The disposable test APK is uninstalled by the runner, which
        // removes both tiny test documents without a cross-package action.
    }

    @Test fun externalProviderOpenWithThenShareRetainsExactUnsubmittedSource() {
        // An instrumented test runs inside the Chunks process, but the
        // content provider APK and its files are owned by a different UID.
        assertNotEquals(chunksApp.packageName, sourceApp.packageName)
        assertNotEquals(chunksApp.applicationInfo.uid, sourceApp.applicationInfo.uid)

        val firstName = "external-first.bin"
        val secondName = "external-second.bin"
        val (first, firstBytes) = fixture("first")
        dispatch("view")
        awaitSource(firstName, first, firstBytes)

        var originalTask = -1
        withResumed { activity, owner ->
            originalTask = activity.taskId
            assertNull("The first foreign Open With does not submit Add", owner.session.attemptedTicket)
        }

        val (second, secondBytes) = fixture("second")
        dispatch("share")
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
