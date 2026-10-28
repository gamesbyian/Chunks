package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Android Parcelable contract, not a mock text-only magnet decoder. */
@RunWith(AndroidJUnit4::class)
class ExternalMagnetInputInstrumentedTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40) +
        "&dn=Public%20test"

    @Test fun styledAndPlainSendTextPopulateExactlyTheSameMagnetDraft() {
        val styled = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, SpannableString("  $magnet  "))
        }
        assertEquals(magnet, ExternalMagnetInput.fromIntent(styled))
        assertEquals(magnet, ExternalMagnetInput.fromIntent(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, magnet)
            }))
        assertEquals(magnet, ExternalMagnetInput.fromIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(magnet))))
    }

    @Test fun unrelatedOrOversizedSharesNeverBecomeMagnetDrafts() {
        assertNull(ExternalMagnetInput.fromIntent(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://example.test")))
        assertNull(ExternalMagnetInput.fromIntent(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT,
                SpannableString("magnet:?" + "x".repeat(8192)))))
        assertNull(ExternalMagnetInput.fromIntent(
            Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_TEXT, magnet)))
        assertNull(ExternalMagnetInput.fromIntent(null))
    }
}
