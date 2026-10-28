package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TransferHealthReportTest {
    private val now = Instant.parse("2026-10-27T22:30:00Z")
    private val secretName = "Private tracker passkey=LEAK-ME / Secret Movie"
    private val privateHash = "a".repeat(40)
    private val remote = RemoteTransfer(
        hash = privateHash, name = secretName, sizeBytes = 2_048,
        completedBytes = 0, started = true,
        directory = "/home/owner/secret/sensitive",
        downBytesPerSecond = 0, upBytesPerSecond = null
    )

    private fun local(
        title: String = secretName,
        error: String? = "fatal private tracker URL with passkey LEAK-ME",
        peers: Int = 0
    ) = LocalTransferStatus(
        infoHash = privateHash, name = title, state = LocalTransferState.DOWNLOADING,
        stopped = false, progressPpm = 100, wantedBytes = 100_000,
        doneBytes = 20, payloadDownloadedBytes = 512,
        payloadUploadedBytes = 0, peers = peers,
        savePath = "/data/private/path", error = error,
        uploadOnly = true, metadataKnown = true
    )

    @Test fun remoteIsRedactedAndSeparatesIntentRateAndUnknownPeers() {
        val report = TransferHealthReport.remote(listOf(remote),
            serverView = "main", stale = false, capturedAt = now)
        val text = String(report.bytes)
        assertEquals(1, report.reported)
        assertFalse(report.includesNames)
        for (secret in listOf(secretName, privateHash, "LEAK-ME",
            "/home/owner", "passkey", "secret/sensitive")) {
            assertFalse("Leaked: $secret", text.contains(secret))
        }
        assertTrue(text.contains("\"startedFlag\":true"))
        assertTrue(text.contains("\"downloadRateState\":\"zero\""))
        assertTrue(text.contains("\"uploadRateState\":\"unreported\""))
        assertTrue(text.contains("\"completedBytes\":0"))
        assertTrue(text.contains("\"capturedAtUtc\":\"2026-10-27T22:30:00Z\""))
        assertTrue(text.contains("\"hashesPathsTrackersOmitted\":true"))
        assertFalse(text.contains("\"title\":"))
        assertFalse(text.contains("peersObserved"))
    }

    @Test fun localReportsMeasuredVerifiedBytesAndErrorFlagNotErrorDetails() {
        val report = TransferHealthReport.local(listOf(local()),
            stale = true, capturedAt = now)
        val text = String(report.bytes)
        assertTrue(text.contains("\"source\":\"local\""))
        assertTrue(text.contains("\"fresh\":false"))
        assertTrue(text.contains("\"peersObserved\":0"))
        assertTrue(text.contains("\"verifiedCompletedBytes\":20"))
        assertTrue(text.contains("\"allTimePayloadDownloadedBytes\":512"))
        assertTrue(text.contains("\"uploadOnly\":true"))
        assertTrue(text.contains("\"errorPresent\":true"))
        for (secret in listOf(secretName, privateHash, "LEAK-ME", "/data/private"))
            assertFalse(text.contains(secret))
    }

    @Test fun optedInTitlesAreBoundedAndEscapedButHashesAndPathsRemainHidden() {
        val name = "Chapter \"one\"\\two\n" + "Z".repeat(300)
        val report = TransferHealthReport.local(listOf(local(title = name)),
            stale = false, includeTitles = true, capturedAt = now)
        val text = String(report.bytes)
        assertTrue(report.includesNames)
        assertTrue(text.contains("\"torrentTitlesIncluded\":true"))
        assertTrue(text.contains("Chapter \\\"one\\\"\\\\two\\n"))
        assertFalse(text.contains(privateHash))
        assertFalse(text.contains("/data/private/path"))
        assertFalse(text.contains("Z".repeat(145)))
    }

    @Test fun hugeInventoryIsBoundedAndReportsOmissionsWithoutTruncatingSilently() {
        val data = List(550) { n -> remote.copy(name = "row-$n") }
        val r = TransferHealthReport.remote(data, "strange view \n /owners",
            stale = true, capturedAt = now)
        val text = String(r.bytes)
        assertEquals(550, r.observed)
        assertEquals(512, r.reported)
        assertEquals(38, r.omitted)
        assertTrue(text.contains("\"serverView\":\"custom\""))
        assertTrue(text.contains("\"reported\":512"))
        assertTrue(text.contains("\"omittedDueToLimit\":38"))
        assertFalse(text.contains("owners"))
        assertTrue(r.bytes.size <= TransferHealthReport.MAX_BYTES)
        assertEquals(512, Regex("\\"index\\":").findAll(text).count())
    }

    @Test fun unknownRemoteRateDoesNotMasqueradeAsZero() {
        val missing = remote.copy(downBytesPerSecond = null, upBytesPerSecond = -1)
        val text = String(TransferHealthReport.remote(listOf(missing), "main",
            false, capturedAt = now).bytes)
        assertTrue(text.contains("\"downloadRateState\":\"unreported\""))
        assertFalse(text.contains("\"downloadBytesPerSecond\":"))
        assertFalse(text.contains("\"uploadBytesPerSecond\":"))
    }
}
