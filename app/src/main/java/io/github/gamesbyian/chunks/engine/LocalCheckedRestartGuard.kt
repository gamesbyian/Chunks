package io.github.gamesbyian.chunks.engine

import java.io.File

/**
 * Non-mutating restart containment for a checked native job which has already
 * been started in this process.
 *
 * Verified native file-progress == 0 does not grant libtorrent ownership of
 * any filename. In particular, an ignored/skipped file can be planted while
 * a partially downloaded job is stopped. Refuse Start rather than allowing
 * libtorrent to reopen that foreign file when priorities later change.
 *
 * Scope: a point-in-time guard, NOT atomic filesystem ownership. Positive
 * progress is not proof that a file wasn't later substituted. The engine
 * must separately verify that the same reserved root and paths still apply.
 */
internal object LocalCheckedRestartGuard {
    internal sealed interface Result {
        data object ClearAtObservation : Result
        data class Refused(val reason: String) : Result
    }

    fun inspect(
        root: File,
        nativePaths: List<String>,
        verifiedFileBytes: List<Long>,
        maxPaths: Int = LocalPayloadCollisionSurvey.MAX_NATIVE_PATHS
    ): Result {
        if (nativePaths.isEmpty() || nativePaths.size > maxPaths ||
            verifiedFileBytes.size != nativePaths.size || maxPaths <= 0 ||
            verifiedFileBytes.any { it < 0L }) {
            return Result.Refused("Native per-file verification state is unavailable")
        }
        // Validate *every* native path, not just the still-missing ones.
        // A swapped parent symlink or revoked root must never be treated as an
        // ordinary data file. Other regular files may be legitimate owned data,
        // but are NOT granted ownership by this point-in-time observation.
        when (val all = LocalPayloadCollisionSurvey.inspect(root, nativePaths, maxPaths)) {
            is LocalPayloadCollisionSurvey.Result.Unknown ->
                return Result.Refused("Cannot verify backing paths before restart: " + all.problem)
            else -> Unit
        }
        val zeroProgressPaths = nativePaths.filterIndexed { index, _ ->
            verifiedFileBytes[index] == 0L
        }
        if (zeroProgressPaths.isEmpty()) return Result.ClearAtObservation
        return when (val missing = LocalPayloadCollisionSurvey.inspect(
            root, zeroProgressPaths, maxPaths
        )) {
            is LocalPayloadCollisionSurvey.Result.ClearAtObservation -> Result.ClearAtObservation
            is LocalPayloadCollisionSurvey.Result.ExistingPaths ->
                Result.Refused("A file with no verified native bytes appeared or remains at " +
                    missing.total + " path(s); Start refused to protect existing data")
            is LocalPayloadCollisionSurvey.Result.Unknown ->
                Result.Refused("Cannot inspect not-yet-verified files before restart: " +
                    missing.problem)
        }
    }
}
