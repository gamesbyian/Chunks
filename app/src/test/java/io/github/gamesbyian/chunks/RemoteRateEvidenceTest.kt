package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.*
import org.junit.Test

class RemoteRateEvidenceTest {
    private fun row(
        hashDigit: Char,
        down: Long? = null,
        up: Long? = null,
        ratio: Long? = null
    ) = RemoteTransfer(hashDigit.toString().repeat(40), "Item $hashDigit",
        1000, 0, true, "/server", down, up, ratio)

    @Test fun downloadKnownButUploadAndRatioUnknownAreNotZero() {
        val transfer = row('a', down = 123L, up = null, ratio = null)
        assertEquals(listOf("Download: 123 B/s", "Upload: not reported", "Ratio: not reported"),
            RemoteRateEvidence.detailLines(transfer))
        val upload = RemoteRateEvidence.upload(listOf(transfer))
        assertEquals("Not reported", upload.tileLabel())
        assertEquals("Upload rates: 1 transfer(s) not reported; tile is a known-only subtotal.",
            upload.caveat("Upload"))
    }

    @Test fun genuineZeroInEveryRateAndRatioRemainsReportedZero() {
        val transfer = row('a', 0L, 0L, 0L)
        assertEquals(listOf("Download: 0 B/s", "Upload: 0 B/s", "Ratio: 0.0"),
            RemoteRateEvidence.detailLines(transfer))
        for (total in listOf(RemoteRateEvidence.download(listOf(transfer)),
            RemoteRateEvidence.upload(listOf(transfer)))) {
            assertEquals("0 B/s", total.tileLabel())
            assertEquals(1, total.reportedRows)
            assertEquals(0, total.unknownRows)
            assertNull(total.caveat("Rate"))
            assertFalse(total.beyondRange)
        }
    }

    @Test fun mixedKnownUnknownIsOnlyAPartialSubtotal() {
        val transfers = listOf(row('a', down = 0L, up = 99L), row('b'),
            row('c', down = 120L, up = null))
        val down = RemoteRateEvidence.download(transfers)
        assertEquals(2, down.reportedRows)
        assertEquals(1, down.unknownRows)
        assertEquals(120L, down.knownBytesPerSecond)
        assertEquals("Known 120 B/s", down.tileLabel())
        assertTrue(down.caveat("Download")!!.contains("1 transfer(s) not reported"))
        val up = RemoteRateEvidence.upload(transfers)
        assertEquals("Known 99 B/s", up.tileLabel())
        assertEquals(2, up.unknownRows)
    }

    @Test fun entirelyUnknownViewIsNotReportedRatherThanZero() {
        val totals = RemoteRateEvidence.download(listOf(row('a'), row('b')))
        assertEquals(0, totals.reportedRows)
        assertEquals(2, totals.unknownRows)
        assertEquals(0, totals.knownBytesPerSecond)
        assertEquals("Not reported", totals.tileLabel())
    }

    @Test fun emptyKnownViewReallyHasZeroRateButNoUnknownJobs() {
        val empty = RemoteRateEvidence.download(emptyList())
        assertEquals("Not reported", empty.tileLabel())
        assertNull(empty.caveat("Download"))
        assertEquals(0, empty.reportedRows)
        assertEquals(0, empty.unknownRows)
    }

    @Test fun longOverflowReportsAboveRangeWithoutWrapping() {
        val rows = listOf(row('a', down = Long.MAX_VALUE, up = Long.MAX_VALUE),
            row('b', down = 1L, up = 1L), row('c', down = null, up = null))
        for (total in listOf(RemoteRateEvidence.download(rows), RemoteRateEvidence.upload(rows))) {
            assertEquals(Long.MAX_VALUE, total.knownBytesPerSecond)
            assertTrue(total.beyondRange)
            assertEquals("Above range", total.tileLabel())
            assertTrue(total.caveat("Rates")!!.contains("sum exceeds the reporting range"))
            assertEquals(1, total.unknownRows)
        }
        val exact = RemoteRateEvidence.download(listOf(row('a', down = Long.MAX_VALUE)))
        assertFalse(exact.beyondRange)
        assertEquals(0, exact.unknownRows)
    }

    @Test fun invalidNegativeTelemetryDoesNotMasqueradeAsValidRate() {
        val row = row('a', down = -1, up = -55, ratio = -1)
        assertEquals(listOf("Download: not reported", "Upload: not reported",
            "Ratio: not reported"), RemoteRateEvidence.detailLines(row))
        assertEquals(1, RemoteRateEvidence.download(listOf(row)).unknownRows)
    }
}
