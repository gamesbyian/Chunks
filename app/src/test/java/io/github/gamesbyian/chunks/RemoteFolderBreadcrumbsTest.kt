package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Breadcrumbs never offer navigation above or beside the observed server root. */
class RemoteFolderBreadcrumbsTest {
    @Test fun rootOnlyHasNoClickableAncestor() {
        assertEquals(
            listOf(RemoteFolderCrumb("/srv/wood/downloads", "Root")),
            remoteFolderBreadcrumbs("/srv/wood/downloads", "/srv/wood/downloads")
        )
    }

    @Test fun nestedLocationHasExactOrderedRootBoundedTargets() {
        assertEquals(
            listOf(
                RemoteFolderCrumb("/srv/wood/downloads", "Root"),
                RemoteFolderCrumb("/srv/wood/downloads/TV", "TV"),
                RemoteFolderCrumb("/srv/wood/downloads/TV/Season 1", "Season 1")
            ),
            remoteFolderBreadcrumbs("/srv/wood/downloads",
                "/srv/wood/downloads/TV/Season 1")
        )
    }

    @Test fun traversalAndSiblingPrefixesAreNeverClickable() {
        for (candidate in listOf(
            "/srv/wood", "/srv/wood/downloads-other",
            "/srv/wood/downloads/../private", "/other/account", ""
        )) {
            assertTrue(remoteFolderBreadcrumbs("/srv/wood/downloads", candidate).isEmpty())
        }
        assertTrue(remoteFolderBreadcrumbs(null, "/srv/wood/downloads").isEmpty())
        assertTrue(remoteFolderBreadcrumbs("/srv/wood/downloads", null).isEmpty())
    }

    @Test fun manualModeStartsAtTheActuallyBrowsedFolderWithoutDiscardingTypedDraft() {
        assertEquals("/srv/wood/downloads/TV",
            remoteManualEntrySeed("/srv/wood/downloads", false,
                "/srv/wood/downloads/TV"))
        // After one manual edit, toggling Browse and returning cannot replace
        // the user-entered path with a different browsed directory.
        assertEquals("/srv/wood/custom",
            remoteManualEntrySeed("/srv/wood/custom", true,
                "/srv/wood/downloads/Movies"))
        // Manual fallback when listing is unavailable is still usable.
        assertEquals("/srv/wood/initial",
            remoteManualEntrySeed("/srv/wood/initial", false, null))
    }

    @Test fun filesystemRootDoesNotProduceDoubleSlashes() {
        assertEquals(
            listOf(RemoteFolderCrumb("/", "Root"), RemoteFolderCrumb("/TV", "TV")),
            remoteFolderBreadcrumbs("/", "/TV")
        )
    }
}
