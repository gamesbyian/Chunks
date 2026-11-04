package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed production Combined screen, synthetic read-only inventory; no Add/peer acceptance claim. */
@RunWith(AndroidJUnit4::class)
class CombinedAddedBrowsingInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun exactAddedNoticeClearsOnlyTheViewAtLargeType() {
        CombinedViewPreferences(rule.activity).save(UnifiedTransferQuery())
        val scope = "https://box.example/rutorrent2/RPC2\u0000reader"
        val key = UnifiedTransferKey.of(EnvironmentSnapshots.serverIdOfAliasScope(scope)!!, "a".repeat(40))
        val search = mutableStateOf("unrelated search")
        val commands = AtomicInteger()
        val row = RemoteTransfer(key.infoHash, "Visible after Show all", 100, 0, false, "/downloads", 0, 0)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme { Box(Modifier.requiredSize(320.dp, 700.dp)) { Surface {
                    CombinedTransfersScreen(
                        server = CombinedServerSource(scope, listOf(row), false, true,
                            System.currentTimeMillis(), lifecycle = CombinedServerLifecycle(
                                begin = { commands.incrementAndGet(); false }, settled = {},
                                setStarted = { _, _ -> error("Browsing must never issue a command") })),
                        savedProfiles = emptyList(), onOpenPhone = {}, onOpenServer = {}, onBack = {},
                        searchState = search, recentlyAddedTransfer = key)
                } } }
            }
        }
        val notice = "Your new transfer on ${serverEnvironmentLabel(scope)} is hidden by the current search, filter or environment view."
        rule.waitUntil(10_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(notice))
            }.isSuccess
        }
        rule.onNodeWithText(notice).assertExists()
        save("combined-hidden-added-200pct")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Show all transfers"))
        rule.onNodeWithText("Show all transfers").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(row.name))
        rule.onNodeWithText(row.name).assertExists()
        rule.onNodeWithText(notice).assertDoesNotExist()
        assertEquals("", search.value)
        assertEquals(0, commands.get())
    }

    @Test fun failedSavedServerCanRecoverWithoutReadingAnotherServer() {
        CombinedViewPreferences(rule.activity).save(UnifiedTransferQuery())
        val first = AtomicInteger()
        val other = AtomicInteger()
        val row = RemoteTransfer("b".repeat(40), "Only server two was refreshed", 100, 50, false,
            "/downloads", 0, 0)
        fun profile(number: Int, load: suspend () -> List<RemoteTransfer>) = CombinedSavedProfile(
            EnvironmentSnapshots.serverId("https://box.example/rutorrent$number/RPC2", "reader").value,
            "Server $number", "https://box.example/rutorrent$number/RPC2\u0000reader", listOnly = load)
        val profiles = listOf(profile(2) {
            if (first.incrementAndGet() == 1) throw java.net.SocketTimeoutException()
            listOf(row)
        }, profile(3) { other.incrementAndGet(); emptyList() })
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme { Box(Modifier.requiredSize(320.dp, 700.dp)) { Surface {
                    CombinedTransfersScreen(null, profiles, onOpenPhone = {}, onOpenServer = null, onBack = {})
                } } }
            }
        }
        fun reveal(text: String) {
            rule.waitUntil(10_000) {
                runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text)) }.isSuccess
            }
        }
        reveal("Environment status · 3 sources")
        rule.onNodeWithText("Environment status · 3 sources").performClick()
        reveal("Load Server · Server 2 transfers")
        rule.onNodeWithText("Load Server · Server 2 transfers").assertHeightIsAtLeast(48.dp).performClick()
        reveal("Server · Server 2: Could not load (did not answer in time) · 0 transfers")
        reveal("Load Server · Server 2 transfers")
        rule.onNodeWithText("Load Server · Server 2 transfers").assertHeightIsAtLeast(48.dp).performClick()
        reveal("Refresh Server · Server 2 transfers")
        reveal(row.name)
        rule.onNodeWithText(row.name).assertExists()
        save("combined-saved-recovery-200pct")
        assertEquals(2, first.get())
        assertEquals(0, other.get())
    }

    private fun save(name: String) {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        resolver.openOutputStream(uri).use { output ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(output)))
        }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
    }

}
