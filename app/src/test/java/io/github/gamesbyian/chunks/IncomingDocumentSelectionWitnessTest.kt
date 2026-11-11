package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure source-choice tests: no provider, network, native engine or RPC write. */
class IncomingDocumentSelectionWitnessTest {
    private val sourceBytes = ("d4:infod6:lengthi5e4:name8:test.txt" +
        "12:piece lengthi16384e6:pieces20:01234567890123456789ee").toByteArray()
    private val preview by lazy { TorrentMetainfoReader.preview(sourceBytes) }
    private fun phone() = PendingLocalAdd(preview, sourceBytes.copyOf())
    private fun remote(account: String = "exact-account") = RemoteAddSession(account)
    private val text = ""

    @Test fun unchangedOrExplicitlyReviewedOldDraftCanBeReplaced() {
        val existing = phone()
        val handedOff = sourceBytes.copyOf()
        val session = remote()
        session.selectedBytes = sourceBytes.copyOf()
        session.useFile = true
        val witness = IncomingDocumentSelectionWitness(text, existing, handedOff, session)
        assertTrue("An explicitly approved replacement of an unchanged source is valid",
            witness.canApply(text, existing, handedOff, session))
        assertFalse("Even a valid existing source cannot be overridden after typing",
            witness.canApply("magnet:?xt=urn:btih:" + "a".repeat(40), existing, handedOff, session))
    }

    @Test fun anotherPhonePickerSelectionWinsEvenWithSameInfohash() {
        val prior = phone()
        val witness = IncomingDocumentSelectionWitness(text, prior, null, null)
        val newer = phone() // same infohash, distinct reviewed metainfo object
        assertFalse("Do not compare only hashes or names of document selections",
            witness.canApply(text, newer, null, null))
        assertTrue(witness.canApply(text, prior, null, null))
    }

    @Test fun remoteHandoffChangingDuringProviderReadRevokesOldSource() {
        val original = sourceBytes.copyOf()
        val changed = sourceBytes.copyOf()
        val witness = IncomingDocumentSelectionWitness(text, null, original, null)
        assertFalse("Byte array identity, not equals/hash, preserves exact source choice",
            witness.canApply(text, null, changed, null))
    }

    @Test fun newerRemotePickerAndModeSelectionOutrankIncomingRead() {
        val server = remote()
        val original = sourceBytes.copyOf()
        server.selectedBytes = original
        server.useFile = true
        val witness = IncomingDocumentSelectionWitness(text, null, null, server)
        server.selectedBytes = sourceBytes.copyOf()
        assertFalse("A later remote file picker must not be overwritten",
            witness.canApply(text, null, null, server))
        server.selectedBytes = original
        server.useFile = false
        assertFalse("Switching to a newer text source also revokes the stale read",
            witness.canApply(text, null, null, server))
    }

    @Test fun targetSwitchWithoutCompetingFileRetainsSource() {
        val first = remote("account-one")
        val second = remote("account-two")
        val witness = IncomingDocumentSelectionWitness(text, null, null, first)
        assertTrue("Changing an Add destination alone must preserve source",
            witness.canApply(text, null, null, second))
        second.useFile = true
        second.selectedBytes = sourceBytes.copyOf()
        assertFalse("An existing exact-account document wins over older Share read",
            witness.canApply(text, null, null, second))
        second.useFile = false
        second.selectedBytes = null
        first.selectedBytes = sourceBytes.copyOf()
        assertFalse("Old remote account changes also revoke late source authority",
            witness.canApply(text, null, null, second))
    }

    @Test fun shareStartingWithEmptyPhoneDraftCannotOverwriteNewFile() {
        val witness = IncomingDocumentSelectionWitness(text, null, null, null)
        assertFalse(witness.canApply(text, phone(), null, null))
        assertTrue(witness.canApply(text, null, null, null))
    }
}
