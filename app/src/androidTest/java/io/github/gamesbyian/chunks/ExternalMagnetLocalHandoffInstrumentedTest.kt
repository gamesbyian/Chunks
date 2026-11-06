package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
                rule.onAllNodesWithText("On this phone (experimental)")
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
                rule.onAllNodesWithText("On this phone (experimental)")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Reaching the native Add form is required, but merely opening
            // this external link must not execute any native Add or Start.
            rule.onNodeWithText("Start downloading after Add (recommended)").assertExists()
            rule.onNodeWithText(magnet).assertExists()
            assertTrue(rule.onAllNodesWithText(magnet).fetchSemanticsNodes().isNotEmpty())
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
            rule.onNodeWithText("All environments…").performScrollTo().performClick()
            rule.onNodeWithText("Back").performClick()
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
                rule.onAllNodesWithText("On this phone (experimental)")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Review download").assertDoesNotExist()
            org.junit.Assert.assertEquals("Recreation must not Add from old Intent",
                before, LocalEngineHolder.current()?.completeIdentityInventory().orEmpty())
        }
    }

}
