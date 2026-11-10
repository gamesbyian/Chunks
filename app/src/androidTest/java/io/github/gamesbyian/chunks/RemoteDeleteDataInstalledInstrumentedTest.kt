package io.github.gamesbyian.chunks

import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Remote L6 through the installed app, on a DISPOSABLE real rTorrent behind
 * the pinned upstream ruTorrent PHP tree: connect via ruTorrent's own
 * httprpc route, open the exact staged transfer and choose "Remove transfer
 * and data". The app must report the exact session gone and cleanup only
 * QUEUED (never claim files are gone). The harness then runs the real
 * erasedata collector and independently verifies payload absence and the
 * foreign canary. Never a protected host.
 */
@RunWith(AndroidJUnit4::class)
class RemoteDeleteDataInstalledInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val args: Bundle get() = InstrumentationRegistry.getArguments()
    private fun decoded(name: String) =
        String(Base64.decode(requireNotNull(args.getString(name)), Base64.NO_WRAP), Charsets.UTF_8)

    private fun stage(name: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("chunks_stage", name) })

    private fun visible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun scrollTo(text: String) =
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))

    private fun waitFor(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        try { rule.waitUntil(timeoutMs, condition) } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out: $what", failure)
        }
    }

    private fun typeInto(label: String, value: String) {
        var last: Throwable? = null
        repeat(5) {
            try {
                scrollTo(label)
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

    /** Read/scroll checks may retry; the final click happens exactly once. */
    private fun clickOnce(text: String) {
        waitFor("$text enabled", 45_000) {
            runCatching { scrollTo(text); true }.getOrDefault(false) && visible(text)
        }
        rule.onNodeWithText(text).performScrollTo()
        rule.waitForIdle()
        rule.onNodeWithText(text).performClick()
    }

    @Test fun installedRemoveTransferAndDataQueuesExactErasedataWithoutClaimingFilesGone() {
        assumeTrue("Requires the disposable ruTorrent delete-data workflow",
            args.getString("chunks_remote_delete") == "authorized")
        val url = decoded("chunks_remote_delete_url_b64")
        val hash = decoded("chunks_remote_delete_hash_b64").lowercase()
        require(url == "http://127.0.0.1:18082/rutorrent1/" && hash.matches(Regex("[0-9a-f]{40}")))
        val remote = RTorrentRemote(RemoteCredentials(url, "", ""))
        val before = runBlocking { remote.listTransfers("main") }
        assertTrue("Staged fixture must be present before Remove", before.any { it.hash.equals(hash, true) })
        stage("ground-truth")

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("persistent Connections destination") { visible("Connections") }
            rule.onNodeWithContentDescription("Connections").performClick()
            waitFor("connection form") { visible("Seedbox hostname, ruTorrent URL or RPC address") }
            typeInto("Seedbox hostname, ruTorrent URL or RPC address", url)
            scrollTo("Connect to rTorrent")
            rule.onNodeWithText("Connect to rTorrent").performClick()
            waitFor("verified Connect result", 90_000) { visible("Connection verified") }
            rule.onNodeWithText("Continue to Connections").performClick()
            waitFor("verified Connections management") {
                visible("Connected ·") && visible("View transfers")
            }
            rule.onNodeWithContentDescription("View transfers on current verified server").performClick()
            val stagedName = before.single { it.hash.equals(hash, true) }.name
            val exactRow = hasContentDescription("Details for $stagedName on", substring = true)
            waitFor("canonical exact staged transfer", 90_000) {
                runCatching {
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(exactRow)
                    rule.onNode(exactRow).fetchSemanticsNode() != null
                }.getOrDefault(false)
            }
            rule.onNode(exactRow).performClick()
            // Until canonical Details owns reviewed destructive Remove, hand
            // off only the exact authenticated owner. Never infer this from
            // a saved-profile view, duplicate hash or scope-only filter.
            rule.onNode(hasText("Open Server ·", substring = true))
                .performScrollTo().performClick()
            waitFor("exact-owner operational rTorrent queue", 90_000) {
                visible("· ${before.size} transfer") && visible("+ Add torrent")
            }
            stage("connected")
            typeInto("Search names, aliases, folders or hashes", hash)
            // The search filter applies asynchronously (run on 18a8e87): wait
            // for the filtered card, open its disclosure, then the action.
            // Disclosure is read-only, so repeating it is safe.
            waitFor("Remove action reachable on the exact transfer", 60_000) {
                val reachable = runCatching { scrollTo("Remove from rTorrent…"); true }.getOrDefault(false)
                if (!reachable) runCatching {
                    scrollTo("More details")
                    rule.onNodeWithText("More details").performScrollTo().performClick()
                    rule.waitForIdle()
                }
                reachable
            }
            clickOnce("Remove from rTorrent…")
            waitFor("data deletion offered on the ruTorrent route") {
                visible("Remove transfer and data") && !visible("Remove transfer and data (unavailable)")
            }
            stage("review-open")
            clickOnce("Remove transfer and data")
            // The result card can leave with the removed row on refresh, so
            // accept the queued message OR independent exact-session absence.
            waitFor("queued delete-data outcome", 60_000) {
                visible("physical data deletion is NOT yet verified") ||
                    runBlocking { remote.lookupName(hash) } == null
            }
            assertTrue("The app must never claim remote files are already gone",
                !visible("payload files are absent") && !visible("files were deleted"))
            stage("queued-not-claimed")
        }
        // Independent server readback: only the exact session is gone.
        val after = runBlocking { remote.listTransfers("main") }
        assertTrue("Exact session must be absent", after.none { it.hash.equals(hash, true) })
        assertTrue(runBlocking { remote.lookupName(hash) } == null)
        assertEquals("No unrelated session may change",
            before.filterNot { it.hash.equals(hash, true) }.map { it.hash.lowercase() }.toSet(),
            after.map { it.hash.lowercase() }.toSet())
        stage("session-absent")
    }
}
