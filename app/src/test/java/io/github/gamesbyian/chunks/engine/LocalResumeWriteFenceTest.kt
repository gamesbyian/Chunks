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
    @Test fun twoWritersForSameHandleNeverOverlapTheirTemporaryFileWrites() {
        val handles = mutableMapOf("abc" to "current-handle")
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val writes = mutableListOf<String>()
        try {
            val initialAdd = workers.submit<Boolean> {
                LocalResumeWriteFence.writeIfCurrent(handles, "abc", "current-handle") {
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    writes.add("initial-add")
                }.also { firstFinished.countDown() }
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val asyncSave = workers.submit<Boolean> {
                LocalResumeWriteFence.writeIfCurrent(handles, "abc", "current-handle") {
                    secondEntered.countDown()
                    writes.add("async-save")
                }
            }
            assertFalse("A second writer must not enter while the first owns the resume temp file",
                secondEntered.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertTrue(initialAdd.get(3, TimeUnit.SECONDS))
            assertTrue(firstFinished.await(2, TimeUnit.SECONDS))
            assertTrue(asyncSave.get(3, TimeUnit.SECONDS))
            assertEquals(listOf("initial-add", "async-save"), writes)
        } finally {
            releaseFirst.countDown()
            workers.shutdownNow()
        }
    }

}
