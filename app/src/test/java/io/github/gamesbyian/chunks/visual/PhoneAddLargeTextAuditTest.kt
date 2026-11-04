package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.MagnetDraftCheck
import io.github.gamesbyian.chunks.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phone Add at the smallest supported width with 200% text: the magnet check,
 * Paste and Add controls keep 48dp laid-out targets and fit the width. Nothing
 * is added.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi", fontScale = 2.0f)
class PhoneAddLargeTextAuditTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val field get() = rule.onNode(hasSetTextAction() and hasText("Or paste a magnet link"))

    private fun assertFitsAndTargets(where: String) {
        val density = rule.activity.resources.displayMetrics.density
        val small = rule.smallTouchTargets(density)
        assertTrue("$where: touch targets under 48dp: $small", small.isEmpty())
        val window = rule.onRoot().fetchSemanticsNode().size.width
        val overflowing = rule.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.boundsInRoot.width > 0f && it.boundsInRoot.right > window + 1 }
            .map { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString() }
        assertTrue("$where: text past the right edge: $overflowing", overflowing.isEmpty())
    }

    @Test fun pasteProblemAndValidStatesKeepTargetsAt320dpAnd200Percent() {
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Add torrent on this phone").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Paste magnet link").performScrollTo()
        assertFitsAndTargets("empty draft")
        rule.onRoot().captureRoboImage("build/chunks-visual/phone-add-320-200-empty.png")

        field.performScrollTo().performTextInput("magnet:?xt=urn:btih:" + "a".repeat(39))
        rule.onNodeWithText(MagnetDraftCheck.BAD_HASH).performScrollTo()
        assertFitsAndTargets("mistyped hash")
        rule.onRoot().captureRoboImage("build/chunks-visual/phone-add-320-200-problem.png")

        field.performTextReplacement("magnet:?xt=urn:btih:" + "a".repeat(40) +
            "&dn=" + "A%20very%20long%20display%20name%20that%20must%20wrap%20on%20a%20narrow%20phone".repeat(2))
        rule.onNodeWithText("Magnet for:", substring = true).performScrollTo()
        assertFitsAndTargets("valid magnet")
        rule.onRoot().captureRoboImage("build/chunks-visual/phone-add-320-200-valid.png")
    }
}
