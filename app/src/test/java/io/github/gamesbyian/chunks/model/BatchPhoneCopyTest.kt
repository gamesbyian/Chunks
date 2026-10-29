package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.BatchPhoneCopy.Item
import io.github.gamesbyian.chunks.model.BatchPhoneCopy.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchPhoneCopyTest {
    private fun status(hash: String) = LocalTransferStatus(infoHash = hash, name = "n",
        state = LocalTransferState.DOWNLOADING_METADATA, stopped = true, progressPpm = 0, wantedBytes = 0,
        doneBytes = 0, payloadDownloadedBytes = 0, payloadUploadedBytes = 0, peers = 0, savePath = "/p", error = null)
    private fun item(n: Int) = Item("%040x".format(n), "T$n")

    @Test fun addsInOrderAndClassifiesDuplicatesAndRefusals() = runBlocking {
        val added = mutableListOf<String>()
        val report = BatchPhoneCopy.run((1..4).map(::item),
            magnetFor = { if (it.name == "T3") null else "magnet:?xt=urn:btih:${it.infoHash}" },
            addMetadataOnly = { m -> added += m
                when {
                    m.endsWith(item(2).infoHash) -> LocalAddResult.Refused("This torrent is already in the local engine")
                    m.endsWith(item(4).infoHash) -> LocalAddResult.Refused("Choose an existing folder for this download")
                    else -> LocalAddResult.FetchingMetadata(status(m.takeLast(40)))
                }
            })
        assertEquals(listOf(Result.ADDED, Result.ALREADY_ON_PHONE, Result.NO_MAGNET, Result.REFUSED),
            report.results.map { it.second })
        assertEquals(3, added.size)
        assertTrue(report.summary().startsWith("1 added to the phone, 1 already there"))
    }

    @Test fun stopsAtFirstUnknownOutcome() = runBlocking {
        var calls = 0
        val report = BatchPhoneCopy.run((1..3).map(::item), { "magnet:?xt=urn:btih:${it.infoHash}" },
            { calls++; if (calls == 1) error("JNI threw") else LocalAddResult.FetchingMetadata(status(it.takeLast(40))) })
        assertEquals(1, calls)
        assertEquals(listOf(Result.UNCONFIRMED, Result.NOT_RUN, Result.NOT_RUN), report.results.map { it.second })
    }

    @Test fun validatesSelection() {
        assertNotNull(BatchPhoneCopy.validate(emptyList()))
        assertNotNull(BatchPhoneCopy.validate(listOf(item(1), item(1).copy(infoHash = item(1).infoHash.uppercase()))))
        assertNull(BatchPhoneCopy.validate(listOf(item(1), item(2))))
    }
}
