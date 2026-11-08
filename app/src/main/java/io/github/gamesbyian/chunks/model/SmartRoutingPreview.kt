package io.github.gamesbyian.chunks.model

import java.util.Locale

/**
 * Side-effect-free first phase of Smart Routing. This deliberately has no
 * engine, RPC, Android, persistent store or network dependencies.
 *
 * It suggests an exact configured environment for the existing Universal Add
 * review. A suggestion is never an Add authorization, consent to Move, or a
 * claim that another environment contains a registered job.
 */
internal object SmartRoutingPreview {
    enum class Kind {
        MOVIE, TV, BOOK, COMIC, MUSIC, AUDIOBOOK, GAME, SOFTWARE,
        DOCUMENT, PICTURE, ARCHIVE
    }

    enum class Outcome {
        /** The caller may *show* this proposal in Universal Add review. */
        PROPOSED,
        /** No confident classification, or multiple incompatible signals. */
        ASK,
        /** A rule matched but its exact configured destination is unavailable. */
        TARGET_UNAVAILABLE,
        /** An explicit user choice names an unavailable exact destination. */
        EXPLICIT_TARGET_UNAVAILABLE
    }

    /** Caller-supplied file inventory, from already authorized local inspection.
     * No original tracker URLs, passkeys, addresses or credentials are needed.
     */
    data class Evidence(val title: String, val fileNames: List<String> = emptyList()) {
        init {
            require(title.length <= 2048 && title.none { it.isISOControl() })
            require(fileNames.size <= 2000 &&
                fileNames.all { it.length <= 4096 && it.none(Char::isISOControl) })
        }
    }

    data class Classification(
        val kind: Kind?,
        val confidence: Int,
        /** Diagnostic categories only; never expose source names or tracker paths. */
        val signals: List<String>,
        val ambiguous: Boolean
    )

    /** Caller must derive this from the current authenticated capability and
     * verified destination Place, never from a saved-only profile/row/filter.
     */
    data class Destination(
        val environmentId: String,
        val authenticated: Boolean,
        val acceptsAdd: Boolean,
        val verifiedPlace: Boolean
    ) {
        init { require(environmentId.isNotBlank() && environmentId.length <= 256 &&
            environmentId.none { it.isISOControl() }) }
        val usable: Boolean get() = authenticated && acceptsAdd && verifiedPlace
    }

    data class Rule(
        val id: String,
        /** Highest number first; stable ID breaks ties. */
        val priority: Int,
        val kind: Kind,
        val minimumConfidence: Int,
        /** Exact configured environment ID; never a mere "remote" type. */
        val targetEnvironmentId: String
    ) {
        init {
            require(id.isNotBlank() && id.length <= 120 && id.none { it.isISOControl() })
            require(minimumConfidence in 0..100)
            require(targetEnvironmentId.isNotBlank() && targetEnvironmentId.length <= 256 &&
                targetEnvironmentId.none { it.isISOControl() })
        }
    }

    data class Decision(
        val outcome: Outcome,
        val classification: Classification,
        val ruleId: String? = null,
        /** A candidate only. Add still requires explicit user review/confirmation. */
        val proposedEnvironmentId: String? = null,
        val reason: String
    )

    private val video = setOf("mkv", "mp4", "avi", "mov", "webm", "m4v")
    private val books = setOf("epub", "mobi", "azw", "azw3")
    private val comics = setOf("cbz", "cbr")
    private val music = setOf("flac", "mp3", "ogg", "m4a", "aac", "wav", "opus")
    private val audiobooks = setOf("m4b")
    private val pictures = setOf("jpg", "jpeg", "png", "webp", "heic", "gif")
    private val archives = setOf("zip", "rar", "7z", "tar", "gz")
    private val docs = setOf("pdf", "doc", "docx", "odt", "txt", "rtf")
    private val software = setOf("apk", "exe", "msi", "deb", "rpm")
    private val games = setOf("nes", "sfc", "smc", "gba", "nsp", "xci")
    private val episode = Regex("(?i)(?:^|[ ._\\-])s[0-9]{1,2}e[0-9]{1,3}(?:$|[ ._\\-])")

