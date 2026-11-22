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
        io.github.gamesbyian.chunks.rpc.ClientSessionInfo(io.github.gamesbyian.chunks.rpc.TorrentClientKind.DELUGE, "2", dir, caps.toSet())

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

    @Test fun invalidDaemonDefaultCannotMakeAnUnavailableClientLookReady() {
        for (path in listOf("", " ", "\t", "\r\n", "relative/path", "/srv/../other", "/srv/\u0000other")) {
            val unknownPlace = SmartRoutingAddSuggestion.candidateFor(qb, "qB",
                session(C.ADD_MAGNET, C.ADD_STOPPED, dir = path), magnetSource = true, startNow = false)
            assertTrue("An invalid default cannot prove a Save to folder", !unknownPlace.placeKnown)
            assertTrue("The exact matching rule must not silently reroute",
                SmartRoutingAddSuggestion.evaluate(policy, "Show.S01E02.mkv",
                    listOf("Show.S01E02.mkv"), listOf(unknownPlace, c(deluge, "Deluge")))
                    is SmartRoutingAddSuggestion.Result.Unavailable)
        }
        // A nonblank, genuinely reported default remains selectable; do not
        // require an unnecessary picker or damage normal successful Adds.
        assertTrue(SmartRoutingAddSuggestion.candidateFor(qb, "qB",
            session(C.ADD_MAGNET, C.ADD_STOPPED, dir = "/srv/downloads"),
            magnetSource = true, startNow = false).placeKnown)
    }

    @Test fun incapableMatchedClientIsUnavailableNotRerouted() {
        val noMagnet = SmartRoutingAddSuggestion.candidateFor(qb, "qB", session(C.ADD_TORRENT_FILE), true, true)
        val r = SmartRoutingAddSuggestion.evaluate(policy, "Show.S01E02", listOf("Show.S01E02.mkv"),
            listOf(noMagnet, c(deluge, "Deluge")))
        assertTrue(r is SmartRoutingAddSuggestion.Result.Unavailable)
    }

    @Test fun metadataLateMoveSuggestionIsAdvisoryAndOnlyForAnotherClient() {
        val targets = listOf(c(qb, "Seedbox qB"), c(deluge, "Deluge"))
        // A TV job sitting on Local, now that its real name is known: suggest qB.
        val s = SmartRoutingAddSuggestion.moveSuggestion(policy, "Show.S01E02.1080p.mkv", false, "local", targets)
        assertEquals(qb, s?.environmentId)
        // Already on the rule's client: stay, no suggestion.
        assertEquals(null, SmartRoutingAddSuggestion.moveSuggestion(policy, "Show.S01E02.1080p.mkv", false, qb, targets))
        // Metadata still pending, unknown name, routing off or target offline: nothing.
        assertEquals(null, SmartRoutingAddSuggestion.moveSuggestion(policy, "Show.S01E02.1080p.mkv", true, "local", targets))
        assertEquals(null, SmartRoutingAddSuggestion.moveSuggestion(policy, null, false, "local", targets))
        assertEquals(null, SmartRoutingAddSuggestion.moveSuggestion(policy, "Show.S01E02", null, "local", targets))
        assertEquals(qb, SmartRoutingAddSuggestion.moveSuggestion(policy, "Show.S01E02", null, "local", targets,
            fileNames = listOf("Show.S01E02.mkv"))?.environmentId)
        assertEquals(null, SmartRoutingAddSuggestion.moveSuggestion(policy.copy(enabled = false),
            "Show.S01E02.1080p.mkv", false, "local", targets))
        assertEquals(null, SmartRoutingAddSuggestion.moveSuggestion(policy, "Show.S01E02.1080p.mkv", false, "local",
            listOf(c(qb, "Seedbox qB", live = false))))
    }

    @Test fun theJobsOwnFilesDecideWhenItsNameIsAmbiguous() {
        val targets = listOf(c(qb, "Seedbox qB"), c(deluge, "Deluge"))
        // A bare name says nothing; its files are books.
        assertEquals(null, SmartRoutingAddSuggestion.moveSuggestion(policy, "Collection", false, "local", targets))
        assertEquals(deluge, SmartRoutingAddSuggestion.moveSuggestion(policy, "Collection", false, "local", targets,
            fileNames = listOf("Collection/One.epub", "Collection/Two.epub"))?.environmentId)
        // Mixed files ask rather than guess, even with a confident-looking name.
        assertEquals(null, SmartRoutingAddSuggestion.moveSuggestion(policy, "Show.S01E02", false, "local", targets,
            fileNames = listOf("Show.S01E02.mkv", "Guide.epub")))
    }

    @Test fun explainSaysWhatTheRulesWouldDo() {
        val targets = listOf(qb to "Seedbox qB", deluge to "Deluge")
        assertEquals(null, SmartRoutingAddSuggestion.explain(policy, "  \n ", targets))
        val tv = SmartRoutingAddSuggestion.explain(policy, "Show.S01E02.1080p\nShow.S01E02.mkv", targets)!!
        assertTrue(tv, tv.startsWith("Recognised as tv episode") && tv.endsWith("Rule 1 suggests Seedbox qB."))
        assertTrue(SmartRoutingAddSuggestion.explain(policy, "Pack\na.mkv\nb.epub", targets)!!
            .startsWith("Mixed or unclear content"))
        assertTrue(SmartRoutingAddSuggestion.explain(policy.copy(enabled = false), "x.mkv", targets)!!
            .endsWith("Smart Routing is off, so Add asks."))
        // A rule whose client was removed never falls through to another client.
        assertTrue(SmartRoutingAddSuggestion.explain(policy, "Novel.epub", listOf(qb to "Seedbox qB"))!!
            .endsWith("its client is no longer set up, so Add asks."))
    }
}
