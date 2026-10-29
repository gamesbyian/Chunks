package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.NoRouteToHostException
import java.io.IOException
import kotlinx.coroutines.CancellationException
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

    /** One tried candidate, safe to show: description, path only, outcome. */
    data class Attempt(val description: String, val path: String, val outcome: String)

    class ConnectionFailed(
        val category: Category,
        val tested: Int,
        val attempts: List<Attempt> = emptyList()
    ) : IOException(
        when (category) {
            Category.AUTH -> "Login rejected. Check the username/password for this rTorrent instance."
            Category.TLS -> "Secure connection failed. Check the HTTPS hostname/certificate. Chunks will not bypass TLS."
            Category.NETWORK -> "The server is unreachable or did not respond. Check its address and network."
            Category.ENDPOINT -> "No compatible rTorrent XML-RPC endpoint was verified. Paste the complete ruTorrent URL or enter the RPC path in Advanced settings."
        }
    )

    enum class Category { AUTH, TLS, NETWORK, ENDPOINT }

    /** Targeted next steps for a failed connection; never echoes secrets. */
    fun repairAdvice(failure: ConnectionFailed, input: String): List<String> {
        val explicit = "://" in input && try {
            URI(input.trim()).rawPath.orEmpty().trim('/').isNotEmpty()
        } catch (_: Exception) { false }
        return when (failure.category) {
            Category.AUTH -> listOf(
                "Use the same username and password that open ruTorrent in your browser.",
                "Some providers use a separate web-UI password from the SSH/FTP one.",
                "Chunks stopped after the login was refused so repeated guesses cannot lock your account.")
            Category.TLS -> listOf(
                "Open the same address in your phone's browser and check that it shows a valid padlock.",
                "If your server uses a self-signed certificate, Chunks cannot connect to it yet; it will not bypass certificate checks.")
            Category.NETWORK -> listOf(
                "Check the hostname for typos and that the phone is online.",
                "If the server is only reachable on a home network or VPN, connect to that network first.")
            Category.ENDPOINT -> buildList {
                if (!explicit) add("Open ruTorrent in your browser and paste the full address from the address bar " +
                    "(for example https://host/username/rutorrent/).")
                else add("Check that this exact address opens ruTorrent or your provider's RPC page in a browser.")
                add("If your provider documents an XML-RPC address (often ending in /RPC2 or /RPC1), paste that instead.")
                if (failure.attempts.any { it.outcome.startsWith("No rTorrent gateway") })
                    add("Addresses answering “not found” are not the right path on this server.")
            }
        }
    }

    /** Short user-facing reason for one candidate's failure. */
    internal fun describe(failure: Exception): String {
        val message = failure.message.orEmpty()
        val status = Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            failure is RpcFault -> "Answered, but rTorrent refused the read-only check"
            status == 404 -> "No rTorrent gateway here (HTTP 404)"
            status == 401 || status == 403 -> "Login refused (HTTP $status)"
            status in 300..399 -> "Redirected (HTTP $status); Chunks does not follow redirects with your login"
            status != null -> "Server error (HTTP $status)"
            failure is RpcReplyTooLarge -> "Answered with an unexpectedly large reply"
            else -> "Answered, but not with an rTorrent XML-RPC reply"
        }
    }

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
            (uri.rawAuthority?.length?.let { it in 1..255 } == true)) {
            "Enter a host/URL without embedded credentials, query or fragment"
        }
        val origin = "${uri.scheme.lowercase()}://${uri.rawAuthority}"
        val rawPath = uri.rawPath.orEmpty().trimEnd('/')
        // Avoid URI normalization/path traversal changing the intended tenant
        // or accidentally selecting a different backend mount.
        require(!Regex("(?i)(?:^|/)(?:\\.|%2e){1,2}(?:/|$)").containsMatchIn(rawPath)) {
            "Unsafe path segments in server address"
        }
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
            // Feral documents a separate /ACCOUNT/rtorrent/rpc gateway.
            // ACCOUNT is taken only from the pasted ruTorrent path, *not*
            // from the HTTP auth username (often literally "rutorrent").
            if (uri.host.endsWith(".feralhosting.com", true) &&
                Regex("^/[^/]+/rutorrent$", RegexOption.IGNORE_CASE).matches(webRoot)) {
                add(webRoot.removeSuffix("/rutorrent") + "/rtorrent/rpc", "Feral nginx XML-RPC")
            }
            add("$webRoot/plugins/httprpc/action.php", "This ruTorrent instance")
            return entries.values.toList()
        }
        if (rawPath.isNotBlank()) {
            // /RPC1, /RPC2, /xmlrpc, /username, and custom mounts are exact.
            // Refuse to search other mounts behind an explicit URL.
            add(rawPath, "Provided RPC address")
            return entries.values.toList()
        }

        val safeUser = username.trim().takeIf {
            it.length in 1..64 && Regex("[A-Za-z0-9_.-]+").matches(it) &&
                it != "." && it != ".."
        }
        // Reviewed provider templates (source URL + date in the catalog), or
        // bounded generic nginx/Apache patterns. Custom providers remain
        // possible through an explicit, user-pasted full URL.
        for ((path, description) in RemoteProviderCatalog.plan(uri.host, safeUser)) add(path, description)
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
        /** Called before each candidate: 1-based index, total, candidate. */
        onAttempt: (Int, Int, Candidate) -> Unit = { _, _, _ -> },
        probe: suspend (RemoteCredentials) -> T
    ): Verified<T> {
        val choices = candidates(input, username)
        var attempted = 0
        val attempts = ArrayList<Attempt>()
        for (candidate in choices) {
            attempted++
            onAttempt(attempted, choices.size, candidate)
            val path = try { URI(candidate.endpoint).rawPath.orEmpty().ifEmpty { "/" } }
                catch (_: Exception) { "/" }
            val credentials = RemoteCredentials(candidate.endpoint, username, password)
            try {
                val result = probe(credentials)
                return Verified(credentials, result, candidate.description, attempted)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val category = when {
                    failure is SSLException -> Category.TLS
                    failure is UnknownHostException || failure is ConnectException ||
                        failure is SocketTimeoutException || failure is NoRouteToHostException -> Category.NETWORK
                    failure is IOException &&
                        Regex("(?i)HTTP (?:401|403)(?:\\D|$)").containsMatchIn(failure.message.orEmpty()) ->
                        Category.AUTH
                    failure is IOException && (failure.message.orEmpty().contains("authentication challenge", true) ||
                            failure.message.orEmpty().contains("authentication required", true)) ->
                        Category.AUTH
                    else -> Category.ENDPOINT
                }
                attempts += Attempt(candidate.description, path, when (category) {
                    Category.ENDPOINT -> describe(failure)
                    Category.AUTH -> "Login refused"
                    Category.TLS -> "Secure connection failed"
                    Category.NETWORK -> "Unreachable"
                })
                if (category != Category.ENDPOINT)
                    throw ConnectionFailed(category, attempted, attempts)
            }
        }
        throw ConnectionFailed(Category.ENDPOINT, attempted, attempts)
    }
}
