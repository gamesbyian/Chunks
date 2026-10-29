package io.github.gamesbyian.chunks.engine

import java.util.Locale

/**
 * Finish in Order (#415): a *rolling* per-file completion policy for one
 * multi-file torrent. Pure and deterministic; the engine applies its output
 * only after a fresh exact preflight, and readback decides the outcome.
 *
 * The user's order is a logical, unbounded ladder mapped onto three native
 * bands (High/Normal/Low). The active target advances only when every piece
 * of a file has passed its hash check. Native file priority biases piece
 * requests; it cannot make unseeded pieces exist, so this never guarantees
 * that the earliest file finishes first.
 *
 * Never touched: skipped files (native priority 0), files the user has pinned
 * or re-prioritised by hand, files outside the reviewed order, and any bytes
 * on disk. Nothing is unskipped, deleted, renamed or moved.
 */
internal object FinishInOrderPlanner {
    enum class Mode {
        BALANCED,
        FINISH_FIRST,
        /**
         * Finish first priorities plus the engine's per-transfer in-order
         * piece picking (phone engine only). Requests the active file's
         * pieces front to back so playback can start sooner; it lowers swarm
         * efficiency and still cannot make unseeded pieces exist.
         */
        SEQUENTIAL_PLAYBACK
    }

    /** How the suggested order was derived; shown so the user can check it. */
    enum class OrderSource { EPISODES, FILENAMES }

    data class Suggestion(val source: OrderSource, val order: List<Int>)

    /** One stable file identity captured when the user reviewed the order. */
    data class OrderedFile(val index: Int, val path: String, val sizeBytes: Long)

    data class Policy(
        val mode: Mode,
        val order: List<OrderedFile>,
        /** Following files kept at Normal in Balanced mode. */
        val lookahead: Int = 1
    ) {
        init {
            require(order.size in 2..MAX_FILES) { "Finish in Order needs 2–$MAX_FILES files" }
            require(order.map { it.index }.toSet().size == order.size) { "Duplicate file in order" }
            require(lookahead in 0..MAX_LOOKAHEAD) { "Unsupported lookahead" }
        }
    }

    /**
     * One observed file. [verifiedBytes] counts only hash-checked pieces;
     * [blocked] means at least one missing piece is held by no connected peer
     * (or no peers are connected), which is evidence, not a permanent fact.
     */
    data class Observed(
        val file: LocalTransferFile,
        val verifiedBytes: Long,
        val blocked: Boolean = false
    ) {
        val complete: Boolean get() = file.sizeBytes > 0 && verifiedBytes >= file.sizeBytes
    }

    data class Change(val index: Int, val expectedPriority: Int, val requestedPriority: Int)

    enum class FileRole { ACTIVE, FALLBACK, LOOKAHEAD, BACKGROUND, DONE, SKIPPED, PINNED }

    sealed class Decision {
        /** The reviewed files no longer match: re-review, never guess. */
        data class Refused(val reason: String) : Decision()

        data class Plan(
            val changes: List<Change>,
            /** Every managed file's target, changed or not: the next baseline. */
            val desired: Map<Int, Int>,
            val roles: Map<Int, FileRole>,
            /** Earliest unfinished managed file, or null when all are done. */
            val active: Int?,
            /** Files whose native priority differs from what Chunks last set. */
            val newlyPinned: Set<Int>,
            val activeBlocked: Boolean
        ) : Decision() {
            val finished: Boolean get() = active == null
        }
    }

    const val MAX_FILES = 512
    const val MAX_LOOKAHEAD = 3
    const val HIGH = 7
    const val NORMAL = 4
    const val LOW = 1

    private val sidecar = setOf("srt", "ass", "ssa", "sub", "idx", "vtt", "nfo", "txt",
        "jpg", "jpeg", "png", "gif", "sfv", "md5", "url", "db")
    private val extras = Regex("""(?i)(?:^|[/ ._-])(sample|trailer|featurette|extras?|behind.the.scenes)(?:[/ ._-]|$)""")
    private val digits = Regex("""\d+""")

