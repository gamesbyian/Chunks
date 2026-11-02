package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class MediaRelationIndexTest {
    private fun key(environment: String, number: Int) = UnifiedTransferKey.of(
        EnvironmentId(environment), number.toString(16).padStart(40, '0')
    )
    private fun member(
        id: String, work: String? = null, series: String? = null,
        season: Int? = null, episode: Int? = null,
        credits: Set<MediaPersonCredit> = emptySet(), topics: Set<String> = emptySet()
    ) = MediaContentMember(id, work, series, season, episode, credits, topics)
    private fun credit(id: String, role: MediaCreditRole, label: String) =
        MediaPersonCredit(id, role, label)

    @Test fun episodeWriterLinksOnlyToVerifiedComicIssueSubset() {
        val ep = key("Wood", 1); val collection = key("Stone", 2)
        val david = credit("wd:Q1351554", MediaCreditRole.WRITER, "Peter David")
        val index = MediaRelationIndex.build(listOf(
            MediaTransferMembers(ep, listOf(member("s02e07", credits = setOf(david)))),
            MediaTransferMembers(collection, listOf(
                member("hulk331", credits = setOf(david)),
                member("hulk332"), member("hulk333")
            ))
        ))
        val related = index.similar(ep, setOf(ep, collection)).single()
        assertEquals(collection, related.key)
        assertEquals(MediaRelationKind.SHARED_WRITER, related.strongestRelation)
        assertEquals("Peter David", related.evidence[0].name)
        assertEquals(1, related.matchedMembers)
        assertEquals(3, related.observedMembers)
        assertEquals("hulk331", related.evidence[0].matchedMemberId)
    }

    @Test fun identicalNamesAndUnresolvedPersonIDsCannotCreateMatches() {
        val e = key("Wood", 1); val c = key("Stone", 2)
        val index = MediaRelationIndex.build(listOf(
            MediaTransferMembers(e, listOf(member("same-title", credits = setOf(credit(
                "tmdb:person:156223", MediaCreditRole.WRITER, "Peter David"))))),
            MediaTransferMembers(c, listOf(member("same-title", credits = setOf(credit(
                "gcd:creator:10447", MediaCreditRole.WRITER, "Peter David")))))
        ))
        assertTrue(index.similar(e, setOf(e,c)).isEmpty())
    }

    @Test fun sameWorkAcrossEnvironmentsAndVisibilityGate() {
        val wood = key("Wood", 1); val stone = key("Stone", 1); val phone = key("Phone", 2)
        val index = MediaRelationIndex.build(listOf(
            MediaTransferMembers(wood, listOf(member("feature", work = "tmdb:movie:1"))),
            MediaTransferMembers(stone, listOf(member("feature", work = "tmdb:movie:1"))),
            MediaTransferMembers(phone, listOf(member("feature", work = "tmdb:movie:1")))
        ))
        assertEquals(2, index.similar(wood, setOf(wood, stone, phone)).size)
        assertEquals(0, index.similar(wood, setOf(wood, stone, phone), sameEnvironmentOnly = true).size)
        assertEquals(listOf(stone), index.similar(wood, setOf(wood, stone)).map { it.key })
        assertTrue(index.similar(wood, setOf(stone)).isEmpty()) // privacy-locked source
        assertEquals(MediaRelationKind.SAME_TORRENT,
            index.similar(wood, setOf(wood, stone)).single().strongestRelation)
    }

    @Test fun adjacentEpisodeRanksAboveNonAdjacentEpisode() {
        val origin = key("Wood",1); val adjacent = key("Stone",2); val distant = key("Phone",3)
        val s = "tmdb:tv:123"
        val index = MediaRelationIndex.build(listOf(
            MediaTransferMembers(origin,listOf(member("s02e07",series=s,season=2,episode=7))),
            MediaTransferMembers(adjacent,listOf(member("s02e08",series=s,season=2,episode=8))),
            MediaTransferMembers(distant,listOf(member("s03e11",series=s,season=3,episode=11)))
        ))
        val results = index.similar(origin,setOf(origin,adjacent,distant))
        assertEquals(listOf(adjacent,distant),results.map { it.key })
        assertEquals(MediaRelationKind.ADJACENT_EPISODE,results.first().strongestRelation)
        assertEquals(MediaRelationKind.SAME_SERIES,results.last().strongestRelation)
    }

    @Test fun biographiesAndArtistsWorkInBothDirections() {
        val film=key("Wood",1); val flacs=key("Stone",2)
        val cash = "wd:Q18965"
        val index=MediaRelationIndex.build(listOf(
            MediaTransferMembers(film,listOf(member("walk-the-line",credits=setOf(
                credit(cash,MediaCreditRole.BIOGRAPHICAL_SUBJECT,"Johnny Cash"))))),
            MediaTransferMembers(flacs,listOf(member("recording",credits=setOf(
                credit(cash,MediaCreditRole.PERFORMER,"Johnny Cash")))))
        ))
        assertEquals(MediaRelationKind.BIOGRAPHICAL_SUBJECT_AND_ARTIST,
            index.similar(film,setOf(film,flacs)).single().strongestRelation)
        assertEquals(MediaRelationKind.BIOGRAPHICAL_SUBJECT_AND_ARTIST,
            index.similar(flacs,setOf(film,flacs)).single().strongestRelation)
    }

    @Test fun sharedActorAndSpecificTopicAreIndependentEvidence() {
        val ray=key("Wood",1); val stealth=key("Stone",2); val her=key("Phone",3)
        val foxx=credit("wd:Q20566",MediaCreditRole.ACTOR,"Jamie Foxx")
        val index=MediaRelationIndex.build(listOf(
            MediaTransferMembers(ray,listOf(member("ray",credits=setOf(foxx),topics=setOf("topic:music-biography")))),
            MediaTransferMembers(stealth,listOf(member("stealth",credits=setOf(foxx),
                topics=setOf("topic:artificial-intelligence")))),
            MediaTransferMembers(her,listOf(member("her",topics=setOf("topic:artificial-intelligence"))))
        ))
        val rayHits=index.similar(ray,setOf(ray,stealth,her))
        assertEquals(1,rayHits.size)
        assertEquals(MediaRelationKind.SHARED_ACTOR,rayHits.single().strongestRelation)
        val stealthHits=index.similar(stealth,setOf(ray,stealth,her))
        assertEquals(2,stealthHits.size)
        assertEquals(MediaRelationKind.SHARED_TOPIC,stealthHits.last().strongestRelation)
    }

    @Test fun topicOnlyOverlapsWhenIdenticalVerifiedTagAndNeverPlainTitle() {
        val a=key("Wood",1); val b=key("Wood",2); val c=key("Wood",3)
        val index=MediaRelationIndex.build(listOf(
            MediaTransferMembers(a,listOf(member("Carrie",topics=setOf("topic:artificial-intelligence")))),
            MediaTransferMembers(b,listOf(member("Carrie",topics=setOf("topic:ordinary-music")))),
            MediaTransferMembers(c,listOf(member("Carrie",topics=setOf("topic:artificial-intelligence"))))
        ))
        assertEquals(listOf(c),index.similar(a,setOf(a,b,c)).map { it.key })
    }

    @Test fun exactTorrentAcrossEnvironmentsMatchesWithoutMediaMetadata() {
        val wood=key("Wood",99); val stone=key("Stone",99); val unrelated=key("Phone",100)
        val index=MediaRelationIndex.build(listOf(
            MediaTransferMembers(wood,emptyList()),
            MediaTransferMembers(stone,emptyList()),
            MediaTransferMembers(unrelated,emptyList())
        ))
        assertEquals(listOf(stone),index.similar(wood,setOf(wood,stone,unrelated)).map { it.key })
        assertEquals(MediaRelationKind.SAME_TORRENT,
            index.similar(wood,setOf(wood,stone)).single().strongestRelation)
        assertTrue(index.similar(wood,setOf(wood,stone),sameEnvironmentOnly=true).isEmpty())
        assertTrue(index.similar(wood,setOf(wood)).isEmpty())
    }

    @Test fun invalidIdentifiersOrDuplicateMembersFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { member("a",work="uncategorized title") }
        assertThrows(IllegalArgumentException::class.java) { member("a",season=1) }
        assertThrows(IllegalArgumentException::class.java) {
            credit("Peter David",MediaCreditRole.WRITER,"Peter David")
        }
        val k=key("Wood",1)
        assertThrows(IllegalArgumentException::class.java) {
            MediaTransferMembers(k,listOf(member("x"),member("x")))
        }
        val rec=MediaTransferMembers(k,listOf(member("x")))
        assertThrows(IllegalArgumentException::class.java) { MediaRelationIndex.build(listOf(rec,rec)) }
        assertThrows(IllegalArgumentException::class.java) {
            MediaRelationIndex.build(listOf(rec)).similar(k,setOf(k),limit=0)
        }
    }
}
