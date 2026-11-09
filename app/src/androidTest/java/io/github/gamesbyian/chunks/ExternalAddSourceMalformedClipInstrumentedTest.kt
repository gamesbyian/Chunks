package io.github.gamesbyian.chunks

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Intent subclasses exercising untrusted ClipData getters. No URI reads or Add. */
@RunWith(AndroidJUnit4::class)
class ExternalAddSourceMalformedClipInstrumentedTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40) +
        "&tr=https%3A%2F%2Ftracker.example%2Fprivate-passkey"

    @Test fun brokenClipDataCannotCrashOrFallBackToUnrelatedShareText() {
        val poisoned = object : Intent(Intent.ACTION_SEND) {
            override fun getClipData(): ClipData? {
                throw BadParcelableException("simulated invalid ClipData parcel")
            }
        }.apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, magnet)
        }
        assertNull(ExternalAddSource.fromIntent(poisoned))
    }

    @Test fun validUriListStillPreservesEntirePrivateMagnetWithoutMutation() {
        val clip = ClipData.newRawUri("magnet", Uri.parse(magnet))
        val valid = Intent(Intent.ACTION_SEND).apply {
            type = "text/uri-list"
            clipData = clip
        }
        assertEquals(ExternalAddSource.Magnet(magnet), ExternalAddSource.fromIntent(valid))
    }
}
