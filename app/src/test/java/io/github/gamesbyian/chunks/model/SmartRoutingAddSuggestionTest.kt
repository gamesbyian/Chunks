package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.ClientCapability as C

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRoutingAddSuggestionTest {
    private val qb = "a".repeat(64)
    private val deluge = "b".repeat(64)
    private fun rule(id: String, kind: SmartRoutingPreview.Kind, target: String, priority: Int) =
        SmartRoutingPreview.Rule(id, priority, kind, 60, target)
    private fun c(id: String, label: String, live: Boolean = true) =
        SmartRoutingAddSuggestion.Candidate(id, label, live, true, true)
    private val policy = SmartRoutingPolicyStore.Policy(enabled = true, rules = listOf(
        rule("tv", SmartRoutingPreview.Kind.TV, qb, 2),
        rule("books", SmartRoutingPreview.Kind.BOOK, deluge, 1)))

    @Test fun tvEpisodeGoesToTheExactQBittorrentClient() {
        val r = SmartRoutingAddSuggestion.evaluate(policy, "Show.S01E02.1080p",
            listOf("Show.S01E02.1080p/Show.S01E02.mkv"), listOf(c("local", "Local"), c(qb, "Seedbox qB")))
        val s = (r as SmartRoutingAddSuggestion.Result.Suggested).suggestion
        assertEquals(qb, s.environmentId)
        assertEquals("Seedbox qB", s.label)
        assertEquals("tv", s.ruleId)
    }

    @Test fun matchedButOfflineTargetNeverFallsThroughToAnotherClient() {
        val r = SmartRoutingAddSuggestion.evaluate(policy, "Novel", listOf("Novel.epub"),
            listOf(c(qb, "Seedbox qB"), c(deluge, "Deluge", live = false)))
        assertTrue(r is SmartRoutingAddSuggestion.Result.Unavailable)
    }

    @Test fun mixedOrUnknownContentAsks() {
        val r = SmartRoutingAddSuggestion.evaluate(policy, "Pack", listOf("a.mkv", "b.epub"), listOf(c(qb, "qB")))
        assertTrue(r is SmartRoutingAddSuggestion.Result.Ask)
    }

    @Test fun disabledOrSourcelessSuggestsNothing() {
        assertEquals(SmartRoutingAddSuggestion.Result.Disabled,
            SmartRoutingAddSuggestion.evaluate(policy.copy(enabled = false), "x.mkv", emptyList(), listOf(c(qb, "qB"))))
        assertEquals(SmartRoutingAddSuggestion.Result.NoSource,
            SmartRoutingAddSuggestion.evaluate(policy, null, emptyList(), listOf(c(qb, "qB"))))
    }

    @Test fun duplicateCandidateIdentityIsNeverUsable() {
        val r = SmartRoutingAddSuggestion.evaluate(policy, "Show.S01E02", listOf("Show.S01E02.mkv"),
            listOf(c(qb, "One"), c(qb, "Two")))
        assertTrue(r is SmartRoutingAddSuggestion.Result.Unavailable)
    }

    private fun session(vararg caps: io.github.gamesbyian.chunks.rpc.ClientCapability, dir: String? = "/srv") =
        io.github.gamesbyian.chunks.rpc.ClientSessionInfo(RemoteBackendKind.DELUGE, "2", dir, caps.toSet())

    @Test fun candidacyFollowsTheClientsOwnLiveCapabilities() {
        val full = session(C.ADD_MAGNET, C.ADD_TORRENT_FILE, C.ADD_STOPPED)
        assertTrue(SmartRoutingAddSuggestion.candidateFor(qb, "qB", full, magnetSource = true, startNow = false).let {
            it.live && it.acceptsAdd && it.placeKnown })
        // Not answered yet: never usable.
        assertTrue(!SmartRoutingAddSuggestion.candidateFor(qb, "qB", null, true, true).live)
        // No magnet support, or no stopped Add when Start is off: not an Add target.
        assertTrue(!SmartRoutingAddSuggestion.candidateFor(qb, "qB", session(C.ADD_TORRENT_FILE, C.ADD_STOPPED), true, true).acceptsAdd)
        assertTrue(!SmartRoutingAddSuggestion.candidateFor(qb, "qB", session(C.ADD_MAGNET), true, false).acceptsAdd)
        assertTrue(SmartRoutingAddSuggestion.candidateFor(qb, "qB", session(C.ADD_MAGNET), true, true).acceptsAdd)
        // No reported Save to folder: not a verified Place.
        assertTrue(!SmartRoutingAddSuggestion.candidateFor(qb, "qB", session(C.ADD_MAGNET, dir = null), true, true).placeKnown)
    }

    @Test fun incapableMatchedClientIsUnavailableNotRerouted() {
        val noMagnet = SmartRoutingAddSuggestion.candidateFor(qb, "qB", session(C.ADD_TORRENT_FILE), true, true)
        val r = SmartRoutingAddSuggestion.evaluate(policy, "Show.S01E02", listOf("Show.S01E02.mkv"),
            listOf(noMagnet, c(deluge, "Deluge")))
        assertTrue(r is SmartRoutingAddSuggestion.Result.Unavailable)
    }
}
