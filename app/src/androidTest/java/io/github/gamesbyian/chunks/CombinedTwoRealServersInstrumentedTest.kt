package io.github.gamesbyian.chunks

import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * Finally A's daemon is stopped mid-session and Combined must report it truthfully.
 */
@RunWith(AndroidJUnit4::class)
class CombinedTwoRealServersInstrumentedTest {
    // Queue frame continuations on the Compose test scheduler. The legacy eager
    // scheduler can resume Android ripple work on an IO completion thread.
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

    /** Verified session management is Connections, never a second transfer list. */
    private fun connectedQueue() = visible("Connected ·") && visible("View transfers")

    private fun enterConnectedQueueIfCombined() {
        // Automatic remembered-login restoration remains in the canonical
        // browser with exact profile scope; manual Connect acknowledges and
        // stays on Connections until explicitly entering that same browser.
        // Neither case is allowed to open the retired server-only list.
        waitFor("verified restored session or Connect acknowledgement", 90_000) {
            visible("Filters") || visible("Batch tools") ||
                visible("Continue to Connections") || connectedQueue()
        }
        if (visible("Filters") || visible("Batch tools")) return
        if (visible("Continue to Connections")) {
            rule.onNodeWithText("Continue to Connections").performClick()
        }
        if (!connectedQueue()) {
            rule.onNodeWithContentDescription("Connections").performClick()
        }
        waitFor("Connections manages current verified account") { connectedQueue() }
        scrollTo("View transfers")
        rule.onNodeWithText("View transfers").performClick()
        waitFor("canonical Transfers catalog") { visible("Filters") || visible("Batch tools") }
    }

    /** A deliberate All scope, never a second legacy list or an auth switch. */
    private fun selectAllInCanonicalCatalog() {
        scrollTo("Filters")
        rule.onNodeWithText("Filters").performClick()
        rule.onNodeWithText("All").performClick()
        waitFor("canonical All scope") { visible("Batch tools") }
    }

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

    private fun connect(url: String, user: String = "", password: String = "") {
        // Server A sits behind basic auth; server B is anonymous, so its
        // fields are explicitly cleared after A's login was typed.
        typeInto("Seedbox hostname, ruTorrent URL or RPC address", url)
        typeInto("Username, if required", user)
        typeInto("Password", password)
        scrollTo("Remember this server and login on this device")
        // The IME can recycle this lazy item during selector resolution
        // (42238837684 and 42241610339 failed BEFORE action invocation).
        // Repeat only readiness/selector reads. Once the framework supplies
        // its action, latch BEFORE invoking it: no exception or refused action
        // may lead to a second production Connect invocation.
        var connectInvoked = false
        rule.waitUntil(15_000) {
            if (connectedQueue()) true else {
                try {
                    scrollTo("Connect to rTorrent")
                    rule.onNodeWithText("Connect to rTorrent").assertIsDisplayed().assertIsEnabled()
                        .performSemanticsAction(SemanticsActions.OnClick) {
                            check(!connectInvoked)
                            connectInvoked = true
                            assertTrue("The production Connect action must be accepted", it())
                        }
                    connectInvoked
                } catch (failure: Throwable) {
                    if (connectInvoked) throw failure
                    false
                }
            }
        }
        waitFor("verified Connect acknowledgement", 90_000) {
            visible("Continue to Connections")
        }
        rule.onNodeWithText("Continue to Connections").performClick()
        waitFor("verified session shown on Connections", 90_000) { connectedQueue() }
    }

