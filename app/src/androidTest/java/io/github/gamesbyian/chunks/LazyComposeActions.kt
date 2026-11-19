package io.github.gamesbyian.chunks

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement

/**
 * The lazy-container interaction contract (docs/TEST-ARCHITECTURE.md) as two reusable actions, so a
 * lazy row that is not composed, or is disposed between lookup and action, is handled once and the
 * same way everywhere instead of by per-test copies.
 *
 * Shared rules:
 * - Find -> reveal -> re-find -> assert -> act, in ONE bounded loop. Nothing is cached across a
 *   scroll or recomposition.
 * - Presence, uniqueness and enabled state are read from the node's own semantics: defined
 *   conditions, not caught assertions.
 * - Compose has no dedicated type for a row disposed between that read and the action: it is a plain
 *   [IllegalStateException] "unattached node", thrown BEFORE the action runs. That single case is
 *   matched by message; everything else propagates. An ambiguous match fails loudly.
 */
internal object LazyComposeActions {
    private const val DETACHED = "unattached node"

    private fun scrollIntoView(rule: ComposeTestRule, target: SemanticsMatcher, unmerged: Boolean) {
        // A plain scroll when a lazy list is not involved; otherwise scroll the nearest lazy list to it.
        if (rule.onAllNodes(target, unmerged).fetchSemanticsNodes().isNotEmpty())
            rule.onNode(target, unmerged).performScrollTo()
        else if (rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty())
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(target)
    }

    /**
     * Click [target] through its semantics action AT MOST once (never a coordinate tap, so a
     * reflowing lazy list cannot make it miss), once it is unique and enabled. Use for one-shot
     * Add / Connect / Remove actions: after the action returns, the loop ends and nothing is
     * ever dispatched again.
     */
    fun activateOnce(
        rule: ComposeTestRule,
        target: SemanticsMatcher,
        timeoutMillis: Long = 30_000,
        /** True when only the unmerged button node is present (seen on API 35 viewport transitions). */
        useUnmergedTree: Boolean = false
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var detached: IllegalStateException? = null
        while (true) {
            scrollIntoView(rule, target, useUnmergedTree)
            val nodes = rule.onAllNodes(target, useUnmergedTree).fetchSemanticsNodes()
            if (nodes.size > 1) throw AssertionError("Ambiguous click target: ${nodes.size} nodes match")
            val node = nodes.singleOrNull()
            if (node != null && SemanticsActions.OnClick in node.config &&
                SemanticsProperties.Disabled !in node.config) {
                try {
                    rule.onNode(target, useUnmergedTree).performSemanticsAction(SemanticsActions.OnClick)
                    return
                } catch (failure: IllegalStateException) {
                    if (failure.message?.contains(DETACHED) != true) throw failure
                    detached = failure
                }
            }
            if (SystemClock.elapsedRealtime() > deadline)
                throw AssertionError("Click target was not unique and enabled in time (detached each time: ${detached != null})", detached)
            rule.waitForIdle()
        }
    }

    /**
     * Replace the text of [target]. Text replacement is idempotent and submits nothing, so
     * re-locating after a not-yet-composed or disposed row is safe.
     */
    fun replaceText(
        rule: ComposeTestRule,
        target: SemanticsMatcher,
        text: String,
        timeoutMillis: Long = 30_000,
        useUnmergedTree: Boolean = false
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var detached: IllegalStateException? = null
        while (true) {
            scrollIntoView(rule, target, useUnmergedTree)
            val present = rule.onAllNodes(target, useUnmergedTree).fetchSemanticsNodes().size
            if (present > 1) throw AssertionError("Ambiguous field: $present nodes match")
            if (present == 1) {
                try {
                    rule.onNode(target, useUnmergedTree).performTextReplacement(text)
                    return
                } catch (failure: IllegalStateException) {
                    if (failure.message?.contains(DETACHED) != true) throw failure
                    detached = failure
                }
            }
            if (SystemClock.elapsedRealtime() > deadline)
                throw AssertionError("Could not enter a field (never composed, or detached each time)", detached)
            rule.waitForIdle()
        }
    }
}
