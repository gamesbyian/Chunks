package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class PublicTrackerHandoffTest {
    private val base = "magnet:?xt=urn:btih:b63eaa9720ee797667ef58ea35ef7d81ab5d1c16&dn=Big%20Buck%20Bunny"

    @Test fun addsEncodedPublicTrackersDeduplicatedAndBounded() {
        val magnet = PublicTrackerHandoff.withTrackers(base, listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "https://bt1.archive.org/announce",
            "udp://tracker.opentrackr.org:1337/announce"))
        assertEquals(base + "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce" +
            "&tr=https%3A%2F%2Fbt1.archive.org%2Fannounce", magnet)
        val many = PublicTrackerHandoff.withTrackers(base, (1..50).map { "udp://t$it.example:80/announce" })
        assertEquals(PublicTrackerHandoff.MAX_TRACKERS, many.split("&tr=").size - 1)
    }

    @Test fun dropsCredentialLookingAndMalformedTrackers() {
        val rejected = listOf(
            "https://tracker.example/announce?passkey=abc",
            "https://tracker.example/announce.php?pk=x",
            "https://tracker.example/a1b2c3d4e5f6a7b8c9d0/announce",
            "https://user:pw@tracker.example/announce",
            "ftp://tracker.example/announce",
            "https://tracker.example/announce\nx",
            "not a url",
            "https:///announce")
        for (url in rejected) assertFalse(url, PublicTrackerHandoff.usable(url))
        assertEquals(base, PublicTrackerHandoff.withTrackers(base, rejected))
        assertTrue(PublicTrackerHandoff.usable("http://tracker.example:6969/announce"))
    }
}
