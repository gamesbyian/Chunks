package io.github.gamesbyian.chunks

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.model.SmartRoutingPolicyStore
import io.github.gamesbyian.chunks.model.SmartRoutingPreview
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed Android task + real MainActivity tier for the owner's Open With regression.
 * Unlike Robolectric, this uses Android's ActivityTaskManager and a real document
 * provider, but the provider lives in this app. It does not establish grants
 * from external apps or physical Pixel/Files/Drive acceptance.
 *
 * Merely reaching review must never authorize Add, Start or network work.
 */
@RunWith(AndroidJUnit4::class)
class InstalledOpenWithHandoffInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = FixtureDocumentsProvider.baseDir(context)

    @Before fun prepare() { base.deleteRecursively(); base.mkdirs() }
    @After fun clean() { base.deleteRecursively() }

    private fun b(value: String) = "${value.toByteArray(Charsets.UTF_8).size}:$value"

    private fun metainfo(name: String): ByteArray = (
        "d8:announce" + b("https://tracker.example/announce") +
            "4:infod6:lengthi5e4:name" + b(name) +
            "12:piece lengthi16384e6:pieces20:01234567890123456789ee"
        ).toByteArray(Charsets.UTF_8)

    private fun document(name: String): Uri {
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        // Android chooser only sees generic binary, not the torrent's bencoded
        // content. The Add review must inspect those bytes independently.
        val uri = requireNotNull(DocumentsContract.createDocument(
            context.contentResolver, root, "application/octet-stream", "incoming.torrent"
        ))
        context.contentResolver.openOutputStream(uri, "wt").use { stream ->
            requireNotNull(stream).write(metainfo(name))
        }
        return uri
    }

    private fun openWith(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setClass(context, MainActivity::class.java)
        setDataAndType(uri, "application/octet-stream")
        // A normal external Open With does not clear Chunks' existing task.
        // singleTask must deliver subsequent sources to the same Activity.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ActivityScenario expects a newly created Activity. Isolate the first
    // fixture from whichever instrumented test left a singleTask Chunks
    // Activity alive. ONLY the initial fixture launch clears that task; the
    // second external Open With intentionally keeps and reuses it.
    private fun freshFixtureLaunch(uri: Uri): Intent =
        openWith(uri).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)

    // For actual task-reentry testing, launch through ActivityTaskManager,
    // not ActivityScenario: that harness owns a separate task lease which can
    // remain PAUSED when singleTask reparenting has already succeeded.
    private class ActivityHandle(val visit: ((MainActivity) -> Unit) -> Unit) {
        fun onActivity(block: (MainActivity) -> Unit) = visit(block)
    }

    private fun liveActivity(uri: Uri): ActivityHandle {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(freshFixtureLaunch(uri)) as MainActivity
        return ActivityHandle { block ->
            instrumentation.runOnMainSync { block(activity) }
        }
    }

    private fun scenarioActivity(scenario: ActivityScenario<MainActivity>): ActivityHandle =
        ActivityHandle { block -> scenario.onActivity { block(it) } }

    @Test fun openWithDataOnlyUsesAndroidResolvedMimeAndRetainsTheExactReview() {
        val name = "android-resolved-mime.bin"
        val uri = document(name)
        val implicit = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(context.packageName)
        }
        assertNull("Android may dispatch a VIEW whose sender left type unset", implicit.type)
        assertEquals("application/octet-stream", implicit.resolveType(context))
        assertEquals(MainActivity::class.java.name, implicit.resolveActivity(context.packageManager)?.className)
        val launch = Intent(implicit).setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            fun assertExactReview() {
                waitForReview(name, scenarioActivity(scenario), uri)
                scenario.onActivity { activity ->
                    val owner = ViewModelProvider(activity)[LocalAddAttemptOwner::class.java]
                    val reviewed = requireNotNull(owner.reviewedTorrent.value)
                    assertEquals(uri, reviewed.originalDocument)
                    assertArrayEquals(metainfo(name), reviewed.bytes)
                    assertNull("Activity recreation retains the live review without an interruption notice",
                        owner.sharedTorrentError.value)
                    assertNull(owner.session.attemptedTicket)
                    assertNull(LocalEngineHolder.current()?.status(reviewed.preview.infoHashV1))
                }
            }
            assertExactReview()
            compose.waitForIdle()
            scenario.recreate()
            assertExactReview()
        }
    }

    @Test fun conflictingOpenWithClipDataCannotReplaceTheRetainedReview() {
        val firstName = "retained-review.torrent-content"
        val original = document(firstName)
        val other = document("unselected-review.bin")
        ActivityScenario.launch<MainActivity>(freshFixtureLaunch(original)).use { scenario ->
            waitForReview(firstName, scenarioActivity(scenario), original)
            val conflict = openWith(original).apply { clipData = ClipData.newRawUri("conflict", other) }
            assertNull(SharedTorrentDocument.fromIntent(conflict))
            val multiple = openWith(original).apply {
                clipData = ClipData.newRawUri("first", original).also { it.addItem(ClipData.Item(other)) }
            }
            assertNull(SharedTorrentDocument.fromIntent(multiple))
            assertEquals(original, SharedTorrentDocument.fromIntent(openWith(original).apply {
                clipData = ClipData.newRawUri("matching", original)
            }))
            scenario.onActivity { activity ->
                activity.startActivity(conflict)
            }
            compose.waitUntil(20_000) {
                var delivered = false
                scenario.onActivity { delivered = it.intent.clipData?.getItemAt(0)?.uri == other }
                delivered
            }
            scenario.onActivity { activity ->
                val owner = ViewModelProvider(activity)[LocalAddAttemptOwner::class.java]
                assertEquals(original, owner.reviewedTorrent.value?.originalDocument)
                assertNull(owner.pendingSharedDocument.value)
                assertNull(owner.session.attemptedTicket)
            }
        }
    }

    @Test fun unresolvedReplacementDoesNotRouteUsingRetainedTorrent() {
        val name = "Show.S01E02.mkv"
        val uri = document(name)
        val store = SmartRoutingPolicyStore(context)
        val previous = store.read() as SmartRoutingPolicyStore.Read.Ready
        val rule = SmartRoutingPreview.Rule("tv", 1, SmartRoutingPreview.Kind.TV, 60, "local")
        assertEquals(SmartRoutingPolicyStore.Save.SAVED, store.save(previous.revision,
            SmartRoutingPolicyStore.Policy(enabled = true, rules = listOf(rule), autoSelect = true)))
        try {
            ActivityScenario.launch<MainActivity>(freshFixtureLaunch(uri)).use { scenario ->
                waitForReview(name, scenarioActivity(scenario), uri)
                val sourceField = hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD)
                val destination = hasTestTag(ChunksSemantics.ADD_TARGET_DEVICE)
                val suggestion = hasTestTag("chunks.add.routing.suggestion")
                fun editSource(text: String) {
                    // Local uses an eagerly composed scrolling Column. Editing
                    // its field's semantics does not require a viewport reveal.
                    compose.onNode(sourceField).assertIsEnabled()
                        .performSemanticsAction(SemanticsActions.SetText) { setText ->
                            assertTrue(setText(AnnotatedString(text)))
                        }
                }
                fun showDestination() {
                    compose.onNode(destination).performScrollTo().assertExists()
                }

                showDestination()
                compose.waitUntil(20_000) {
                    compose.onAllNodes(suggestion).fetchSemanticsNodes().size == 1
                }
                editSource("https://fixture.invalid/next.torrent")
                showDestination()
                // SetText edits the draft synchronously, but the derived
                // routing suggestion is disposed on a subsequent Compose
                // frame. Wait for the negative state, then keep the strict
                // absence assertion. Never accept a suggestion for the
                // superseded source.
                compose.waitUntil(10_000) {
                    compose.onAllNodes(suggestion).fetchSemanticsNodes().isEmpty()
                }
                compose.onNode(suggestion).assertDoesNotExist()
                scenario.onActivity { activity ->
                    val owner = ViewModelProvider(activity)[LocalAddAttemptOwner::class.java]
                    val retained = requireNotNull(owner.reviewedTorrent.value)
                    assertEquals(uri, retained.originalDocument)
                    assertArrayEquals(metainfo(name), retained.bytes)
                    assertNull(owner.session.attemptedTicket)
                }

                editSource("")
                showDestination()
                // Clearing the newer draft reselects the retained, reviewed
                // .torrent. Check that exact positive rule becomes visible.
                compose.waitUntil(10_000) {
                    compose.onAllNodes(suggestion).fetchSemanticsNodes().size == 1
                }
                compose.onNode(suggestion).assertExists()
            }
        } finally {
            val current = store.read() as SmartRoutingPolicyStore.Read.Ready
            assertEquals(SmartRoutingPolicyStore.Save.SAVED, store.save(current.revision, previous.policy))
        }
    }

    private fun waitForReview(
        name: String, scenario: ActivityHandle, expected: Uri
    ) {
        try {
            compose.waitUntil(60_000) {
                compose.onAllNodes(hasText(name, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // The already-whitelisted fixed boolean fingerprint also applies
            // to an INITIAL source review timeout. Using the same sanitizer
            // avoids leaking a source URI, document name, or raw exception
            // into public CI logs; the test method identifies the stage.
            throw secondSourceTimeout(scenario, expected)
        }
    }

    /**
     * Bounded failure fingerprint only. Never include a source URI, file name,
     * intent extras, torrent contents, or account identity in installed CI.
     * The CI parser deliberately rejects arbitrary exception messages.
     */
    private fun secondSourceTimeout(
        scenario: ActivityHandle, second: Uri
    ): AssertionError {
        var delivered = false
        var queued = false
        var reviewed = false
        var finishing = true
        scenario.onActivity { activity ->
            val owner = androidx.lifecycle.ViewModelProvider(activity)[LocalAddAttemptOwner::class.java]
            delivered = activity.intent?.data == second
            queued = owner.pendingSharedDocument.value == second
            reviewed = owner.reviewedTorrent.value != null
            finishing = activity.isFinishing
        }
        val choice = compose.onAllNodes(hasText("Use new source", substring = true))
            .fetchSemanticsNodes().isNotEmpty()
        return AssertionError(
            "openwith second-source timeout: " +
                "intent=$delivered queued=$queued reviewed=$reviewed " +
                "choice=$choice finishing=$finishing"
        )
    }

    /** An enum-sized diagnostic when the failure precedes the UI timeout.
     * No external exception text or URI is included in the recorded message.
     * The underlying test still fails and retains all original assertions. */
    private fun stagedFailure(stage: String, failure: Throwable): Nothing {
        val kind = when (failure) {
            is androidx.compose.ui.test.ComposeTimeoutException -> "timeout"
            is AssertionError -> "assertion"
            is SecurityException -> "security"
            is IllegalStateException -> "state"
            else -> "other"
        }
        throw AssertionError("openwith staged failure: stage=$stage kind=$kind")
    }

    @Test fun installedSecondOpenWithReentersSameTaskAndQueuesSourceWithoutSubmitting() {
        var stage = "fixture"
        try {
        val originalName = "task-reentry-original.mkv"
        val original = document(originalName)
        stage = "launch"
        // The external Open With is delivered through the real task manager.
        // This test must not attach ActivityScenario's cleanup lease to a
        // singleTask activity that Android can reparent on the second VIEW.
        liveActivity(original).let { scenario ->
            stage = "initial-review"
            waitForReview(originalName, scenario, original)
            stage = "owner"
            scenario.onActivity { activity ->
                assertNotNull("The original selected .torrent must be retained before a second Open With",
                    androidx.lifecycle.ViewModelProvider(activity)[LocalAddAttemptOwner::class.java]
                        .reviewedTorrent.value)
            }
            // The second document is created only after the first review is
            // visible. That models a real second external Open With and does
            // not conflate provider fixture setup with task reentry.
            stage = "second-source"
            val second = document("task-reentry-second.mkv")
            stage = "second-intent"
            context.startActivity(openWith(second))
            // Distinct from the visual replacement-choice contract below:
            // Android must deliver the second VIEW to the already-running
            // Activity, then Chunks must hold it as an unsubmitted choice.
            // A separate test identity identifies routing versus rendering.
            stage = "delivery"
            try {
                compose.waitUntil(30_000) {
                    var acceptedIntoExistingTask = false
                    scenario.onActivity { activity ->
                        val owner = androidx.lifecycle.ViewModelProvider(activity)[
                            LocalAddAttemptOwner::class.java]
                        acceptedIntoExistingTask = activity.intent?.data == second &&
                            owner.pendingSharedDocument.value == second &&
                            owner.session.attemptedTicket == null
                    }
                    acceptedIntoExistingTask
                }
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                throw secondSourceTimeout(scenario, second)
            }
            stage = "final"
            scenario.onActivity {
                assertFalse("Queued source must not finish the current task", it.isFinishing)
            }
            // The product asserts above have passed. Finish the activity
            // without ActivityScenario's stale task lease. Do not treat
            // Android's eventual teardown timing as an Open With failure.
            stage = "cleanup"
            scenario.onActivity { activity ->
                activity.finish()
                assertTrue("The disposable fixture accepted finish", activity.isFinishing)
            }
        }
        } catch (failure: Throwable) {
            // Preserve the existing explicitly whitelisted timeout fingerprint.
            if (failure is AssertionError &&
                failure.message?.startsWith("openwith second-source timeout:") == true) throw failure
            stagedFailure(stage, failure)
        }
    }

    @Test fun installedSecondOpenWithReturnsToExistingChunksAndOffersAnExplicitSourceChoice() {
        var stage = "fixture"
        try {
        val originalName = "installed-original-source.mkv"
        val newName = "installed-second-source.mkv"
        val original = document(originalName)
        stage = "launch"
        // The external Open With is delivered through the real task manager.
        // This test must not attach ActivityScenario's cleanup lease to a
        // singleTask activity that Android can reparent on the second VIEW.
        liveActivity(original).let { scenario ->
            stage = "initial-review"
            waitForReview(originalName, scenario, original)
            stage = "owner"
            scenario.onActivity { activity ->
                assertNotNull("A visible original review must be retained in its Activity owner",
                    androidx.lifecycle.ViewModelProvider(activity)[LocalAddAttemptOwner::class.java]
                        .reviewedTorrent.value)
            }
            // A genuine ActivityTaskManager startActivity into the existing
            // singleTask Activity. Never clear the task or invoke the internal
            // source parser directly; this is the previously failing handoff.
            stage = "second-source"
            val second = document(newName)
            stage = "second-intent"
            context.startActivity(openWith(second))
            // Android singleTask reentry is a separate contract from the
            // Compose source-choice affordance. Probe the *existing* scenario's
            // Activity intent (set by MainActivity.onNewIntent) before diagnosing
            // an absent replacement choice as a presentation error.
            stage = "delivery"
            try {
                compose.waitUntil(30_000) {
                    var deliveredToExistingTask = false
                    scenario.onActivity { activity ->
                        deliveredToExistingTask = activity.intent?.data == second
                    }
                    deliveredToExistingTask
                }
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                throw secondSourceTimeout(scenario, second)
            }
            // The previous review must remain available while Chunks offers
            // the new source as an explicit, non-mutating choice.
            stage = "choice"
            waitForReview(originalName, scenario, original)
            try {
                compose.waitUntil(60_000) {
                    compose.onAllNodes(hasText("Use new source", substring = true))
                        .fetchSemanticsNodes().isNotEmpty()
                }
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                throw secondSourceTimeout(scenario, second)
            }
            // A second provider source cannot silently replace the current
            // unsubmitted draft or cause the user to leave Chunks.
            stage = "final"
            scenario.onActivity {
                assertFalse("Second Open With must not finish the Chunks task", it.isFinishing)
            }
            // The product asserts above have passed. Finish the activity
            // without ActivityScenario's stale task lease. Do not treat
            // Android's eventual teardown timing as an Open With failure.
            stage = "cleanup"
            scenario.onActivity { activity ->
                activity.finish()
                assertTrue("The disposable fixture accepted finish", activity.isFinishing)
            }
        }
        } catch (failure: Throwable) {
            // Preserve the existing explicitly whitelisted timeout fingerprint.
            if (failure is AssertionError &&
                failure.message?.startsWith("openwith second-source timeout:") == true) throw failure
            stagedFailure(stage, failure)
        }
    }

    @Test fun installedGenericBinaryOpenWithKeepsUnsubmittedReviewAcrossBackgroundAndRecreation() {
        val name = "installed-open-with-fixture.mkv"
        val uri = document(name)
        assertTrue(uri.scheme == "content")
        ActivityScenario.launch<MainActivity>(freshFixtureLaunch(uri)).use { scenario ->
            val handle = scenarioActivity(scenario)
            waitForReview(name, handle, uri)
            scenario.onActivity {
                assertFalse("Open With must not close Chunks after accepting the document", it.isFinishing)
            }
            // The review may be temporarily backgrounded while the chooser or
            // source app regains focus. Its unsubmitted document must survive.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitForReview(name, handle, uri)
            // An actual Android Activity recreation is distinct from a
            // disposable Compose route and must keep the Add draft in memory.
            scenario.recreate()
            waitForReview(name, handle, uri)
            scenario.onActivity {
                assertFalse("Recreated Chunks must still own its Add review", it.isFinishing)
            }
        }
    }
}
