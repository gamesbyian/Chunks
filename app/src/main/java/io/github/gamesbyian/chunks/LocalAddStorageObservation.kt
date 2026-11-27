package io.github.gamesbyian.chunks

import android.content.Context
import android.os.StatFs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.gamesbyian.chunks.model.LocalAddStoragePlanner
import io.github.gamesbyian.chunks.model.LocalAddStoragePreflight
import io.github.gamesbyian.chunks.model.LocalFolderInventoryProbe
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The folder advisory budget and target volume are distinct observations.
 * An as-yet uncreated Place may borrow a parent volume's free-space sample,
 * but never its folder inventory or advisory cap. No writes or admission.
 */
internal object LocalAddStorageObservation {
    fun read(
        root: File,
        target: File,
        declaredBytes: Long,
        capReader: (File) -> Long?,
        freeReader: (File) -> Long?
    ): LocalAddStoragePreflight {
        fun unavailable() = LocalAddStoragePlanner.assess(declaredBytes, null, null, null)
        fun directory(file: File) = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        fun absent(file: File) = Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (!root.isAbsolute || !target.isAbsolute ||
            root.toPath().normalize() != root.toPath() || target.toPath().normalize() != target.toPath() ||
            (target != root && target.parentFile != root)) return unavailable()
        return try {
            val rootExists = directory(root)
            if (!rootExists && !absent(root)) return unavailable()
            val volumeFolder = when {
                !rootExists -> root.parentFile?.takeIf(::directory)
                target == root -> root
                directory(target) && target.canonicalFile.parentFile == root.canonicalFile -> target
                absent(target) -> root
                else -> null
            }
            // An existing non-directory, link or inaccessible target is not
            // a lazily created child. Do not borrow free space for it.
            val free = volumeFolder?.let { safeRead { freeReader(it) } }
            val survey = if (rootExists) safeRead { LocalFolderInventoryProbe.measure(root) } else null
            val cap = if (rootExists) safeRead { capReader(root) } else null
            LocalAddStoragePlanner.assess(declaredBytes, survey, cap, free)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun <T> safeRead(block: () -> T?): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    fun read(context: Context, root: File, target: File, declaredBytes: Long) =
        read(root, target, declaredBytes,
            capReader = { LocalCapPreferenceStore(context).get(it) },
            freeReader = { StatFs(it.absolutePath).availableBytes.takeIf { bytes -> bytes >= 0L } })
}

/** The state cell is replaced synchronously with every exact source/root/target
 * change, even before the previous asynchronous effect can be cancelled.
 * A same-infohash source replacement cannot display the previous observation.
 */
@Composable
internal fun rememberLocalAddStoragePreflight(
    review: PendingLocalAdd?,
    root: File?,
    target: File?,
    placeRevision: Int,
    resumeRevision: Int
): LocalAddStoragePreflight? {
    val context = LocalContext.current
    val result = remember(context, review, root, target, placeRevision, resumeRevision) {
        mutableStateOf<LocalAddStoragePreflight?>(null)
    }
    LaunchedEffect(result) {
        if (review != null && root != null && target != null) {
            result.value = withContext(Dispatchers.IO) {
                LocalAddStorageObservation.read(context, root, target, review.preview.sizeBytes)
            }
        }
    }
    return result.value
}