    /**
     * Second instrumentation run after the harness force-stops the app
     * process: the remembered verified server reconnects read-only from a
     * truly new process, with no Connect form and no command replay.
     */
    @Test fun afterProcessDeathRememberedServerReconnects() {
        assumeTrue("Requires the two-disposable-server workflow phase 2",
            args.getString("chunks_combined_phase") == "after-process-death")
        val urlB = decoded("chunks_combined_b_b64")
        val before = runBlocking { RTorrentRemote(RemoteCredentials(urlB, "", "")).listTransfers("main") }
            .map { it.hash to it.started }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use {
            enterConnectedQueueIfCombined()
            waitFor("canonical Transfers after verified reconnect", 90_000) {
                visible("Filters") || visible("Batch tools")
            }
            assertTrue(!visible("Seedbox hostname, ruTorrent URL or RPC address"))
            stage("process-death-reconnected")
        }
        val after = runBlocking { RTorrentRemote(RemoteCredentials(urlB, "", "")).listTransfers("main") }
            .map { it.hash to it.started }.toSet()
        assertEquals("Reconnect must not replay any command", before, after)
        stage("process-death-unchanged")
    }

    @Test fun combinedShowsSameHashOnTwoRealServersWithDistinctOriginsAndReconnects() {
        assumeTrue("Requires the two-disposable-server workflow",
            args.getString("chunks_combined_disposable") == "authorized")
        val urlA = decoded("chunks_combined_a_b64")
        val userA = decoded("chunks_combined_a_user_b64")
        val passA = decoded("chunks_combined_a_pass_b64")
        fun credsFor(url: String) = if (url == urlA) RemoteCredentials(url, userA, passA)
            else RemoteCredentials(url, "", "")
        val urlB = decoded("chunks_combined_b_b64")
        val shared = decoded("chunks_combined_shared_b64")
        val onlyA = decoded("chunks_combined_only_a_b64")
        val onlyB = decoded("chunks_combined_only_b_b64")
        val sharedHash = decoded("chunks_combined_shared_hash_b64").lowercase()
        require(listOf(urlA, urlB).all { it.startsWith("http://127.0.0.1:") } && urlA != urlB)
        val portA = urlA.substringAfter("127.0.0.1:").substringBefore('/')
        val portB = urlB.substringAfter("127.0.0.1:").substringBefore('/')

        // Independent ground truth before any UI step.
        val truthA = runBlocking { RTorrentRemote(credsFor(urlA)).listTransfers("main") }
        val truthB = runBlocking { RTorrentRemote(RemoteCredentials(urlB, "", "")).listTransfers("main") }
        assertEquals(setOf(shared, onlyA), truthA.map { it.name }.toSet())
        assertEquals(setOf(shared, onlyB), truthB.map { it.name }.toSet())
        assertEquals(sharedHash, truthA.single { it.name == shared }.hash.lowercase())
        assertEquals(sharedHash, truthB.single { it.name == shared }.hash.lowercase())
        // Read-only independent witness for the canonical file panel on B.
        // A has the same hash but is saved-only, so its UI must offer NO
        // authenticated file/priority operations under B's session.
        val filesOnB = runBlocking {
            RTorrentRemote(RemoteCredentials(urlB, "", "")).listFiles(sharedHash)
        }
        assertTrue("Disposable authenticated B must report real file rows", filesOnB.isNotEmpty())
        stage("ground-truth")

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("persistent Connections destination") { visible("Connections") }
            rule.onNodeWithContentDescription("Connections").performClick()
            waitFor("connection form") { visible("Seedbox hostname, ruTorrent URL or RPC address") }
            connect(urlA, userA, passA)
            stage("connected-a")
            // Connections is now a persistent direct destination. The exact
            // connected server's Disconnect remains distinct from Forget.
            waitFor("Connections destination") { visible("Connections") }
            rule.onNodeWithContentDescription("Connections").performClick()
            waitFor("connected server management") { visible("Disconnect current session") }
            scrollTo("Disconnect current session")
            rule.onNodeWithText("Disconnect current session").performClick()
            waitFor("connection screen after Disconnect") {
                visible("Seedbox hostname, ruTorrent URL or RPC address") || visible("Saved servers")
            }
            stage("disconnected-a")
            connect(urlB)
            stage("connected-b")

            enterConnectedQueueIfCombined()
            selectAllInCanonicalCatalog()
            stage("combined-open")
            scrollTo("Load other saved servers (read-only)")
            rule.onNodeWithText("Load other saved servers (read-only)").performClick()
            // Two real sources, two rows each: the union must keep the
            // same-hash transfer once PER SERVER, never collapse it by hash.
            // The compact count is an earlier LazyColumn item. Clicking the
            // bottom-of-list saved-server load action can leave the viewport
            // scrolled past it, so a raw semantics lookup can falsely report
            // absence even when both same-hash rows are already composed.
            // Scroll to the actual count item; still require a real four-row
            // result before proceeding with all per-server identity checks.
            waitFor("union of both real servers", 60_000) {
                runCatching {
                    scrollTo("Showing 4")
                    visible("Showing 4")
                }.getOrDefault(false)
            }
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

            // The same infohash exists on both servers. Details must follow
            // the selected environment, never borrow the other server's
            // observation, reported Place, or command authority. Use the real
            // Compose row and read-only detail screen, not a synthetic panel.
            fun inspectExactDetails(port: String, expected: io.github.gamesbyian.chunks.rpc.RemoteTransfer) {
                // The compact row itself opens full exact-account Details.
                // There is no expandable card or intermediate All-details CTA.
                val row = hasContentDescription("Details for $shared on", substring = true) and
                    hasContentDescription(":$port", substring = true)
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(row)
                rule.onNode(row).performClick()
                waitFor("exact-environment Details for $port") {
                    visible("Transfer details") && visible("Environment:") && visible(":$port")
                }
                assertTrue("Details lost the selected transfer name on $port", visible(shared))
                rule.onNodeWithText("Show technical details").performScrollTo().performClick()
                assertTrue("Details borrowed another transfer hash on $port",
                    visible("Exact infohash: $sharedHash"))
                assertTrue("Details borrowed another server's reported Place on $port",
                    visible("Save folder reported by engine: ${expected.directory}"))
                if (port == portB) {
                    // The remote file/policy panel has migrated into the SAME
                    // canonical Details; opening it may only READ, not mutate.
                    rule.onNodeWithText("Files & priorities…").performScrollTo().performClick()
                    waitFor("exact authenticated B file inventory inside full Details", 45_000) {
                        visible("${filesOnB.size} server-reported files")
                    }
                    rule.onNodeWithText(filesOnB.first().path, substring = true)
                        .performScrollTo().assertExists()
                } else {
                    assertTrue("Saved-only A cannot borrow B's mutation/file authority",
                        !visible("Files & priorities…"))
                }
                rule.onNodeWithText("Back to transfers").performScrollTo().performClick()
                // The catalog restores its previous scrolled viewport: the
                // offscreen summary ("Showing 4") is not a valid visible-node
                // gate after returning from a row near the bottom. Verify
                // the actual lazy catalog and direct-Details rows are present;
                // exact server/hash/Place authority is asserted above and
                // by the subsequent same-hash second-server inspection.
                waitFor("Combined catalog restored after Details for $port") {
                    rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() &&
                        rule.onAllNodes(
                            hasContentDescription("Details for", substring = true)
                        ).fetchSemanticsNodes().isNotEmpty()
                }
            }
            inspectExactDetails(portA, truthA.single { it.hash.equals(sharedHash, true) })
            inspectExactDetails(portB, truthB.single { it.hash.equals(sharedHash, true) })
            stage("combined-exact-details")

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
                RTorrentRemote(credsFor(url)).listTransfers("main")
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
            scrollTo("Batch tools")
            rule.onNodeWithText("Batch tools").performClick()
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
            enterConnectedQueueIfCombined()
            waitFor("automatic read-only reconnect into canonical catalog", 90_000) {
                visible("Filters") || visible("Batch tools")
            }
            assertTrue("Relaunch must not require the Connect form",
                !visible("Seedbox hostname, ruTorrent URL or RPC address"))
            stage("relaunch-reconnected")
        }
        // Read-only test: both inventories are exactly as before.
        val afterA = runBlocking { RTorrentRemote(credsFor(urlA)).listTransfers("main") }
        val afterB = runBlocking { RTorrentRemote(RemoteCredentials(urlB, "", "")).listTransfers("main") }
        assertEquals(truthA.map { it.hash to it.started }.toSet(), afterA.map { it.hash to it.started }.toSet())
        assertEquals(truthB.map { it.hash to it.started }.toSet(), afterB.map { it.hash to it.started }.toSet())
        stage("unchanged")

