package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Legacy cache paths are never automatically deleted by name or timestamp. */
@RunWith(AndroidJUnit4::class)
class LocalZipAbandonedStagingInstrumentedTest {
    @Test fun preservesOldNamedReviewFilesBecauseNamesAndAgeCannotProveOwnership() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val now = System.currentTimeMillis()
        val old = File.createTempFile("chunks-zip-review-", ".zip", cache)
        val fresh = File.createTempFile("chunks-zip-review-", ".zip", cache)
        val unrelated = File.createTempFile("chunks-unrelated-", ".zip", cache)
        val directory = File(cache, "chunks-zip-review-folder.zip")
        try {
            old.writeText("old staged ZIP")
            fresh.writeText("active staged ZIP")
            unrelated.writeText("another application's cache")
            assertTrue(directory.mkdir())
            assertTrue(old.setLastModified(now - 48L * 60 * 60 * 1000))
            assertTrue(fresh.setLastModified(now - 60_000L))
            assertTrue(unrelated.setLastModified(now - 48L * 60 * 60 * 1000))
            assertEquals(0, LocalZipDocumentInspector.cleanAbandonedCopies(cache, now))
            assertTrue(old.exists())
            assertTrue(fresh.exists())
            assertTrue(unrelated.exists())
            assertTrue(directory.isDirectory)
            assertEquals(0, LocalZipDocumentInspector.cleanAbandonedCopies(cache, now))
            assertTrue(fresh.readText().contains("active staged ZIP"))
        } finally {
            old.delete()
            fresh.delete()
            unrelated.delete()
            directory.delete()
        }
    }

    @Test fun unknownAndFutureModifiedTimesAreNotDeleted() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val now = System.currentTimeMillis()
        val future = File.createTempFile("chunks-zip-review-", ".zip", cache)
        try {
            future.writeText("future timestamp")
            assertTrue(future.setLastModified(now + 60_000))
            LocalZipDocumentInspector.cleanAbandonedCopies(cache, now)
            assertTrue(future.exists())
        } finally {
            future.delete()
        }
    }
}
