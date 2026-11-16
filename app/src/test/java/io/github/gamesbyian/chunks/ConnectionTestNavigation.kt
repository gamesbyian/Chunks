package io.github.gamesbyian.chunks

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Deliberate setup entry, shared by actual-Activity journeys after roster consolidation. */
internal fun ComposeTestRule.openConnectionFormIfNeeded(allowConnected: Boolean = true) {
    if (!allowConnected && onAllNodesWithText("Connected", substring = true).fetchSemanticsNodes().isNotEmpty()) return
    val add = onAllNodesWithTag(ChunksSemantics.CONNECTION_ADD)
    if (add.fetchSemanticsNodes().isNotEmpty()) add.onFirst().performScrollTo().performClick()
}
internal fun ComposeTestRule.openConnectedManageIfNeeded() {
    val manage = onAllNodesWithText("Manage")
    if (manage.fetchSemanticsNodes().isNotEmpty()) manage.onFirst().performScrollTo().performClick()
}
