package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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

        val target = URI(url!!)
        assertTrue("Protected test requires a credential-free HTTPS URL",
            target.scheme.equals("https", true) && target.host != null &&
                target.userInfo == null && target.rawQuery == null && target.rawFragment == null)

        // All interaction uses the REAL installed application's Compose fields.
        rule.onNodeWithText("ruTorrent web URL or RPC address").performTextReplacement(url)
        rule.onNodeWithText("Username, if required").performTextReplacement(user!!)
        rule.onNodeWithText("Password").performTextReplacement(password!!)
        rule.onNodeWithText("Connect to rTorrent").performClick()
        try {
            rule.waitUntil(90_000) {
                rule.onAllNodesWithText("It's all here.").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            // Do not dump the still-visible credential fields or HTTP headers.
            throw AssertionError("Real Android app did not reach the transfer queue. " +
                "Inspect the protected runner locally; no credentials are included.", failure)
        }
        rule.onNodeWithText("It's all here.").assertExists()

        val totals = rule.onAllNodesWithText("transfers in the 'main' server view", substring = true)
            .fetchSemanticsNodes()
        assertTrue("The actual main view should render a server inventory summary", totals.isNotEmpty())
        val summary = totals.first().config[SemanticsProperties.Text].joinToString(" ")
        val count = Regex("(\\d+) transfers in the 'main' server view").find(summary)
            ?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("Real development server returned an empty or malformed queue", count != null && count > 0)

        // Only the authenticated transfer screen is captured. Never record login.
        captureConnectedQueue("seedhost-android-queue-live")
        rule.onNodeWithText("Refresh").performClick()
        rule.waitUntil(60_000) {
            rule.onAllNodesWithText("Refresh", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("It's all here.").assertExists()
        rule.onNodeWithText("Refresh problem").assertDoesNotExist()
        captureConnectedQueue("seedhost-android-queue-refreshed-live")
    }
}
