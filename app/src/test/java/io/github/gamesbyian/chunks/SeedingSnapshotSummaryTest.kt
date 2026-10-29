package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.seeding.RateLimit
import io.github.gamesbyian.chunks.seeding.SeedingController
import io.github.gamesbyian.chunks.seeding.SeedingPolicy
import io.github.gamesbyian.chunks.seeding.SeedingResolution
import java.math.BigDecimal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedingSnapshotSummaryTest {
    @get:Rule val rule = createComposeRule()
    private val policy = SeedingPolicy(ratioGoal = BigDecimal("1.00"), uploadLimit = RateLimit.parse("24 KiB/s").getOrThrow())

    private fun show(assignment: SeedingController.Assignment) = rule.setContent {
        ChunksTheme { Surface(color = ChunksColors.Background) { Column { SeedingSnapshotSummary("h", assignment) } } }
    }

    @Test fun ruleSnapshotExplainsWhyOnRequestAndNotesARulePause() {
        show(SeedingController.Assignment(policy, SeedingResolution.Source.RULE, "r",
            reasons = listOf("Tracker host tracker.example matches"), stoppedByRule = "ratio 1.00 reached"))
        rule.onNodeWithText("Seeding: rule · " + policy.describe()).assertExists()
        rule.onNodeWithText("ratio 1.00 reached", substring = true).assertExists()
        rule.onNodeWithText("• Tracker host tracker.example matches").assertDoesNotExist()
        rule.onNodeWithText("Why this rule?").performClick()
        rule.onNodeWithText("• Tracker host tracker.example matches").assertExists()
        rule.onNodeWithText("Later rule edits do not change it.", substring = true).assertExists()
    }

    @Test fun transfersFromBeforeTheControllerSayTheyAreUnmanaged() {
        show(SeedingController.Assignment(null, null, null))
        rule.onNodeWithText("not managed by rules", substring = true).assertExists()
        rule.onNodeWithText("Why this rule?").assertDoesNotExist()
    }
}
