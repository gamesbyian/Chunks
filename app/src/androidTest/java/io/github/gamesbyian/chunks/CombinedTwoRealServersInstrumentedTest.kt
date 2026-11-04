package io.github.gamesbyian.chunks

import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
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
 * D1/C1 on TWO real disposable rTorrent daemons (never a protected host):
 * the installed app's own Connect form saves both profiles, Combined shows the
 * union with the SAME infohash on both servers as two separately labelled
 * rows (two instances on one host must never look identical), and a fresh
 * activity launch reconnects the remembered server read-only. A mixed Combined
 * batch Start/Stop over the same hash on both servers may change only the
 * signed-in server B (independent XML-RPC readback); saved-only A is untouched.
 */
@RunWith(AndroidJUnit4::class)
class CombinedTwoRealServersInstrumentedTest {
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

    // Compact server header: "<host> · <n> transfers" with the server's own
    // Add action. Each disposable server holds exactly two fixtures.
    private fun connectedQueue() = visible("127.0.0.1 · 2 transfers") && visible("+ Add torrent")

    private fun scrollTo(text: String) =
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))

    private fun waitFor(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val ui = runCatching { rule.onRoot(useUnmergedTree = true).printToString().take(6_000) }
                .getOrDefault("semantics unavailable")
            throw AssertionError("Timed out: $what\n$ui", failure)
        }
    }

    /** IME focus can recompose the lazy form mid-input; re-scroll and retry. */
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

    private fun connect(url: String) {
        // Both disposable daemons are anonymous: the username and password
        // fields stay empty from the fresh install onward and are not retyped.
        typeInto("Seedbox hostname, ruTorrent URL or RPC address", url)
        scrollTo("Remember this server and login on this device")
        scrollTo("Connect to rTorrent")
        rule.onNodeWithText("Connect to rTorrent").performClick()
        waitFor("server queue for the requested endpoint", 90_000) { connectedQueue() }
    }

    @Test fun combinedShowsSameHashOnTwoRealServersWithDistinctOriginsAndReconnects() {
        assumeTrue("Requires the two-disposable-server workflow",
            args.getString("chunks_combined_disposable") == "authorized")
        val urlA = decoded("chunks_combined_a_b64")
        val urlB = decoded("chunks_combined_b_b64")
        val shared = decoded("chunks_combined_shared_b64")
        val onlyA = decoded("chunks_combined_only_a_b64")
        val onlyB = decoded("chunks_combined_only_b_b64")
        val sharedHash = decoded("chunks_combined_shared_hash_b64").lowercase()
        require(listOf(urlA, urlB).all { it.startsWith("http://127.0.0.1:") } && urlA != urlB)
        val portA = urlA.substringAfter("127.0.0.1:").substringBefore('/')
        val portB = urlB.substringAfter("127.0.0.1:").substringBefore('/')

        // Independent ground truth before any UI step.
        val truthA = runBlocking { RTorrentRemote(RemoteCredentials(urlA, "", "")).listTransfers("main") }
        val truthB = runBlocking { RTorrentRemote(RemoteCredentials(urlB, "", "")).listTransfers("main") }
        assertEquals(setOf(shared, onlyA), truthA.map { it.name }.toSet())
        assertEquals(setOf(shared, onlyB), truthB.map { it.name }.toSet())
        assertEquals(sharedHash, truthA.single { it.name == shared }.hash.lowercase())
        assertEquals(sharedHash, truthB.single { it.name == shared }.hash.lowercase())
        stage("ground-truth")

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("phone-first launch") { visible("On this phone (experimental)") }
            rule.onNodeWithText("Back").performScrollTo().performClick()
            connect(urlA)
            stage("connected-a")
            // Options lists screen destinations; Disconnect is on "Server".
            rule.onNodeWithText("Options").performClick()
            waitFor("Options destinations") { visible("Find a screen or tool") }
            rule.onNodeWithText("Server").performScrollTo().performClick()
            waitFor("Server screen") { visible("Disconnect") }
            scrollTo("Disconnect")
            rule.onNodeWithText("Disconnect").performClick()
            waitFor("connection screen after Disconnect") {
                visible("Seedbox hostname, ruTorrent URL or RPC address") || visible("Saved servers")
            }
            stage("disconnected-a")
            connect(urlB)
            stage("connected-b")

            rule.onAllNodesWithText("All environments…")[0].performClick()
            waitFor("Combined view") { visible("All environments") && !visible("All environments…") }
            stage("combined-open")
            scrollTo("Load other saved servers (read-only)")
            rule.onNodeWithText("Load other saved servers (read-only)").performClick()
            // Two real sources, two rows each: the union must keep the
            // same-hash transfer once PER SERVER, never collapse it by hash.
            waitFor("union of both real servers", 60_000) { visible("Showing 4") }
            stage("combined-union")
            scrollTo("Environment status")
            rule.onNodeWithText("Environment status", substring = true).performClick()
            waitFor("both server sources reported with their own port") {
                rule.onAllNodesWithText(":$portA", substring = true).fetchSemanticsNodes().any() &&
                    rule.onAllNodesWithText(":$portB", substring = true).fetchSemanticsNodes().any() &&
                    rule.onAllNodesWithText("· 2 transfers", substring = true)
                        .fetchSemanticsNodes().size >= 2
            }
            for (name in listOf(onlyA, onlyB, shared)) {
                scrollTo(name)
                assertTrue("Combined row missing: $name", visible(name))
            }
            stage("combined-rows")

            // Per-target command scope: select the SAME infohash on saved-only
            // server A and signed-in server B, then Start. Only B may change;
            // independent XML-RPC is the postcondition, not the UI text.
            fun selectRow(port: String) {
                val box = hasContentDescription("Select $shared on", substring = true) and
                    hasContentDescription(":$port", substring = true)
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(box)
                rule.onNode(box).performClick()
            }
            fun startedOn(url: String) = runBlocking {
                RTorrentRemote(RemoteCredentials(url, "", "")).listTransfers("main")
            }.single { it.hash.equals(sharedHash, true) }.started
            fun runBatch(action: String, confirm: String) {
                scrollTo(action)
                rule.onNodeWithText(action).performClick()
                scrollTo(confirm)
                rule.onNodeWithText(confirm).performClick()
                waitFor("batch outcome", 60_000) { visible("Batch result available") }
            }
            fun ensureSelecting() {
                val already = runCatching { scrollTo("Done selecting") }.isSuccess
                if (!already) {
                    scrollTo("Select…")
                    rule.onNodeWithText("Select…").performClick()
                }
            }
            ensureSelecting()
            selectRow(portA)
            selectRow(portB)
            runBatch("Start…", "Start selected")
            waitFor("server B started by Combined", 30_000) { startedOn(urlB) }
            assertTrue("Saved-only server A must not be mutated by Combined", !startedOn(urlA))
            stage("combined-start-scoped")
            ensureSelecting()
            selectRow(portB)
            runBatch("Stop…", "Stop selected")
            waitFor("server B stopped by Combined", 30_000) { !startedOn(urlB) }
            assertTrue(!startedOn(urlA))
            stage("combined-stop-scoped")
        }

        // Fresh activity in the same app process: the remembered verified
        // login reconnects read-only once the Connection screen is entered,
        // without retyping any address or credential. Not process-death proof.
        ActivityScenario.launch(MainActivity::class.java).use {
            // Run 41789912090: with a remembered verified server, launch opens
            // that server's queue directly. Accept either entry route, but the
            // queue must appear with no address or credential retyped.
            waitFor("relaunch entry") { connectedQueue() || visible("On this phone (experimental)") }
            if (!connectedQueue()) rule.onNodeWithText("Back").performScrollTo().performClick()
            waitFor("automatic read-only reconnect of remembered server", 90_000) {
                connectedQueue()
            }
            assertTrue("Relaunch must not require the Connect form",
                !visible("Seedbox hostname, ruTorrent URL or RPC address"))
            stage("relaunch-reconnected")
        }
        // Read-only test: both inventories are exactly as before.
        val afterA = runBlocking { RTorrentRemote(RemoteCredentials(urlA, "", "")).listTransfers("main") }
        val afterB = runBlocking { RTorrentRemote(RemoteCredentials(urlB, "", "")).listTransfers("main") }
        assertEquals(truthA.map { it.hash to it.started }.toSet(), afterA.map { it.hash to it.started }.toSet())
        assertEquals(truthB.map { it.hash to it.started }.toSet(), afterB.map { it.hash to it.started }.toSet())
        stage("unchanged")
    }
}
