package io.github.gamesbyian.chunks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Real POSIX folders the phone engine can save torrents into, with the
 * standard shared Downloads/Chunks Downloads preferred where Android lets an
 * app write there: Android 11+ always, Android 10 only after the user grants
 * its storage permission (requested from an explicit button, never on its
 * own). Chunks never reads another app's preferences (such as a browser's
 * download folder) and never asks for all-files access.
 *
 * Every candidate is write-probed on the IO lane before it may become the
 * default. The public folder is created lazily, only when an Add is
 * confirmed, and never on a remote server.
 */
internal object LocalDownloadPlaces {
    const val PUBLIC_FOLDER_NAME = "Chunks Downloads"
    /** Read to seed, write to download; both are declared for Android 10 only. */
    val ANDROID_10_STORAGE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    private const val PREFS = "local-download-place"
    private const val KEY_DEFAULT = "default-kind"
    private const val KEY_RESOLVED_ROOT = "resolved-root"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun publicParent(): File? = try {
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    } catch (_: Exception) { null }

    private fun legacyStorage(): Boolean = try {
        @Suppress("DEPRECATION")
        Environment.isExternalStorageLegacy()
    } catch (_: Exception) { false }

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
            // An earlier pathname survey cannot stop the native disk backend
            // overwriting an unrelated inode after Stop, restore or file swap.
            result += LocalPlaceChoice(publicPlace(parent),
                "Native writes to shared Downloads are disabled to protect existing files")
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
                "Direct native writes are unavailable until non-clobbering storage is implemented."),
                "Native writes to removable storage are disabled to protect existing files")
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
        // A saved public preference or stale caller must not bypass containment.
        if (place.kind != LocalPlaceKind.APP_STORAGE) return null
        val root = when (place.kind) {
            LocalPlaceKind.PUBLIC_DOWNLOADS -> place.root.parentFile
                ?.let { LocalPlaceWriteProbe.ensureChild(it, place.root.name) }
            else -> place.root.takeIf { it.isDirectory || it.mkdirs() }
        } ?: return null
        return root.takeIf { LocalPlaceWriteProbe.verify(it) == null }
    }
}
