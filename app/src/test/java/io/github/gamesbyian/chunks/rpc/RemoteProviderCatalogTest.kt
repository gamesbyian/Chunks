package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

class RemoteProviderCatalogTest {
    private fun paths(host: String, user: String) =
        RemoteSmartConnect.candidates(host, user).map { it.endpoint.removePrefix("https://$host") to it.description }

    /** Exact hostname-only plans, pinned so catalog edits are deliberate. */
    @Test fun hostnamePlansArePinned() {
        val h = "/rutorrent/plugins/httprpc/action.php"
        assertEquals(listOf(h to "SeedHost ruTorrent", "/RPC1" to "SeedHost direct RPC",
            "/ianseeds$h" to "SeedHost account ruTorrent", "/ianseeds/RPC1" to "SeedHost account SCGI"),
            paths("ianseeds.wood.seedhost.eu", "ianseeds"))
        assertEquals(listOf("/ianseeds$h" to "SeedHost account ruTorrent",
            "/ianseeds/RPC1" to "SeedHost account SCGI", h to "SeedHost ruTorrent", "/RPC1" to "SeedHost direct RPC"),
            paths("wood.seedhost.eu", "ianseeds"))
        assertEquals(listOf(h to "SeedHost ruTorrent", "/RPC1" to "SeedHost direct RPC"),
            paths("wood.seedhost.eu", ""))
        assertEquals(listOf("/RPC2" to "Ultra.cc direct RPC", h to "ruTorrent HTTP-RPC"), paths("box.usbx.me", "u"))
        assertEquals(listOf("/xmlrpc" to "Whatbox XML-RPC", h to "ruTorrent HTTP-RPC"), paths("whatbox.ca", "u"))
        assertEquals(listOf("/RPC2" to "Appbox direct RPC", h to "ruTorrent HTTP-RPC"), paths("a.appboxes.co", "u"))
        assertEquals(listOf("/plugins/rpc/rpc.php" to "RapidSeedbox ruTorrent RPC",
            "/rutorrent/plugins/rpc/rpc.php" to "RapidSeedbox prefixed RPC"), paths("rt-x.a.seedbox.vip", "u"))
        val generic = listOf(h to "ruTorrent HTTP-RPC", "/RPC2" to "Common direct SCGI",
            "/xmlrpc" to "Common XML-RPC", "/someone" to "Account SCGI mount")
        assertEquals(generic, paths("my-server.example", "someone"))
        assertEquals(generic, paths("apollo.feralhosting.com", "someone"))
        assertEquals(generic, paths("x.seedbox.vip", "someone"))
        assertEquals(generic.dropLast(1), paths("my-server.example", "bad/user"))
    }

    @Test fun lookalikeHostsDoNotMatchProviders() {
        for (host in listOf("evilwhatbox.ca", "whatbox.ca.attacker.example", "notseedhost.eu",
                "seedhost.eu.example", "usbx.me.example", "rt-seedbox.vip.example"))
            assertNull(host, RemoteProviderCatalog.match(host))
        assertEquals("whatbox", RemoteProviderCatalog.match("Box.WhatBox.CA")?.id)
        assertEquals("seedhost", RemoteProviderCatalog.match("seedhost.eu")?.id)
        assertNull(RemoteProviderCatalog.match("seedbox.vip"))
    }

    @Test fun everyEntryIsReviewedHttpsSourcedAndUnaccepted() {
        val ids = RemoteProviderCatalog.providers.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (p in RemoteProviderCatalog.providers) {
            assertTrue(p.id, p.reference.startsWith("https://"))
            assertTrue(p.id, Regex("\\d{4}-\\d{2}-\\d{2}").matches(p.reviewed))
            assertTrue(p.id, p.domains.isNotEmpty() && p.domains.all { '.' in it && !it.startsWith(".") })
            assertTrue(p.id, p.templates.isNotEmpty() || p.tip != null)
            assertTrue(p.id, p.templates.all { it.path.startsWith("/") && ".." !in it.path })
            // No provider has a recorded handset journey yet; flip only with ledger evidence.
            assertFalse(p.id, p.liveAccepted)
        }
    }

    @Test fun hintIsHonestAndNeverEchoesCredentials() {
        val whatbox = RemoteProviderCatalog.hint("box.whatbox.ca")!!
        assertTrue(whatbox.startsWith("Recognised Whatbox."))
        assertTrue(whatbox.contains("documented addresses first, read-only"))
        assertTrue(whatbox.contains("not yet tested by Chunks"))
        val feral = RemoteProviderCatalog.hint("https://apollo.feralhosting.com/")!!
        assertTrue(feral.contains("Paste your full ruTorrent address"))
        assertFalse(feral.contains("documented addresses first"))
        // A pasted instance URL is used exactly; no "will try" promise.
        assertFalse(RemoteProviderCatalog.hint("https://a.seedhost.eu/u/rutorrent2/")!!.contains("will try"))
        assertNull(RemoteProviderCatalog.hint("https://user:secret@box.whatbox.ca/"))
        assertNull(RemoteProviderCatalog.hint("my-server.example"))
        assertNull(RemoteProviderCatalog.hint(""))
        assertNull(RemoteProviderCatalog.hint("http://[bad"))
    }
}
