package io.github.gamesbyian.chunks

import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PROTECTED, READ-ONLY D1: the installed app saves the owner's primary and
 * numbered instances 2 and 3 through its own Connect form, then Combined must
 * show the union of all three with each source's own count and the staged
 * same-hash cohort fixture once per numbered server. No Add/Start/Stop/Remove.
 * Never reports transfer names, paths, URLs or credentials: failures surface
 * only enumerated stages; the harness withholds raw output.
 */
@RunWith(AndroidJUnit4::class)
class CombinedThreeSeedhostReadOnlyInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val args: Bundle get() = InstrumentationRegistry.getArguments()
    private fun decoded(name: String): String =
        String(Base64.decode(requireNotNull(args.getString(name)), Base64.NO_WRAP), Charsets.UTF_8)

    private fun stage(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putString("chunks_stage", name) })
    }

    private fun visible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun scrollTo(text: String) =
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))

    private fun waitFor(stageOnTimeout: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            stage(stageOnTimeout)
            // Fixed label only; never the semantics tree of a real inventory.
            throw AssertionError("Timed out at $stageOnTimeout")
        }
    }

    private fun typeInto(label: String, value: String) {
        repeat(5) {
            try {
                scrollTo(label)
                rule.waitForIdle()
                rule.onNodeWithText(label).performTextReplacement(value)
                rule.waitForIdle()
                return
            } catch (_: AssertionError) {
                Thread.sleep(400)
            }
        }
        stage("form-input-failed")
        throw AssertionError("Could not enter a Connect field")
    }

    private fun queueWith(count: Int) = visible("· $count transfers") && visible("+ Add torrent")

    private fun connect(server: RemoteCredentials, count: Int, label: String) {
        typeInto("Seedbox hostname, ruTorrent URL or RPC address", server.endpoint)
        typeInto("Username, if required", server.username)
        typeInto("Password", server.password)
        scrollTo("Connect to rTorrent")
        rule.onNodeWithText("Connect to rTorrent").performClick()
        waitFor("connect-$label-failed", 120_000) { queueWith(count) }
        stage("connected-$label")
    }

    private fun disconnect(label: String) {
        // The Options panel is remembered across Disconnect/reconnect (run
        // 41800987696): tapping it again would close it. Open only if shut.
        if (!visible("Find a screen or tool")) rule.onNodeWithText("Options").performClick()
        waitFor("options-$label-missing") { visible("Find a screen or tool") }
        rule.onNodeWithText("Server").performScrollTo().performClick()
        waitFor("server-screen-$label-missing") { visible("Disconnect") }
        scrollTo("Disconnect")
        rule.onNodeWithText("Disconnect").performClick()
        // With saved servers listed first, the address field may be below the
        // lazy fold (run 41803179533); either marks the Connection screen.
        waitFor("disconnect-$label-failed") {
            visible("Saved servers") || visible("Seedbox hostname, ruTorrent URL or RPC address")
        }
        stage("disconnected-$label")
    }

    @Test fun combinedUnionOfPrimaryAndNumberedInstancesIsReadOnly() {
        assumeTrue("Protected trusted-main manual dispatch only",
            args.getString("chunks_protected_combined") == "authorized")
        val user = decoded("chunks_user_b64")
        val primary = RemoteCredentials("https://ianseeds.wood.seedhost.eu/rutorrent/", user,
            decoded("chunks_pass1_b64"))
        val second = RemoteCredentials("https://ianseeds.wood.seedhost.eu/rutorrent2/", "ianseeds",
            decoded("chunks_pass2_b64"))
        val third = RemoteCredentials("https://ianseeds.wood.seedhost.eu/rutorrent3/", "ianseeds",
            decoded("chunks_pass3_b64"))
        for (s in listOf(primary, second, third)) {
            val u = URI(s.endpoint)
            require(u.scheme == "https" && u.host == "ianseeds.wood.seedhost.eu" &&
                s.password.isNotEmpty())
        }
        val sharedHash = decoded("chunks_shared_hash_b64").lowercase()
        require(sharedHash.matches(Regex("[0-9a-f]{40}")))

        fun truth(c: RemoteCredentials) =
            runBlocking { RTorrentRemote(c).listTransfers("main") }.map { it.hash.lowercase() to it.started }
        val before = listOf(primary, second, third).map(::truth)
        val counts = before.map { it.size }
        require(before[1].any { it.first == sharedHash } && before[2].any { it.first == sharedHash }) {
            "Staged same-hash cohort fixture absent; stage it before this read-only check"
        }
        stage("ground-truth")

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("launch-failed") { visible("On this phone (experimental)") }
            rule.onNodeWithText("Back").performScrollTo().performClick()
            connect(primary, counts[0], "primary")
            disconnect("primary")
            connect(second, counts[1], "second")
            disconnect("second")
            connect(third, counts[2], "third")
            rule.onAllNodesWithText("All environments…")[0].performClick()
            waitFor("combined-open-failed") { visible("Load other saved servers (read-only)") ||
                visible("Refresh other saved servers") }
            scrollTo("Load other saved servers (read-only)")
            rule.onNodeWithText("Load other saved servers (read-only)").performClick()
            // Union by environment: the same infohash on instances 2 and 3 is
            // two rows, so the total equals the sum of independent counts.
            waitFor("combined-union-mismatch", 120_000) { visible("Showing ${counts.sum()}") }
            stage("combined-union")
            scrollTo("Environment status")
            rule.onNodeWithText("Environment status", substring = true).performClick()
            fun sourceLine(path: String, n: Int) = rule.onAllNodes(
                hasText(path, substring = true) and hasText("· $n transfers", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
            waitFor("combined-origins-mismatch") {
                sourceLine("/rutorrent/", counts[0]) && sourceLine("/rutorrent2/", counts[1]) &&
                    sourceLine("/rutorrent3/", counts[2])
            }
            stage("combined-origins")
        }
        val after = listOf(primary, second, third).map(::truth)
        // Read-only: numbered fixtures exactly unchanged. The primary is a
        // live seedbox whose own activity may change; require only its
        // pre-existing identities to remain.
        assertEquals(before[1].toSet(), after[1].toSet())
        assertEquals(before[2].toSet(), after[2].toSet())
        require(after[0].map { it.first }.containsAll(before[0].map { it.first }))
        stage("unchanged")
    }
}
