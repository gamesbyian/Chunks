package io.github.gamesbyian.chunks

import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real preferences-backed journal, read by a second owner as a restarted process would. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesReceiptJournalTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val key = ProtocolOperationReceiptOwner.Key("profile-a", "cd".repeat(20))

    private fun restartedOwner() = ProtocolOperationReceiptOwner().also {
        it.attach(SharedPreferencesReceiptJournal(context))
    }

    @Test fun possibleWriteSurvivesRestartWithoutRetainingSecrets() {
        val first = restartedOwner()
        val ticket = requireNotNull(first.begin(key, "secret-generation-token", ProtocolOperationKind.REMOVE_DATA))
        assertTrue(first.possibleWrite(ticket))

        val raw = context.getSharedPreferences("chunks_protocol_receipts_v1", 0).getString("entries", "")!!
        assertFalse(raw.contains("secret-generation-token"))

        val second = restartedOwner()
        val restored = requireNotNull(second.receipt(key))
        assertEquals(ProtocolOperationReceiptOwner.Phase.UNKNOWN, restored.phase)
        assertEquals(ProtocolOperationKind.REMOVE_DATA, restored.ticket.kind)
        assertNull(second.begin(key, "generation-2", ProtocolOperationKind.REMOVE))

        assertTrue(second.reconcile(restored.ticket, present = true))
        assertNull(restartedOwner().receipt(key))
    }

    @Test fun verifiedWriteLeavesNothingBehind() {
        val owner = restartedOwner()
        val ticket = requireNotNull(owner.begin(key, "g", ProtocolOperationKind.STOP))
        assertTrue(owner.possibleWrite(ticket))
        owner.complete(ticket, ClientOperationOutcome.Verified(key.hash, null))
        assertNull(restartedOwner().receipt(key))
        assertNotNull(restartedOwner().begin(key, "g", ProtocolOperationKind.START))
    }

    @Test fun corruptStoredTextIsIgnoredWithoutBlockingWrites() {
        context.getSharedPreferences("chunks_protocol_receipts_v1", 0).edit().putString("entries", "{not json").commit()
        val owner = ProtocolOperationReceiptOwner()
        owner.attach(SharedPreferencesReceiptJournal(context))
        assertNotNull(owner.begin(key, "g", ProtocolOperationKind.START))
    }
}
