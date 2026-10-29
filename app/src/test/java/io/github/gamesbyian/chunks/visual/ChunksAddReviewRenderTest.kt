package io.github.gamesbyian.chunks.visual

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.VerifiedAddScreen
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** JVM render of the server Add review; nothing is submitted (unreachable dummy endpoint). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi")
class ChunksAddReviewRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun addReview() {
        var magnet by mutableStateOf("magnet:?xt=urn:btih:" + "ab".repeat(20) + "&dn=Public%20test")
        var destination by mutableStateOf("/home/dev/chunks-public-test")
        rule.setContent {
            ChunksTheme { Surface {
                VerifiedAddScreen(RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", "")),
                    magnet, destination, onMagnet = { magnet = it }, onDestination = { destination = it },
                    onBack = {}, onComplete = {}, onMutationStarted = { true }, onMutationSettled = {},
                    recentPlaces = listOf("/home/dev/TV", "/home/dev/Movies"))
            } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/add-form.png")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Review Add"))
        rule.onNodeWithText("Review Add").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Confirm: add then start"))
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/add-review.png")
    }
}