    fun classify(evidence: Evidence): Classification {
        val candidates = if (evidence.fileNames.isEmpty()) listOf(evidence.title)
            else evidence.fileNames
        val counts = linkedMapOf<Kind, Int>()
        for (name in candidates) {
            // Classify only the final file extension, not a directory that
            // happens to contain a dot or a private tracker host.
            val leaf = name.substringAfterLast('/').substringAfterLast('\\')
            val ext = leaf.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val kind = when {
                ext in video -> if (episode.containsMatchIn(leaf) ||
                    episode.containsMatchIn(evidence.title)) Kind.TV else Kind.MOVIE
                ext in comics -> Kind.COMIC
                ext in books -> Kind.BOOK
                ext in audiobooks -> Kind.AUDIOBOOK
                ext in music -> Kind.MUSIC
                ext in pictures -> Kind.PICTURE
                ext in archives -> Kind.ARCHIVE
                ext in docs -> Kind.DOCUMENT
                ext in software -> Kind.SOFTWARE
                ext in games -> Kind.GAME
                else -> null
            }
            if (kind != null) counts[kind] = (counts[kind] ?: 0) + 1
        }
        if (counts.isEmpty()) return Classification(null, 0, listOf("no-recognized-file-types"), false)
        // Different recognized types are evidence of a mixed torrent. Never
        // guess that an archive, image or document is its "main" payload.
        if (counts.size != 1)
            return Classification(null, 0, listOf("mixed-file-types"), true)
        val kind = counts.keys.single()
        val confidence = when (kind) {
            Kind.MOVIE -> 65 // video files are also clips, concerts, tutorials
            Kind.ARCHIVE, Kind.DOCUMENT, Kind.PICTURE -> 75
            Kind.TV -> 96
            Kind.BOOK, Kind.COMIC, Kind.MUSIC, Kind.GAME, Kind.SOFTWARE -> 90
            Kind.AUDIOBOOK -> 95 // audiobook-specific container, not generic audio
        }
        return Classification(kind, confidence,
            listOf(if (evidence.fileNames.isEmpty()) "title-extension" else "file-extensions",
                if (kind == Kind.TV) "episode-pattern" else "single-content-kind"), false)
    }

    /** Preview only. Rules are evaluated in deterministic priority order.
     * A matching unavailable target never silently falls through to an
     * unrelated server. User-specified target always overrides these rules.
     */
    fun decide(
        evidence: Evidence,
        rules: List<Rule>,
        destinations: List<Destination>,
        explicitEnvironmentId: String? = null
    ): Decision {
        require(rules.size <= 500 && destinations.size <= 100)
        // Duplicate IDs with equal priorities could otherwise change the
        // selected account just because caller list iteration order changed.
        require(rules.map { it.id }.toSet().size == rules.size) { "Duplicate routing rule IDs" }
        val category = classify(evidence)
        // Multiple descriptors of the same account are ambiguous authority.
        val targets = destinations.groupBy { it.environmentId }
        if (explicitEnvironmentId != null) {
            val exact = targets[explicitEnvironmentId]?.singleOrNull()
            return if (exact?.usable == true)
                Decision(Outcome.PROPOSED, category, proposedEnvironmentId = exact.environmentId,
                    reason = "explicit-user-target; review-required")
            else Decision(Outcome.EXPLICIT_TARGET_UNAVAILABLE, category,
                reason = "explicit-exact-target-unavailable")
        }
        if (category.ambiguous || category.kind == null)
            return Decision(Outcome.ASK, category, reason = "classification-ambiguous-or-unknown")
        val chosen = rules.asSequence().filter {
            it.kind == category.kind && category.confidence >= it.minimumConfidence
        }.sortedWith(compareByDescending<Rule> { it.priority }.thenBy { it.id }).firstOrNull()
            ?: return Decision(Outcome.ASK, category, reason = "no-confident-rule")
        val exact = targets[chosen.targetEnvironmentId]?.singleOrNull()
        if (exact?.usable != true)
            return Decision(Outcome.TARGET_UNAVAILABLE, category, ruleId = chosen.id,
                reason = "matched-rule-exact-target-unavailable")
        return Decision(Outcome.PROPOSED, category, chosen.id, exact.environmentId,
            "matched-rule; reviewed-add-required")
    }
}
