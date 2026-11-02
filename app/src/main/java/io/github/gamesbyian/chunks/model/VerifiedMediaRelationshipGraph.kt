package io.github.gamesbyian.chunks.model

/** Provider-neutral entity identity. The kind and namespace prevent cross-catalog numeric collisions. */
enum class MediaGraphEntityKind {
    FILM, TV_SERIES, TV_EPISODE, BOOK_WORK, BOOK_EDITION, COMIC_SERIES, COMIC_ISSUE,
    ALBUM, RECORDING, GAME, GAME_EDITION, SOFTWARE, PERIODICAL, PERSON, CHARACTER,
    SERIES, FRANCHISE, UNIVERSE, PRODUCTION, TOPIC, OTHER_WORK
}

data class MediaGraphEntity(val namespace: String, val kind: MediaGraphEntityKind, val id: String) {
    init {
        require(namespace.matches(Regex("[a-z][a-z0-9-]*")))
        require(id.isNotBlank() && id.none { it.isISOControl() })
    }
}

/** A directional fact. The graph never rewrites a relationship into identity equality. */
enum class MediaGraphPredicate {
    ADAPTED_FROM, REMAKE_OF, SEQUEL_TO, EDITION_OF, PART_OF_SERIES,
    WRITTEN_BY, CREATED_BY, ACTED_BY, PERFORMED_BY, ABOUT_PERSON,
    FEATURES_CHARACTER, SET_IN_UNIVERSE, PRODUCED_BY, ABOUT_TOPIC
}

enum class MediaGraphClaimState { VERIFIED, USER_CONFIRMED, INFERRED, DISPUTED, RETRACTED }

/**
 * One independently addressable field assertion. Repeated imports use the same provider/record/field
 * key with a higher revision, including when the original field is removed or retracted.
 * raw record keys must be sanitized by adapters: no names, credentials, passkeys or remote paths.
 */
data class MediaGraphClaim(
    val provider: String,
    val recordKey: String,
    val fieldKey: String,
    val revision: Long,
    val subject: MediaGraphEntity,
    val predicate: MediaGraphPredicate,
    val target: MediaGraphEntity,
    val state: MediaGraphClaimState,
    /** Only a curated, specific subject may support a default related-transfer result. */
    val specificTopic: Boolean = false
) {
    init {
        require(provider.matches(Regex("[a-z][a-z0-9-]*")))
        // Opaque sanitized locator, never a URL, file path, query string or tracker/passkey.
        val safeLocator = Regex("""[A-Za-z0-9][A-Za-z0-9_.:$\[\]-]{0,255}""")
        require(recordKey.matches(safeLocator) && fieldKey.matches(safeLocator))
        require(revision >= 0)
        require(subject != target)
        require(predicate != MediaGraphPredicate.ABOUT_TOPIC || target.kind == MediaGraphEntityKind.TOPIC)
        require(!specificTopic || predicate == MediaGraphPredicate.ABOUT_TOPIC)
        when (predicate) {
            MediaGraphPredicate.WRITTEN_BY, MediaGraphPredicate.CREATED_BY,
            MediaGraphPredicate.ACTED_BY, MediaGraphPredicate.PERFORMED_BY,
            MediaGraphPredicate.ABOUT_PERSON -> require(target.kind == MediaGraphEntityKind.PERSON)
            MediaGraphPredicate.FEATURES_CHARACTER -> require(target.kind == MediaGraphEntityKind.CHARACTER)
            MediaGraphPredicate.SET_IN_UNIVERSE -> require(target.kind == MediaGraphEntityKind.UNIVERSE)
            MediaGraphPredicate.PRODUCED_BY -> require(target.kind == MediaGraphEntityKind.PRODUCTION)
            MediaGraphPredicate.PART_OF_SERIES -> require(target.kind == MediaGraphEntityKind.SERIES || target.kind == MediaGraphEntityKind.FRANCHISE || target.kind == MediaGraphEntityKind.TV_SERIES || target.kind == MediaGraphEntityKind.COMIC_SERIES)
            else -> Unit
        }
    }
}

