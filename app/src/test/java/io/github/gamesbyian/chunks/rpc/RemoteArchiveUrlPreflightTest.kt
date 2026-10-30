package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

/**
 * The archive torrent URL goes to an rTorrent load command, which can fetch
 * it from the server network. Preflight must not permit alternate ports or
 * ambiguous paths that select a different resource after server-side decode.
 * These cases perform no HTTP or RPC requests.
 */
class RemoteArchiveUrlPreflightTest {
    private val hash = "aB".repeat(20)
    private val directory = "/home/test/Downloads"

    @Test fun ordinaryOfficialArchiveTorrentUrlsStillWork() {
        for (url in listOf(
            "https://archive.org/download/example/example_archive.torrent",
            "https://archive.org:443/download/example/example_archive.torrent",
            "https://ARCHIVE.ORG/download/Public%20Sample/Public%20Sample_archive.torrent"
        )) {
            val plan = StoppedRemoteAddPlan.fromArchiveTorrent(url, hash, directory)
            assertEquals(StoppedRemoteAddPlan.Kind.ARCHIVE_TORRENT_URL, plan.kind)
            assertEquals(url, plan.source)
            assertEquals(hash.lowercase(), plan.expectedInfoHash)
            assertEquals(directory, plan.destination)
        }
    }

    @Test fun unapprovedPortsAndRedirectableOrAmbiguousPathsNeverCreateAnAddPlan() {
        val rejected = listOf(
            "https://archive.org:8080/download/example/file.torrent",
            "https://archive.org:4443/download/example/file.torrent",
            "https://archive.org/download/example/../file.torrent",
            "https://archive.org/download/%2e%2e/file.torrent",
            "https://archive.org/download/%252e%252e/file.torrent",
            "https://archive.org/download/example/%2fother.torrent",
            "https://archive.org/download/example/%252fother.torrent",
            "https://archive.org/download/example/%5cother.torrent",
            "https://archive.org/download/example/%00file.torrent",
            "https://archive.org/download//file.torrent",
            "https://archive.org/download/./file.torrent",
            "https://archive.org/download/file.torrent",
            "https://archive.org/download/example/file.torrent?redirect=1",
            "https://archive.org/download/example/file.torrent#fragment",
            "https://user:password@archive.org/download/example/file.torrent",
            "https://archive.org.evil.test/download/example/file.torrent",
            "http://archive.org/download/example/file.torrent"
        )
        for (url in rejected) {
            try {
                StoppedRemoteAddPlan.fromArchiveTorrent(url, hash, directory)
                fail("Unsafe server-side torrent URL was accepted: $url")
            } catch (_: IllegalArgumentException) {
                // Invalid source never reaches rTorrent's URL fetch.
            }
        }
    }

    @Test fun directoryAndExactMetainfoHashRemainBoundToTheReviewedPlan() {
        val url = "https://archive.org/download/example/example_archive.torrent"
        val valid = StoppedRemoteAddPlan.fromArchiveTorrent(url, hash, directory)
        assertEquals(hash.lowercase(), valid.expectedInfoHash)
        for (badHash in listOf("deadbeef", "../", "z".repeat(40))) {
            try {
                StoppedRemoteAddPlan.fromArchiveTorrent(url, badHash, directory)
                fail("Invalid verified hash must refuse Add")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            StoppedRemoteAddPlan.fromArchiveTorrent(url, hash, "/home/../escape")
            fail("Destination traversal must fail preflight")
        } catch (_: IllegalArgumentException) { }
    }
}
