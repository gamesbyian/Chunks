package io.github.gamesbyian.chunks.engine

import java.io.File

/**
 * Non-mutating restart containment for a checked native job which has already
 * been started in this process.
 *
 * A selected torrent destination permits libtorrent to recheck/reuse/replace
 * existing regular payload files, including ones with zero prior progress.
 * This guard retains path-shape, containment, symlink, special-file and
 * access checks, as well as a bounded native layout; it is not an atomic
 * filesystem race defense. Other file mutations still need their own consent.
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
        // Missing or existing regular payload files are conventional torrent
        // inputs. The native piece hashes, not a previous Chunks ownership
        // receipt, decide which bytes can be trusted as complete.
        return when (val all = LocalPayloadCollisionSurvey.inspect(root, nativePaths, maxPaths)) {
            is LocalPayloadCollisionSurvey.Result.Unknown ->
                Result.Refused("Cannot verify backing paths before restart: " + all.problem)
            else -> Result.ClearAtObservation
        }
    }
}
