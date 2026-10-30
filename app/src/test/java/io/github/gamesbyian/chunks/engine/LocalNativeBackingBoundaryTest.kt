package io.github.gamesbyian.chunks.engine

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalNativeBackingBoundaryTest {
    @Test fun permitsOwnedPrivateRootAndItsDirectories() {
        val privateRoot = Files.createTempDirectory("chunks-native-private").toFile()
        val child = Files.createDirectory(privateRoot.toPath().resolve("owned-job")).toFile()
        assertNull(LocalNativeBackingBoundary.refusal(privateRoot, privateRoot))
        assertNull(LocalNativeBackingBoundary.refusal(privateRoot, child))
    }

    @Test fun refusesSharedSiblingAndTraversal() {
        val parent = Files.createTempDirectory("chunks-native-parent")
        val privateRoot = Files.createDirectory(parent.resolve("private")).toFile()
        val shared = Files.createDirectory(parent.resolve("Downloads")).toFile()
        assertEquals(LocalNativeBackingBoundary.REFUSAL,
            LocalNativeBackingBoundary.refusal(privateRoot, shared))
        assertEquals(LocalNativeBackingBoundary.REFUSAL,
            LocalNativeBackingBoundary.refusal(privateRoot,
                privateRoot.resolve("../Downloads")))
    }

    @Test fun refusesSymlinkedSubfolderEvenWhenTargetIsInsideSandbox() {
        val privateRoot = Files.createTempDirectory("chunks-native-links")
        val owned = Files.createDirectory(privateRoot.resolve("owned"))
        val link = privateRoot.resolve("link")
        Files.createSymbolicLink(link, owned.fileName)
        assertNotNull(LocalNativeBackingBoundary.refusal(privateRoot.toFile(), link.toFile()))
    }

    @Test fun refusesMissingOrRenamedPrivateRoots() {
        val privateRoot = Files.createTempDirectory("chunks-native-lost")
        val missing = privateRoot.resolve("not-here").toFile()
        assertNotNull(LocalNativeBackingBoundary.refusal(privateRoot.toFile(), missing))
        val privateRootAlias = privateRoot.resolveSibling("not-created")
        assertNotNull(LocalNativeBackingBoundary.refusal(privateRootAlias.toFile(), privateRoot.toFile()))
    }
}
