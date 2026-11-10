package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed Android cold-start handoff, without remote credentials or Add. */
@RunWith(AndroidJUnit4::class)
class ExternalMagnetLocalHandoffInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)

    @Test fun consumedExternalShareIsNotReplayedAfterActivityRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(magnet)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText(magnet).fetchSemanticsNodes().isNotEmpty()
            }
            // The incoming external source was consumed into the initial phone
            // review. Android retains the old Activity Intent across recreate,
            // but it is no longer authority to initiate ANOTHER review.
            scenario.recreate()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText("Add on this device")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Local navigation may legitimately restore the empty Add form;
            // the consumed magnet must not be injected into it again.
            rule.onNodeWithText(magnet).assertDoesNotExist()
        }
    }

    @Test fun externalViewOpensReviewedPhoneAddWithoutRemoteLoginOrAutoStart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(magnet)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText("Add on this device")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Reaching the native Add form is required, but merely opening
            // this external link must not execute any native Add or Start.
            rule.onNodeWithText("Start downloading after Add (recommended)").assertExists()
            rule.onNodeWithText(magnet).assertExists()
            assertTrue(rule.onAllNodesWithText(magnet).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test fun malformedSecondIntentCannotEraseQueuedFirstShareBeforeReview() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val initial = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val before = LocalEngineHolder.current()?.completeIdentityInventory().orEmpty()
        ActivityScenario.launch<MainActivity>(initial).use { scenario ->
            // Deliver real Android singleTop Intents, back-to-back from
            // one UI dispatch. Calling Activity.onNewIntent directly would
            // bypass the framework and is protected on MainActivity.
            scenario.onActivity { activity ->
                fun share(text: String) = Intent(activity, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                activity.startActivity(share(magnet))
                activity.startActivity(share("magnet:?xt=urn:btih:" + "b".repeat(40)).apply {
                    putExtra(Intent.EXTRA_STREAM, 42) // malformed, not a granted document
                })
            }
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText(magnet).fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithText("Review on this phone").fetchSemanticsNodes().isNotEmpty()
            }
            // A connected server requires choosing an exact Add destination.
            // The fallback route with no server already opens device Add.
            if (rule.onAllNodesWithText("Review on this phone").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithText("Review on this phone").performClick()
            }
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText(magnet).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(magnet).assertExists()
            org.junit.Assert.assertEquals("Android Share intake cannot issue a phone Add",
                before, LocalEngineHolder.current()?.completeIdentityInventory().orEmpty())
            // ActivityScenario tracks its original launch identity; a genuine
            // onNewIntent has replaced it. Restore only the test monitor's
            // identity after all UI assertions, never redispatch the source.
            scenario.onActivity { it.intent = initial }
        }
    }

    @Test fun reviewedTorrentSurvivesPhoneNavigationWithoutAddingOrPersistingItsPrivateSource() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = ("d8:announce14:http://ignored4:info" +
            "d6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
            "01234567890123456789ee").toByteArray(Charsets.UTF_8)
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = requireNotNull(DocumentsContract.createDocument(
            context.contentResolver, root, "application/x-bittorrent", "navigation-review.torrent"))
        context.contentResolver.openOutputStream(uri, "wt").use {
            requireNotNull(it).write(bytes)
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "application/x-bittorrent"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val before = LocalEngineHolder.current()?.completeIdentityInventory().orEmpty()
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText("Review download").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("test.txt").assertExists()
            rule.onNodeWithText("Options").performClick()
            rule.onNodeWithText("+ Add").performClick()
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText("Review download").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("test.txt").assertExists()
            org.junit.Assert.assertEquals("Browsing cannot authorize a phone Add",
                before, LocalEngineHolder.current()?.completeIdentityInventory().orEmpty())
            // Source bytes are process-only. Activity recreation must not replay the
            // consumed Android Share Intent or serialize potentially private metainfo.
            scenario.recreate()
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText("Add on this device")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Review download").assertDoesNotExist()
            org.junit.Assert.assertEquals("Recreation must not Add from old Intent",
                before, LocalEngineHolder.current()?.completeIdentityInventory().orEmpty())
        }
    }

    @Test fun refusedReplacementKeepsTheReviewedSourceThroughRealAndroidIntentDelivery() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "public-source-kept.txt"
        val bytes = ("d4:infod6:lengthi5e4:name${name.length}:$name" +
            "12:piece lengthi16384e6:pieces20:01234567890123456789ee").toByteArray()
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        fun document(label: String, content: ByteArray): Uri {
            val uri = requireNotNull(DocumentsContract.createDocument(context.contentResolver, root,
                "application/x-bittorrent", label))
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(content) }
            return uri
        }
        val first = document("retained-source.torrent", bytes)
        val refused = document("refused-replacement.torrent", "not metainfo".toByteArray())
        fun sourceIntent(uri: Uri) = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, "application/x-bittorrent")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        // Establish the real engine inventory independently before the UI's
        // normal read-only restore/observation can run. Never confirm an Add.
        val engine = LocalEngineHolder.get(context)
        val before = engine.completeIdentityInventory()
        var stage = "launch"
        var primaryFailure: Throwable? = null
        var scenario: ActivityScenario<MainActivity>? = null
        val launchIntent = sourceIntent(first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun captureFailure(failure: Throwable) {
            // A disposable fixture-only display, with a fixed stage and error
            // class in the filename. No incoming URI or exception message is logged.
            runCatching {
                val kind = failure.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z]+")) } ?: "Failure"
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.takeScreenshot()?.let { bitmap ->
                        val directory = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES), "ChunksAddAtlas").apply { mkdirs() }
                        java.io.File(directory, "phone-source-$stage-$kind.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
            }
        }
        try {
            val opened = ActivityScenario.launch<MainActivity>(launchIntent).also { scenario = it }
            stage = "first-review"
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText(name, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            stage = "replacement-intent"
            opened.onActivity { it.startActivity(sourceIntent(refused)) }
            stage = "refusal"
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText("Your previous .torrent remains selected.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(name, substring = true).assertExists()
            stage = "combined"
            rule.onNodeWithText("Options").performClick()
            stage = "return"
            rule.onNodeWithText("+ Add").performClick()
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText(name, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            org.junit.Assert.assertEquals("Replacing/refusing/browsing a source cannot register a job",
                before, engine.completeIdentityInventory())
            stage = "close"
        } catch (failure: Throwable) {
            primaryFailure = failure
            captureFailure(failure)
            throw failure
        } finally {
            try {
                try {
                    // ActivityScenario filters lifecycle events by its launch
                    // Intent's data/type. Real onNewIntent calls setIntent to
                    // the replacement URI, so restore only the test monitor's
                    // identity AFTER all product assertions, without dispatch
                    // or accept(). Otherwise DESTROYED is ignored at close.
                    scenario?.onActivity { it.intent = launchIntent }
                    scenario?.close()
                } catch (close: Throwable) {
                    val original = primaryFailure
                    if (original != null) original.addSuppressed(close)
                    else {
                        stage = "close"
                        captureFailure(close)
                        throw close
                    }
                }
            } finally {
                DocumentsContract.deleteDocument(context.contentResolver, first)
                DocumentsContract.deleteDocument(context.contentResolver, refused)
            }
        }
    }

}
