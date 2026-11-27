package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.gamesbyian.chunks.model.LocalAddStoragePlanner
import io.github.gamesbyian.chunks.model.LocalAddStoragePreflight
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalAddStorageObservationComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sameHashReplacementAndChangedPlaceNeverRenderPreviousReading() {
        val preview = TorrentMetainfoPreview("test", "a".repeat(40), 1, 100, 16_384)
        val review = mutableStateOf(PendingLocalAdd(preview, byteArrayOf(1)))
        val root = mutableStateOf(File("/first"))
        val target = mutableStateOf(File("/first"))
        val calls = AtomicInteger()
        val secondRelease = CountDownLatch(1)
        val thirdRelease = CountDownLatch(1)
        val reader: (Context, File, File, Long) -> LocalAddStoragePreflight = { _, _, _, bytes ->
            val count = calls.incrementAndGet()
            if (count == 2) check(secondRelease.await(10, TimeUnit.SECONDS))
            if (count == 3) check(thirdRelease.await(10, TimeUnit.SECONDS))
            LocalAddStoragePlanner.assess(bytes, null, null, count.toLong() * 1_000)
        }
        fun waitText(value: String) {
            rule.waitUntil(5_000) { rule.onAllNodes(hasText(value)).fetchSemanticsNodes().isNotEmpty() }
        }
        try {
            rule.setContent {
                val result = rememberLocalAddStoragePreflight(review.value, root.value, target.value, 0, 0, reader)
                Text(result?.availableVolumeBytes?.let { "free=" + it } ?: "checking")
            }
            waitText("free=1000")
            rule.runOnIdle { review.value = PendingLocalAdd(preview, byteArrayOf(2)) }
            waitText("checking")
            rule.onNodeWithText("free=1000").assertDoesNotExist()
            secondRelease.countDown()
            waitText("free=2000")
            rule.runOnIdle {
                root.value = File("/second")
                target.value = File("/second/child")
            }
            waitText("checking")
            rule.onNodeWithText("free=2000").assertDoesNotExist()
            thirdRelease.countDown()
            waitText("free=3000")
            assertEquals(3, calls.get())
        } finally {
            secondRelease.countDown()
            thirdRelease.countDown()
        }
    }
}
