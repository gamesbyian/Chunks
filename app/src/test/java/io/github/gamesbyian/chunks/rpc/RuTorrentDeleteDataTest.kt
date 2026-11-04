package io.github.gamesbyian.chunks.rpc

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class RuTorrentDeleteDataTest {
    @Test fun mapsOnlyExactRutorrrentPluginMount() {
        assertEquals("/rutorrent/plugins/erasedata/action.php",
            RuTorrentDeleteData.endpoint(URI(
                "https://host.example/rutorrent/plugins/httprpc/action.php"))?.rawPath)
        assertEquals("/rutorrent3/plugins/erasedata/action.php",
            RuTorrentDeleteData.endpoint(URI(
                "https://host.example/rutorrent3/plugins/httprpc/action.php"))?.rawPath)
        for (path in listOf("/RPC2", "/rutorrent4/plugins/httprpc/action.php",
                "/rutorrent/plugins/other/action.php", "/rutorrent/plugins/httprpc/action.php/extra")) {
            assertNull(RuTorrentDeleteData.endpoint(URI("https://host.example$path")))
        }
    }

    @Test fun rejectsUnsafePathsBeforeAnyDestructivePost() {
        assertTrue(RuTorrentDeleteData.safeFilePaths(listOf(
            RemoteFile("My Show/episode.mkv", 100, 1))))
        assertFalse(RuTorrentDeleteData.safeFilePaths(emptyList()))
        for (name in listOf("../parent", "/absolute", "a/../outside",
                "file\\child", "a//b", "line\nbreak", "dir/./target")) {
            assertFalse(name, RuTorrentDeleteData.safeFilePaths(listOf(RemoteFile(name, 1, 1))))
        }
        assertFalse(RuTorrentDeleteData.safeFilePaths(listOf(
            RemoteFile("Same.mp4", 1, 1), RemoteFile("same.MP4", 1, 1))))
    }

    @Test fun refusesHtmlOrFalseRatherThanClaimingDeletion() {
        assertTrue(RuTorrentDeleteData.acceptedResponse("[]"))
        assertTrue(RuTorrentDeleteData.acceptedResponse("[0,0,0]"))
        for (body in listOf("false", "null", "true", "<html>login</html>",
            "{\"result\":\"ok\"}", "[", "[]more")) {
            assertFalse(body, RuTorrentDeleteData.acceptedResponse(body))
        }
    }
}
