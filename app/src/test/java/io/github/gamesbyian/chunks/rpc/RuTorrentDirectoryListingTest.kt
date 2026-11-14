package io.github.gamesbyian.chunks.rpc

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuTorrentDirectoryListingTest {
    @Test fun directoryListingStaysOnExactNumberedTenantMount() {
        val valid = URI("https://example.invalid/tenant/rutorrent2/plugins/httprpc/action.php")
        assertEquals(
            "/tenant/rutorrent2/plugins/_getdir/listdir.php",
            RuTorrentDirectoryListing.endpoint(valid)?.path)
        assertNull(RuTorrentDirectoryListing.endpoint(
            URI("https://example.invalid/tenant/RPC2")))
        assertNull(RuTorrentDirectoryListing.endpoint(
            URI("https://example.invalid/rutorrent2/plugins/httprpc/other.php")))
    }

    @Test fun parserAcceptsOnlyActualBoundedCanonicalServerDirectoryChildren() {
        val json = """{"path":"/home/tenant/downloads/","directories":["TV","Movies",".."],"files":["private.txt"]}"""
        val outcome = RuTorrentDirectoryListing.parse(json, "/home/tenant/downloads")
        assertTrue(outcome is RemoteDirectoryLookup.Ready)
        val page = (outcome as RemoteDirectoryLookup.Ready).page
        assertEquals("/home/tenant/downloads", page.path)
        assertEquals(listOf("Movies", "TV"), page.folders)
        assertFalse(page.folders.contains("private.txt"))
    }

    @Test fun silentRootFallbackAndHostileNamesNeverBecomeSelectable() {
        val root = """{"path":"/other/account","directories":["OK"],"files":[]}"""
        val fallback = RuTorrentDirectoryListing.parse(root, "/home/tenant/downloads")
        assertEquals(RemoteDirectoryLookup.Reason.OUTSIDE_SCOPE,
            (fallback as RemoteDirectoryLookup.Unavailable).reason)
        for (name in listOf("../other", "x/y", "a\\b", ".", "")) {
            val json = """{"path":"/home/tenant","directories":["$name"],"files":[]}"""
            val result = RuTorrentDirectoryListing.parse(json, "/home/tenant")
            assertTrue("unsafe entry must refuse the whole listing",
                result is RemoteDirectoryLookup.Unavailable)
        }
    }

    @Test fun legacyGetDirInfoSupportsReadOnlyListingWithoutTrustingSilentFallback() {
        val rpc = URI("https://example.invalid/tenant/rutorrent2/plugins/httprpc/action.php")
        assertEquals("/tenant/rutorrent2/plugins/_getdir/info.php",
            RuTorrentDirectoryListing.legacyEndpoint(rpc)?.path)
        val json = """{"basedir":"/home/tenant/downloads","dirlist":["..","TV","Films"]}"""
        val result = RuTorrentDirectoryListing.parse(json, "/home/tenant/downloads", legacy = true)
        assertTrue(result is RemoteDirectoryLookup.Ready)
        assertEquals(listOf("Films", "TV"),
            (result as RemoteDirectoryLookup.Ready).page.folders)
        val changed = RuTorrentDirectoryListing.parse(json, "/home/other/downloads", legacy = true)
        assertEquals(RemoteDirectoryLookup.Reason.OUTSIDE_SCOPE,
            (changed as RemoteDirectoryLookup.Unavailable).reason)
        assertTrue(RuTorrentDirectoryListing.parse(
            """{"basedir":"/home/tenant","dirlist":["../../"]}""", "",
            legacy = true) is RemoteDirectoryLookup.Unavailable)
    }

    @Test fun pathContainmentIsSeparatorAwareAndDisallowsTraversal() {
        assertTrue(RuTorrentDirectoryListing.contains("/home/ian", "/home/ian/tv"))
        assertFalse(RuTorrentDirectoryListing.contains("/home/ian", "/home/ianna/tv"))
        assertNull(RuTorrentDirectoryListing.canonical("/home/ian/../other"))
        assertNull(RuTorrentDirectoryListing.canonical("/home//ian"))
        assertNull(RuTorrentDirectoryListing.canonical("/home\\ian"))
        assertEquals("/home/ian", RuTorrentDirectoryListing.canonical("/home/ian/"))
    }
}
