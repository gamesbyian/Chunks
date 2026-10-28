package io.github.gamesbyian.chunks

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Real POSIX folders the phone engine can save torrents into, with the
 * standard shared Downloads/Chunks Downloads preferred where Android lets an
 * app write there without broad storage permission (Android 11+). Chunks
 * never reads another app's preferences (such as a browser's download
 * folder) and never asks for all-files access.
 *
 * Every candidate is write-probed on the IO lane before it may become the
 * default. The public folder is created lazily, only when an Add is
 * confirmed, and never on a remote server.
 */
internal object LocalDownloadPlaces {
    const val PUBLIC_FOLDER_NAME = "Chunks Downloads"
    private const val PREFS = "local-download-place"
    private const val KEY_DEFAULT = "default-kind"
    private const val KEY_RESOLVED_ROOT = "resolved-root"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun publicParent(): File? = try {
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    } catch (_: Exception) { null }

    private fun publicPlace(parent: File) = LocalDownloadPlace(
        LocalPlaceKind.PUBLIC_DOWNLOADS, File(parent, PUBLIC_FOLDER_NAME),
        "Downloads/$PUBLIC_FOLDER_NAME",
        "Visible in the Files app and kept if Chunks is uninstalled. A reinstalled " +
            "Chunks may not be allowed to reopen these files to keep seeding."
    )

    /** Probe every candidate. Performs small file I/O: call off the main thread. */
    fun choices(context: Context): List<LocalPlaceChoice> {
        val result = ArrayList<LocalPlaceChoice>()
        val parent = publicParent()
        if (parent != null) {
            val place = publicPlace(parent)
            val failure = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                    "Android 10 only allows this with broad storage permission, which Chunks does not request"
                Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED ->
                    "Shared storage is not mounted"
                // Lazy creation: probe the existing folder, else its parent,
                // so viewing the phone screen never creates Chunks Downloads.
                place.root.exists() -> LocalPlaceWriteProbe.verify(place.root)
                else -> LocalPlaceWriteProbe.verify(parent)
            }
            result += LocalPlaceChoice(place, failure)
        }
        LocalEngineHolder.appDownloads(context)?.let { root ->
            result += LocalPlaceChoice(LocalDownloadPlace(LocalPlaceKind.APP_STORAGE, root,
                "Chunks app folder",
                "Private to Chunks on phone storage. Removed if Chunks is uninstalled."),
                LocalPlaceWriteProbe.verify(root))
        }
        val removable = try {
            context.getExternalFilesDirs("Downloads").drop(1).filterNotNull().firstOrNull { dir ->
                try { Environment.isExternalStorageRemovable(dir) } catch (_: Exception) { false }
            }
        } catch (_: Exception) { null }
        if (removable != null) {
            val ready = removable.isDirectory || removable.mkdirs()
            result += LocalPlaceChoice(LocalDownloadPlace(LocalPlaceKind.REMOVABLE_APP_STORAGE, removable,
                "SD card (Chunks app folder)",
                "Chunks' folder on removable storage. Transfers fail while the card is " +
                    "removed, and the folder is deleted if Chunks is uninstalled."),
                if (ready) LocalPlaceWriteProbe.verify(removable) else "SD card folder could not be created")
        }
        return result
    }

    /** Off the main thread. Records the resolved root for read-only panels. */
    fun resolve(context: Context): LocalPlaceResolution {
        val remembered = LocalPlaceKind.fromKey(prefs(context).getString(KEY_DEFAULT, null))
        val resolution = LocalDownloadPlacePolicy.resolve(remembered, choices(context))
        resolution.selected?.let {
            prefs(context).edit().putString(KEY_RESOLVED_ROOT, it.root.absolutePath).apply()
        }
        return resolution
    }

    /** Remember a user's choice only after this session's probe verified it. */
    fun remember(context: Context, choice: LocalPlaceChoice): Boolean =
        choice.verified && prefs(context).edit().putString(KEY_DEFAULT, choice.place.kind.key)
            .putString(KEY_RESOLVED_ROOT, choice.place.root.absolutePath).commit()

    /**
     * The folder storage panels should measure: the last resolved default if
     * it currently exists, otherwise Chunks' app folder. Never creates folders.
     */
    fun measuredRoot(context: Context): File? =
        prefs(context).getString(KEY_RESOLVED_ROOT, null)?.let(::File)?.takeIf { it.isDirectory }
            ?: LocalEngineHolder.appDownloads(context)

    /**
     * IO lane, only after an explicit Add confirmation: lazily create
     * Downloads/Chunks Downloads if needed and re-verify writes right before
     * the engine uses the folder. Returns null instead of a false success.
     */
    fun materialize(place: LocalDownloadPlace): File? {
        val root = when (place.kind) {
            LocalPlaceKind.PUBLIC_DOWNLOADS -> place.root.parentFile
                ?.let { LocalPlaceWriteProbe.ensureChild(it, place.root.name) }
            else -> place.root.takeIf { it.isDirectory || it.mkdirs() }
        } ?: return null
        return root.takeIf { LocalPlaceWriteProbe.verify(it) == null }
    }
}
