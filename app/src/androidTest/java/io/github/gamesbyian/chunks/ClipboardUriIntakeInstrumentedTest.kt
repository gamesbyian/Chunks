package io.github.gamesbyian.chunks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed Android clipboard Item.uri and multiple-item contracts.
 * All evidence is foreground, source-only: no network, provider read or Add.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardUriIntakeInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun clearClipboard() {
        rule.runOnUiThread {
            val manager = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.clearPrimaryClip()
        }
    }

    @Test fun singleCopiedUriPrefillsButAmbiguousOrUnrelatedUrisNeverDo() {
        // This test owns only the Android clipboard contract, not Chunks'
        // navigation/background initialization. A minimal foreground Activity
        // avoids unrelated MainActivity focus/launcher races; never proceed
        // without a genuinely focused Android window.
        rule.setContent { Box(Modifier.fillMaxSize()) }
        // API35 CI has repeatedly shown an unrelated Pixel Launcher ANR
        // stealing focus before this clipboard test starts. Dismiss ONLY its
        // exact "Wait" control, at most once; never a Chunks crash, privacy
        // prompt, or arbitrary overlay. The real foreground-focus assertion
        // remains mandatory before any clipboard read or Add decision.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var dismissedLauncherAnr = false
        var nextProbeAt = 0L
        try {
            rule.waitUntil(30_000) {
                if (rule.activity.hasWindowFocus()) true else {
                    val now = SystemClock.elapsedRealtime()
                    if (!dismissedLauncherAnr && now >= nextProbeAt) {
                        nextProbeAt = now + 500L
                        if (device.findObject(By.text("Pixel Launcher isn't responding")) != null) {
                            val wait = device.findObject(By.text("Wait"))
                            if (wait != null)
                                dismissedLauncherAnr = runCatching { wait.click(); true }.getOrDefault(false)
                        }
                    }
                    false
                }
            }
        } catch (failure: Throwable) {
            // Keep the foreground assertion strict. A bounded boolean-only
            // failure fingerprint separates a real target focus failure from
            // the external emulator Launcher ANR without publishing UI text,
            // clipboard contents, private torrent URIs or arbitrary packages.
            val targetFront = runCatching {
                device.currentPackageName == rule.activity.packageName
            }.getOrNull()
            val launcherAnr = runCatching {
                device.findObject(By.text("Pixel Launcher isn't responding")) != null
            }.getOrNull()
            val targetAnr = runCatching {
                device.findObject(By.text("Chunks isn't responding")) != null
            }.getOrNull()
            Log.e("ChunksClipboardFocus",
                "required-focus-failed targetFocused=${rule.activity.hasWindowFocus()} " +
                    "targetPackageForeground=$targetFront pixelLauncherAnr=$launcherAnr " +
                    "targetAnr=$targetAnr launcherAnrDismissed=$dismissedLauncherAnr")
            throw failure
        }
        val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=ClipboardUri"
        val torrentLink = "https://example.org/releases/test.torrent"
        rule.runOnUiThread {
            val manager = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            fun evaluate(clip: ClipData): Pair<String?, String?> {
                manager.setPrimaryClip(clip)
                val raw = ClipboardMagnetPrefill.readText(rule.activity)
                return raw to ClipboardMagnetPrefill.decide(
                    enabled = true, currentDraft = "", otherSourcePresent = false, clipText = raw)
            }

            // A real URI-list ClipData.Item has no CharSequence text.
            assertEquals(magnet to magnet,
                evaluate(ClipData.newRawUri("magnet", Uri.parse(magnet))))
            assertEquals(torrentLink to torrentLink,
                evaluate(ClipData.newRawUri("torrent", Uri.parse(torrentLink))))

            // Text clips continue to work, including an HTTPS torrent link.
            assertEquals(magnet to magnet, evaluate(ClipData.newPlainText("magnet", magnet)))
            assertEquals(torrentLink to torrentLink,
                evaluate(ClipData.newPlainText("torrent", torrentLink)))

            // Content providers are not opened, coerced or mistaken for
            // .torrent sources just because their URI ends with .torrent.
            assertEquals(null to null, evaluate(ClipData.newRawUri(
                "provider", Uri.parse("content://documents.example/share/file.torrent"))))
            assertEquals(null to null, evaluate(ClipData.newRawUri(
                "insecure", Uri.parse("http://example.org/file.torrent"))))

            // Never silently select a first source from ambiguous clipboard
            // material. The person may copy one source explicitly instead.
            val multipleTexts = ClipData.newPlainText("first", magnet).apply {
                addItem(ClipData.Item(torrentLink))
            }
            assertEquals(null to null, evaluate(multipleTexts))
            val multipleUris = ClipData.newRawUri("first", Uri.parse(magnet)).apply {
                addItem(ClipData.Item(Uri.parse(torrentLink)))
            }
            assertEquals(null to null, evaluate(multipleUris))

            // A prior typed draft or external Share always overrides the
            // eligible URI-list clipboard suggestion.
            val raw = evaluate(ClipData.newRawUri("magnet", Uri.parse(magnet))).first
            assertNull(ClipboardMagnetPrefill.decide(true, "existing", false, raw))
            assertNull(ClipboardMagnetPrefill.decide(true, "", true, raw))
        }
    }
}
