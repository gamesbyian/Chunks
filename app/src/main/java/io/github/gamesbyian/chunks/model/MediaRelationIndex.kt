package io.github.gamesbyian.chunks.model

/** Offline-only candidates over exact existing transfer keys. Never authorize file/RPC mutations. */
enum class MediaCreditRole { WRITER, ACTOR, PERFORMER, BIOGRAPHICAL_SUBJECT }

data class MediaPersonCredit(val personId: String, val role: MediaCreditRole, val displayName: String) {
    init {
        require(personId.contains(':') && personId.substringAfter(':').isNotBlank())
        require(displayName.isNotBlank())
    }
}

/** One identified episode, issue, recording or file within a torrent, with verified source IDs. */
data class MediaContentMember(
    val memberId: String,
    val workId: String? = null,
    val seriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val credits: Set<MediaPersonCredit> = emptySet(),
    /** Only high-specificity, verified, namespaced topic IDs belong here. */
    val specificTopics: Set<String> = emptySet()
) {
    init {
        require(memberId.isNotBlank())
        require(workId == null || ':' in workId)
        require(seriesId == null || ':' in seriesId)
        require((season == null) == (episode == null))
        require(season == null || (season >= 0 && episode!! >= 0))
        require(specificTopics.all { ':' in it && it.substringAfter(':').isNotBlank() })
    }
}

data class MediaTransferMembers(val key: UnifiedTransferKey, val members: List<MediaContentMember>) {
    init { require(members.map { it.memberId }.distinct().size == members.size) }
}

enum class MediaRelationKind(val rank: Int) {
    SAME_TORRENT(120),
    SAME_WORK(100), ADJACENT_EPISODE(90), SAME_SERIES(80), SHARED_WRITER(70),
    BIOGRAPHICAL_SUBJECT_AND_ARTIST(65), SAME_ARTIST(55), SHARED_ACTOR(45), SHARED_TOPIC(30)
}

data class MediaRelationEvidence(
    val kind: MediaRelationKind,
    val identifier: String,
    val name: String?,
    val sourceMemberId: String,
    val matchedMemberId: String
)

data class RelatedTransferCandidate(
    val key: UnifiedTransferKey,
    val evidence: List<MediaRelationEvidence>,
    /** Matched members are a proven subset, never a claim about an entire collection. */
    val matchedMembers: Int,
    val observedMembers: Int
) {
    val strongestRelation: MediaRelationKind get() = evidence.first().kind
}

