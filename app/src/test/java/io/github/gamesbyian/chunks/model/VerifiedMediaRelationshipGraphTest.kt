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


    @Test fun corroboratingProvidersRetainAllEvidenceWithoutCrossProductDuplicates() {
        val episode = entity("ep", MediaGraphEntityKind.TV_EPISODE)
        val issue = entity("issue", MediaGraphEntityKind.COMIC_ISSUE)
        val author = entity("author", MediaGraphEntityKind.PERSON)
        val facts = listOf(
            fact("episodeWriter", episode, MediaGraphPredicate.WRITTEN_BY, author, provider="tvmaze"),
            fact("issueWriter", issue, MediaGraphPredicate.WRITTEN_BY, author, provider="gcd"),
            fact("episodeWriter", episode, MediaGraphPredicate.WRITTEN_BY, author, provider="wikidata"),
            fact("issueWriter", issue, MediaGraphPredicate.WRITTEN_BY, author, provider="wikidata")
        )
        val visible = setOf(episode, issue)
        fun evidence(approved: Set<String>, sources: List<MediaGraphClaim> = facts): List<MediaGraphClaim> =
            VerifiedMediaRelationshipGraph.build(sources, approved)
                .related(episode, visible).single().explanations.single().evidence
        val all = evidence(setOf("tvmaze", "gcd", "wikidata"))
        assertEquals(4, all.size)
        assertEquals(all, evidence(setOf("tvmaze", "gcd", "wikidata"), facts.reversed()))
        assertEquals(4, all.distinct().size)
        // Removing one of two independent corroborators must not leak withdrawn evidence.
        val withoutWiki = evidence(setOf("tvmaze", "gcd"))
        assertEquals(setOf("tvmaze", "gcd"), withoutWiki.map { it.provider }.toSet())
        assertEquals(2, withoutWiki.size)
        assertTrue(VerifiedMediaRelationshipGraph.build(facts, setOf("tvmaze"))
            .related(episode, visible).isEmpty())
    }

    @Test fun conflictingEditionParentsQuarantineOnlyThatEditionIdentity() {
        val edition = entity("edition", MediaGraphEntityKind.BOOK_EDITION)
        val first = entity("first-work", MediaGraphEntityKind.BOOK_WORK)
        val second = entity("second-work", MediaGraphEntityKind.BOOK_WORK)
        val a = fact("parent", edition, MediaGraphPredicate.EDITION_OF, first, provider="catalog-a")
        val b = fact("parent", edition, MediaGraphPredicate.EDITION_OF, second, provider="catalog-b")
        val visible = setOf(edition, first, second)
        fun parents(facts: List<MediaGraphClaim>, allowed: Set<String>) =
            VerifiedMediaRelationshipGraph.build(facts, allowed).related(edition, visible)
        assertEquals(first, parents(listOf(a, b), setOf("catalog-a")).single().entity)
        assertTrue(parents(listOf(a, b), setOf("catalog-a", "catalog-b")).isEmpty())
        // A later edition correction supersedes the earlier claim from the same source field.
        assertEquals(second, parents(listOf(a, b.copy(provider="catalog-a", revision=2)),
            setOf("catalog-a")).single().entity)
        // Explicit, consent-approved user correction resolves catalog conflict in favor
        // of its chosen parent, without silently retaining the rejected catalog lineage.
        val corrected = b.copy(provider="user", state=MediaGraphClaimState.USER_CONFIRMED)
        val chosen = parents(listOf(a, b, corrected), setOf("catalog-a", "catalog-b", "user"))
        assertEquals(listOf(second), chosen.map { it.entity })
        assertEquals(setOf("catalog-b", "user"),
            chosen.single().explanations.single().evidence.map { it.provider }.toSet())
        assertTrue(parents(listOf(a, b, corrected,
            a.copy(provider="user", recordKey="another-correction", state=MediaGraphClaimState.USER_CONFIRMED)),
            setOf("catalog-a", "catalog-b", "user")).isEmpty())
        // Different credits on different field keys are legitimately multivalued.
        val personOne = entity("one", MediaGraphEntityKind.PERSON)
        val personTwo = entity("two", MediaGraphEntityKind.PERSON)
        val comic = entity("comic", MediaGraphEntityKind.COMIC_ISSUE)
        val credits = listOf(
            fact("writer1", first, MediaGraphPredicate.WRITTEN_BY, personOne),
            fact("writer2", first, MediaGraphPredicate.WRITTEN_BY, personTwo),
            fact("comicWriter", comic, MediaGraphPredicate.WRITTEN_BY, personTwo)
        )
        assertEquals(MediaGraphRelation.SHARED_WRITER,
            VerifiedMediaRelationshipGraph.build(credits, setOf("recorded"))
                .related(first, setOf(first, comic)).single().strongest)
    }

    @Test fun independentProviderRetractionDoesNotEraseOtherProvidersVerifiedFact() {
        val film = entity("film", MediaGraphEntityKind.FILM)
        val work = entity("work", MediaGraphEntityKind.BOOK_WORK)
        val a = fact("adapt", film, MediaGraphPredicate.ADAPTED_FROM, work, provider="source-a")
        val b = fact("adapt", film, MediaGraphPredicate.ADAPTED_FROM, work, provider="source-b")
        val visible = setOf(film, work)
        fun result(facts: List<MediaGraphClaim>) = VerifiedMediaRelationshipGraph.build(facts,
            setOf("source-a", "source-b")).related(film, visible)
        assertEquals(2, result(listOf(a,b)).single().explanations.single().evidence.size)
        val retracted = a.copy(revision=2, state=MediaGraphClaimState.RETRACTED)
        assertEquals(listOf(b), result(listOf(a, b, retracted)).single().explanations.single().evidence)
        // An explicit dispute of the same edge is deliberately stronger than retraction.
        assertTrue(result(listOf(a,b,a.copy(revision=2, state=MediaGraphClaimState.DISPUTED))).isEmpty())
    }

    @Test fun genericHighDegreeWriterIsNotExpandedToEveryTransfer() {
        val writer = entity("large-cast", MediaGraphEntityKind.PERSON)
        val works = (0..257).map { entity("work$it", MediaGraphEntityKind.COMIC_ISSUE) }
        val claims = works.mapIndexed { index, work ->
            fact("credit$index", work, MediaGraphPredicate.WRITTEN_BY, writer)
        }
        val graph = VerifiedMediaRelationshipGraph.build(claims, setOf("recorded"))
        assertTrue(graph.related(works.first(), works.toSet(), 100).isEmpty())
    }

    @Test fun corroboratingSourceStatementsDoNotTripDistinctWorkFanoutCap() {
        val writer = entity("widely-credited", MediaGraphEntityKind.PERSON)
        val works = (0 until 200).map { entity("work$it", MediaGraphEntityKind.COMIC_ISSUE) }
        val claims = works.flatMapIndexed { index, work ->
            listOf("source-a", "source-b").map { provider ->
                fact("credit$index", work, MediaGraphPredicate.WRITTEN_BY, writer, provider=provider)
            }
        }
        val related = VerifiedMediaRelationshipGraph.build(claims, setOf("source-a", "source-b"))
            .related(works.first(), setOf(works.first(), works.last()))
        assertEquals(1, related.size)
        assertEquals(MediaGraphRelation.SHARED_WRITER, related.single().strongest)
        assertEquals(4, related.single().explanations.single().evidence.size)
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
