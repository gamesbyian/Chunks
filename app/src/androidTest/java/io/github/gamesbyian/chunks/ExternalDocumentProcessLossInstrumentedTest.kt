package io.github.gamesbyian.chunks

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.github.gamesbyian.chunks.external.ExternalTorrentTestProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android two-process, same-task and foreign-UID source-grant test.
 * No ComposeTestRule: starting an isolated --no-restart instrumentation after
 * several installed journeys can fail to initialize coroutine TestScope's
 * ServiceLoader before any test instruction runs (run 45004002272).
 *
 * The host-only phase key guards actual process termination; none of the
 * original URI, torrent bytes or Add authority is restored from saved state.
 */
@RunWith(AndroidJUnit4::class)
class ExternalDocumentProcessLossInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val sourceApp get() = instrumentation.context
    private val chunksApp get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    private fun fixture(): Pair<Uri, ByteArray> {
        val uri = ExternalTorrentTestProvider.uri(sourceApp, "first")
        assertFalse(uri.toString().endsWith(".torrent"))
        return uri to ExternalTorrentTestProvider.torrent("external-first.bin")
    }

    private fun dispatch(preserveTask: Boolean = false) {
        // The separate test APK/UID sends the content URI and actual grant.
        val command = "am start -W -n " + sourceApp.packageName +
            "/io.github.gamesbyian.chunks.external.ExternalSourceSenderActivity" +
            " --es fixture_mode view --es fixture_target " + chunksApp.packageName +
            (if (preserveTask) " --ez fixture_keep_task true" else "")
        val output = device.executeShellCommand(command)
        assertTrue("Foreign source sender did not start",
            output.contains("Status: ok") && !output.contains("Error:"))
    }

    private fun withResumed(block: (MainActivity, LocalAddAttemptOwner) -> Unit): Boolean {
        var found = false
        instrumentation.runOnMainSync {
            val matches = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
            if (matches.size == 1) {
                found = true
                val activity = matches.single()
                block(activity, ViewModelProvider(activity)[LocalAddAttemptOwner::class.java])
            }
        }
        return found
    }

    private fun await(timeoutMillis: Long, reason: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        throw AssertionError("Document process-loss precondition timed out: $reason")
    }

    private fun awaitExactReview(uri: Uri, bytes: ByteArray) {
        // The ordinary cross-UID installed Compose journey separately proves
        // the source name is visible in Add. This lifecycle-only process-loss
        // witness must establish the reviewed identity, real foreign UID grant,
        // and absence of any Add before killing its instrumentation process.
        // UiAutomator's visible-window text match may not expose Compose's
        // merged/offscreen source node even when these authority checks pass.
        var lastState = "activityResumed=false"
        try {
            await(60_000, "exact foreign-UID source review") {
            var exact = false
            val resumed = withResumed { activity, owner ->
                val reviewed = owner.reviewedTorrent.value
                val uriMatches = reviewed?.originalDocument == uri
                val bytesMatch = reviewed?.bytes?.contentEquals(bytes) == true
                val nameMatches = reviewed?.preview?.name == "external-first.bin"
                val granted = activity.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
                val noAdd = owner.session.attemptedTicket == null
                lastState = "activityResumed=true reviewed=${reviewed != null} " +
                    "uriMatches=$uriMatches bytesMatch=$bytesMatch " +
                    "nameMatches=$nameMatches readGrant=$granted noAdd=$noAdd"
                exact = uriMatches && bytesMatch && nameMatches && granted && noAdd
            }
            resumed && exact
            }
        } catch (timeout: AssertionError) {
            throw AssertionError("Exact foreign-UID review did not settle: $lastState", timeout)
        }
        assertTrue(withResumed { activity, owner ->
            val reviewed = requireNotNull(owner.reviewedTorrent.value)
            assertEquals(uri, reviewed.originalDocument)
            assertArrayEquals(bytes, reviewed.bytes)
            assertEquals("external-first.bin", reviewed.preview.name)
            assertEquals(PackageManager.PERMISSION_GRANTED,
                activity.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION))
            assertNull(owner.session.attemptedTicket)
        })
    }

    @After fun closeRestoredTask() {
        // The save phase kills its process before test cleanup is dispatched.
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
                .forEach { it.finish() }
        }
    }

    @Test fun externalReviewTaskSurvivesProcessLossWithoutReplayingTheDocument() {
        val phase = InstrumentationRegistry.getArguments().getString("chunks_document_restore_phase")
        assumeTrue("Only the disposable host may trigger a process kill",
            phase == "save" || phase == "restore")
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        val markers = chunksApp.getSharedPreferences("document-restore-instrumentation", 0)
        val (uri, bytes) = fixture()
        val infoHash = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(bytes).infoHashV1

        if (phase == "save") {
            dispatch()
            awaitExactReview(uri, bytes)
            var taskId = -1
            assertTrue(withResumed { activity, owner ->
                taskId = activity.taskId
                assertNull(owner.session.attemptedTicket)
            })
            assertTrue(taskId >= 0)
            assertNull(LocalEngineHolder.current()?.status(infoHash))
            assertTrue(markers.edit().putInt("task", taskId)
                .putInt("pid", Process.myPid()).commit())
            assertTrue(device.pressHome())
            await(15_000, "original task must be STOPPED before kill") {
                var stopped = false
                instrumentation.runOnMainSync {
                    stopped = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.STOPPED)
                        .any { it is MainActivity && it.taskId == taskId }
                }
                stopped
            }
            instrumentation.waitForIdleSync()
            Log.i("ChunksDocumentRestore", "external-review-task-stopped-before-process-loss task=$taskId")
            Process.killProcess(Process.myPid())
            return
        }

        val taskId = markers.getInt("task", -1)
        val oldPid = markers.getInt("pid", -1)
        assertTrue(taskId >= 0 && oldPid > 0)
        assertNotEquals("The saved task must restart in a new process", oldPid, Process.myPid())
        // Host reactivates the stopped task before no-restart can attach.
        // Cycle Home/focus after instrumentation attaches so the task's
        // RESUMED lifecycle is visible to the AndroidJUnit4 monitor.
        assertTrue(device.pressHome())
        val focus = device.executeShellCommand("am task focus $taskId").trim()
        assertEquals("Focus existing task without re-delivering source Intent",
            "Setting focus to task $taskId", focus)
        await(30_000, "visible interruption notice without original source replay") {
            device.hasObject(By.text(INTERRUPTED_DOCUMENT_REVIEW))
        }
        assertTrue(withResumed { activity, owner ->
            assertEquals(taskId, activity.taskId)
            assertNull(owner.incomingTorrent.value)
            assertNull(owner.pendingSharedDocument.value)
            assertNull(owner.reviewedTorrent.value)
            assertNull(owner.session.attemptedTicket)
        })
        assertNull(LocalEngineHolder.current()?.status(infoHash))

        // Explicitly reopen through the foreign provider to acquire a fresh
        // grant. Recovered task alone never authorizes an Add.
        dispatch(preserveTask = true)
        awaitExactReview(uri, bytes)
        assertTrue(withResumed { activity, owner ->
            assertEquals(taskId, activity.taskId)
            assertNull(owner.sharedTorrentError.value)
            assertNull(owner.session.attemptedTicket)
        })
        assertNull(LocalEngineHolder.current()?.status(infoHash))
        Log.i("ChunksDocumentRestore", "same-task-new-process-notice-explicit-reopen-no-add")
    }
}
