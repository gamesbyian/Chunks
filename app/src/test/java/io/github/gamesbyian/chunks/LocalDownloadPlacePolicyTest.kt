package io.github.gamesbyian.chunks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDownloadPlacePolicyTest {
    private fun choice(kind: LocalPlaceKind, failure: String? = null) = LocalPlaceChoice(
        LocalDownloadPlace(kind, File("/x/" + kind.key), kind.key + " title", "detail"), failure)

    private val publicOk = choice(LocalPlaceKind.PUBLIC_DOWNLOADS)
    private val appOk = choice(LocalPlaceKind.APP_STORAGE)
    private val sdOk = choice(LocalPlaceKind.REMOVABLE_APP_STORAGE)

    @Test fun verifiedPublicDownloadsIsTheDefaultWithoutNotice() {
        val resolved = LocalDownloadPlacePolicy.resolve(null, listOf(publicOk, appOk, sdOk))
        assertEquals(LocalPlaceKind.PUBLIC_DOWNLOADS, resolved.selected?.kind)
        assertNull(resolved.notice)
    }

    @Test fun rememberedVerifiedChoiceWins() {
        val resolved = LocalDownloadPlacePolicy.resolve(
            LocalPlaceKind.REMOVABLE_APP_STORAGE, listOf(publicOk, appOk, sdOk))
        assertEquals(LocalPlaceKind.REMOVABLE_APP_STORAGE, resolved.selected?.kind)
        assertNull(resolved.notice)
    }

    @Test fun missingOrFailedRememberedChoiceFallsBackVisibly() {
        val ejected = LocalDownloadPlacePolicy.resolve(
            LocalPlaceKind.REMOVABLE_APP_STORAGE, listOf(publicOk, appOk))
        assertEquals(LocalPlaceKind.PUBLIC_DOWNLOADS, ejected.selected?.kind)
        assertTrue(ejected.notice!!.contains("not available"))

        val failed = LocalDownloadPlacePolicy.resolve(LocalPlaceKind.REMOVABLE_APP_STORAGE,
            listOf(publicOk, appOk, choice(LocalPlaceKind.REMOVABLE_APP_STORAGE, "read-only")))
        assertEquals(LocalPlaceKind.PUBLIC_DOWNLOADS, failed.selected?.kind)
        assertTrue(failed.notice!!.contains("read-only"))
    }

    @Test fun unavailablePublicDownloadsIsStatedWhenAppStorageIsUsed() {
        val resolved = LocalDownloadPlacePolicy.resolve(null,
            listOf(choice(LocalPlaceKind.PUBLIC_DOWNLOADS, "Android 10 needs storage permission"), appOk))
        assertEquals(LocalPlaceKind.APP_STORAGE, resolved.selected?.kind)
        assertTrue(resolved.notice!!.contains("Android 10 needs storage permission"))
    }

    @Test fun rememberedAppStorageDoesNotWarnAboutPublicDownloads() {
        val resolved = LocalDownloadPlacePolicy.resolve(LocalPlaceKind.APP_STORAGE,
            listOf(choice(LocalPlaceKind.PUBLIC_DOWNLOADS, "unavailable"), appOk))
        assertEquals(LocalPlaceKind.APP_STORAGE, resolved.selected?.kind)
        assertNull(resolved.notice)
    }

    @Test fun noVerifiedPlaceMeansNoDefaultAndAnExplicitBlocker() {
        val resolved = LocalDownloadPlacePolicy.resolve(null,
            listOf(choice(LocalPlaceKind.PUBLIC_DOWNLOADS, "a"), choice(LocalPlaceKind.APP_STORAGE, "b")))
        assertNull(resolved.selected)
        assertTrue(resolved.notice!!.contains("unavailable"))
    }

    @Test fun unknownStoredKeyIsIgnored() {
        assertNull(LocalPlaceKind.fromKey("legacy-or-tampered"))
        assertEquals(LocalPlaceKind.APP_STORAGE, LocalPlaceKind.fromKey("app-storage"))
    }

    @Test fun android10NeedsLegacyStorageAndBothPermissionsBeforeTheWriteProbe() {
        val access = LocalPublicDownloadsAccess
        assertNull(access.refusal(30, legacyStorage = false, storagePermissionGranted = false))
        assertNull(access.refusal(37, legacyStorage = false, storagePermissionGranted = false))
        assertNull(access.refusal(29, legacyStorage = true, storagePermissionGranted = true))
        assertEquals(access.NEEDS_PERMISSION,
            access.refusal(29, legacyStorage = true, storagePermissionGranted = false))
        val scoped = access.refusal(29, legacyStorage = false, storagePermissionGranted = true)
        assertTrue(scoped != null && scoped != access.NEEDS_PERMISSION)
        assertTrue(access.refusal(28, legacyStorage = true, storagePermissionGranted = true) != null)
    }

    @Test fun permissionRefusalFallsBackVisiblyUntilGranted() {
        val waiting = LocalPlaceChoice(publicOk.place, LocalPublicDownloadsAccess.NEEDS_PERMISSION,
            needsStoragePermission = true)
        val resolved = LocalDownloadPlacePolicy.resolve(null, listOf(waiting, appOk))
        assertEquals(LocalPlaceKind.APP_STORAGE, resolved.selected?.kind)
        assertTrue(resolved.notice.orEmpty().contains(LocalPublicDownloadsAccess.NEEDS_PERMISSION))
        // Granting only removes the refusal; the regular write probe then decides.
        assertEquals(LocalPlaceKind.PUBLIC_DOWNLOADS,
            LocalDownloadPlacePolicy.resolve(null, listOf(publicOk, appOk)).selected?.kind)
    }
}