enum class MediaGraphRelation(val rank: Int) {
    DIRECT_ADAPTATION(100), DIRECT_REMAKE(95), DIRECT_SEQUEL(94), DIRECT_EDITION(92),
    SAME_SOURCE_WORK(90), SAME_UNIVERSE(80), SAME_CHARACTER(75), SAME_SERIES(72),
    SHARED_WRITER(70), SUBJECT_AND_PERFORMER(66), SHARED_CREATOR(64),
    SHARED_PRODUCTION(60), SHARED_PERFORMER(55), SHARED_ACTOR(48), SHARED_TOPIC(25)
}

/** Exact provenance for the two assertions behind an explanation, not a fabricated certainty %. */
data class MediaGraphExplanation(
    val relation: MediaGraphRelation,
    val via: MediaGraphEntity?,
    val evidence: List<MediaGraphClaim>,
    /** Directional direct facts preserve the original predicate; reverse matches are explicitly marked. */
    val reverseOfDirectFact: Boolean = false
)

data class MediaGraphRelatedEntity(
    val entity: MediaGraphEntity,
    val explanations: List<MediaGraphExplanation>
) {
    val strongest: MediaGraphRelation get() = explanations.first().relation
}

/**
 * Immutable, local-only relationship query over approved, verified facts. This is a standalone
 * derived-data component, not a downloader, provider client or transfer identity resolver.
 * The caller supplies only consent-authorized providers and visible entities per query.
 */
