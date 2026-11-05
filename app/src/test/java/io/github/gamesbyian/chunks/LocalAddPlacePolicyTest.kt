package io.github.gamesbyian.chunks

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LocalAddPlacePolicyTest {
    private fun choice(kind: LocalPlaceKind, root: String, failure: String? = null) =
        LocalPlaceChoice(LocalDownloadPlace(kind, File(root), kind.key, "detail"), failure)
    private val public = choice(LocalPlaceKind.PUBLIC_DOWNLOADS, "/downloads")
    private val app = choice(LocalPlaceKind.APP_STORAGE, "/app")
    private fun default(choices: List<LocalPlaceChoice> = listOf(public, app)) =
        LocalDownloadPlacePolicy.resolve(LocalPlaceKind.PUBLIC_DOWNLOADS, choices)

    @Test fun noOverrideUsesTheVerifiedDefault() {
        assertEquals(default(), LocalAddPlacePolicy.resolve(default(), null))
    }
    @Test fun oneTimeChoiceDoesNotChangeTheDefault() {
        val saved = default()
        val current = LocalAddPlacePolicy.resolve(saved, LocalAddPlacePolicy.identity(app.place))
        assertEquals(app.place, current.selected)
        assertEquals(LocalPlaceResolution.Provenance.CURRENT_ADD, current.provenance)
        assertEquals(public.place, saved.selected)
    }
    @Test fun unavailableOneTimeChoiceBlocksInsteadOfFallingBack() {
        val id = LocalAddPlacePolicy.identity(app.place)
        val current = LocalAddPlacePolicy.resolve(default(listOf(public, app.copy(failure = "revoked"))), id)
        assertNull(current.selected)
        assertTrue(current.notice!!.contains("Choose another location"))
        assertEquals(public.place, default().selected)
    }
    @Test fun anotherVolumeOfTheSameKindCannotReplaceAnExplicitChoice() {
        val sd = choice(LocalPlaceKind.REMOVABLE_APP_STORAGE, "/sd-one")
        val replacement = choice(LocalPlaceKind.REMOVABLE_APP_STORAGE, "/sd-two")
        val current = LocalAddPlacePolicy.resolve(default(listOf(public, replacement)),
            LocalAddPlacePolicy.identity(sd.place))
        assertNull(current.selected)
    }
    @Test fun recoveredChoiceResolvesWithoutChangingItsIdentity() {
        val id = LocalAddPlacePolicy.identity(app.place)
        assertNull(LocalAddPlacePolicy.resolve(default(listOf(public)), id).selected)
        assertEquals(app.place, LocalAddPlacePolicy.resolve(default(), id).selected)
    }
}
