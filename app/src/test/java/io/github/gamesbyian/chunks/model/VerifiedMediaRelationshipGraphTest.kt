package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VerifiedMediaRelationshipGraphTest {
    private fun entity(id: String, kind: MediaGraphEntityKind) = MediaGraphEntity("wd", kind, id)
    private fun fact(field: String, subject: MediaGraphEntity, verb: MediaGraphPredicate,
                     target: MediaGraphEntity, status: MediaGraphClaimState = MediaGraphClaimState.VERIFIED,
                     provider: String = "recorded", revision: Long = 1, specificTopic: Boolean = false) =
        MediaGraphClaim(provider, "record", field, revision, subject, verb, target, status, specificTopic)

    @Test fun crossMediaEpisodeAndComicIssueRequireExactVerifiedWriterIdentity() {
        val episode = entity("episode", MediaGraphEntityKind.TV_EPISODE)
        val comic = entity("issue331", MediaGraphEntityKind.COMIC_ISSUE)
        val david = entity("Q1351554", MediaGraphEntityKind.PERSON)
        val graph = VerifiedMediaRelationshipGraph.build(listOf(
            fact("episodeWriter", episode, MediaGraphPredicate.WRITTEN_BY, david),
            fact("comicScript", comic, MediaGraphPredicate.WRITTEN_BY, david)
        ), setOf("recorded"))
        val result = graph.related(episode, setOf(episode, comic)).single()
        assertEquals(MediaGraphRelation.SHARED_WRITER, result.strongest)
        assertEquals(david, result.explanations.single().via)
        assertEquals(setOf("episodeWriter", "comicScript"),
            result.explanations.single().evidence.map { it.fieldKey }.toSet())
    }

    @Test fun filmSubjectAndAlbumPerformerSharePersonButNotActorConfusion() {
        val film = entity("walk", MediaGraphEntityKind.FILM)
        val album = entity("cash-album", MediaGraphEntityKind.ALBUM)
        val cash = entity("cash", MediaGraphEntityKind.PERSON)
        val graph = VerifiedMediaRelationshipGraph.build(listOf(
            fact("subject", film, MediaGraphPredicate.ABOUT_PERSON, cash),
            fact("performer", album, MediaGraphPredicate.PERFORMED_BY, cash)
        ), setOf("recorded"))
        assertEquals(MediaGraphRelation.SUBJECT_AND_PERFORMER, graph.related(film, setOf(film, album)).single().strongest)
        assertEquals(MediaGraphRelation.SUBJECT_AND_PERFORMER, graph.related(album, setOf(film, album)).single().strongest)
    }

    @Test fun adaptationsAndSequelsRemainDirectionalAndDistinctWorks() {
        val book = entity("book", MediaGraphEntityKind.BOOK_WORK)
        val movie = entity("movie", MediaGraphEntityKind.FILM)
        val tv = entity("tv", MediaGraphEntityKind.TV_SERIES)
        val sequel = entity("sequel", MediaGraphEntityKind.FILM)
        val graph = VerifiedMediaRelationshipGraph.build(listOf(
            fact("adaptFilm", movie, MediaGraphPredicate.ADAPTED_FROM, book),
            fact("adaptTv", tv, MediaGraphPredicate.ADAPTED_FROM, book),
            fact("sequel", sequel, MediaGraphPredicate.SEQUEL_TO, movie)
        ), setOf("recorded"))
        val visible = setOf(book, movie, tv, sequel)
        assertEquals(MediaGraphRelation.SAME_SOURCE_WORK, graph.related(movie, visible).first { it.entity == tv }.strongest)
        assertEquals(MediaGraphRelation.DIRECT_ADAPTATION, graph.related(movie, visible).first { it.entity == book }.strongest)
        assertTrue(graph.related(book, visible).first { it.entity == movie }.explanations.first().reverseOfDirectFact)
        assertTrue(graph.related(movie, visible).first { it.entity == sequel }.explanations.first().reverseOfDirectFact)
    }

    @Test fun sharedCharacterUniverseAndProductionAreExplainableNotMergedIdentities() {
        val game = entity("game", MediaGraphEntityKind.GAME)
        val tv = entity("series", MediaGraphEntityKind.TV_SERIES)
        val character = entity("character", MediaGraphEntityKind.CHARACTER)
        val universe = entity("universe", MediaGraphEntityKind.UNIVERSE)
        val production = entity("studio", MediaGraphEntityKind.PRODUCTION)
        val graph = VerifiedMediaRelationshipGraph.build(listOf(
            fact("a1", game, MediaGraphPredicate.FEATURES_CHARACTER, character),
            fact("a2", tv, MediaGraphPredicate.FEATURES_CHARACTER, character),
            fact("b1", game, MediaGraphPredicate.SET_IN_UNIVERSE, universe),
            fact("b2", tv, MediaGraphPredicate.SET_IN_UNIVERSE, universe),
            fact("c1", game, MediaGraphPredicate.PRODUCED_BY, production),
            fact("c2", tv, MediaGraphPredicate.PRODUCED_BY, production)
        ), setOf("recorded"))
        val matches = graph.related(game, setOf(game, tv)).single().explanations
        assertEquals(setOf(MediaGraphRelation.SAME_CHARACTER, MediaGraphRelation.SAME_UNIVERSE,
            MediaGraphRelation.SHARED_PRODUCTION), matches.map { it.relation }.toSet())
    }

    @Test fun inferredOrUnknownSourceAndGenericTopicNeverProduceSuggestions() {
        val film = entity("stealth", MediaGraphEntityKind.FILM)
        val other = entity("her", MediaGraphEntityKind.FILM)
        val ai = entity("artificial-intelligence", MediaGraphEntityKind.TOPIC)
        val claims = listOf(
            fact("a", film, MediaGraphPredicate.ABOUT_TOPIC, ai, specificTopic = false),
            fact("b", other, MediaGraphPredicate.ABOUT_TOPIC, ai, specificTopic = false),
            fact("c", film, MediaGraphPredicate.WRITTEN_BY, entity("writer", MediaGraphEntityKind.PERSON),
                status = MediaGraphClaimState.INFERRED),
            fact("d", other, MediaGraphPredicate.WRITTEN_BY, entity("writer", MediaGraphEntityKind.PERSON),
                provider = "unapproved")
        )
        assertTrue(VerifiedMediaRelationshipGraph.build(claims, setOf("recorded"))
            .related(film, setOf(film, other)).isEmpty())
        val specific = claims.take(2).map { it.copy(specificTopic = true) }
        assertEquals(MediaGraphRelation.SHARED_TOPIC,
            VerifiedMediaRelationshipGraph.build(specific, setOf("recorded"))
                .related(film, setOf(film, other)).single().strongest)
    }

    @Test fun sameTitleAndDifferentTypedIdsNeverConnectAndVisibilityIsExplicit() {
        val film = MediaGraphEntity("tmdb", MediaGraphEntityKind.FILM, "42")
        val book = MediaGraphEntity("ol", MediaGraphEntityKind.BOOK_WORK, "42")
        assertTrue(VerifiedMediaRelationshipGraph.build(emptyList(), setOf("tmdb"))
            .related(film, setOf(film, book)).isEmpty())
        val author = entity("a", MediaGraphEntityKind.PERSON)
        val graph = VerifiedMediaRelationshipGraph.build(listOf(
            fact("author1", film, MediaGraphPredicate.WRITTEN_BY, author),
            fact("author2", book, MediaGraphPredicate.WRITTEN_BY, author)
        ), setOf("recorded"))
        assertTrue(graph.related(film, setOf(book)).isEmpty())
        assertTrue(graph.related(film, setOf(film)).isEmpty())
        assertEquals(1, graph.related(film, setOf(film, book)).size)
    }

    @Test fun retractionDisputeAndEqualRevisionDisagreementFailClosed() {
        val film = entity("film", MediaGraphEntityKind.FILM)
        val book = entity("book", MediaGraphEntityKind.BOOK_WORK)
        val claim = fact("adapt", film, MediaGraphPredicate.ADAPTED_FROM, book)
        val visible = setOf(film, book)
        fun links(facts: List<MediaGraphClaim>) = VerifiedMediaRelationshipGraph.build(facts,setOf("recorded"))
            .related(film,visible)
        assertEquals(1, links(listOf(claim)).size)
        assertTrue(links(listOf(claim,claim.copy(revision=2, state=MediaGraphClaimState.RETRACTED))).isEmpty())
        assertTrue(links(listOf(claim,claim.copy(revision=2, state=MediaGraphClaimState.DISPUTED))).isEmpty())
        assertTrue(links(listOf(claim,claim.copy(state=MediaGraphClaimState.INFERRED))).isEmpty())
        assertTrue(links(listOf(claim, claim.copy(fieldKey="other", state=MediaGraphClaimState.DISPUTED))).isEmpty())
    }

    @Test fun sourceWithdrawalPurgesDerivedConnectionsWithoutTouchingTransfers() {
        val movie = entity("movie", MediaGraphEntityKind.FILM)
        val album = entity("album", MediaGraphEntityKind.ALBUM)
        val person = entity("person", MediaGraphEntityKind.PERSON)
        val claims = listOf(
            fact("film", movie, MediaGraphPredicate.ABOUT_PERSON, person,provider="provider-a"),
            fact("album", album, MediaGraphPredicate.PERFORMED_BY, person,provider="provider-b")
        )
        val visible=setOf(movie, album)
        assertEquals(1,VerifiedMediaRelationshipGraph.build(claims,setOf("provider-a","provider-b"))
            .related(movie,visible).size)
        assertTrue(VerifiedMediaRelationshipGraph.build(claims,setOf("provider-a"))
            .related(movie,visible).isEmpty())
    }

    @Test fun invalidEndpointTypesAndUnqualifiedIdentifiersRejected() {
        val movie = entity("film", MediaGraphEntityKind.FILM)
        val book = entity("book", MediaGraphEntityKind.BOOK_WORK)
        assertThrows(IllegalArgumentException::class.java) {
            fact("writer", movie, MediaGraphPredicate.WRITTEN_BY, book)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fact("bogus-adaptation", movie, MediaGraphPredicate.ADAPTED_FROM,
                entity("person", MediaGraphEntityKind.PERSON))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fact("bogus-sequel", entity("actor", MediaGraphEntityKind.PERSON),
                MediaGraphPredicate.SEQUEL_TO, movie)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaGraphEntity("TMDB", MediaGraphEntityKind.FILM, "1")
        }
    }

    @Test fun providerRecordKeysMustNotContainPathsOrQueryCredentials() {
        val movie = entity("movie", MediaGraphEntityKind.FILM)
        val book = entity("book", MediaGraphEntityKind.BOOK_WORK)
        assertThrows(IllegalArgumentException::class.java) {
            MediaGraphClaim("source", "https://tracker.example/announce?passkey=secret", "id", 1,
                movie, MediaGraphPredicate.ADAPTED_FROM, book, MediaGraphClaimState.VERIFIED)
        }
        val safe = MediaGraphClaim("source", "episode:42", "crew[0].person", 1,
            movie, MediaGraphPredicate.ADAPTED_FROM, book, MediaGraphClaimState.VERIFIED)
        assertEquals("episode:42", safe.recordKey)
    }

    @Test fun offlinePureQueryHasBoundedLimitAndNoUnlimitedHops() {
        val movie = entity("m",MediaGraphEntityKind.FILM)
        val person = entity("person",MediaGraphEntityKind.PERSON)
        val book = entity("book",MediaGraphEntityKind.BOOK_WORK)
        val other = entity("other",MediaGraphEntityKind.FILM)
        val graph = VerifiedMediaRelationshipGraph.build(listOf(
            fact("actor",movie,MediaGraphPredicate.ACTED_BY,person),
            fact("author",book,MediaGraphPredicate.WRITTEN_BY,person),
            fact("adapt",other,MediaGraphPredicate.ADAPTED_FROM,book)
        ),setOf("recorded"))
        assertTrue(graph.related(movie,setOf(movie,book,other)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { graph.related(movie,setOf(movie,book,other),0) }
    }
}
