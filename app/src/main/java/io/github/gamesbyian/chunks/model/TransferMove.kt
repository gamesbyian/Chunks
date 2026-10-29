package io.github.gamesbyian.chunks.model

import java.util.Locale

/**
 * Move Transfer between the phone engine and one rTorrent account moves the
 * torrent **job**, never downloaded files (owner contract,
 * docs/CROSS-ENGINE-TRANSFER-JOBS.md):
 *
 * 1. The user starts a Move; the existing reviewed Add handoff opens on the
 *    destination. Nothing is removed.
 * 2. A fresh exact-infohash observation on the destination verifies the new job.
 * 3. A *separate* confirmation removes only the source job (phone: session
 *    removal keeping files; rTorrent: guarded d.erase). The stage is journaled
 *    before that mutation, so a crash or lost reply becomes UNCERTAIN and is
 *    resolved by observation, never by an automatic repeat.
 *
 * Source payload is never transferred, modified or deleted by this flow.
 */
internal object TransferMove {
    enum class Direction { PHONE_TO_SERVER, SERVER_TO_PHONE }

    enum class Stage {
        /** Handoff opened; destination job not yet observed. */
        AWAITING_TARGET,
        /** Destination job observed by exact infohash; source untouched. */
        TARGET_VERIFIED,
        /** Journaled immediately before the one source-removal request. */
        SOURCE_REMOVAL_REQUESTED,
        /** The removal outcome is unknown; only observation may resolve it. */
        SOURCE_REMOVAL_UNCERTAIN
    }

    data class Record(
        val direction: Direction,
        val infoHash: String,
        val name: String,
        /** Opaque digest of the rTorrent endpoint + account; never the URL itself. */
        val serverScopeDigest: String,
        /** Host only, for "connect to …" guidance. */
        val serverLabel: String,
        val stage: Stage
    ) {
        init {
            require(Regex("[0-9a-f]{40}").matches(infoHash)) { "Move needs an exact v1 identity" }
            require(Regex("[0-9a-f]{64}").matches(serverScopeDigest)) { "Invalid server scope" }
        }

        fun encode(): String = listOf("v1", direction.name, infoHash, serverScopeDigest, stage.name,
            escape(serverLabel), escape(name)).joinToString("\n")
    }

    sealed class Observation {
        object Present : Observation()
        object Absent : Observation()
        /** Could not observe (not connected, engine stopped, read failed). */
        object Unknown : Observation()
    }

    sealed class Next {
        data class Save(val record: Record, val message: String) : Next()
        /** The Move is finished or abandoned: forget the journal entry. */
        data class Done(val message: String) : Next()
        data class Keep(val message: String) : Next()
    }

    fun start(direction: Direction, infoHash: String, name: String,
              serverScopeDigest: String, serverLabel: String): Record =
        Record(direction, infoHash.lowercase(Locale.ROOT), name.take(512),
            serverScopeDigest, serverLabel.take(253), Stage.AWAITING_TARGET)

    /** A process death mid-removal leaves REQUESTED: treat it as unknown. */
    fun recovered(record: Record): Record =
        if (record.stage == Stage.SOURCE_REMOVAL_REQUESTED)
            record.copy(stage = Stage.SOURCE_REMOVAL_UNCERTAIN) else record

    fun onTargetObserved(record: Record, seen: Observation): Next = when {
        record.stage != Stage.AWAITING_TARGET && record.stage != Stage.TARGET_VERIFIED ->
            Next.Keep("Resolve the earlier removal first.")
        seen == Observation.Present -> Next.Save(record.copy(stage = Stage.TARGET_VERIFIED),
            "The new job is on the ${destination(record)}. You can now remove the original job.")
        seen == Observation.Absent -> Next.Save(record.copy(stage = Stage.AWAITING_TARGET),
            "Not on the ${destination(record)} yet. Finish its Add review first; nothing was removed.")
        else -> Next.Keep("Chunks could not check the ${destination(record)}. Nothing was removed.")
    }

    /**
     * Must be persisted before the removal request. Requires the target to be
     * re-observed as present immediately beforehand (fresh preflight).
     */
    fun beginRemoval(record: Record, targetNow: Observation): Next = when {
        record.stage != Stage.TARGET_VERIFIED -> Next.Keep("Check the ${destination(record)} first.")
        targetNow != Observation.Present -> Next.Save(record.copy(stage = Stage.AWAITING_TARGET),
            "The new job is no longer confirmed on the ${destination(record)}. The original job was kept.")
        else -> Next.Save(record.copy(stage = Stage.SOURCE_REMOVAL_REQUESTED), "Removing the original job…")
    }

    /** [sourceAfter] is a fresh observation of the source after the request. */
    fun onRemovalResult(record: Record, sourceAfter: Observation): Next = when (sourceAfter) {
        Observation.Absent -> Next.Done(doneMessage(record))
        Observation.Present -> Next.Save(record.copy(stage = Stage.SOURCE_REMOVAL_UNCERTAIN),
            "The original job is still listed. It may be removed shortly; check again before deciding.")
        Observation.Unknown -> Next.Save(record.copy(stage = Stage.SOURCE_REMOVAL_UNCERTAIN),
            "Chunks could not confirm whether the original job was removed. Check again; " +
                "nothing will be retried automatically.")
    }

    /**
     * Resolve UNCERTAIN by observation only. If the source is still present,
     * the user may confirm removal again, because a fresh observation (not a
     * missing reply) shows the job exists: that is not a blind retry.
     */
    fun onSourceObserved(record: Record, seen: Observation): Next = when {
        record.stage != Stage.SOURCE_REMOVAL_UNCERTAIN -> Next.Keep("")
        seen == Observation.Absent -> Next.Done(doneMessage(record))
        seen == Observation.Present -> Next.Save(record.copy(stage = Stage.TARGET_VERIFIED),
            "The original job is still there. Recheck the destination before removing it again.")
        else -> Next.Keep("Still unable to check the original job. Nothing was changed.")
    }

    fun destination(record: Record) =
        if (record.direction == Direction.PHONE_TO_SERVER) "server" else "phone"

    private fun doneMessage(record: Record) =
        if (record.direction == Direction.PHONE_TO_SERVER)
            "Moved: the job now lives on the server. Downloaded files remain on this phone and still use its storage."
        else "Moved: the job now lives on this phone. Downloaded files remain on the server and still use its storage."

    fun decode(text: String): Record? = try {
        val parts = text.split('\n')
        require(parts.size == 7 && parts[0] == "v1")
        recovered(Record(Direction.valueOf(parts[1]), parts[2], unescape(parts[6]), parts[3],
            unescape(parts[5]), Stage.valueOf(parts[4])))
    } catch (_: Exception) { null }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n")
    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                out.append(if (value[i + 1] == 'n') '\n' else value[i + 1]); i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
