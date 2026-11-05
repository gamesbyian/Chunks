package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage

/**
 * WCAG contrast between a rendered control's dominant colour (its background)
 * and the colour farthest from it in luminance (its text or outline). Measures
 * actual pixels, so it catches a theme default that the source does not show.
 * Pass the unmerged Text node, not its button, so an outline is not mistaken
 * for the words.
 */
internal fun SemanticsNodeInteraction.renderedContrast(): Double {
    val pixels = captureToImage().toPixelMap()
    val counts = HashMap<Int, Int>()
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
        val c = pixels[x, y]
        if (c.alpha > 0.9f) counts.merge(argb(c), 1, Int::plus)
    }
    val background = Color(counts.maxBy { it.value }.key)
    val bgL = background.luminance().toDouble()
    val inkL = counts.keys.map { Color(it).luminance().toDouble() }
        .maxBy { kotlin.math.abs(it - bgL) }
    val (hi, lo) = if (inkL > bgL) inkL to bgL else bgL to inkL
    return (hi + 0.05) / (lo + 0.05)
}

private fun argb(c: Color): Int =
    ((c.alpha * 255).toInt() shl 24) or ((c.red * 255).toInt() shl 16) or
        ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()

/**
 * Text nodes below 4.5:1 among those fully visible and enabled right now.
 * A line cut by the viewport edge has no text pixels to measure, and a
 * disabled control (any ancestor carries Disabled) is exempt under WCAG 1.4.3.
 */
internal fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.lowContrastText(
    tag: String, rootHeightPx: Int, density: Float, minimum: Double = 4.5
): Pair<Int, List<String>> {
    val nodes = onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
        androidx.compose.ui.semantics.SemanticsProperties.Text), useUnmergedTree = true)
    var measured = 0
    val low = ArrayList<String>()
    for (i in 0 until nodes.fetchSemanticsNodes().size) {
        val n = nodes[i].fetchSemanticsNode()
        val b = n.boundsInRoot
        var p: androidx.compose.ui.semantics.SemanticsNode? = n
        var disabled = false
        while (p != null && !disabled) {
            disabled = p.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled); p = p.parent
        }
        if (b.height < 12f * density || b.width < 4f || b.top < 0f || b.bottom > rootHeightPx || disabled) continue
        val c = nodes[i].renderedContrast()
        measured++
        if (c < minimum) low += "$tag ${"%.2f".format(c)}:1 " + (n.config.getOrNull(
            androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString()?.take(70) ?: "")
    }
    return measured to low
}
