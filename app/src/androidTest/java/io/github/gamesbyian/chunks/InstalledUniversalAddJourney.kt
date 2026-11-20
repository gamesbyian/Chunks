package io.github.gamesbyian.chunks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode

/** Shared installed acceptance for review, a single dispatch, receipt and continuation. */
internal class InstalledUniversalAddJourney(private val rule: ComposeTestRule) {
    private fun reveal(matcher: SemanticsMatcher) {
        val matches = rule.onAllNodes(matcher).fetchSemanticsNodes()
        if (matches.isEmpty()) rule.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        rule.onNode(matcher).performScrollTo()
    }

    fun activate(tag: String, timeoutMillis: Long = 45_000) =
        LazyComposeActions.activateOnce(rule, hasTestTag(tag), timeoutMillis)

    fun submit() = activate(ChunksSemantics.ADD_SUBMIT)

    fun hasReceiptState(state: String): Boolean = listOf(false, true).any { unmerged ->
        rule.onAllNodes(hasStateDescription(state), useUnmergedTree = unmerged)
            .fetchSemanticsNodes().isNotEmpty()
    }

    fun viewExactTransfer(environment: String, hash: String) {
        activate(ChunksSemantics.ADD_VIEW_TRANSFER)
        val search = hasTestTag(ChunksSemantics.TRANSFERS_SEARCH)
        val toggles = hasTestTag(ChunksSemantics.TRANSFERS_ENVIRONMENT_TOGGLES)
        val destination = hasTestTag(ChunksSemantics.TRANSFERS_ENVIRONMENT_TOGGLES + "." + environment)
        val selectedClient = hasAnyAncestor(toggles) and isSelected()
        rule.waitUntil(45_000) {
            runCatching {
                reveal(search)
                rule.onNode(search).fetchSemanticsNode().config[SemanticsProperties.EditableText]
                    .text.equals(hash, ignoreCase = true) &&
                    rule.onAllNodes(destination and selectedClient).fetchSemanticsNodes().size == 1 &&
                    rule.onAllNodes(selectedClient).fetchSemanticsNodes().size == 1
            }.getOrDefault(false)
        }
    }
}
