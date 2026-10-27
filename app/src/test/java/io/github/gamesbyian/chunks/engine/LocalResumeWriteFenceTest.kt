package io.github.gamesbyian.chunks.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResumeWriteFenceTest {
    @Test fun staleAlertCannotResurrectADeletedResumeFile() {
        val handles = mutableMapOf("abc" to "first-handle")
        var fileExists = true
        synchronized(handles) { handles.remove("abc"); fileExists = false }
        assertFalse(LocalResumeWriteFence.writeIfCurrent(handles, "abc", "first-handle") {
            fileExists = true
        })
        assertFalse(fileExists)
    }

    @Test fun alertForPreviousHandleCannotOverwriteNewlyAddedTransfer() {
        val handles = mutableMapOf("abc" to "new-handle")
        var writes = 0
        assertFalse(LocalResumeWriteFence.writeIfCurrent(handles, "abc", "old-handle") { writes++ })
        assertEquals(0, writes)
        assertTrue(LocalResumeWriteFence.writeIfCurrent(handles, "abc", "new-handle") { writes++ })
        assertEquals(1, writes)
    }

    @Test fun removalWaitsForAdmittedWriterThenDeletesWithoutLateRecreation() {
        val handles = mutableMapOf("abc" to "first-handle")
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val removalFinished = CountDownLatch(1)
        var fileExists = false
        val workers = Executors.newFixedThreadPool(2)
        try {
            val writer = workers.submit<Boolean> {
                LocalResumeWriteFence.writeIfCurrent(handles, "abc", "first-handle") {
                    writerEntered.countDown()
                    check(releaseWriter.await(5, TimeUnit.SECONDS))
                    fileExists = true
                }
            }
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS))
            val remover = workers.submit {
                synchronized(handles) {
                    handles.remove("abc")
                    fileExists = false
                }
                removalFinished.countDown()
            }
            assertFalse("Remove must wait for an admitted save", removalFinished.await(100, TimeUnit.MILLISECONDS))
            releaseWriter.countDown()
            assertTrue(writer.get(3, TimeUnit.SECONDS))
            remover.get(3, TimeUnit.SECONDS)
            assertTrue(removalFinished.await(2, TimeUnit.SECONDS))
            assertFalse(fileExists)
            assertFalse(LocalResumeWriteFence.writeIfCurrent(handles, "abc", "first-handle") {
                fileExists = true
            })
            assertFalse(fileExists)
        } finally {
            releaseWriter.countDown()
            workers.shutdownNow()
        }
    }
}
