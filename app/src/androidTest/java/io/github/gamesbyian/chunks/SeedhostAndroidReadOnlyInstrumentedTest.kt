package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.os.Environment
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URI
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real installed Android app -> owner's protected development Seedhost.
 *
 * This test is skipped by ordinary PR instrumentation. It is invoked only by
 * the manual, main-only protected workflow. No mutation, password screenshots,
 * response-body diagnostics or secret values in assertion messages.
 */
@RunWith(AndroidJUnit4::class)
class SeedhostAndroidReadOnlyInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun decoded(key: String): String? {
        val encoded = InstrumentationRegistry.getArguments().getString(key) ?: return null
        return String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
    }

    /**
     * Only fixed enum checkpoint labels reach protected CI. Never include
     * credentials, URLs, tracker values, file paths or exception bodies.
     */
    private fun checkpoint(stage: String) {
        require(stage in setOf(
            "arguments", "form", "connect-visible", "connect", "queue", "inventory",
            "first-capture", "refresh-click", "refresh-busy",
            "refresh-idle", "final-capture", "add-open", "add-preview", "add-return",
            "connect-error", "connect-busy", "connect-login-form", "connect-no-visible-state"
        ))
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2, Bundle().apply { putString("chunks_stage", stage) }
        )
    }

    private fun captureConnectedQueue(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { stream ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, requireNotNull(stream)))
        }
        check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null) == 1)
    }

    @Test fun authenticHttpsSeedhostInventoryRendersInTheInstalledApp() {
        val url = decoded("chunks_live_url_b64")
        val user = decoded("chunks_live_user_b64")
        val password = decoded("chunks_live_pass_b64")
        assumeTrue("Manual protected Seedhost workflow only",
            !url.isNullOrBlank() && !user.isNullOrBlank() && !password.isNullOrBlank())

        checkpoint("arguments")
        val target = URI(url!!)
        assertTrue("Protected test requires a credential-free HTTPS URL",
            target.scheme.equals("https", true) && target.host != null &&
                target.userInfo == null && target.rawQuery == null && target.rawFragment == null)

        // All interaction uses the REAL installed application's Compose fields.
        // The Connect list composes lazily; at large text the field starts below the fold.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Seedbox hostname, ruTorrent URL or RPC address"))
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").performTextReplacement(url)
        rule.onNodeWithText("Username, if required").performTextReplacement(user!!)
        rule.onNodeWithText("Password").performTextReplacement(password!!)
        checkpoint("form")
        // Connect is below several text fields inside a LazyColumn. After the
        // password IME opens, its item can be completely uncomposed, so
        // onNodeWithText(...).performScrollTo() cannot find it. Ask the lazy
        // container to compose and scroll to the item BEFORE finding the node.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connect to rTorrent"))
        checkpoint("connect-visible")
        rule.onNodeWithText("Connect to rTorrent").performClick()
        checkpoint("connect")
        try {
            rule.waitUntil(90_000) {
                rule.onAllNodesWithText("It's all here.").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            // Only branch on fixed, nonsecret UI titles. Never emit the actual
            // server error text, login values, screen hierarchy or throwable.
            // Compose LazyColumn virtualizes offscreen children: absence of a
            // visible error title alone cannot prove there was no error.
            val stage = when {
                rule.onAllNodesWithText("Connection unsuccessful").fetchSemanticsNodes().isNotEmpty() ->
                    "connect-error"
                rule.onAllNodesWithText("Finding secure rTorrent…").fetchSemanticsNodes().isNotEmpty() ->
                    "connect-busy"
                rule.onAllNodesWithText("Connect to rTorrent").fetchSemanticsNodes().isNotEmpty() ->
                    "connect-login-form"
                else -> "connect-no-visible-state"
            }
            checkpoint(stage)
            throw AssertionError("The installed app did not reach the real server queue. " +
                "See only the fixed private-safe stage marker; raw failure withheld.")
        }
        checkpoint("queue")
        rule.onNodeWithText("It's all here.").assertExists()
        // No screenshot is permitted while the credentials field is onscreen.
        rule.onNodeWithText("Password").assertDoesNotExist()

        val totals = rule.onAllNodesWithText("transfers in the 'main' server view", substring = true)
            .fetchSemanticsNodes()
        assertTrue("The actual main view should render a server inventory summary", totals.isNotEmpty())
        val summary = totals.first().config[SemanticsProperties.Text].joinToString(" ")
        val count = Regex("(\\d+) transfers in the 'main' server view").find(summary)
            ?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("Real development server returned an empty or malformed queue", count != null && count > 0)

        // Only the authenticated transfer screen is captured. Never record login.
        checkpoint("inventory")
        captureConnectedQueue("seedhost-android-queue-live")
        checkpoint("first-capture")
        rule.onNodeWithText("Refresh").performClick()
        checkpoint("refresh-click")
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Refreshing…").fetchSemanticsNodes().isNotEmpty()
        }
        checkpoint("refresh-busy")
        rule.waitUntil(90_000) {
            rule.onAllNodesWithText("Refresh", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("It's all here.").assertExists()
        rule.onNodeWithText("Refresh problem").assertDoesNotExist()
        checkpoint("refresh-idle")
        captureConnectedQueue("seedhost-android-queue-refreshed-live")
        checkpoint("final-capture")

        // This is an additional READ-ONLY installed UI journey. It never
        // supplies a magnet, selects a .torrent, or presses Review/Confirm.
        // The same actual Seedhost adapter must render the Add destinations
        // without inventing a Chunks folder on the remote server.
        rule.onNodeWithText("+ Add torrent").performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("A place for every download.").fetchSemanticsNodes().isNotEmpty()
        }
        checkpoint("add-open")
        // The Add screen is another LazyColumn. Once the destination-status
        // request settles, explicitly compose the custom folder item before
        // asserting it exists; a label offscreen is otherwise not discoverable.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasText("Custom server folder (optional)")
        )
        rule.onNodeWithText("Custom server folder (optional)").assertExists()
        rule.waitUntil(30_000) {
            val hasDefault = rule.onAllNodesWithText("Using rTorrent default Place:", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
            val unavailable = rule.onAllNodesWithText("No usable default Place was reported.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
            hasDefault || unavailable
        }
        rule.onNodeWithText("Review Add").assertDoesNotExist()
        // No preview candidate exists, so the Add mutation must remain
        // impossible while we inspect this genuine connected screen.
        captureConnectedQueue("seedhost-android-add-readonly-live")
        checkpoint("add-preview")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Back to transfers"))
        rule.onNodeWithText("Back to transfers").performClick()
        rule.onNodeWithText("It's all here.").assertExists()
        checkpoint("add-return")
    }
}
