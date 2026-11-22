package io.github.gamesbyian.chunks

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString

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
 * - A row disposed between the read and an action can produce either of these
 *   re-fetch failures, BEFORE any action is dispatched:
 *   - a plain [IllegalStateException] "unattached node";
 *   - an [AssertionError] from the re-fetch that found no node, or more than one ("Expected exactly
 *     '1' node but ..."). The installed Connect journey hit this as an immediate failure on its
 *     first field.
 *   A read-only performScrollTo may also report "The node is no longer in the
 *   tree"; re-find that scroll target, but never re-dispatch an actual action.
 *   Only these known pre-action cases are matched; everything else propagates.
 * - A transient second match (an outgoing and an incoming screen composed together for a frame)
 *   is not acted on; the loop waits for exactly one. An ambiguity that persists until the deadline
 *   fails loudly.
 */
internal object LazyComposeActions {
    private const val DETACHED = "unattached node"
    /** Compose's re-fetch failure prefix: thrown before the action when the match is not unique. */
    private const val NOT_UNIQUE_AT_ACTION = "Expected exactly '1' node but"

    /** True only for a failure Compose raised while re-fetching the node, before acting on it. */
    private fun undispatched(failure: Throwable): Boolean = when (failure) {
        is IllegalStateException -> failure.message?.contains(DETACHED) == true
        is AssertionError -> failure.message?.contains(NOT_UNIQUE_AT_ACTION) == true
        else -> false
    }

    private const val NO_SCROLL_PARENT = "no parent layout with a Scroll"
    private const val NO_SCROLL_TARGET = "No node found that matches"
    private const val DETACHED_DURING_SCROLL = "The node is no longer in the tree"

    /**
     * The CI JVM run 44963652692 observed a node disappear between the
     * read-only count and performScrollTo. Compose reports this as an
     * AssertionError with the last known semantics, not as an unattached-node
     * IllegalStateException. This decision is ONLY for a scroll, before any
     * Add/Connect/Remove click is even attempted; action failures still fail.
     */
    internal fun retryableScrollFailure(failure: AssertionError): Boolean {
        val message = failure.message.orEmpty()
        val detachedScroll =
            (message.startsWith("Action performScrollTo() failed.") ||
                message.startsWith("Action performScrollToNode() failed.")) &&
                message.contains(DETACHED_DURING_SCROLL)
        return message.contains(NO_SCROLL_PARENT) || message.contains(NO_SCROLL_TARGET) ||
            detachedScroll || undispatched(failure)
    }

    /**
     * Read/scroll-only reveal. Scroll a node only when exactly one matches: `performScrollTo` on a
     * transiently duplicated match throws "found '2' nodes" (the installed Connect journey's
     * immediate first-field failure). Ambiguity is left to the caller's wait. A target without a
     * scrollable parent, or one disposed mid-scroll, simply needs no scroll this round.
     */
    private fun scrollIntoView(rule: ComposeTestRule, target: SemanticsMatcher, unmerged: Boolean) {
        val matches = rule.onAllNodes(target, unmerged).fetchSemanticsNodes().size
        try {
            if (matches == 1) rule.onNode(target, unmerged).performScrollTo()
            // A plain scroll when a lazy list is not involved; otherwise scroll the nearest lazy list to it.
            else if (matches == 0 && rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1)
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(target)
        } catch (failure: AssertionError) {
            if (!retryableScrollFailure(failure)) throw failure
        } catch (failure: IllegalStateException) {
            if (!undispatched(failure)) throw failure
        }
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
        var detached: Throwable? = null
        var lastCount = 0
        while (true) {
            scrollIntoView(rule, target, useUnmergedTree)
            val nodes = rule.onAllNodes(target, useUnmergedTree).fetchSemanticsNodes()
            lastCount = nodes.size
            val node = nodes.singleOrNull()
            if (node != null && SemanticsActions.OnClick in node.config &&
                SemanticsProperties.Disabled !in node.config) {
                try {
                    rule.onNode(target, useUnmergedTree).performSemanticsAction(SemanticsActions.OnClick)
                    return
                } catch (failure: Throwable) {
                    // Only a re-fetch failure, raised before the click, may be re-located.
                    if (!undispatched(failure)) throw failure
                    detached = failure
                }
            }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError(
                if (lastCount > 1) "Ambiguous click target: $lastCount nodes match"
                else "Click target was not unique and enabled in time (detached each time: ${detached != null})",
                detached)
            rule.waitForIdle()
        }
    }

    /**
     * Edit a draft using its SetText semantics. A separate focus request can
     * open the keyboard, resize the viewport and dispose the field before SetText.
     * Tests that exercise focus or an IME action must do so explicitly.
     */
    fun replaceText(
        rule: ComposeTestRule,
        target: SemanticsMatcher,
        text: String,
        timeoutMillis: Long = 30_000,
        useUnmergedTree: Boolean = false
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var detached: Throwable? = null
        val editable = target and hasSetTextAction() and isEnabled()
        while (true) {
            scrollIntoView(rule, target, useUnmergedTree)
            val nodes = rule.onAllNodes(target, useUnmergedTree).fetchSemanticsNodes()
            val present = nodes.size
            if (nodes.singleOrNull()?.let(editable::matches) == true) {
                try {
                    rule.onNode(target, useUnmergedTree).performSemanticsAction(SemanticsActions.SetText) { setText ->
                        check(setText(AnnotatedString(text))) { "Editable field rejected its SetText action" }
                    }
                    return
                } catch (failure: Throwable) {
                    if (!undispatched(failure)) throw failure
                    detached = failure
                }
            }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError(
                if (present > 1) "Ambiguous field: $present nodes match"
                else "Could not enter a field (never composed, or detached each time)", detached)
            rule.waitForIdle()
        }
    }
}
