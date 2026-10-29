package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.model.BatchServerCopy.Item
import io.github.gamesbyian.chunks.model.BatchServerCopy.Result
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchServerCopyTest {
    /** A minimal single-file v1 torrent and its exact info-hash. */
    private fun torrent(name: String): Pair<String, ByteArray> {
        val info = "d6:lengthi1e4:name${name.length}:${name}12:piece lengthi16384e6:pieces20:" +
            "a".repeat(20) + "e"
        val hash = MessageDigest.getInstance("SHA-1").digest(info.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return hash to ("d4:info" + info + "e").toByteArray(Charsets.ISO_8859_1)
    }

    @Test fun addsInOrderSkipsDuplicatesAndMissingDefinitionsWithoutStopping() = runBlocking {
        val (h1, t1) = torrent("one"); val (h2, t2) = torrent("two"); val (h3, _) = torrent("three")
        val (h4, t4) = torrent("four")
        val defs = mapOf(h1 to t1, h2 to t2, h4 to t4)
        val sent = ArrayList<ByteArray>()
        val report = BatchServerCopy.run(listOf(Item(h1, "one"), Item(h2, "two"), Item(h3, "three"), Item(h4, "four")),
            exportTorrent = { defs[it] },
            addStopped = { bytes -> sent += bytes
                if (bytes.contentEquals(t2)) RemoteAddOutcome.AlreadyPresent(h2)
                else RemoteAddOutcome.VerifiedStopped("x", "/srv") })
        assertEquals(listOf(Result.ADDED, Result.ALREADY_ON_SERVER, Result.NO_DEFINITION, Result.ADDED),
            report.results.map { it.second })
        assertEquals(3, sent.size)
        assertFalse(report.stoppedEarly)
        assertTrue(report.summary().contains("2 added, 1 already there, 1 skipped"))
    }

    @Test fun stopsAtFirstUnconfirmedAddAndNeverRetriesIt() = runBlocking {
        val (h1, t1) = torrent("one"); val (h2, t2) = torrent("two"); val (h3, t3) = torrent("three")
        val defs = mapOf(h1 to t1, h2 to t2, h3 to t3)
        var calls = 0
        val report = BatchServerCopy.run(listOf(Item(h1, "1"), Item(h2, "2"), Item(h3, "3")),
            exportTorrent = { defs[it] },
            addStopped = { calls++; if (calls == 2) error("lost reply") else RemoteAddOutcome.VerifiedStopped("x", "/") })
        assertEquals(2, calls)
        assertEquals(listOf(Result.ADDED, Result.UNCONFIRMED, Result.NOT_RUN), report.results.map { it.second })
        assertTrue(report.stoppedEarly)
        val uncertain = BatchServerCopy.run(listOf(Item(h1, "1"), Item(h2, "2")), { defs[it] },
            { RemoteAddOutcome.Uncertain(h1) })
        assertEquals(listOf(Result.UNCONFIRMED, Result.NOT_RUN), uncertain.results.map { it.second })
    }

    @Test fun mismatchedDefinitionIsNeverSentAndSelectionIsValidated() = runBlocking {
        val (h1, _) = torrent("one"); val (_, other) = torrent("other")
        var sent = 0
        val report = BatchServerCopy.run(listOf(Item(h1, "one")), { other }, { sent++; RemoteAddOutcome.VerifiedStopped("x", "/") })
        assertEquals(Result.NO_DEFINITION, report.results.single().second)
        assertEquals(0, sent)
        assertNotNull(BatchServerCopy.validate(emptyList()))
        assertNotNull(BatchServerCopy.validate(listOf(Item(h1, "a"), Item(h1.uppercase(), "b"))))
        assertNotNull(BatchServerCopy.validate(listOf(Item("ab".repeat(32), "v2"))))
        assertNotNull(BatchServerCopy.validate((0 until 101).map { Item("%040x".format(it), "n") }))
    }
}
