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

    @Test fun filesystemRootDoesNotProduceDoubleSlashes() {
        assertEquals(
            listOf(RemoteFolderCrumb("/", "Root"), RemoteFolderCrumb("/TV", "TV")),
            remoteFolderBreadcrumbs("/", "/TV")
        )
    }
}
