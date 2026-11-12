package io.github.gamesbyian.chunks

import java.io.File

/**
 * Local Add pre-write gate. Only an explicit Add confirmation may call this.
 * Verify the actual selected Place before persisting an opt-out for a torrent
 * that may never be admitted. An opt-out still commits before libtorrent sees
 * the immutable, source/Place/Start-frozen Add ticket.
 *
 * The callback performs only the existing exact environment+hash preference
 * write; this function never adds, starts, retries or cleans up a torrent.
 */
internal sealed interface LocalAddArtworkPreflight {
    // Avoid data-class toString(): a private download Place is not diagnostic text.
    class Ready(val folder: File) : LocalAddArtworkPreflight
    data object InvalidPlace : LocalAddArtworkPreflight
    data object PreferenceNotSaved : LocalAddArtworkPreflight
}

internal fun prepareLocalAddPlaceAndArtwork(
    root: File?,
    subfolder: String,
    allowArtwork: Boolean,
    persistExactExclusion: () -> Boolean
): LocalAddArtworkPreflight {
    val folder = LocalOwnedAddFolder.prepare(root, subfolder)
        ?: return LocalAddArtworkPreflight.InvalidPlace
    if (!allowArtwork && !persistExactExclusion())
        return LocalAddArtworkPreflight.PreferenceNotSaved
    return LocalAddArtworkPreflight.Ready(folder)
}
