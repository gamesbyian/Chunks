package io.github.gamesbyian.chunks.engine

import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Change
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Mode
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.OrderedFile
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Policy
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Chunks-only Finish in Order state for one phone transfer, keyed outside
 * this record by local scope + verified infohash. Never sent to a server and
 * never written into torrent metadata.
 *
 * [pending] is the write-ahead journal: the exact change set is committed
 * *before* the first native write. After process death, the next observation
 * decides whether all, none or only part of it landed; part is UNCERTAIN and
 * suspends automatic scheduling rather than replaying the batch.
 */
internal data class FinishInOrderRecord(
    val policy: Policy,
    val lastApplied: Map<Int, Int> = emptyMap(),
    val pinned: Set<Int> = emptySet(),
    val pending: List<Change> = emptyList(),
    /** Non-null stops automatic writes until the user reviews and resumes. */
    val suspendedReason: String? = null,
    val finished: Boolean = false
) {
    /**
     * The user's own new order/mode for an active policy. Refused (null) while
     * a journaled batch is unresolved. Verified baselines and pins carry over
     * for files that stay in the order, so a hand-set priority stays pinned.
     */
    fun reordered(newPolicy: Policy): FinishInOrderRecord? {
        if (pending.isNotEmpty()) return null
        val kept = newPolicy.order.map { it.index }.toSet()
        return copy(policy = newPolicy, lastApplied = lastApplied.filterKeys { it in kept },
            pinned = pinned.filter { it in kept }.toSet(), finished = false)
    }

    /**
     * One-tap "Finish this next": [index] moves to the front of the order and
     * every other file keeps its relative place. The user is explicitly asking
     * for that file, so a pin on it is released. Null while a journal is
     * unresolved or when the file is not part of this policy.
     */
    fun finishNext(index: Int): FinishInOrderRecord? {
        val target = policy.order.firstOrNull { it.index == index } ?: return null
        return reordered(policy.copy(order = listOf(target) + policy.order.filter { it.index != index }))
            ?.let { it.copy(pinned = it.pinned - index, lastApplied = it.lastApplied - index) }
    }

    fun encode(): String = buildString {
        append("v=1\n")
        append("mode=").append(policy.mode.name).append('\n')
        append("lookahead=").append(policy.lookahead).append('\n')
        policy.order.forEach {
            append("file=").append(it.index).append('|').append(it.sizeBytes).append('|')
                .append(enc(it.path)).append('\n')
        }
        append("applied=").append(lastApplied.entries.joinToString(",") { "${it.key}:${it.value}" }).append('\n')
        append("pinned=").append(pinned.joinToString(",")).append('\n')
        append("pending=").append(pending.joinToString(",") {
            "${it.index}:${it.expectedPriority}:${it.requestedPriority}"
        }).append('\n')
        append("suspended=").append(suspendedReason?.let(::enc).orEmpty()).append('\n')
        append("finished=").append(finished).append('\n')
    }

    companion object {
        /** Returns null for anything malformed; callers treat that as no policy. */
        fun decode(text: String): FinishInOrderRecord? = try {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            require(lines.firstOrNull() == "v=1")
            val values = lines.drop(1).map { it.substringBefore('=') to it.substringAfter('=', "") }
            fun one(key: String) = values.single { it.first == key }.second
            val order = values.filter { it.first == "file" }.map { (_, value) ->
                val parts = value.split('|', limit = 3)
                require(parts.size == 3)
                OrderedFile(parts[0].toInt(), dec(parts[2]), parts[1].toLong())
            }
            val policy = Policy(Mode.valueOf(one("mode")), order, one("lookahead").toInt())
            val applied = one("applied").takeIf { it.isNotEmpty() }?.split(',')?.associate {
                val (index, priority) = it.split(':')
                index.toInt() to priority.toInt().also { p -> require(p in 1..7) }
            }.orEmpty()
            val pinned = one("pinned").takeIf { it.isNotEmpty() }?.split(',')
                ?.map { it.toInt() }?.toSet().orEmpty()
            val pending = one("pending").takeIf { it.isNotEmpty() }?.split(',')?.map {
                val (index, expected, requested) = it.split(':').map(String::toInt)
                Change(index, expected, requested)
            }.orEmpty()
            val known = order.map { it.index }.toSet()
            require(known.containsAll(applied.keys) && known.containsAll(pinned) &&
                known.containsAll(pending.map { it.index }))
            FinishInOrderRecord(policy, applied, pinned, pending,
                one("suspended").takeIf { it.isNotEmpty() }?.let(::dec),
                one("finished").toBooleanStrict())
        } catch (_: Exception) { null }

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun dec(value: String) = URLDecoder.decode(value, "UTF-8")
    }
}
