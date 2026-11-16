package io.github.gamesbyian.chunks

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1786 (owner Pixel): Open With → Chunks on a `.torrent` was visible briefly, then Android
 * returned to the source app and the Add handoff was gone. Drives the *real* MainActivity
 * through Android's cold `ACTION_VIEW` launch, a warm `onNewIntent()` and an Activity
 * recreation, and pins the one-task launch mode that keeps the review in Chunks' own task.
 * Robolectric tier only: no real provider grant, ActivityTaskManager or Pixel evidence.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class OpenWithActivityIntakeTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun b(text: String) = "${text.toByteArray().size}:$text"
    private fun torrent(name: String) = ("d8:announce" + b("https://tracker.example/announce") +
        "4:infod6:lengthi5e4:name" + b(name) + "12:piece lengthi16384e6:pieces20:01234567890123456789ee")
        .toByteArray()

    private fun document(path: String, name: String): Uri {
        val uri = Uri.parse("content://chunks.test.documents/$path")
        shadowOf(context.contentResolver).registerInputStream(uri, torrent(name).inputStream())
        return uri
    }

    private fun view(uri: Uri, type: String) = Intent(Intent.ACTION_VIEW)
        .setClassName(context, MainActivity::class.java.name)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** What Android does for a singleTask/singleTop re-launch (protected on Activity). */
    private fun deliverNewIntent(activity: MainActivity, intent: Intent) {
        android.app.Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)
    }

    private fun waitForText(text: String) = rule.waitUntil(30_000) {
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun mainActivityIsTheSingleChunksTaskForExternalHandoffs() {
        // singleTop let a caller's task host a second, invisible-to-launcher instance.
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }

    @Test fun coldOpenWithShowsTheReviewAndStaysOpen() {
        ActivityScenario.launch<MainActivity>(view(document("cold-1.torrent", "cold-open-with.mkv"),
            "application/x-bittorrent")).use { scenario ->
            waitForText("cold-open-with.mkv")
            scenario.onActivity { assertFalse("Open With must not finish Chunks", it.isFinishing) }
        }
    }

    @Test fun genericBinaryNamedTorrentAndWarmSecondOpenWithReachTheSameReview() {
        ActivityScenario.launch<MainActivity>(view(document("warm-a.torrent", "warm-first.mkv"),
            "application/octet-stream")).use { scenario ->
            waitForText("warm-first.mkv")
            // A second Open With while the unsubmitted first draft is open is held for an
            // explicit choice, never silently swapped or submitted.
            val second = view(document("warm-b.torrent", "warm-second.mkv"), "application/x-bittorrent")
            scenario.onActivity { deliverNewIntent(it, second) }
            waitForText("Use new source")
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test fun recreationKeepsTheOpenedTorrentReview() {
        ActivityScenario.launch<MainActivity>(view(document("recreate.torrent", "recreated-open-with.mkv"),
            "application/x-bittorrent")).use { scenario ->
            waitForText("recreated-open-with.mkv")
            scenario.recreate()
            waitForText("recreated-open-with.mkv")
        }
    }
}
