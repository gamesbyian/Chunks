package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.AddRouting.Target
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure Smart Routing Add decisions extracted from `ChunksApp` (A2611-02). */
class AddRoutingModelTest {
    private val local = EnvironmentSnapshots.LOCAL.value
    private fun preview(name: String) = TorrentMetainfoPreview(name, "aa".repeat(20), 1, 10L, 16384L)

    private fun profile(id: String, writable: Boolean = true) = CombinedReadOnlyProtocolProfile(
        id = id, backend = RemoteBackendKind.TRANSMISSION, label = "Box $id", verifiedAtMillis = 1L,
        operations = if (writable) ({ error("not used by the decision") }) else null,
        inspect = { error("not used by the decision") })

    // --- source -------------------------------------------------------------------------

    @Test fun aServerSourceStillBeingPreparedHasNoRoutingSource() {
        val file = preview("File")
        assertNull(AddRouting.source(true, true, file, true, true, file))
        assertNull(AddRouting.source(true, false, file, true, true, file))
    }

    @Test fun aReviewedServerFileCountsOnlyWhileItIsStillTheActiveSource() {
        val file = preview("Remote")
        assertSame(file, AddRouting.source(false, true, file, true, false, null))
        assertNull("a file whose text was edited is no longer evidence",
            AddRouting.source(false, true, file, false, false, null))
    }

    @Test fun localReviewedFileIsEvidenceOnlyForAnEmptyDraftAndNeverOverServerText() {
        val file = preview("Local")
        assertSame(file, AddRouting.source(false, false, null, false, true, file))
        assertNull("typed text wins over a retained file",
            AddRouting.source(false, false, null, false, false, file))
        assertNull("an authenticated target never borrows Local's file",
            AddRouting.source(false, true, null, false, true, file))
    }

    // --- candidates ---------------------------------------------------------------------

    @Test fun localIsAlwaysACandidateAndOnlyWritableProtocolClientsJoin() {
        val sessions = mapOf("t1" to ClientSessionInfo(TorrentClientKind.TRANSMISSION, "4", "/dl",
            setOf(ClientCapability.ADD_MAGNET)))
        val list = AddRouting.candidates(null, null, false, false,
            listOf(profile("t1"), profile("ro", writable = false)), sessions, magnetSource = true, startNow = true)
        assertEquals(listOf(local, "t1"), list.map { it.environmentId })
        assertTrue(list[0].live && list[0].acceptsAdd && list[0].placeKnown)
        assertTrue(list[1].live && list[1].acceptsAdd && list[1].placeKnown)
    }

    @Test fun aProtocolClientWithoutALiveSessionIsACandidateButNeverLive() {
        val list = AddRouting.candidates(null, null, false, false, listOf(profile("t1")), emptyMap(),
            magnetSource = true, startNow = true)
        assertFalse(list.single { it.environmentId == "t1" }.live)
    }

    @Test fun theAuthenticatedRTorrentCandidateReflectsItsLiveSessionAndKnownPlace() {
        val stale = AddRouting.candidates("srv", null, rTorrentLive = false, rTorrentPlaceKnown = false,
            emptyList(), emptyMap(), true, true).single { it.environmentId == "srv" }
        assertEquals("rTorrent", stale.label)
        assertFalse(stale.live); assertFalse(stale.placeKnown)
        val ready = AddRouting.candidates("srv", "Seedbox", true, true, emptyList(), emptyMap(), true, true)
            .single { it.environmentId == "srv" }
        assertEquals("Seedbox", ready.label)
        assertTrue(ready.live && ready.placeKnown)
    }

    // --- target -------------------------------------------------------------------------

    @Test fun targetsResolveInTheDocumentedOrder() {
        val profiles = listOf(profile("t1"), profile("ro", writable = false))
        assertNull(AddRouting.targetFor(null, "srv", profiles, false, null))
        assertEquals(Target.Local, AddRouting.targetFor(local, "srv", profiles, false, null))
        assertEquals(Target.AuthenticatedRTorrent, AddRouting.targetFor("srv", "srv", profiles, false, null))
        assertEquals(Target.Protocol("t1"), AddRouting.targetFor("t1", "srv", profiles, false, null))
    }

    @Test fun aSuggestionNeverMovesTheDraftToAClientThatCannotTakeTheAdd() {
        val profiles = listOf(profile("t1"), profile("ro", writable = false))
        assertNull("read-only profile", AddRouting.targetFor("ro", null, profiles, false, null))
        assertNull("unknown client", AddRouting.targetFor("ghost", null, profiles, false, null))
        assertNull("rTorrent no longer connected", AddRouting.targetFor("srv", null, profiles, false, null))
    }

    @Test fun aProtocolAddInFlightBlocksAnyOtherProtocolTargetButNotItsOwn() {
        val profiles = listOf(profile("t1"), profile("t2"))
        assertNull(AddRouting.targetFor("t2", null, profiles, protocolAddBusy = true, protocolAddProfileId = "t1"))
        assertEquals(Target.Protocol("t1"),
            AddRouting.targetFor("t1", null, profiles, protocolAddBusy = true, protocolAddProfileId = "t1"))
        assertEquals("Local is not an in-flight protocol Add's concern",
            Target.Local, AddRouting.targetFor(local, null, profiles, true, "t1"))
    }

    // --- selection ----------------------------------------------------------------------

    @Test fun selectedMatchesTheExactTargetOnly() {
        assertFalse(AddRouting.isSelected(null, null, false, null, true))
        assertTrue(AddRouting.isSelected("t1", "t1", false, null, false))
        assertFalse(AddRouting.isSelected("t1", "t2", false, null, false))
        assertTrue(AddRouting.isSelected("srv", null, true, "srv", false))
        assertFalse("a protocol target is not the authenticated rTorrent",
            AddRouting.isSelected("srv", "t1", true, "srv", false))
        assertFalse("an unconnected rTorrent is not selected", AddRouting.isSelected("srv", null, false, "srv", false))
        assertTrue(AddRouting.isSelected(local, null, false, null, true))
        assertFalse(AddRouting.isSelected(local, null, false, null, false))
    }
}
