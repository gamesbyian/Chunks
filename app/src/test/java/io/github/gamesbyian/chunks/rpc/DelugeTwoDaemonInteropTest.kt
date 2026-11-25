package io.github.gamesbyian.chunks.rpc

import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #2072: one Deluge Web UI shares ONE daemon slot across every session. With two
 * real daemons registered, a client bound to daemon A must never read or write
 * through daemon B after something else switched the slot. Env-gated like the
 * other interop tests (CHUNKS_INTEROP_DELUGE2_*).
 */
class DelugeTwoDaemonInteropTest {
    private fun env(name: String) = System.getenv("CHUNKS_INTEROP_DELUGE2_$name").orEmpty()

    @Test fun switchedSlotIsRefusedAndNeverWritesToTheOtherDaemon(): Unit = runBlocking {
        assumeTrue(env("URL").isNotBlank() && env("HOST_A").isNotBlank() && env("HOST_B").isNotBlank())
        val credentials = RemoteCredentials(env("URL"), "", env("PASS"))
        val a = DelugeClient(credentials, env("HOST_A"))
        val b = DelugeClient(credentials, env("HOST_B"))

        // Control: A answers while the slot is bound to A.
        val aBefore = a.torrents().map { it.hash }
        println("CHUNKS_DELUGE2: bound_a_read=ok count=${aBefore.size}")

        // Another session (B) takes the process-global slot.
        val bBefore = b.torrents().map { it.hash }
        println("CHUNKS_DELUGE2: switched_to_b count=${bBefore.size}")

        // A must now refuse before returning anything from B.
        val readRefused = try { a.torrents(); false } catch (e: Exception) { true }
        assertTrue("A must not read through B's slot", readRefused)
        println("CHUNKS_DELUGE2: stale_read=refused")

        val hash = ByteArray(20).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val magnet = ClientAddSource.Magnet(
            "magnet:?xt=urn:btih:$hash&dn=chunks-two-daemon&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce")
        val writeRefused = try {
            a.add(ClientAddRequest(magnet, null, startNow = false)); false
        } catch (e: Exception) { true }
        assertTrue("A must not write through B's slot", writeRefused)
        println("CHUNKS_DELUGE2: stale_write=refused")

        // Independent readback: the probe hash exists on neither daemon.
        assertFalse("write leaked to B", b.torrents().any { it.hash == hash })
        println("CHUNKS_DELUGE2: b_unchanged=true")
        val a2 = DelugeClient(credentials, env("HOST_A"))
        assertFalse("write reached A despite refusal", a2.torrents().any { it.hash == hash })
        assertEquals("A inventory changed", aBefore.toSet(), a2.torrents().map { it.hash }.toSet())
        println("CHUNKS_DELUGE2: a_unchanged=true rebind=ok")
    }
}
