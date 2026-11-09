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
        assertEquals("/rutorrent4/plugins/erasedata/action.php",
            RuTorrentDeleteData.endpoint(URI(
                "https://host.example/rutorrent4/plugins/httprpc/action.php"))?.rawPath)
        for (path in listOf("/RPC2", "/rutorrent1000/plugins/httprpc/action.php",
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

    @Test fun safeDirectoriesCannotAliasAnotherJobThroughDotsOrRoot() {
        assertTrue(RuTorrentDeleteData.safeDirectory("/downloads/Films"))
        assertTrue(RuTorrentDeleteData.safeDirectory("/home/person/My Shows"))
        assertFalse("Deleting from filesystem root is never approved",
            RuTorrentDeleteData.safeDirectory("/"))
        assertTrue("A different job at root must still be checked",
            RuTorrentDeleteData.safeDirectory("/", allowRoot = true))
        for (path in listOf("", "relative", "//downloads", "/downloads/",
                "/downloads/../other", "/other/./downloads", "/other//downloads",
                "/downloads\\files", "/downloads\nother")) {
            assertFalse(path, RuTorrentDeleteData.safeDirectory(path, allowRoot = true))
        }
    }

    @Test fun nonzeroCommandResultsCannotBecomeAcceptedDeletionQueueReceipt() {
        for (body in listOf(
                "[1,0,0]", "[0,-1,0]", "[0,0,2]", "[1,1,1]",
                "[\"\",1,0]", "[\"\",0,\"1\"]", "[\"\",\"-1\",\"0\"]",
                "[\"1\",\"0\",\"0\"]", "[\"\",0,-1]")) {
            assertFalse("Nonzero/negative cleanup command result: $body",
                RuTorrentDeleteData.acceptedResponse(body))
        }
        assertTrue(RuTorrentDeleteData.acceptedResponse("[\"0\",\"0\",\"0\"]"))
        assertTrue(RuTorrentDeleteData.acceptedResponse("[  \"\" , \"0\" , 0 ]"))
    }

    @Test fun refusesHtmlOrFalseRatherThanClaimingDeletion() {
        assertTrue(RuTorrentDeleteData.acceptedResponse("[0,0,0]"))
        assertTrue(RuTorrentDeleteData.acceptedResponse("[\"\",\"0\",\"0\"]"))
        assertTrue(RuTorrentDeleteData.acceptedResponse("[\"\",0,0]"))
        for (body in listOf("[]", "[\"\"]", "[\"\",0]", "[\"\",\"\",\"\"]",
            "[\"\",0,0,0]", "false", "null", "true", "<html>login</html>",
            "{\"result\":\"ok\"}", "[", "[]more")) {
            assertFalse(body, RuTorrentDeleteData.acceptedResponse(body))
        }
    }
}