/** Inverted index; a details query only reads postings for terms in the selected transfer. */
class MediaRelationIndex private constructor(
    private val records: Map<UnifiedTransferKey, MediaTransferMembers>,
    private val postings: Map<String, List<IndexedMember>>,
    private val byInfohash: Map<String, List<UnifiedTransferKey>>
) {
    private data class IndexedMember(val key: UnifiedTransferKey, val member: MediaContentMember)

    companion object {
        fun build(transfers: Collection<MediaTransferMembers>): MediaRelationIndex {
            require(transfers.map { it.key }.distinct().size == transfers.size) {
                "Ambiguous duplicate transfer identities"
            }
            val inverted = mutableMapOf<String, MutableList<IndexedMember>>()
            for (transfer in transfers) for (member in transfer.members) {
                val ref = IndexedMember(transfer.key, member)
                for (term in terms(member)) inverted.getOrPut(term) { mutableListOf() }.add(ref)
            }
            return MediaRelationIndex(transfers.associateBy { it.key }, inverted,
                transfers.groupBy { it.key.infoHash }.mapValues { (_, group) -> group.map { it.key } })
        }

        private fun terms(member: MediaContentMember): Set<String> = buildSet {
            member.workId?.let { add("work:" + it) }
            member.seriesId?.let { add("series:" + it) }
            for (credit in member.credits) when (credit.role) {
                MediaCreditRole.WRITER -> add("writer:" + credit.personId)
                MediaCreditRole.ACTOR -> add("actor:" + credit.personId)
                MediaCreditRole.BIOGRAPHICAL_SUBJECT, MediaCreditRole.PERFORMER ->
                    add("subject-artist:" + credit.personId)
            }
            for (topic in member.specificTopics) add("topic:" + topic)
        }
    }

    /**
     * [visible] is supplied by the authorized inventory: never pass LOCKED, revoked or
     * excluded items. Exact scoped identity is preserved; no network calls or full scan.
     */
    fun similar(
        source: UnifiedTransferKey,
        visible: Set<UnifiedTransferKey>,
        sameEnvironmentOnly: Boolean = false,
        limit: Int = 5
    ): List<RelatedTransferCandidate> {
        require(limit in 1..100) { "Use an explicit bounded results window" }
        val sourceRecord = records[source] ?: return emptyList()
        if (source !in visible) return emptyList()
        val hits = mutableMapOf<UnifiedTransferKey, MutableList<MediaRelationEvidence>>()
        // Exact infohash means the same torrent, regardless of media lookup success.
        for (sameHash in byInfohash[source.infoHash].orEmpty()) {
            if (sameHash != source && sameHash in visible &&
                (!sameEnvironmentOnly || sameHash.environment == source.environment)) {
                hits.getOrPut(sameHash) { mutableListOf() }.add(
                    MediaRelationEvidence(MediaRelationKind.SAME_TORRENT, source.infoHash,
                        null, "<transfer>", "<transfer>")
                )
            }
        }
        for (member in sourceRecord.members) {
            for (term in terms(member)) {
                for (candidate in postings[term].orEmpty()) {
                    if (candidate.key == source || candidate.key !in visible ||
                        (sameEnvironmentOnly && candidate.key.environment != source.environment)) continue
                    val evidence = classify(term, member, candidate.member) ?: continue
                    hits.getOrPut(candidate.key) { mutableListOf() }.add(evidence)
                }
            }
        }
        return hits.mapNotNull { (key, allEvidence) ->
            val unique = allEvidence.distinctBy {
                listOf(it.kind.name, it.identifier, it.sourceMemberId, it.matchedMemberId)
            }.sortedWith(compareByDescending<MediaRelationEvidence> { it.kind.rank }
                .thenBy { it.identifier }.thenBy { it.matchedMemberId })
            val count = records[key]?.members?.size ?: return@mapNotNull null
            RelatedTransferCandidate(key, unique, unique.map { it.matchedMemberId }.distinct().size, count)
        }.sortedWith(compareByDescending<RelatedTransferCandidate> { it.strongestRelation.rank }
            .thenByDescending { it.matchedMembers }
            .thenBy { it.key.environment.value }.thenBy { it.key.infoHash }).take(limit)
    }

    private fun classify(
        term: String, source: MediaContentMember, candidate: MediaContentMember
    ): MediaRelationEvidence? {
        val type = term.substringBefore(':')
        val identifier = term.substringAfter(':')
        val relation = when (type) {
            "work" -> MediaRelationKind.SAME_WORK
            "series" -> if (source.season != null && source.season == candidate.season &&
                kotlin.math.abs(source.episode!! - candidate.episode!!) == 1)
                    MediaRelationKind.ADJACENT_EPISODE else MediaRelationKind.SAME_SERIES
            "writer" -> MediaRelationKind.SHARED_WRITER
            "actor" -> MediaRelationKind.SHARED_ACTOR
            "topic" -> MediaRelationKind.SHARED_TOPIC
            "subject-artist" -> {
                val sourceRoles = source.credits.filter { it.personId == identifier }.map { it.role }.toSet()
                val targetRoles = candidate.credits.filter { it.personId == identifier }.map { it.role }.toSet()
                when {
                    (MediaCreditRole.BIOGRAPHICAL_SUBJECT in sourceRoles && MediaCreditRole.PERFORMER in targetRoles) ||
                        (MediaCreditRole.PERFORMER in sourceRoles && MediaCreditRole.BIOGRAPHICAL_SUBJECT in targetRoles) ->
                        MediaRelationKind.BIOGRAPHICAL_SUBJECT_AND_ARTIST
                    MediaCreditRole.PERFORMER in sourceRoles && MediaCreditRole.PERFORMER in targetRoles ->
                        MediaRelationKind.SAME_ARTIST
                    else -> return null
                }
            }
            else -> return null
        }
        val humanName = if (type == "writer" || type == "actor" || type == "subject-artist")
            source.credits.firstOrNull { it.personId == identifier }?.displayName else null
        return MediaRelationEvidence(relation, identifier, humanName, source.memberId, candidate.memberId)
    }
}
