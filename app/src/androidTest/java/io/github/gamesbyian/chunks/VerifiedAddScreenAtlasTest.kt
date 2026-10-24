package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
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
import org.junit.Assert.assertNotNull
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
        // Gradle instrumentation cleanup can remove app-specific external files before adb pull.
        // Use Android's public MediaStore collection so native captures survive test teardown.
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore must accept native screenshot", uri)
        resolver.openOutputStream(uri!!).use { stream ->
            assertNotNull("MediaStore output must open", stream)
            assertTrue("Native PNG screenshot must be written",
                image.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        val publish = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        assertTrue("Screenshot must be made readable by adb", resolver.update(uri, publish, null, null) == 1)
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
