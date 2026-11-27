package io.github.gamesbyian.chunks

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
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

    private fun dispatch(mode: String, preserveTask: Boolean = false) {
        require(mode == "view" || mode == "share")
        // The shell launches only the foreign Activity. That Activity writes
        // the source and sends the grant from its own package UID.
        val command = "am start -W -n " + sourceApp.packageName +
            "/io.github.gamesbyian.chunks.external.ExternalSourceSenderActivity" +
            " --es fixture_mode " + mode +
            " --es fixture_target " + chunksApp.packageName +
            if (preserveTask) " --ez fixture_keep_task true" else ""
        val output = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            .executeShellCommand(command)
        assertTrue("ActivityManager did not accept the external sender launch",
            output.contains("Status: ok") && !output.contains("Error:"))
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
                lastState = "activityResumed=false"
                val resumed = withResumed { activity, owner ->
                    val file = owner.reviewedTorrent.value
                    val alive = !activity.isFinishing
                    val nameMatches = file?.preview?.name == name
                    val uriMatches = file?.originalDocument == expected
                    val bytesMatch = file?.bytes?.contentEquals(bytes) == true
                    val canRead = activity.checkUriPermission(expected, Process.myPid(), Process.myUid(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
                    val noDispatch = owner.session.attemptedTicket == null
                    lastState = "activityResumed=true alive=$alive loaded=${file != null} " +
                        "nameMatches=$nameMatches uriMatches=$uriMatches " +
                        "bytesMatch=$bytesMatch readGrant=$canRead noDispatch=$noDispatch"
                    reviewed = alive && nameMatches && uriMatches && bytesMatch && canRead && noDispatch
                }
                // The Activity and its retained review can be ready while the
                // window is between Compose roots during Android task handoff.
                // An empty tree is a pending poll; the visible-source assertion
                // still has to succeed inside the original deadline.
                val sourceVisible = resumed && reviewed && compose.onAllNodes(hasText(name, substring = true))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                lastState += " sourceVisible=$sourceVisible"
                sourceVisible
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            // waitUntil throws ComposeTimeoutException, not AssertionError.
            // Keep only booleans, never source identity or payload, in CI logs.
            android.util.Log.e("ChunksExternalGrant", "review-timeout $lastState")
            // A synthetic, local-only source fixture. Preserve the full app
            // screen for distinguishing task routing from invisible semantics,
            // using the same atlas directory as the other installed journeys.
            runCatching {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    val directory = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES), "ChunksAddAtlas"
                    ).apply { mkdirs() }
                    java.io.File(directory, "external-provider-review-timeout.png")
                        .outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    bitmap.recycle()
                }
            }
            throw AssertionError("Foreign provider Add review timed out: $lastState", failure)
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
        assertTrue("Chunks must still be resumed after the first review", withResumed { activity, owner ->
            originalTask = activity.taskId
            assertNull("The first foreign Open With does not submit Add", owner.session.attemptedTicket)
        })

        val (second, secondBytes) = fixture("second")
        dispatch("share")
        compose.waitUntil(60_000) {
            var queued = false
            val resumed = withResumed { activity, owner ->
                queued = activity.taskId == originalTask && !activity.isFinishing &&
                    owner.pendingSharedDocument.value == second &&
                    owner.reviewedTorrent.value?.originalDocument == first &&
                    owner.reviewedTorrent.value?.bytes?.contentEquals(firstBytes) == true &&
                    owner.session.attemptedTicket == null
            }
            resumed && queued && compose.onAllNodes(hasText("Use new source", substring = true))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }

        // The new external source cannot silently displace the first one.
        // This is the person's one deliberate replacement choice, never Add.
        LazyComposeActions.activateOnce(compose,
            hasText("Use new source") and hasClickAction())
        awaitSource(secondName, second, secondBytes)
        assertTrue("Chunks must still be resumed after replacement", withResumed { activity, owner ->
            assertEquals("Both external intents must stay in the Chunks task",
                originalTask, activity.taskId)
            assertFalse("The source app must not bounce Chunks out", activity.isFinishing)
            assertNull("Neither external intent may authorize Add or Start",
                owner.session.attemptedTicket)
            assertArrayEquals(secondBytes, requireNotNull(owner.reviewedTorrent.value).bytes)
        })
    }
}
