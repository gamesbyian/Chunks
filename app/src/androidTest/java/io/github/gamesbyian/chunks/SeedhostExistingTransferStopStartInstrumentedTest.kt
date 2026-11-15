package io.github.gamesbyian.chunks

import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PROTECTED, OWNER-AUTHORIZED (2026-11-14) real-host operation through the INSTALLED app:
 * choose one EXISTING completed, currently started transfer on the primary Seedhost,
 * then Stop it and Start it again from its canonical Transfers row. Each step is
 * one dispatch, read back independently by exact hash; the journey ends in the
 * original started state. No Add, Remove, priority, path or file operation, no
 * retry after an uncertain result. Failures report only fixed stage markers.
 */
@RunWith(AndroidJUnit4::class)
class SeedhostExistingTransferStopStartInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val args: Bundle get() = InstrumentationRegistry.getArguments()

    private fun decoded(key: String): String? = args.getString(key)?.let {
        String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8)
    }

    private fun stage(name: String) {
        require(name in STAGES)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("chunks_stage", name) })
    }

    private fun waitOrStage(failStage: String, timeoutMs: Long, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            stage(failStage)
            throw AssertionError("Timed out at $failStage")
        }
    }

    private fun exact(remote: RTorrentRemote, hash: String): RemoteTransfer? = runBlocking {
        remote.listTransfers("main").singleOrNull { it.hash.equals(hash, ignoreCase = true) }
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

    @Test fun existingSeedingTransferStopsAndStartsFromTheInstalledApp() {
        assumeTrue("Protected explicit owner-authorized existing-transfer operation only",
            args.getString("chunks_existing_stop_start") == "authorized")
        val url = requireNotNull(decoded("chunks_live_url_b64"))
        val user = requireNotNull(decoded("chunks_live_user_b64"))
        val password = requireNotNull(decoded("chunks_live_pass_b64"))
        val target = URI(url)
        require(target.scheme.equals("https", true) && target.host.equals("ianseeds.wood.seedhost.eu", true) &&
            target.userInfo == null && target.rawQuery == null && target.rawFragment == null)
        val remote = RTorrentRemote(RemoteCredentials(url, user, password))
        stage("authorized")

        // Choose one existing job: complete, currently started, with a name that is
        // unique in the inventory so its row and controls are unambiguous.
        val inventory = runBlocking { remote.listTransfers("main") }
        val names = inventory.groupingBy { it.name }.eachCount()
        val chosen = inventory.filter {
            it.started && it.sizeBytes > 0 && it.completedBytes == it.sizeBytes &&
                names[it.name] == 1 && it.name.length in 3..120 && it.name.none { c -> c.isISOControl() }
        }.minByOrNull { it.hash.lowercase() }
        if (chosen == null) {
            stage("no-candidate")
            throw AssertionError("No existing completed started transfer with a unique name")
        }
        val hash = chosen.hash
        val identitiesBefore = inventory.map { it.hash.lowercase() }.toSet()
        stage("candidate")

        // Installed journey: named Connect -> verified -> Connections -> View transfers.
        waitOrStage("launch-failed", 30_000) { textVisible("Connections") }
        rule.onNodeWithContentDescription("Connections").performClick()
        waitOrStage("form-missing", 30_000) {
            textVisible("Seedbox hostname, ruTorrent URL or RPC address") ||
                runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(
                    hasText("Seedbox hostname, ruTorrent URL or RPC address")); true }.getOrDefault(false)
        }
        if (!(typeInto("Connection name (optional)", "Wood") &&
                typeInto("Seedbox hostname, ruTorrent URL or RPC address", url) &&
                typeInto("Username, if required", user) && typeInto("Password", password))) {
            stage("form-input-failed"); throw AssertionError("Could not enter a Connect field")
        }
        val connect = hasTestTag(ChunksSemantics.CONNECTION_CONNECT)
        waitOrStage("connect-control-missing", 20_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(connect)
                rule.onNode(connect, useUnmergedTree = true).assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        rule.waitForIdle()
        rule.onNode(connect, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        waitOrStage("connect-failed", 90_000) {
            rule.onAllNodesWithText("Connection verified").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Continue to Connections").performClick()
        waitOrStage("connections-missing", 30_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connected ·", substring = true))
                true
            }.getOrDefault(false)
        }
        rule.onNodeWithContentDescription("View transfers on current verified server").performClick()
        waitOrStage("transfers-missing", 60_000) {
            rule.onAllNodesWithText("Search names or hashes").fetchSemanticsNodes().isNotEmpty()
        }
        stage("connected")

        // Narrow the canonical list to the exact hash, then expand that one row.
        rule.onNodeWithText("Search names or hashes").performTextReplacement(hash.lowercase())
        val row = hasContentDescription(" on Server", substring = true)
        waitOrStage("row-missing", 30_000) {
            runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(row); true }.getOrDefault(false)
        }
        rule.onAllNodes(row).onFirst().performClick()
        // Only the one exact-hash row is expanded; its quick-control group holds the
        // single lifecycle button (labelled with the row's display name).
        fun control(verb: String) = hasContentDescription("$verb ", substring = true) and
            hasAnyAncestor(hasTestTag(ChunksSemantics.TRANSFER_ROW_QUICK_CONTROLS))
        waitOrStage("stop-control-missing", 30_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(control("Stop"))
                rule.onNode(control("Stop")).assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        stage("row-expanded")

        // STOP: exactly one dispatch, then independent exact-hash readback.
        rule.onNode(control("Stop")).performSemanticsAction(SemanticsActions.OnClick)
        stage("stop-sent")
        waitOrStage("stop-unverified", 60_000) { exact(remote, hash)?.started == false }
        stage("stopped-readback")

        // START: the refreshed row now offers Start for the same exact job.
        waitOrStage("start-control-missing", 60_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(control("Start"))
                rule.onNode(control("Start")).assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        rule.onNode(control("Start")).performSemanticsAction(SemanticsActions.OnClick)
        stage("start-sent")
        waitOrStage("start-unverified", 60_000) { exact(remote, hash)?.started == true }
        stage("started-readback")

        // Restored and nothing else removed: every pre-existing identity remains.
        val after = runBlocking { remote.listTransfers("main") }
        assertTrue("Target restored to started", after.single { it.hash.equals(hash, true) }.started)
        assertTrue("No pre-existing transfer disappeared",
            after.map { it.hash.lowercase() }.toSet().containsAll(identitiesBefore))
        stage("restored")
    }

    private companion object {
        val STAGES = setOf("authorized", "no-candidate", "candidate", "launch-failed", "form-missing", "form-input-failed", "connect-control-missing",
            "connect-failed", "connections-missing", "transfers-missing", "connected", "row-missing",
            "stop-control-missing", "row-expanded", "stop-sent", "stop-unverified", "stopped-readback",
            "start-control-missing", "start-sent", "start-unverified", "started-readback", "restored")
    }
}
