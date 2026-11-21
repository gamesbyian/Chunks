package io.github.gamesbyian.chunks

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UniversalAddRouteTest {
    @get:Rule val rule = createComposeRule()

    @Test fun optionsCanHandItsLocalSnapshotReviewToAdd() {
        val pane = mutableStateOf(LocalPane.OPTIONS)
        rule.setContent {
            ChunksTheme {
                UniversalAddRoute(
                    target = UniversalAddRouteTarget.Local, localPane = pane.value,
                    local = {
                        val reviewedSnapshot = remember { mutableStateOf(false) }
                        if (it == LocalPane.OPTIONS) {
                            Button(onClick = {
                                reviewedSnapshot.value = true
                                pane.value = LocalPane.ADD
                            }) { Text("Review stopped snapshot restore") }
                        } else if (reviewedSnapshot.value) {
                            Text("Original snapshot review is ready")
                        }
                    },
                    remote = null, protocol = null, destinationPicker = {}, onBack = {}
                )
            }
        }
        rule.onNodeWithText("Review stopped snapshot restore").performClick()
        rule.onNodeWithText("Original snapshot review is ready").assertExists()
    }

    @Test fun unavailableExactClientNeverFallsThroughToLocalAdd() {
        rule.setContent {
            ChunksTheme {
                UniversalAddRoute(
                    target = UniversalAddRouteTarget.RTorrent("previous-account"),
                    localPane = LocalPane.ADD, local = { Text("Local submit") },
                    remote = null, protocol = null,
                    destinationPicker = { Text("Choose a destination") }, onBack = {}
                )
            }
        }
        rule.onNodeWithText("Choose a destination").assertExists()
        rule.onNodeWithText("Local submit").assertDoesNotExist()
    }
}
