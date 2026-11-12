package io.github.gamesbyian.chunks

/**
 * A native Remove acknowledgement and a read-only current-session observation
 * are independent facts. A failed final resume commit can leave the handle
 * absent even when removeKeepingData returns false. Do not imply an automatic
 * retry is safe, or that session absence proves the on-disk payload was deleted.
 *
 * visibleAfter is null when the engine/readback is unavailable.
 */
internal fun localRemoveReadbackFeedback(
    acknowledged: Boolean?,
    visibleAfter: Boolean?
): String? = when {
    acknowledged == true && visibleAfter == false -> null
    acknowledged == true && visibleAfter == true ->
        "Native Remove was requested, but this exact transfer is still listed. Refresh the local device list before deciding what to do next. Downloaded files were kept."
    acknowledged == false && visibleAfter == false ->
        "The exact transfer is absent from the current local session, but Remove persistence was not confirmed. Refresh before re-adding; do not repeat Remove blindly. Downloaded files were not requested for deletion."
    acknowledged == false && visibleAfter == true ->
        "Remove was not confirmed and this exact transfer is still present. Refresh the local device list, then review a new Remove attempt if it remains. Downloaded files were kept."
    acknowledged == null && visibleAfter == false ->
        "The exact transfer is absent from this device session after an uncertain Remove. The native result could not be confirmed; refresh and verify saved state before re-adding. Never repeat Remove blindly. Downloaded files were not requested for deletion."
    acknowledged == null && visibleAfter == true ->
        "The exact transfer is still present after an uncertain Remove. Refresh its current state before reviewing any new attempt; no automatic retry was sent. Downloaded files were not requested for deletion."
    else ->
        "Remove outcome could not be verified against the local device engine. Refresh the transfer list before another action. Downloaded files were not requested for deletion."
}
