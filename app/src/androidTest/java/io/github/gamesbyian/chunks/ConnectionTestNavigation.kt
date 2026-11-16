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

/** Independent expected contain geometry for provider-bitmap pixel witnesses. */
internal fun fitArtworkForPixelCheck(source: android.graphics.Bitmap, width: Int, height: Int): android.graphics.Bitmap {
    val expected = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
    val w = source.width * scale; val h = source.height * scale
    android.graphics.Canvas(expected).drawBitmap(source, null, android.graphics.RectF(
        (width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    return expected
}
