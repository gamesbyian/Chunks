package io.github.gamesbyian.chunks

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * One in-progress artwork choice for the phone, and one for each independently
 * authenticated server route + principal. Process memory only: source magnets,
 * signed links and account identifiers are never written to SavedState.
 *
 * This is presentation draft state, never Add submission authority. The
 * UniversalAddIntent freezes the selected value with the exact target and
 * source; the Add coordinator alone owns the attempted-write fence.
 */
internal class AddArtworkDraftChoices {
    private val phone = mutableStateOf(true)
    private val serverChoices = mutableMapOf<String, MutableState<Boolean>>()

    fun onPhone(): MutableState<Boolean> = phone

    /** The account scope includes the exact endpoint/numbered route AND principal. */
    fun onServer(exactAccountScope: String): MutableState<Boolean> {
        require(exactAccountScope.isNotBlank()) { "An authenticated Add account is required" }
        return serverChoices.getOrPut(exactAccountScope) { mutableStateOf(true) }
    }
}
