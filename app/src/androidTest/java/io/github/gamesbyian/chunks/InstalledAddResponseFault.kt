package io.github.gamesbyian.chunks

import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals

/** Only talks to the CI runner's disposable response relay. */
internal class InstalledAddResponseFault(appUrl: String) {
    private val base = URI(appUrl).also { require(it.scheme == "http" && it.host == "127.0.0.1") }

    private fun request(action: String, post: Boolean = false): String {
        val control = URI("http", null, base.host, base.port, "/__chunks_add_response_fault/$action", null, null)
        val connection = control.toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            if (post) connection.requestMethod = "POST"
            assertEquals(if (post) 204 else 200, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun arm(mode: String) {
        request(mode, post = true)
    }

    private fun counts() = JSONObject(request("counts"))

    fun hasWithheldReply() = counts().getInt("acknowledgments_withheld") == 1

    fun requireOneWriteAndNoStart() {
        val counters = counts()
        assertEquals("Exactly one Add passed through the relay", 1, counters.getInt("adds"))
        assertEquals("Only one accepted daemon response was withheld", 1, counters.getInt("acknowledgments_withheld"))
        assertEquals("No recovery Start", 0, counters.getInt("starts"))
        assertEquals("The daemon accepted the write before the relay fault", 0, counters.getInt("upstream_errors"))
    }
}
