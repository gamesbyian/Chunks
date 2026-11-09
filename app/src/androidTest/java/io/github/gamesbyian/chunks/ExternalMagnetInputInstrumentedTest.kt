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

    @Test fun mixedCaseMagnetSchemeReachesAddWithoutChangingQueryOrTrackerText() {
        val mixed = "MAGNET:?XT=URN:BTIH:" + "A".repeat(40) +
            "&Dn=Mixed%20case&tr=https%3A%2F%2Fexample.org%2Fannounce%3Ftoken%3Dfixture"
        val expected = "magnet:?" + mixed.substring("magnet:?".length)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, SpannableString(" $mixed "))
        }
        assertEquals(expected, ExternalMagnetInput.fromIntent(send))
        assertEquals(expected, ExternalMagnetInput.fromIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(mixed))))
        assertEquals(ExternalAddSource.Magnet(expected),
            ExternalAddSource.fromIntent(send))
        // The handoff normalizes only the scheme: the existing Add validator
        // still checks the real hash and other fields before user confirmation.
        assertEquals("magnet:?", expected.take("magnet:?".length))
        assertEquals(expected.substring("magnet:?".length),
            mixed.substring("magnet:?".length))

        // A malformed magnet still reaches the visible Add review/error state
        // instead of being silently dropped by intake.
        val incomplete = "MAGNET:?xt=urn:btih:abc"
        assertEquals("magnet:?xt=urn:btih:abc",
            ExternalMagnetInput.fromIntent(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, incomplete)
            }))
    }

    @Test fun malformedExternalTextExtrasAreRefusedWithoutCrashingOrChangingSource() {
        // Android shares originate in untrusted external apps. A Parcelable,
        // number or other wrong type in EXTRA_TEXT is not a text magnet. It
        // must never crash MainActivity's normal source admission.
        val wrongParcelable = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, Uri.parse(magnet))
        }
        assertNull(ExternalMagnetInput.fromIntent(wrongParcelable))
        assertNull(ExternalAddSource.fromIntent(wrongParcelable))
        val wrongNumber = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, 42)
        }
        assertNull(ExternalMagnetInput.fromIntent(wrongNumber))
        assertNull(ExternalAddSource.fromIntent(wrongNumber))
        // The exact same input as a genuine CharSequence still reaches the
        // regular visible review, never triggering a mutation in this test.
        assertEquals(magnet, ExternalMagnetInput.fromIntent(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, SpannableString(magnet))
            }))
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
