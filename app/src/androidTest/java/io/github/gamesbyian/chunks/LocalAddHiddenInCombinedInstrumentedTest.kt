package io.github.gamesbyian.chunks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed journey across the local device engine and All environments: an
 * acknowledged local magnet Add that the Combined search then hides is
 * explained, and Show all brings it back. The magnet is metadata-only with an
 * unused hash and no trackers, so no payload is fetched; it is removed by
 * exact hash afterwards, keeping (empty) data.
 *
 * Behavioral: the canonical footer opens Local Universal Add directly, then
 * the exact native job is observed before search hides it. Recovery must
 * expose that same job. No legacy device-controls route is a precondition.
 */
@RunWith(AndroidJUnit4::class)
class LocalAddHiddenInCombinedInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val hash = "c0ffee" + "5".repeat(34)
    private val name = "Chunks combined hidden add fixture"

    @After fun cleanup() {
        LocalEngineHolder.current()?.removeKeepingData(hash)
        // Other installed tests can persist a narrow Combined filter. Do not
        // contaminate later exact-hash Add tests with this scenario either.
        CombinedViewPreferences(rule.activity).save(UnifiedTransferQuery())
    }

    private fun waitFor(ms: Long = 20_000, condition: () -> Boolean) = rule.waitUntil(ms, condition)

    /**
     * Find -> reveal -> re-find -> assert -> act (TEST-ARCHITECTURE lazy-container contract) in one
     * bounded loop, dispatching the semantics click AT MOST once. Presence and enabled state come from
     * the node's own semantics. The one Compose case with no dedicated type, a row disposed between
     * that read and the action, is a plain IllegalStateException "unattached node" thrown BEFORE the
     * action runs, so re-locating is safe; anything else propagates.
     */
    private fun activateOnce(target: androidx.compose.ui.test.SemanticsMatcher) {
        var dispatched = false
        waitFor(30_000) {
            if (dispatched) return@waitFor true
            val node = rule.onAllNodes(target).fetchSemanticsNodes().singleOrNull() ?: return@waitFor false
            if (androidx.compose.ui.semantics.SemanticsActions.OnClick !in node.config ||
                SemanticsProperties.Disabled in node.config) return@waitFor false
            try {
                rule.onNode(target).performScrollTo()
                rule.onNode(target).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                dispatched = true
                true
            } catch (failure: IllegalStateException) {
                if (failure.message?.contains("unattached node") != true) throw failure
                false
            }
        }
    }

    @Test fun anAcknowledgedLocalAddHiddenByTheCombinedSearchIsExplained() {
        // Test the ordinary All/All baseline before deliberately narrowing by
        // search. A previous test may have persisted STARTED while this test
        // intentionally adds a stopped (metadata-only) local torrent.
        // Merely tapping All environments never resets the transfer filter.
        CombinedViewPreferences(rule.activity).save(UnifiedTransferQuery())
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).assertIsSelected()
        val field = androidx.compose.ui.test.hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD)
        waitFor { rule.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(field).performScrollTo().performTextReplacement(
            "magnet:?xt=urn:btih:$hash&dn=" + name.replace(" ", "%20"))
        // Metadata only: nothing would download even if a peer appeared.
        val start = androidx.compose.ui.test.hasTestTag(ChunksSemantics.ADD_START_PREFERENCE)
        fun startState() = rule.onNode(start).fetchSemanticsNode().config
            .getOrElseNullable(SemanticsProperties.ToggleableState) { null }?.name
        // Start OFF through the toggle's own click action, then REQUIRE it to read Off: a missed
        // coordinate tap left Start on, so the Add was not metadata-only and the expected
        // message never appeared (the same class as the protocol journey's Start toggle).
        waitFor { rule.onAllNodes(start).fetchSemanticsNodes().isNotEmpty() }
        if (startState() == "On") activateOnce(start)
        waitFor { startState() == "Off" }
        val addTile = androidx.compose.ui.test.hasTestTag(ChunksSemantics.ADD_SUBMIT)
        activateOnce(addTile)
        // The engine acknowledged and read back the Add.
        waitFor(30_000) {
            rule.onAllNodes(hasText("Magnet added in metadata-only mode", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // This is a read-only, exact-hash native confirmation. A metadata-only
        // magnet may carry a placeholder display name until BEP9 completes,
        // but it must still own a single real engine job before Combined
        // can claim that it was newly added.
        var lastNativeSampleAt = 0L
        var exactNativeJobSeen = false
        waitFor(30_000) {
            // JNI inventory reads are not free. Do not hot-loop them on
            // Compose's short test-condition polling interval.
            val now = android.os.SystemClock.elapsedRealtime()
            if (!exactNativeJobSeen && now - lastNativeSampleAt >= 250L) {
                lastNativeSampleAt = now
                exactNativeJobSeen = runCatching {
                    LocalEngineHolder.current()?.list().orEmpty()
                        .count { it.infoHash.equals(hash, ignoreCase = true) } == 1
                }.getOrDefault(false)
            }
            exactNativeJobSeen
        }
        rule.onNode(androidx.compose.ui.test.hasTestTag(ChunksSemantics.FOOTER_TRANSFERS)).performClick()
        // The scope chip exists only when more than one environment is configured; a
        // local-only install is already showing every environment.
        if (rule.onAllNodesWithText("All clients").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithText("All clients").performScrollTo().performClick()
        // First demand a fresh, exact-hash local row in the canonical
        // Combined inventory. A mere native Add receipt is NOT sufficient to
        // claim that a new transfer is hidden; the notice is only allowed
        // after a complete current source observation contains that job.
        val search = hasSetTextAction() and hasText("Search names or hashes")
        waitFor { runCatching {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(search)
            true
        }.getOrDefault(false) }
        rule.onNode(search).performTextReplacement(hash)
        val exactRow = hasContentDescription("Details for", substring = true) and hasClickAction()
        waitFor(30_000) { runCatching {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(exactRow)
            rule.onAllNodes(exactRow).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false) }
        // Now hide the same observed row with a contradictory search.
        // This must surface a recovery action without fabricating a row,
        // changing its Add target, or sending any extra engine command.
        // Once nonempty, the TextField semantics may expose its value instead
        // of the placeholder. Address the only editable search field by action.
        rule.onNode(hasSetTextAction()).performTextReplacement("no transfer is called this")
        val notice = "Your new transfer on On this device is hidden by the current search, filter or client view."
        waitFor(30_000) { runCatching {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(notice))
            true
        }.getOrDefault(false) }
        rule.onNode(hasText("Show all transfers") and hasClickAction()).performScrollTo().performClick()
        waitFor(5_000) { rule.onAllNodes(hasText(notice)).fetchSemanticsNodes().isEmpty() }
        // A second exact-hash search after Show All proves the original job
        // remains accessible. Search/recovery itself never touches its data.
        rule.onNode(hasSetTextAction()).performTextReplacement(hash)
        waitFor(30_000) { runCatching {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(exactRow)
            rule.onAllNodes(exactRow).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false) }
    }
}
