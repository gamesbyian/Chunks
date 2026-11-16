package io.github.gamesbyian.chunks

import androidx.compose.ui.test.onNodeWithTag
import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
 * show the union of all three (restored automatically, no Load tap), each
 * client chip with its own live count, and a same-hash cohort job once per
 * numbered server. No Add/Start/Stop/Remove. Rewritten 2026-11-15 for the
 * current Welcome -> Connections -> acknowledgement -> Transfers journey.
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

    private fun visibleConnectForm() = runCatching {
        scrollTo("Seedbox hostname, ruTorrent URL or RPC address")
        true
    }.getOrDefault(false)

    /**
     * The current production journey (same steps as the passing disposable
     * two-server test): named Connect -> verified acknowledgement -> Connections.
     * Exactly one Connect dispatch; never retried.
     */
    private fun connect(server: RemoteCredentials, name: String, label: String) {
        rule.openConnectionFormIfNeeded()
        waitFor("form-$label-missing") { visibleConnectForm() }
        typeInto("Connection name (optional)", name)
        typeInto("Seedbox hostname, ruTorrent URL or RPC address", server.endpoint)
        typeInto("Username, if required", server.username)
        typeInto("Password", server.password)
        val connectControl = hasTestTag(ChunksSemantics.CONNECTION_CONNECT)
        waitFor("connect-$label-control-missing", 20_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(connectControl)
                rule.onNode(connectControl, useUnmergedTree = true).assertIsDisplayed().assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        rule.waitForIdle()
        rule.onNode(connectControl, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        waitFor("connect-$label-failed", 120_000) { visible("Connection verified") }
        waitFor("connect-$label-name-missing", 10_000) { visible("$name is connected") }
        rule.onNodeWithText("Continue to Connections").performClick()
        waitFor("connections-$label-missing") {
            runCatching { scrollTo("Connected") }
            visible("Connected")
        }
        stage("connected-$label")
    }

    /** Explicit Disconnect keeps the saved verified profile; then add the next. */
    private fun disconnectAndAddAnother(label: String) {
        rule.openConnectedManageIfNeeded()
        scrollTo("Disconnect")
        rule.onNodeWithText("Disconnect").performClick()
        waitFor("disconnect-$label-failed") {
            runCatching { scrollTo("+ Add connection"); true }.getOrDefault(false) && !visible("Connected")
        }
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_ADD).performClick()
        stage("disconnected-$label")
    }

    /** All four clients (Local + three remotes) show the All chip. */
    private fun showAllEnvironments() {
        val preferences = CombinedViewPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val all = io.github.gamesbyian.chunks.model.EnvironmentViewScope.All
        if (preferences.load().scope == all) return
        val allChip = hasContentDescription("Show all clients")
        waitFor("all-chip-missing", 20_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(
                    hasTestTag(ChunksSemantics.TRANSFERS_ENVIRONMENT_TOGGLES))
                rule.onAllNodes(allChip).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        rule.onAllNodes(allChip).onFirst().performScrollTo().performClick()
        waitFor("all-scope-not-applied", 15_000) { preferences.load().scope == all }
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
        val suppliedHash = args.getString("chunks_shared_hash_b64")?.let {
            decoded("chunks_shared_hash_b64").lowercase()
        }
        require(suppliedHash == null || suppliedHash.matches(Regex("[0-9a-f]{40}")))

        fun truth(c: RemoteCredentials) =
            runBlocking { RTorrentRemote(c).listTransfers("main") }.map { it.hash.lowercase() to it.started }
        // A failed read names only the instance and the exception class: never
        // a message, URL, account, path or transfer name.
        val before = listOf("primary" to primary, "second" to second, "third" to third).map { (label, c) ->
            try { truth(c) } catch (failure: Exception) {
                stage("truth-$label-failed-" + failure::class.java.simpleName)
                throw AssertionError("Ground-truth read failed: $label")
            }
        }
        val counts = before.map { it.size }
        // Same-hash cohort: the supplied staged fixture, or any infohash that
        // instances 2 and 3 both already hold (read-only intersection).
        val sharedHash = suppliedHash ?: before[1].map { it.first }
            .intersect(before[2].map { it.first }.toSet()).sorted().firstOrNull()
        require(sharedHash == null ||
            (before[1].any { it.first == sharedHash } && before[2].any { it.first == sharedHash })) {
            "Supplied same-hash cohort fixture absent on instances 2 and 3"
        }
        stage(if (sharedHash == null) "no-shared-hash" else "shared-hash-present")
        stage("ground-truth")

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("launch-failed") { visible("Connections") }
            rule.onNodeWithContentDescription("Connections").performClick()
            rule.openConnectionFormIfNeeded(allowConnected = false)
            connect(primary, "Seed P", "primary")
            disconnectAndAddAnother("primary")
            connect(second, "Seed 2", "second")
            disconnectAndAddAnother("second")
            connect(third, "Seed 3", "third")
            rule.onNodeWithContentDescription("View transfers on current verified server").performClick()
            waitFor("combined-open-failed") { visible("Search names or hashes") }
            showAllEnvironments()
            // Saved instances are restored read-only without a tap. Union by
            // environment: a same infohash on instances 2 and 3 is two rows,
            // so the total equals the sum of independent counts.
            waitFor("combined-union-mismatch", 120_000) {
                runCatching {
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(
                        hasText("${counts.sum()} transfers ·", substring = true))
                    true
                }.getOrDefault(false)
            }
            stage("combined-union")
            // Labels carry the address after the name; match name and count separately.
            fun chip(name: String, n: Int) = rule.onAllNodes(
                hasContentDescription("Showing Server · $name", substring = true) and
                    hasContentDescription(", $n transfers", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
            waitFor("combined-chips-mismatch") {
                runCatching {
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(
                        hasTestTag(ChunksSemantics.TRANSFERS_ENVIRONMENT_TOGGLES))
                    chip("Seed P", counts[0]) && chip("Seed 2", counts[1]) && chip("Seed 3", counts[2])
                }.getOrDefault(false)
            }
            stage("combined-chips")
            if (sharedHash != null) {
                // Search by exact hash: one row per numbered instance, never collapsed.
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(
                    hasText("Search names or hashes", substring = true))
                rule.onNodeWithText("Search names or hashes", substring = true)
                    .performTextReplacement(sharedHash)
                waitFor("combined-same-hash-collapsed", 30_000) {
                    visible("2 of ${counts.sum()} transfers") || visible("2 transfers ·")
                }
                stage("combined-same-hash")
            }
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