class VerifiedMediaRelationshipGraph private constructor(
    private val out: Map<MediaGraphEntity, List<MediaGraphClaim>>,
    private val incoming: Map<MediaGraphEntity, List<MediaGraphClaim>>
) {
    companion object {
        /** Hard fanout ceiling prevents celebrity/universe/topic hotspots from dominating detail opens. */
        private const val MAX_SHARED_POSTING = 256

        fun build(claims: Collection<MediaGraphClaim>, approvedProviders: Set<String>): VerifiedMediaRelationshipGraph {
            // Revisions are per source record field. Equal-revision disagreements quarantine the key.
            val current = claims.asSequence().filter { it.provider in approvedProviders }
                .groupBy { Triple(it.provider, it.recordKey, it.fieldKey) }
                .values.mapNotNull { versions ->
                    val latest = versions.maxOf { it.revision }
                    versions.filter { it.revision == latest }.distinct().singleOrNull()
                }
            // Contradiction to an exact directed edge suppresses ALL providers' claims about it.
            val byEdge = current.groupBy { Triple(it.subject, it.predicate, it.target) }
            val valid = byEdge.values.filter { values ->
                values.none { it.state == MediaGraphClaimState.DISPUTED } &&
                    values.any { it.state == MediaGraphClaimState.VERIFIED || it.state == MediaGraphClaimState.USER_CONFIRMED }
            }.flatten().filter { it.state == MediaGraphClaimState.VERIFIED || it.state == MediaGraphClaimState.USER_CONFIRMED }
                .filter { it.predicate != MediaGraphPredicate.ABOUT_TOPIC || it.specificTopic }
            return VerifiedMediaRelationshipGraph(valid.groupBy { it.subject }, valid.groupBy { it.target })
        }

        private fun direct(predicate: MediaGraphPredicate): MediaGraphRelation? = when (predicate) {
            MediaGraphPredicate.ADAPTED_FROM -> MediaGraphRelation.DIRECT_ADAPTATION
            MediaGraphPredicate.REMAKE_OF -> MediaGraphRelation.DIRECT_REMAKE
            MediaGraphPredicate.SEQUEL_TO -> MediaGraphRelation.DIRECT_SEQUEL
            MediaGraphPredicate.EDITION_OF -> MediaGraphRelation.DIRECT_EDITION
            else -> null
        }

        /** No arbitrary multi-hop walks. Only two explicitly typed edges to a *shared* node. */
        private fun shared(a: MediaGraphPredicate, b: MediaGraphPredicate): MediaGraphRelation? {
            if (a == MediaGraphPredicate.ABOUT_PERSON && b == MediaGraphPredicate.PERFORMED_BY ||
                b == MediaGraphPredicate.ABOUT_PERSON && a == MediaGraphPredicate.PERFORMED_BY) {
                return MediaGraphRelation.SUBJECT_AND_PERFORMER
            }
            if (a != b) return null
            return when (a) {
                MediaGraphPredicate.ADAPTED_FROM -> MediaGraphRelation.SAME_SOURCE_WORK
                MediaGraphPredicate.SET_IN_UNIVERSE -> MediaGraphRelation.SAME_UNIVERSE
                MediaGraphPredicate.FEATURES_CHARACTER -> MediaGraphRelation.SAME_CHARACTER
                MediaGraphPredicate.PART_OF_SERIES -> MediaGraphRelation.SAME_SERIES
                MediaGraphPredicate.WRITTEN_BY -> MediaGraphRelation.SHARED_WRITER
                MediaGraphPredicate.CREATED_BY -> MediaGraphRelation.SHARED_CREATOR
                MediaGraphPredicate.PRODUCED_BY -> MediaGraphRelation.SHARED_PRODUCTION
                MediaGraphPredicate.PERFORMED_BY -> MediaGraphRelation.SHARED_PERFORMER
                MediaGraphPredicate.ACTED_BY -> MediaGraphRelation.SHARED_ACTOR
                MediaGraphPredicate.ABOUT_TOPIC -> MediaGraphRelation.SHARED_TOPIC
                else -> null
            }
        }
    }

    /** Does not enumerate every work in the catalog; only postings touched by the source node. */
    fun related(source: MediaGraphEntity, visibleEntities: Set<MediaGraphEntity>, limit: Int = 5): List<MediaGraphRelatedEntity> {
        require(limit in 1..100)
        if (source !in visibleEntities) return emptyList()
        val hits = mutableMapOf<MediaGraphEntity, MutableList<MediaGraphExplanation>>()
        fun add(target: MediaGraphEntity, explanation: MediaGraphExplanation) {
            if (target != source && target in visibleEntities) hits.getOrPut(target) { mutableListOf() }.add(explanation)
        }
        for (claim in out[source].orEmpty()) {
            direct(claim.predicate)?.let { add(claim.target, MediaGraphExplanation(it, null, listOf(claim))) }
            // Broad, high-degree terms are not useful as a default recommendation signal.
            val peers = incoming[claim.target].orEmpty()
            if (peers.size <= MAX_SHARED_POSTING) {
                for (peer in peers) {
                    val relation = shared(claim.predicate, peer.predicate) ?: continue
                    add(peer.subject, MediaGraphExplanation(relation, claim.target, listOf(claim, peer)))
                }
            }
        }
        for (claim in incoming[source].orEmpty()) {
            direct(claim.predicate)?.let {
                add(claim.subject, MediaGraphExplanation(it, null, listOf(claim), reverseOfDirectFact = true))
            }
        }
        return hits.entries.map { (entity, matches) ->
            MediaGraphRelatedEntity(entity, matches.distinctBy { Triple(it.relation, it.via, it.reverseOfDirectFact) }
                .sortedWith(compareByDescending<MediaGraphExplanation> { it.relation.rank }
                    .thenBy { it.via?.namespace.orEmpty() + ":" + it.via?.id.orEmpty() }))
        }.sortedWith(compareByDescending<MediaGraphRelatedEntity> { it.strongest.rank }
            .thenBy { it.entity.namespace + ":" + it.entity.kind.name + ":" + it.entity.id })
            .take(limit)
    }
}
