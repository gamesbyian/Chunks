package io.github.gamesbyian.chunks

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.captureToImage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose screenshot + semantic existence checks; no external server. */
@RunWith(AndroidJUnit4::class)
class VerifiedAddScreenAtlasTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val remote by lazy {
        RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    }
    private val validMagnet = "magnet:?xt=urn:btih:" + "ab".repeat(20) + "&dn=Public%20test"

    private fun save(name: String) {
        val image = rule.onNode(isRoot()).captureToImage().asAndroidBitmap()
        val folder = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "chunks-atlas"
        )
        assertTrue("Could not create screenshot folder", folder.isDirectory || folder.mkdirs())
        File(folder, "$name.png").outputStream().use {
            assertTrue("Native PNG screenshot must be written", image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun reviewedStoppedAddShowsExactHashAndDestination() {
        var magnet by mutableStateOf(validMagnet)
        var destination by mutableStateOf("/home/dev/chunks-public-test")
        rule.setContent {
            MaterialTheme {
                VerifiedAddScreen(remote, magnet, destination,
                    onMagnet = { magnet = it },
                    onDestination = { destination = it },
                    onBack = {}, onComplete = {})
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Review stopped Add"))
        rule.onNodeWithText("Review stopped Add").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Confirm: add stopped"))
        rule.onNodeWithText("Confirm: add stopped").assertExists()
        rule.onNodeWithText("Infohash: " + "ab".repeat(20)).assertExists()
        save("add-confirmation-real-compose")
    }

    @Test fun traversalDestinationCannotReachConfirmation() {
        var magnet by mutableStateOf(validMagnet)
        var destination by mutableStateOf("/home/dev/../outside")
        rule.setContent {
            MaterialTheme {
                VerifiedAddScreen(remote, magnet, destination,
                    onMagnet = { magnet = it },
                    onDestination = { destination = it },
                    onBack = {}, onComplete = {})
            }
        }
        rule.onNodeWithText("Review stopped Add").assertDoesNotExist()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(
            "Enter a valid v1 magnet or select a .torrent file, then provide an absolute server directory. Chunks cannot pre-verify server permissions."
        ))
        save("add-invalid-path-real-compose")
    }
}
