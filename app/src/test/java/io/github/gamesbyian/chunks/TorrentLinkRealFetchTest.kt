package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real public HTTPS fetch through the production [TorrentLinkFetcher]: an
 * extensionless LinuxTracker download link and an Internet Archive link that
 * redirects to a storage host. Each infohash must equal the source-verified
 * entry in test-resources/public-magnets/catalog.json. Opt-in
 * (CHUNKS_REAL_LINK_FETCH=1) so ordinary CI never depends on outside sites.
 * This proves link retrieval and metainfo identity, not peer download.
 */
class TorrentLinkRealFetchTest {
    private fun fetch(url: String): TorrentLinkResult =
        TorrentLinkFetcher().fetch(TorrentLinkCheck.inspect(url) as TorrentLinkCheck.Fetchable)

    @Test fun extensionlessAndRedirectedPublicLinksReturnTheirVerifiedTorrents() {
        assumeTrue("set CHUNKS_REAL_LINK_FETCH=1 to fetch real public links",
            System.getenv("CHUNKS_REAL_LINK_FETCH") == "1")
        val cases = mapOf(
            "https://linuxtracker.org/torrents/f6a86eee64d65f3f970f22f64e584dfbf3a76d84/download/" to
                "f6a86eee64d65f3f970f22f64e584dfbf3a76d84",
            "https://archive.org/download/FREE_background_music_dhalius/FREE_background_music_dhalius_archive.torrent" to
                "e531c7ca2cca262ea75a3496a7bdbb53de90abed")
        for ((url, hash) in cases) {
            val result = fetch(url)
            check(result is TorrentLinkResult.Fetched) { "Real fetch refused: $result" }
            assertEquals(hash, result.preview.infoHashV1.lowercase())
            println("CHUNKS_REAL_LINK_FETCH ok ${result.display} bytes=${result.bytes.size} infohash=$hash")
        }
    }
}
