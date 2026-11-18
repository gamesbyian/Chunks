package io.github.gamesbyian.chunks

import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.RemoteClientOperations
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.TransmissionClient
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed app against ONE disposable real Transmission daemon on the CI
 * runner (adb reverse loopback; never a protected host). The test preloads a
 * unique stopped fixture with the real client, then drives the app's own
 * Connections form (Transmission type) → canonical Transfers → Details
 * Start → Remove (keep files), with independent client readback after each.
 */
@RunWith(AndroidJUnit4::class)
class ProtocolClientInstalledJourneyTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val args: Bundle get() = InstrumentationRegistry.getArguments()
    private fun decoded(name: String): String =
        String(Base64.decode(requireNotNull(args.getString(name)) { name }, Base64.NO_WRAP), Charsets.UTF_8)

    private fun stage(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putString("chunks_stage", name) })
    }

    private fun visible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            // Prefer the protocol controls when present: the whole root is too long.
            val ui = runCatching {
                rule.onNodeWithTag(ChunksSemantics.PROTOCOL_OPERATIONS, useUnmergedTree = true)
                    .printToString().take(6_000)
            }.recoverCatching { rule.onRoot(useUnmergedTree = true).printToString().take(6_000) }
                .getOrDefault("semantics unavailable")
            throw AssertionError("Timed out: $what\n$ui", failure)
        }
    }

    private fun scrollToNode(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        // Lazy lists compose rows only when scrolled to; Details is a plain
        // scrolling column. Try the lazy path, then the composed node itself.
        if (rule.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() &&
            rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty())
            runCatching { rule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(matcher) }
        rule.onAllNodes(matcher).onFirst().performScrollTo()
    }

    private fun typeInto(label: String, value: String) {
        var last: Throwable? = null
        repeat(5) {
            try {
                scrollToNode(hasText(label))
                rule.waitForIdle()
                rule.onNodeWithText(label).performTextReplacement(value)
                rule.waitForIdle()
                return
            } catch (failure: AssertionError) {
                last = failure
                Thread.sleep(400)
            }
        }
        throw AssertionError("Could not enter $label", last)
    }

    /** One dispatch of a control, after polling only until it is enabled. */
    private fun clickOnce(tag: String, what: String, timeoutMs: Long = 30_000) {
        val matcher = hasTestTag(tag)
        waitFor("enabled $what", timeoutMs) {
            runCatching {
                scrollToNode(matcher)
                rule.onNode(matcher, useUnmergedTree = true).assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        rule.waitForIdle()
        rule.onNode(matcher, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick) {
            assertTrue("$what must accept the click", it())
        }
    }

    @Test fun transmissionConnectTransfersDetailsStartAndRemove() {
        assumeTrue("Disposable runner daemon only", args.getString("chunks_protocol_disposable") == "authorized")
        val url = decoded("chunks_protocol_url_b64")
        val user = decoded("chunks_protocol_user_b64")
        val pass = decoded("chunks_protocol_pass_b64")
        val client = TransmissionClient(RemoteCredentials(url, user, pass))
        val witness = RemoteClientOperations(client, 40, 500)

        // Unique random payload: its hash cannot match any existing job.
        val random = SecureRandom()
        val name = "chunks-installed-" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        val bytes = ByteArray(64 * 1024).also(random::nextBytes)
        val fixture = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            listOf(TorrentMetainfoBuilder.Entry(listOf(name), bytes.size.toLong()) { bytes.inputStream() }),
            pieceLength = 16 * 1024))
        val hash = fixture.infoHashV1
        val added = runBlocking {
            witness.add(ClientAddRequest(ClientAddSource.TorrentFile(fixture.torrent), null, startNow = false))
        }
        assertTrue("Fixture preload: $added", added is ClientOperationOutcome.Verified)
        stage("fixture-preloaded-stopped")

        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitForIdle()
            rule.onNodeWithTag(ChunksSemantics.FOOTER_CONNECTIONS).performClick()
            waitFor("Connections") {
                runCatching { scrollToNode(hasTestTag(ChunksSemantics.CONNECTION_ADD)); true }.getOrDefault(false)
            }
            rule.openConnectionFormIfNeeded()
            clickOnce("connection-backend-transmission", "Transmission type")
            typeInto("Transmission RPC URL", url)
            typeInto("Username, if required", user)
            typeInto("Password", pass)
            clickOnce(ChunksSemantics.CONNECTION_CONNECT, "Connect")
            stage("connect-dispatched-once")

            // The app lands in canonical Transfers focused on the client.
            waitFor("fixture row in canonical Transfers", 90_000) {
                runCatching { scrollToNode(hasText(name, substring = true)); true }.getOrDefault(false) && visible(name)
            }
            stage("transfers-shows-fixture")

            val details = hasContentDescription("Details for $name", substring = true)
            waitFor("Details control") {
                runCatching { scrollToNode(details); true }.getOrDefault(false)
            }
            rule.onAllNodes(details).onFirst().performClick()
            stage("details-open")

            clickOnce(ChunksSemantics.PROTOCOL_START_STOP, "Start", 60_000)
            waitFor("Start confirmed", 60_000) { visible("Start: confirmed") }
            val started = runBlocking { client.torrent(hash) }
            assertTrue("Independent readback: started", started != null &&
                started.runState != ClientRunState.STOPPED)
            stage("start-verified-independently")

            clickOnce(ChunksSemantics.PROTOCOL_REMOVE, "Remove…", 60_000)
            clickOnce(ChunksSemantics.PROTOCOL_REMOVE_CONFIRM, "Remove, keep files")
            waitFor("Remove confirmed", 60_000) { visible("Remove: confirmed") }
            assertNull("Independent readback: removed", runBlocking { client.torrent(hash) })
            stage("remove-verified-independently")
        }
        val profiles = RememberedRemoteProtocolProfiles(InstrumentationRegistry.getInstrumentation().targetContext).list()
        assertEquals("The verified Transmission login was saved once", 1,
            profiles.count { it.credentials.endpoint == url && it.credentials.username == user })
        stage("profile-saved")
    }
}
