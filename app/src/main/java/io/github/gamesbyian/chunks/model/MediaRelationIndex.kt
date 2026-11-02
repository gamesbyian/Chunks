package io.github.gamesbyian.chunks.model

/** Offline-only candidates over exact existing transfer keys. Never authorize file/RPC mutations. */
enum class MediaCreditRole { WRITER, ACTOR, PERFORMER, BIOGRAPHICAL_SUBJECT }

/** Only normalized provider-qualified identifiers may become exact identity facets. */
private val mediaFacetId = Regex("[a-z][a-z0-9-]*:(?:[A-Za-z0-9][A-Za-z0-9_.-]*:)*[A-Za-z0-9][A-Za-z0-9_.-]*")
private fun validMediaFacetId(value: String): Boolean =
    value.length in 3..256 && mediaFacetId.matches(value)

data class MediaPersonCredit(val personId: String, val role: MediaCreditRole, val displayName: String) {
    init {
        require(validMediaFacetId(personId)) { "Person identity requires a qualified, nonblank provider ID" }
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
        require(workId == null || validMediaFacetId(workId))
        require(seriesId == null || validMediaFacetId(seriesId))
        require((season == null) == (episode == null))
        require(season == null || (season >= 0 && episode!! >= 0))
        require(specificTopics.all(::validMediaFacetId))
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
    val observedMembers: Int,
    /** Stable IDs of verified matched target members; opaque internal keys, not display filenames. */
    val matchedMemberIds: List<String> = emptyList(),
    /** True when member-level evidence exceeds the explicit bounded preview. */
    val memberEvidenceTruncated: Boolean = false
) {
    val strongestRelation: MediaRelationKind get() = evidence.first().kind
}

/** Inverted index; a details query only reads postings for terms in the selected transfer. */
/** Indexed on source facet, then by exact transfer key; never scan the entire catalog for a detail. */
class MediaRelationIndex private constructor(
    private val records: Map<UnifiedTransferKey, MediaTransferMembers>,
    private val postings: Map<String, Map<UnifiedTransferKey, List<MediaContentMember>>>,
    private val byInfohash: Map<String, List<UnifiedTransferKey>>
) {
    companion object {
        /** Detail previews are bounded even for multi-thousand-member collections. */
        private const val MAX_MEMBER_EVIDENCE_IDS = 256

        fun build(transfers: Collection<MediaTransferMembers>): MediaRelationIndex {
            require(transfers.map { it.key }.distinct().size == transfers.size) {
                "Ambiguous duplicate transfer identities"
            }
            val inverted = mutableMapOf<String, MutableMap<UnifiedTransferKey, MutableList<MediaContentMember>>>()
            for (transfer in transfers) for (member in transfer.members) {
                for (term in terms(member)) inverted.getOrPut(term) { mutableMapOf() }
                    .getOrPut(transfer.key) { mutableListOf() }.add(member)
            }
            val postings = inverted.mapValues { (_, byTransfer) ->
                byTransfer.mapValues { (_, members) -> members.toList() }
            }
            return MediaRelationIndex(transfers.associateBy { it.key }, postings,
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

    private class Matches {
        val evidence = mutableListOf<MediaRelationEvidence>()
        val memberIds = mutableSetOf<String>()
    }

    /** The caller supplies privacy/lock-authorized exact keys; no network or engine mutation. */
    fun similar(
        source: UnifiedTransferKey,
        visible: Set<UnifiedTransferKey>,
        sameEnvironmentOnly: Boolean = false,
        limit: Int = 5
    ): List<RelatedTransferCandidate> {
        require(limit in 1..100) { "Use an explicit bounded results window" }
        val sourceRecord = records[source] ?: return emptyList()
        if (source !in visible) return emptyList()
        val hits = mutableMapOf<UnifiedTransferKey, Matches>()
        fun allowed(target: UnifiedTransferKey): Boolean = target != source && target in visible &&
            (!sameEnvironmentOnly || target.environment == source.environment)
        for (target in byInfohash[source.infoHash].orEmpty()) if (allowed(target)) {
            hits.getOrPut(target) { Matches() }.evidence.add(MediaRelationEvidence(
                MediaRelationKind.SAME_TORRENT, source.infoHash, null, "<transfer>", "<transfer>"))
        }
        // One visit per distinct facet prevents repeated 10x10 member joins for albums or collections.
        val sourceTerms = mutableMapOf<String, MutableList<MediaContentMember>>()
        for (member in sourceRecord.members) for (term in terms(member))
            sourceTerms.getOrPut(term) { mutableListOf() }.add(member)
        for ((term, sources) in sourceTerms) for ((target, members) in postings[term].orEmpty()) {
            if (!allowed(target)) continue
            val kind = term.substringBefore(':')
            val match = hits.getOrPut(target) { Matches() }
            when (kind) {
                "subject-artist" -> {
                    var best: MediaRelationEvidence? = null
                    for (candidate in members) {
                        var candidateBest: MediaRelationEvidence? = null
                        for (origin in sources) {
                            val evidence = classify(term, origin, candidate) ?: continue
                            if (candidateBest == null || evidence.kind.rank > candidateBest.kind.rank)
                                candidateBest = evidence
                        }
                        if (candidateBest != null) {
                            match.memberIds.add(candidate.memberId)
                            if (best == null || candidateBest.kind.rank > best.kind.rank) best = candidateBest
                        }
                    }
                    if (best != null) match.evidence.add(best)
                }
                "series" -> {
                    var best = classify(term, sources.first(), members.first())!!
                    search@ for (origin in sources) for (candidate in members) {
                        if (origin.season != null && origin.season == candidate.season &&
                            origin.episode != null && candidate.episode != null &&
                            kotlin.math.abs(origin.episode - candidate.episode) == 1) {
                            best = classify(term, origin, candidate)!!
                            break@search
                        }
                    }
                    match.evidence.add(best)
                    for (candidate in members) match.memberIds.add(candidate.memberId)
                }
                else -> {
                    val evidence = classify(term, sources.first(), members.first()) ?: continue
                    match.evidence.add(evidence)
                    for (candidate in members) match.memberIds.add(candidate.memberId)
                }
            }
        }
        return hits.mapNotNull { (key, match) ->
            if (match.evidence.isEmpty()) return@mapNotNull null
            val evidence = match.evidence.distinctBy { it.kind to it.identifier }
                .sortedWith(compareByDescending<MediaRelationEvidence> { it.kind.rank }
                    .thenBy { it.identifier }.thenBy { it.matchedMemberId })
            // An explanation is deliberately collapsed per relation, but the exact
            // matched issue/episode identities must remain auditable and bounded.
            val matchedIds = match.memberIds.sorted()
            RelatedTransferCandidate(key, evidence, matchedIds.size,
                records[key]?.members?.size ?: return@mapNotNull null,
                matchedIds.take(MAX_MEMBER_EVIDENCE_IDS),
                matchedIds.size > MAX_MEMBER_EVIDENCE_IDS)
        }.sortedWith(compareByDescending<RelatedTransferCandidate> { it.strongestRelation.rank }
            .thenByDescending { it.matchedMembers }
            .thenBy { it.key.environment.value }.thenBy { it.key.infoHash }).take(limit)
    }

    private fun classify(term: String, source: MediaContentMember,
                         candidate: MediaContentMember): MediaRelationEvidence? {
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
