package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.os.Environment
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.net.URI
import kotlinx.coroutines.runBlocking
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
            "arguments", "ground-truth", "launch-failed", "form-missing", "form-open", "form-input-failed", "form", "connect-visible", "connect", "verified",
            "connections", "transfers", "inventory", "inventory-mismatch", "first-capture",
            "add-open", "add-preview", "add-return",
            "connect-error", "connect-busy", "connect-login-form", "connect-no-visible-state",
            "connect-identity-unverified", "connect-auth-rejected", "connect-tls-failed",
            "connect-network-failed", "connect-protocol-failed", "connect-service-failed",
            "connect-endpoint-missing", "connect-proxy-or-rate-limited"
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

    private fun textVisible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** The same proven retrying entry used by the passing three-Seedhost journey. */
    private fun typeInto(label: String, value: String): Boolean {
        repeat(5) {
            try {
                if (rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty())
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label, substring = true))
                rule.waitForIdle()
                rule.onNodeWithText(label).performTextReplacement(value)
                rule.waitForIdle()
                return true
            } catch (_: AssertionError) {
                Thread.sleep(400)
            }
        }
        return false
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

        // Independent read-only ground truth through the same adapter class.
        fun serverCount() = runBlocking {
            RTorrentRemote(RemoteCredentials(url, user!!, password!!)).listTransfers("main").size
        }
        val before = serverCount()
        assertTrue("Real development server returned an empty queue", before > 0)
        checkpoint("ground-truth")

        // Current journey (2026-11-15): Welcome -> Connections -> named Connect ->
        // verified acknowledgement -> Connections -> View transfers (canonical list).
        try {
            rule.waitUntil(30_000) { textVisible("Connections") }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            checkpoint("launch-failed"); throw AssertionError("App did not launch to a Connections entry")
        }
        rule.onNodeWithContentDescription("Connections").performClick()
        try {
            rule.waitUntil(30_000) { textVisible("Seedbox hostname, ruTorrent URL or RPC address") ||
                runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(
                    hasText("Seedbox hostname, ruTorrent URL or RPC address")); true }.getOrDefault(false) }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            checkpoint("form-missing"); throw AssertionError("Connection form did not open")
        }
        checkpoint("form-open")
        if (!(typeInto("Connection name (optional)", "Wood") &&
                typeInto("Seedbox hostname, ruTorrent URL or RPC address", url) &&
                typeInto("Username, if required", user!!) && typeInto("Password", password!!))) {
            checkpoint("form-input-failed"); throw AssertionError("Could not enter a Connect field")
        }
        checkpoint("form")
        val connect = hasTestTag(ChunksSemantics.CONNECTION_CONNECT)
        rule.waitUntil(20_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(connect)
                rule.onNode(connect, useUnmergedTree = true).assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        checkpoint("connect-visible")
        rule.waitForIdle()
        // Exactly one Connect dispatch.
        rule.onNode(connect, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        checkpoint("connect")
        try {
            rule.waitUntil(90_000) {
                rule.onAllNodesWithText("Connection verified").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            // Only branch on fixed, nonsecret UI titles. Never emit the actual
            // server error text, login values, screen hierarchy or throwable.
            // Compose LazyColumn virtualizes offscreen children: absence of a
            // visible error title alone cannot prove there was no error.
            val stage = when {
                // These are fixed first-party error messages, not arbitrary
                // server response text. Never emit the real error string,
                // credentials, URL, HTTP challenge or screen hierarchy.
                rule.onAllNodesWithText("The RPC gateway allowed public reads without verifying the supplied login", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() -> "connect-identity-unverified"
                rule.onAllNodesWithText("Login rejected. Check the username/password", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() -> "connect-auth-rejected"
                rule.onAllNodesWithText("Secure connection failed.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() -> "connect-tls-failed"
                rule.onAllNodesWithText("The server is unreachable or did not respond.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() -> "connect-network-failed"
                rule.onAllNodesWithText("The server answered, but its rTorrent identity could not be verified.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() -> "connect-protocol-failed"
                rule.onAllNodesWithText("The seedbox gateway could not process a read-only connection check.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() -> "connect-service-failed"
                rule.onAllNodesWithText("No compatible rTorrent XML-RPC endpoint was verified.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() -> "connect-endpoint-missing"
                rule.onAllNodesWithText("A network proxy requires separate authentication", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithText("The server is limiting connection attempts", substring = true)
                        .fetchSemanticsNodes().isNotEmpty() -> "connect-proxy-or-rate-limited"
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
        checkpoint("verified")
        rule.onNodeWithText("Continue to Connections").performClick()
        rule.waitUntil(30_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connected ·", substring = true))
                true
            }.getOrDefault(false)
        }
        checkpoint("connections")
        rule.onNodeWithContentDescription("View transfers on current verified server").performClick()
        rule.waitUntil(60_000) {
            rule.onAllNodesWithText("Search names or hashes").fetchSemanticsNodes().isNotEmpty()
        }
        checkpoint("transfers")
        // No screenshot is permitted while the credentials field is onscreen.
        rule.onNodeWithText("Password").assertDoesNotExist()
        // The canonical summary counts the exact signed-in scope. The primary is a
        // live seedbox, so accept the independent count read before or after.
        var shown: Int? = null
        try {
            rule.waitUntil(60_000) {
                val text = rule.onAllNodesWithText(" transfers · ", substring = true).fetchSemanticsNodes()
                    .firstOrNull()?.config?.let { c -> if (androidx.compose.ui.semantics.SemanticsProperties.Text in c) c[androidx.compose.ui.semantics.SemanticsProperties.Text] else null }
                    ?.joinToString(" ").orEmpty()
                shown = Regex("^(\\d+) transfers · ").find(text)?.groupValues?.get(1)?.toIntOrNull()
                shown != null && (shown == before || shown == serverCount())
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            checkpoint("inventory-mismatch")
            throw AssertionError("Installed count did not match the independent read-only count")
        }
        checkpoint("inventory")
        captureConnectedQueue("seedhost-android-queue-live")
        checkpoint("first-capture")

        // Additional READ-ONLY Add review on the same account: no source is
        // supplied, so the single Add action must stay unusable while the
        // server's own Save-to is shown. Nothing is created on the server.
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasTestTag(ChunksSemantics.ADD_TARGET_REMOTE)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_REMOTE).performScrollTo().performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Adding to: Wood", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        checkpoint("add-open")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Custom server folder (optional)"))
        rule.onNodeWithText("Custom server folder (optional)").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(ChunksSemantics.ADD_SUBMIT))
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsNotEnabled()
        captureConnectedQueue("seedhost-android-add-readonly-live")
        checkpoint("add-preview")
        rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Search names or hashes").fetchSemanticsNodes().isNotEmpty()
        }
        checkpoint("add-return")
    }
}
