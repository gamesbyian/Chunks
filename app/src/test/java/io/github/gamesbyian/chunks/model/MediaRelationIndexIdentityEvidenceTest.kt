package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRelationIndexIdentityEvidenceTest {
    private fun key(env: String, value: Int) = UnifiedTransferKey.of(
        EnvironmentId(env), value.toString(16).padStart(40, '0')
    )
    private val writer = MediaPersonCredit("wd:Q1351554", MediaCreditRole.WRITER, "Peter David")

    @Test fun malformedIdentityFacetsCannotCreateExactWorkOrContributorMatches() {
        for (bad in listOf("wd:", ":Q123", ":", "tmdb:person:", "wd::Q123",
                "https://metadata.example/person/123", "wd:hello world", "wd:abc\\def",
                "wd:x".padEnd(280, 'a'))) {
            assertThrows(IllegalArgumentException::class.java) {
                MediaContentMember("item", workId=bad)
            }
            assertThrows(IllegalArgumentException::class.java) {
                MediaContentMember("item", seriesId=bad)
            }
            assertThrows(IllegalArgumentException::class.java) {
                MediaContentMember("item", specificTopics=setOf(bad))
            }
            assertThrows(IllegalArgumentException::class.java) {
                MediaPersonCredit(bad, MediaCreditRole.WRITER, "A real name")
            }
        }
        val valid = MediaContentMember("item", workId="tmdb:movie:42",
            seriesId="wd:Q123", specificTopics=setOf("topic:artificial-intelligence"),
            credits=setOf(writer))
        assertEquals("tmdb:movie:42", valid.workId)
        assertEquals("wd:Q1351554", valid.credits.single().personId)
    }

    @Test fun memberEvidenceIsOnlyTheExactVerifiedMatchingSubset() {
        val origin=key("Wood",1); val target=key("Stone",2)
        val members=listOf(
            MediaContentMember("issue331", credits=setOf(writer)),
            MediaContentMember("issue332"),
            MediaContentMember("issue333", credits=setOf(writer)),
            MediaContentMember("issue334")
        )
        val index=MediaRelationIndex.build(listOf(
            MediaTransferMembers(origin,listOf(MediaContentMember("s02e07", credits=setOf(writer)))),
            MediaTransferMembers(target,members)
        ))
        val result=index.similar(origin,setOf(origin,target)).single()
        assertEquals(MediaRelationKind.SHARED_WRITER,result.strongestRelation)
        assertEquals(2,result.matchedMembers)
        assertEquals(4,result.observedMembers)
        assertEquals(listOf("issue331","issue333"),result.matchedMemberIds)
        assertFalse(result.memberEvidenceTruncated)
        assertEquals(1,result.evidence.size) // one concise explanation
        assertTrue(index.similar(origin,setOf(origin)).isEmpty()) // invisible target
    }

    @Test fun veryLargeCollectionMaintainsBoundedDeterministicEvidenceAndTrueCoverage() {
        val origin=key("Wood",4); val target=key("Stone",5)
        val issues=(0 until 300).map { index ->
            MediaContentMember("issue" + index.toString().padStart(3,'0'), credits=setOf(writer))
        } + MediaContentMember("unmatched", workId="wd:Q99999")
        val originRecord=MediaTransferMembers(origin,listOf(MediaContentMember("ep",credits=setOf(writer))))
        fun query(all: List<MediaContentMember>) = MediaRelationIndex.build(listOf(
            originRecord,MediaTransferMembers(target,all)
        )).similar(origin,setOf(origin,target)).single()
        val forward=query(issues)
        val reversed=query(issues.reversed())
        assertEquals(300,forward.matchedMembers)
        assertEquals(301,forward.observedMembers)
        assertEquals(256,forward.matchedMemberIds.size)
        assertTrue(forward.memberEvidenceTruncated)
        assertEquals("issue000",forward.matchedMemberIds.first())
        assertEquals("issue255",forward.matchedMemberIds.last())
        assertEquals(forward.matchedMemberIds,reversed.matchedMemberIds)
        assertEquals(1,forward.evidence.size)
    }

    @Test fun exactTorrentWithoutMetadataDoesNotInventMatchingMemberIds() {
        val origin=key("Wood",44); val target=key("Stone",44)
        val result=MediaRelationIndex.build(listOf(
            MediaTransferMembers(origin,emptyList()),
            MediaTransferMembers(target,emptyList())
        )).similar(origin,setOf(origin,target)).single()
        assertEquals(MediaRelationKind.SAME_TORRENT,result.strongestRelation)
        assertTrue(result.matchedMemberIds.isEmpty())
        assertFalse(result.memberEvidenceTruncated)
    }
}
