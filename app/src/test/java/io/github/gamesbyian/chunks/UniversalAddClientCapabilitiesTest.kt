package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One capability descriptor decides what the shared Add form offers for each exact client. */
class UniversalAddClientCapabilitiesTest {
    private fun session(caps: Set<ClientCapability>, levels: Set<ClientFilePriority> = ClientFilePriority.entries.toSet()) =
        ClientSessionInfo(TorrentClientKind.QBITTORRENT, "4.6", "/dl", caps, levels)

    @Test fun nothingIsOfferedBeforeTheProtocolSessionProbeAnswers() {
        val none = UniversalAddClientCapabilities.protocol(null)
        assertFalse(none.magnet || none.torrentFile || none.stoppedAdd || none.customFolder || none.fileChoices)
        assertTrue(none.priorityLevels.isEmpty())
    }

    @Test fun fileChoicesNeedBothPrioritiesAndAStoppedAdd() {
        val noStopped = UniversalAddClientCapabilities.protocol(session(setOf(ClientCapability.FILE_PRIORITIES, ClientCapability.ADD_MAGNET)))
        assertFalse(noStopped.fileChoices)
        assertEquals("x cannot add this kind of source stopped.", noStopped.refusal(isMagnet = true, startNow = false, clientLabel = "x"))
        assertNull(noStopped.refusal(isMagnet = true, startNow = true, clientLabel = "x"))
        val full = UniversalAddClientCapabilities.protocol(session(ClientCapability.entries.toSet()))
        assertTrue(full.fileChoices && full.customFolder && full.originalSourcePolicy)
    }

    @Test fun onlyLevelsTheClientStoresAreOffered() {
        val highOnly = UniversalAddClientCapabilities.protocol(session(ClientCapability.entries.toSet(),
            setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH)))
        assertEquals(setOf(ClientFilePriority.HIGH), highOnly.priorityLevels)
    }

    @Test fun displayNamesAreOfferedOnlyWhereChunksStoresAndShowsThem() {
        assertTrue(UniversalAddClientCapabilities.LOCAL.displayName)
        assertTrue(UniversalAddClientCapabilities.RTORRENT.displayName)
        // Protocol profiles store names under their exact ProtocolAliasScope (shown in Combined).
        assertTrue(UniversalAddClientCapabilities.protocol(session(ClientCapability.entries.toSet())).displayName)
    }

    @Test fun aMissingSourceKindIsExplainedNotRerouted() {
        val torrentOnly = UniversalAddClientCapabilities.protocol(session(setOf(ClientCapability.ADD_TORRENT_FILE, ClientCapability.ADD_STOPPED)))
        assertEquals("Box cannot add magnet links.", torrentOnly.refusal(isMagnet = true, startNow = true, clientLabel = "Box"))
        assertNull(torrentOnly.refusal(isMagnet = false, startNow = false, clientLabel = "Box"))
    }
}
