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

/** [failure] is null only when the Place passed its write probe this session. */
internal data class LocalPlaceChoice(val place: LocalDownloadPlace, val failure: String?) {
    val verified get() = failure == null
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
