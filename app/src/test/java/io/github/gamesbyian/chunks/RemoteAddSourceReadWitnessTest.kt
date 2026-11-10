package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frozen source identity for async remote URL and document reads, no RPC. */
class RemoteAddSourceReadWitnessTest {
    private val url = "https://example.org/serve?token=privateFixture"

    @Test fun unchangedHttpsRequestMaySupplyReviewedBytes() {
        val witness = RemoteAddSourceReadWitness(url, null, false)
        assertTrue(witness.stillSelected(url, null, false))
    }

    @Test fun editingSignedUrlOrTypingMagnetRevokesOldFetch() {
        val witness = RemoteAddSourceReadWitness(url, null, false)
        assertFalse(witness.stillSelected(
            "https://example.org/serve?token=differentFixture", null, false))
        assertFalse(witness.stillSelected("magnet:?xt=urn:btih:" + "a".repeat(40), null, false))
    }

    @Test fun selectingFileDuringHttpsDownloadRevokesOldFetchEvenWhenTextStays() {
        val witness = RemoteAddSourceReadWitness(url, null, false)
        assertFalse("A newer picker file must win over the older network completion",
            witness.stillSelected(url, byteArrayOf(1, 2, 3), true))
        assertFalse("A changed source mode also revokes the old fetch",
            witness.stillSelected(url, null, true))
    }

    @Test fun originalReviewedFileMayBeReplacedIfUnchanged() {
        val prior = byteArrayOf(1, 2, 3)
        val witness = RemoteAddSourceReadWitness("", prior, true)
        assertTrue(witness.stillSelected("", prior, true))
        assertFalse("A different file with equal bytes is still a newer selection",
            witness.stillSelected("", prior.copyOf(), true))
    }

    @Test fun typedSourceDuringSlowAndroidPickerReadWins() {
        val prior = byteArrayOf(1)
        val witness = RemoteAddSourceReadWitness("", prior, true)
        assertFalse(witness.stillSelected("magnet:?xt=urn:btih:" + "b".repeat(40), prior, false))
        assertFalse(witness.stillSelected("", prior, false))
    }
}
