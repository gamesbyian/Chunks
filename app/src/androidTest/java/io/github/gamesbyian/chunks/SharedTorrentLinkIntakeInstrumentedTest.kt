package io.github.gamesbyian.chunks

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed source admission and the existing Add destination chooser only.
 * No URL request, provider read, engine call, account selection or mutation.
 */
@RunWith(AndroidJUnit4::class)
class SharedTorrentLinkIntakeInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val url = "https://downloads.example.org/serve?file=42&token=privateFixture"

    @Test fun sendHttpsLinkKeepsSignedQueryAndRequiresExplicitAddReview() {
        org.junit.Assert.assertTrue("Local Add must consume a shared HTTPS source",
            reviewableLocalExternalText(url))
        org.junit.Assert.assertTrue("Existing magnet handoff remains supported",
            reviewableLocalExternalText("magnet:?xt=urn:btih:" + "a".repeat(40)))
        org.junit.Assert.assertFalse("HTTP text cannot become an implicit fetch",
            reviewableLocalExternalText("http://downloads.example.org/test.torrent"))
        assertEquals(ExternalAddSource.TorrentLink(url),
            ExternalAddSource.fromIntent(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }))
        val sharedUri = Intent(Intent.ACTION_SEND).apply {
            type = "text/uri-list"
            clipData = ClipData.newRawUri("torrent link", Uri.parse(url))
            putExtra(Intent.EXTRA_TEXT, url)
        }
        assertEquals(ExternalAddSource.TorrentLink(url), ExternalAddSource.fromIntent(sharedUri))
        // Uppercase URI scheme is semantically identical. Never normalize a
        // signed path/query, which may include private tracker authorization.
        assertEquals(ExternalAddSource.TorrentLink(url),
            ExternalAddSource.fromIntent(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "HTTPS://" + url.substring("https://".length))
            }))
        assertNull(ExternalAddSource.fromIntent(sharedUri.apply {
            putExtra(Intent.EXTRA_TEXT, url.replace("privateFixture", "differentFixture"))
        }))
        assertNull(ExternalAddSource.fromIntent(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, 42)
        }))
        assertNull(ExternalAddSource.fromIntent(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "http://downloads.example.org/serve?file=42")
        }))
        // Chunks does not hijack ordinary browser HTTPS VIEW navigations.
        assertNull(ExternalAddSource.fromIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url))))
    }

    @Test fun destinationChooserDescribesALinkWithoutClaimingToAddIt() {
        rule.setContent {
            MaterialTheme {
                CombinedAddActions(serverLabel = "Authenticated server",
                    onOpenLocal = {}, onAddToServer = {},
                    pendingSharedMagnet = true, pendingSharedLink = true)
            }
        }
        rule.onNodeWithText("Choose a destination for the shared torrent link").assertExists()
        rule.onNodeWithText("A shared torrent link is ready. Choose where to review it. Nothing is added until you confirm.")
            .assertExists()
        rule.onNodeWithText("Choose a destination for the shared magnet").assertDoesNotExist()
    }
}