    /**
     * Suggest an order without touching the engine: TV-style episode labels
     * first, otherwise a digit-aware filename order for numbered sets such as
     * audiobook chapters, lessons or volumes. Sidecars, samples and extras
     * are excluded. Returns null when there is nothing meaningful to order.
     */
    fun suggest(files: List<LocalTransferFile>): Suggestion? {
        if (files.size !in 2..MAX_FILES || files.map { it.index }.toSet().size != files.size)
            return null
        val episodes = files.filter { it.wanted }.mapNotNull { file ->
            LocalEpisodeFocusPlanner.episodeKey(file.path)?.let { file to it }
        }.sortedWith(compareBy<Pair<LocalTransferFile, LocalEpisodeFocusPlanner.EpisodeKey>>(
            { it.second.season }, { it.second.episode },
            { it.first.path.lowercase(Locale.ROOT) }, { it.first.index }))
        if (episodes.map { it.second }.distinct().size >= 2)
            return Suggestion(OrderSource.EPISODES, episodes.map { it.first.index })
        val content = files.filter { file ->
            file.wanted && file.sizeBytes > 0 && !extras.containsMatchIn(file.path) &&
                file.path.substringAfterLast('/').substringAfterLast('.', "")
                    .lowercase(Locale.ROOT) !in sidecar
        }
        // A filename order is only suggested when names actually carry numbers.
        if (content.size < 2 || content.count { digits.containsMatchIn(it.path.substringAfterLast('/')) } < 2)
            return null
        return Suggestion(OrderSource.FILENAMES,
            content.sortedWith { a, b -> naturalCompare(a.path, b.path).takeIf { it != 0 } ?: a.index.compareTo(b.index) }
                .map { it.index })
    }

    fun policyFor(mode: Mode, files: List<LocalTransferFile>, order: List<Int>, lookahead: Int = 1): Policy {
        val byIndex = files.associateBy { it.index }
        return Policy(mode, order.map { index ->
            val file = requireNotNull(byIndex[index]) { "File $index is not in this torrent" }
            OrderedFile(file.index, file.path, file.sizeBytes)
        }, lookahead)
    }

    /**
     * Compute the next rolling assignment. [lastApplied] is what Chunks itself
     * last verified for each file; any other observed priority on a managed
     * file is treated as the user's own choice and that file is pinned.
     */
    fun plan(
        policy: Policy,
        observed: List<Observed>,
        lastApplied: Map<Int, Int>,
        pinned: Set<Int>,
        /**
         * Engines with only off/normal/high (rTorrent): everything that is not
         * High stays Normal, so Low is never requested.
         */
        twoBands: Boolean = false
    ): Decision {
        val byIndex = observed.associateBy { it.file.index }
        if (byIndex.size != observed.size) return Decision.Refused("Duplicate file observation")
        for (entry in policy.order) {
            val now = byIndex[entry.index]
                ?: return Decision.Refused("A file in the reviewed order is missing")
            if (now.file.path != entry.path || now.file.sizeBytes != entry.sizeBytes)
                return Decision.Refused("Files changed since the order was reviewed")
        }
        val roles = LinkedHashMap<Int, FileRole>()
        val newlyPinned = LinkedHashSet<Int>()
        val managed = ArrayList<Observed>()
        for (entry in policy.order) {
            val now = byIndex.getValue(entry.index)
            val previous = lastApplied[entry.index]
            when {
                entry.index in pinned -> roles[entry.index] = FileRole.PINNED
                !now.file.wanted || now.file.priority == 0 -> roles[entry.index] = FileRole.SKIPPED
                previous != null && previous != now.file.priority -> {
                    newlyPinned += entry.index
                    roles[entry.index] = FileRole.PINNED
                }
                now.complete -> roles[entry.index] = FileRole.DONE
                else -> managed += now
            }
        }
        val active = managed.firstOrNull()
        val desired = LinkedHashMap<Int, Int>()
        var fallbackUsed = false
        var lookaheadLeft = if (policy.mode == Mode.BALANCED) policy.lookahead else 0
        managed.forEachIndexed { position, file ->
            val index = file.file.index
            when {
                position == 0 -> { desired[index] = HIGH; roles[index] = FileRole.ACTIVE }
                // The active file is starved: let the next file that peers can
                // actually serve share High rather than wasting bandwidth.
                active!!.blocked && !fallbackUsed && !file.blocked -> {
                    fallbackUsed = true
                    desired[index] = HIGH; roles[index] = FileRole.FALLBACK
                }
                lookaheadLeft > 0 -> {
                    lookaheadLeft--
                    desired[index] = NORMAL; roles[index] = FileRole.LOOKAHEAD
                }
                else -> { desired[index] = if (twoBands) NORMAL else LOW; roles[index] = FileRole.BACKGROUND }
            }
        }
        val changes = desired.mapNotNull { (index, priority) ->
            val current = byIndex.getValue(index).file.priority
            if (current == priority) null else Change(index, current, priority)
        }
        return Decision.Plan(changes, desired, roles, active?.file?.index, newlyPinned,
            active?.blocked == true)
    }

    /** Digit-aware, case-insensitive comparison: "Part 2" sorts before "Part 10". */
    fun naturalCompare(a: String, b: String): Int {
        val x = a.lowercase(Locale.ROOT)
        val y = b.lowercase(Locale.ROOT)
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                val si = i; while (i < x.length && x[i].isDigit()) i++
                val sj = j; while (j < y.length && y[j].isDigit()) j++
                val nx = x.substring(si, i).trimStart('0')
                val ny = y.substring(sj, j).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
            } else {
                if (x[i] != y[j]) return x[i] - y[j]
                i++; j++
            }
        }
        return (x.length - i) - (y.length - j)
    }
}