        // C2 through a real adapter: server A's daemon goes away mid-session
        // (its gateway still answers). Combined must report A's refresh
        // failure, keep its last rows marked out of date, keep B live, and
        // never claim A is current.
        ActivityScenario.launch(MainActivity::class.java).use {
            enterConnectedQueueIfCombined()
            selectAllInCanonicalCatalog()
            scrollTo("Load other saved servers (read-only)")
            rule.onNodeWithText("Load other saved servers (read-only)").performClick()
            waitFor("union before fault", 60_000) { visible("Showing 4") }
            fun fault(action: String) {
                val c = java.net.URL("http://127.0.0.1:18083/$action").openConnection()
                    as java.net.HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 40_000
                assertEquals("Harness fault switch $action failed", 200, c.responseCode)
                c.disconnect()
            }
            fun line(port: String, text: String) = rule.onAllNodes(
                hasText(":$port", substring = true) and hasText(text, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
            fun refreshOthers() {
                scrollTo("Refresh other saved servers")
                rule.onNodeWithText("Refresh other saved servers").performClick()
                // Expanded, the toggle reads "Hide environment status".
                scrollTo("nvironment status")
                if (!visible("Hide environment status"))
                    rule.onNodeWithText("Environment status", substring = true).performClick()
            }
            // Timeout: A's daemon is frozen (gateway accepts, no reply).
            fault("freeze-a")
            stage("server-a-frozen")
            refreshOthers()
            waitFor("A unresponsive reported failed with last list", 120_000) {
                line(portA, "Refresh failed (did not answer in time") && line(portA, "showing last list")
            }
            assertTrue("Unresponsive server A must not be Live", !line(portA, ": Live"))
            stage("c2-timeout-truthful")
            // Recovery: the same saved profile reads live again after an
            // explicit refresh, never from the previous failed read.
            fault("thaw-a")
            waitFor("refresh enabled after timeout", 60_000) {
                rule.onAllNodesWithText("Refresh other saved servers").fetchSemanticsNodes().isNotEmpty()
            }
            refreshOthers()
            waitFor("A live again after explicit refresh", 90_000) {
                line(portA, ": Live") && line(portA, "· 2 transfers") && !line(portA, "Refresh failed")
            }
            stage("c2-recovered")
            // Auth rejection: A's gateway password changes under the saved
            // login. Combined must name the sign-in failure, not "network".
            fault("rotate-auth-a")
            refreshOthers()
            waitFor("A sign-in rejection named with last list", 90_000) {
                line(portA, "sign-in rejected") && line(portA, "showing last list")
            }
            assertTrue("Rejected server A must not be Live", !line(portA, ": Live"))
            stage("c2-auth-rejected-truthful")
            fault("restore-auth-a")
            fault("offline-a")
            stage("server-a-offline")
            refreshOthers()
            waitFor("A reported failed with last list retained", 90_000) {
                line(portA, "Refresh failed") && line(portA, "showing last list")
            }
            assertTrue("Offline server A must not be shown as Live", !line(portA, ": Live"))
            assertTrue("Signed-in server B stays live", line(portB, "· 2 transfers") && !line(portB, "Refresh failed"))
            // The compact stale-source summary follows the search/filter
            // controls in the lazy Combined list. Bring it into composition
            // before checking the truthful, specific out-of-date claim.
            // This remains an assertion on the production wording, not a
            // substitute for the independently verified A/B status rows.
            scrollTo("some environments are missing or out of date")
            assertTrue("Combined must flag an out-of-date source",
                visible("some environments are missing or out of date"))
            stage("c2-offline-truthful")
        }
    }
}
