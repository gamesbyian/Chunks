package io.github.gamesbyian.chunks

import java.io.File

/** Kinds of real POSIX folders the phone engine can write torrents into. */
internal enum class LocalPlaceKind(val key: String) {
    /** Android's shared Downloads/Chunks Downloads, visible in Files apps. */
    PUBLIC_DOWNLOADS("public-downloads"),
    /** Chunks' app-specific folder on the primary shared volume. */
    APP_STORAGE("app-storage"),
    /** Chunks' app-specific folder on a removable volume such as an SD card. */
    REMOVABLE_APP_STORAGE("removable-app-storage");

    companion object {
        fun fromKey(key: String?): LocalPlaceKind? = entries.firstOrNull { it.key == key }
    }
}

internal data class LocalDownloadPlace(
    val kind: LocalPlaceKind,
    /** The folder torrents are saved into. A public folder may not exist yet. */
    val root: File,
    val title: String,
    val detail: String
)

/**
 * [failure] is null only when the Place passed its write probe this session.
 * [needsStoragePermission] marks a refusal the user can lift by granting
 * Android 10's storage permission; it never makes the Place usable by itself.
 */
internal data class LocalPlaceChoice(
    val place: LocalDownloadPlace,
    val failure: String?,
    val needsStoragePermission: Boolean = false
) {
    val verified get() = failure == null
}

/**
 * Android 11+ lets any app write its own files into shared Downloads. Android
 * 10 does so only with legacy external storage (granted at install from the
 * manifest flag) AND the user's runtime storage permissions (read to seed,
 * write to download). Returns the
 * refusal reason, or null when the regular write probe should decide.
 */
internal object LocalPublicDownloadsAccess {
    const val ANDROID_10 = 29
    const val NEEDS_PERMISSION =
        "On Android 10, Chunks needs your permission to save into shared Downloads"

    fun refusal(sdk: Int, legacyStorage: Boolean, storagePermissionGranted: Boolean): String? = when {
        sdk > ANDROID_10 -> null
        sdk < ANDROID_10 -> "This Android version is not supported for shared Downloads"
        !legacyStorage -> "Android 10 placed Chunks in scoped storage, so shared Downloads is unavailable"
        !storagePermissionGranted -> NEEDS_PERMISSION
        else -> null
    }
}

internal data class LocalPlaceResolution(
    val selected: LocalDownloadPlace?,
    val choices: List<LocalPlaceChoice>,
    /** Shown whenever the remembered or preferred Place could not be used. */
    val notice: String?
)

/**
 * Picks the default for new phone Adds. A remembered user choice wins only
 * while it still verifies; otherwise the first verified Place in preference
 * order is used and the fallback is stated, never silent. The remembered
 * choice itself is left unchanged so a reinserted SD card is used again.
 */
internal object LocalDownloadPlacePolicy {
    private val preference = listOf(
        LocalPlaceKind.PUBLIC_DOWNLOADS,
        LocalPlaceKind.APP_STORAGE,
        LocalPlaceKind.REMOVABLE_APP_STORAGE
    )

    fun resolve(remembered: LocalPlaceKind?, choices: List<LocalPlaceChoice>): LocalPlaceResolution {
        val byKind = choices.associateBy { it.place.kind }
        val wanted = remembered?.let(byKind::get)
        if (wanted != null && wanted.verified) return LocalPlaceResolution(wanted.place, choices, null)
        val fallback = preference.mapNotNull(byKind::get).firstOrNull { it.verified }
        val notice = when {
            fallback == null -> "No phone download folder passed Chunks' write check, so phone Adds are unavailable."
            remembered != null && wanted == null ->
                "Your saved download location is not available on this phone now. " +
                    "New Adds use ${fallback.place.title} instead."
            wanted != null ->
                "Your saved download location (${wanted.place.title}) failed its write check: " +
                    "${wanted.failure}. New Adds use ${fallback.place.title} instead."
            fallback.place.kind != LocalPlaceKind.PUBLIC_DOWNLOADS -> byKind[LocalPlaceKind.PUBLIC_DOWNLOADS]
                ?.let { "Downloads/Chunks Downloads is unavailable (${it.failure}). " +
                    "New Adds use ${fallback.place.title}." }
            else -> null
        }
        return LocalPlaceResolution(fallback?.place, choices, notice)
    }
}
