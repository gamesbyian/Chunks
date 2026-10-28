package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Bounded, same-origin, read-only discovery for rTorrent XML-RPC gateways.
 *
 * Only a bare host is eligible for multi-path discovery. A pasted ruTorrent
 * web root is mapped to its *own* plugin, and any explicit RPC address is
 * tested verbatim. In particular, rutorrent2 must never fall through to the
 * primary rutorrent instance and accidentally display/mutate its transfers.
 *
 * No insecure-HTTP downgrade, sibling-host guessing, redirects, DNS/IP scan,
 * password embedding, common-credential sweeps or mutations.
 */
object RemoteSmartConnect {
    data class Candidate(val endpoint: String, val description: String)
    data class Verified<T>(
        val credentials: RemoteCredentials,
        val response: T,
        val description: String,
        val attempts: Int
    )

    class ConnectionFailed(
        val category: Category,
        val tested: Int
    ) : IOException(
        when (category) {
            Category.AUTH -> "Login rejected. Check the username/password for this rTorrent instance."
            Category.TLS -> "Secure connection failed. Check the HTTPS hostname/certificate. Chunks will not bypass TLS."
            Category.NETWORK -> "The server is unreachable or did not respond. Check its address and network."
            Category.ENDPOINT -> "No compatible rTorrent XML-RPC endpoint was verified. Paste the complete ruTorrent URL or enter the RPC path in Advanced settings."
        }
    )

    enum class Category { AUTH, TLS, NETWORK, ENDPOINT }

    /** No network access, no login attempts, at most six HTTPS candidates. */
    fun candidates(input: String, username: String): List<Candidate> {
        val raw = input.trim()
        require(raw.isNotEmpty() && raw.length <= 2048) { "Enter a valid seedbox address" }
        // Accept a hostname pasted from a provider dashboard, without
        // asking the user to type https:// themselves.
        val prepared = if ("://" in raw) raw else "https://$raw"
        val uri = URI(prepared)
        require(uri.scheme.equals("https", true) ||
            (uri.scheme.equals("http", true) &&
                uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) {
            "A secure HTTPS address is required"
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawAuthority?.length in 1..255) {
            "Enter a host/URL without embedded credentials, query or fragment"
        }
        val origin = "${uri.scheme.lowercase()}://${uri.rawAuthority}"
        val rawPath = uri.rawPath.orEmpty().trimEnd('/')
        val entries = linkedMapOf<String, Candidate>()
        fun add(path: String, description: String) {
            if (entries.size >= 6) return
            val endpoint = RemoteEndpointHints.rpcAddress(origin + path)
            entries.putIfAbsent(endpoint, Candidate(endpoint, description))
        }

        // Full provider URLs take precedence. NEVER escape a chosen instance.
        val webRoot = when {
            Regex("(?i)(?:^|/)rutorrent[0-9]{0,3}$").containsMatchIn(rawPath) -> rawPath
            Regex("(?i)(?:^|/)rutorrent[0-9]{0,3}/index\\.(?:php|html)$")
                .containsMatchIn(rawPath) -> rawPath.substringBeforeLast('/')
            else -> null
        }
        if (webRoot != null) {
            add("$webRoot/plugins/httprpc/action.php", "This ruTorrent instance")
            return entries.values.toList()
        }
        if (rawPath.isNotBlank()) {
            // /RPC1, /RPC2, /xmlrpc, /username, and custom mounts are exact.
            // Refuse to search other mounts behind an explicit URL.
            add(rawPath, "Provided RPC address")
            return entries.values.toList()
        }

        val host = uri.host.lowercase()
        val safeUser = username.trim().takeIf {
            it.length in 1..64 && Regex("[A-Za-z0-9_.-]+").matches(it) &&
                it != "." && it != ".."
        }
        when {
            host == "seedhost.eu" || host.endsWith(".seedhost.eu") -> {
                // SeedHost documents both /username/RPC1 and an HTTP-RPC
                // route under the ruTorrent root, with deployment variance.
                if (safeUser != null) {
                    add("/$safeUser/rutorrent/plugins/httprpc/action.php", "SeedHost account ruTorrent")
                    add("/$safeUser/RPC1", "SeedHost account SCGI")
                }
                add("/rutorrent/plugins/httprpc/action.php", "SeedHost ruTorrent")
                add("/RPC1", "SeedHost direct RPC")
            }
            host == "usbx.me" || host.endsWith(".usbx.me") -> {
                add("/RPC2", "Ultra.cc direct RPC")
                add("/rutorrent/plugins/httprpc/action.php", "ruTorrent HTTP-RPC")
            }
            host == "whatbox.ca" || host.endsWith(".whatbox.ca") -> {
                add("/xmlrpc", "Whatbox XML-RPC")
                add("/rutorrent/plugins/httprpc/action.php", "ruTorrent HTTP-RPC")
            }
            host == "appboxes.co" || host.endsWith(".appboxes.co") -> {
                add("/RPC2", "Appbox direct RPC")
                add("/rutorrent/plugins/httprpc/action.php", "ruTorrent HTTP-RPC")
            }
            else -> {
                // Bounded generic nginx/Apache patterns. Custom providers
                // remain possible through an explicit, user-pasted full URL.
                add("/rutorrent/plugins/httprpc/action.php", "ruTorrent HTTP-RPC")
                add("/RPC2", "Common direct SCGI")
                add("/xmlrpc", "Common XML-RPC")
                if (safeUser != null) add("/$safeUser", "Account SCGI mount")
            }
        }
        return entries.values.toList()
    }

    /**
     * The probe must perform only idempotent reads: authenticated version and
     * a complete accepted transfer view. A 200 login page or a version-only
     * response must not be mistaken for a connected profile.
     *
     * A bad password/cert or unreachable host ends the process immediately:
     * guessing more paths could lock accounts or expose credentials.
     */
    suspend fun <T> connect(
        input: String,
        username: String,
        password: String,
        probe: suspend (RemoteCredentials) -> T
    ): Verified<T> {
        val choices = candidates(input, username)
        var attempted = 0
        for (candidate in choices) {
            attempted++
            val credentials = RemoteCredentials(candidate.endpoint, username, password)
            try {
                val result = probe(credentials)
                return Verified(credentials, result, candidate.description, attempted)
            } catch (failure: Exception) {
                val category = when {
                    failure is SSLException -> Category.TLS
                    failure is UnknownHostException || failure is ConnectException ||
                        failure is SocketTimeoutException -> Category.NETWORK
                    failure is IOException &&
                        Regex("(?i)HTTP (?:401|403)(?:\\D|$)").containsMatchIn(failure.message.orEmpty()) ->
                        Category.AUTH
                    else -> Category.ENDPOINT
                }
                if (category != Category.ENDPOINT)
                    throw ConnectionFailed(category, attempted)
            }
        }
        throw ConnectionFailed(Category.ENDPOINT, attempted)
    }
}
