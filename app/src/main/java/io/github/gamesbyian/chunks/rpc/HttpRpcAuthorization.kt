package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * A single-challenge, single-retry Basic/Digest HTTP authorization helper.
 *
 * Never caches credentials in process-global Authenticator and never attaches
 * them to a redirect or a different endpoint. Prefer Digest when offered.
 * No server challenge strings, password, realm or nonce enter exceptions/logs.
 */
internal object HttpRpcAuthorization {
    fun fromChallenge(
        headers: List<String>,
        credentials: RemoteCredentials,
        method: String,
        requestTarget: String
    ): String {
        if (credentials.username.isEmpty()) throw IOException("RPC authentication required")
        // Basic uses the first ':' to delimit the username. Digest places the
        // username directly in a quoted HTTP header value. Refuse identities
        // that could select a different Basic principal or inject header
        // controls before either scheme can construct Authorization.
        if (credentials.username.length > 256 ||
            credentials.username.any { it == ':' || it.isISOControl() ||
                it == '\u2028' || it == '\u2029' }) {
            throw IOException("Invalid RPC authentication identity")
        }
        val challenges = supportedChallenges(headers)
        val digest = challenges.firstOrNull { schemeOf(it) == "Digest" }
        if (digest != null) {
            val values = parse(digest.trimStart().substring(6).trimStart(' ', '\t'))
            return digestHeader(values, credentials, method, requestTarget)
        }
        val basic = challenges.firstOrNull { schemeOf(it) == "Basic" }
        if (basic != null) {
            val bytes = (credentials.username + ":" + credentials.password).toByteArray(Charsets.UTF_8)
            return "Basic " + Base64.getEncoder().encodeToString(bytes)
        }
        throw IOException("Unsupported RPC authentication challenge")
    }

    /**
     * RFC 9110 requires SP between auth-scheme and credentials. Recognize
     * nonconforming HTAB as well so a tabbed Digest challenge cannot be
     * silently overlooked in favor of a coadvertised Basic challenge.
     * Never match scheme words inside quoted parameters or longer tokens.
     */
    private fun schemeOf(candidate: String): String? {
        val value = candidate.trimStart()
        return listOf("Digest", "Basic").firstOrNull { name ->
            value.length > name.length && value.regionMatches(0, name, 0, name.length, true) &&
                (value[name.length] == ' ' || value[name.length] == '\t')
        }
    }

    /**
     * WWW-Authenticate may carry several challenges in one field as well as
     * in separate fields. Split only at unquoted commas introducing a
     * supported scheme. Quoted Digest nonce/opaque/qop commas are parameters.
     *
     * Parse the whole field before choosing a scheme: malformed quotation
     * cannot trick us into choosing weaker Basic authentication.
     */
    private fun supportedChallenges(headers: List<String>): List<String> {
        val choices = mutableListOf<String>()
        for (header in headers) {
            if (header.length !in 1..8192 ||
                header.any { it == '\r' || it == '\n' || (it.code < 32 && it != '\t') }) {
                throw IOException("Invalid RPC authentication challenge")
            }
            val boundaries = mutableListOf(0)
            var inQuotes = false
            var escaped = false
            for (index in header.indices) {
                val ch = header[index]
                if (inQuotes) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') inQuotes = false
                    continue
                }
                if (ch == '"') {
                    inQuotes = true
                    continue
                }
                if (ch != ',') continue
                val next = header.substring(index + 1).trimStart()
                if (schemeOf(next) != null) boundaries += index + 1
            }
            if (inQuotes || escaped) throw IOException("Malformed RPC authentication challenge")
            for (index in boundaries.indices) {
                val end = if (index == boundaries.lastIndex) header.length else boundaries[index + 1] - 1
                val candidate = header.substring(boundaries[index], end).trim()
                if (schemeOf(candidate) != null) choices += candidate
            }
        }
        return choices
    }

    private fun parse(header: String): Map<String, String> {
        if (header.length !in 1..8192 ||
            header.any { it == '\r' || it == '\n' || (it.code < 32 && it != '\t') }) {
            throw IOException("Invalid RPC authentication challenge")
        }
        val out = linkedMapOf<String, String>()
        var i = 0
        while (i < header.length) {
            while (i < header.length && (header[i] == ',' || header[i].isWhitespace())) i++
            if (i == header.length) break
            val start = i
            while (i < header.length && (header[i].isLetterOrDigit() || header[i] == '-')) i++
            if (i == start) throw IOException("Malformed Digest authentication challenge")
            val key = header.substring(start, i).lowercase(Locale.ROOT)
            while (i < header.length && header[i].isWhitespace()) i++
            if (i >= header.length || header[i] != '=') throw IOException("Malformed Digest authentication challenge")
            i++
            while (i < header.length && header[i].isWhitespace()) i++
            val value: String
            if (i < header.length && header[i] == '"') {
                i++
                val string = StringBuilder()
                var ended = false
                while (i < header.length) {
                    val c = header[i++]
                    if (c == '"') { ended = true; break }
                    if (c == '\\' && i < header.length) string.append(header[i++])
                    else string.append(c)
                }
                if (!ended) throw IOException("Malformed Digest authentication challenge")
                value = string.toString()
            } else {
                val begin = i
                while (i < header.length && header[i] != ',') i++
                value = header.substring(begin, i).trim()
            }
            if (out.put(key, value) != null) throw IOException("Repeated Digest challenge parameter")
            while (i < header.length && header[i].isWhitespace()) i++
            if (i < header.length && header[i] != ',') throw IOException("Malformed Digest authentication challenge")
        }
        return out
    }

    private fun digestHeader(
        p: Map<String, String>, auth: RemoteCredentials, method: String, target: String
    ): String {
        val realm = p["realm"] ?: throw IOException("Missing Digest realm")
        val nonce = p["nonce"]?.takeIf { it.isNotBlank() } ?: throw IOException("Missing Digest nonce")
        val algorithm = (p["algorithm"] ?: "MD5").uppercase(Locale.ROOT)
        val digestAlgorithm = when (algorithm) {
            "MD5", "MD5-SESS" -> "MD5"
            "SHA-256", "SHA-256-SESS" -> "SHA-256"
            else -> throw IOException("Unsupported Digest algorithm")
        }
        if (p["userhash"]?.equals("true", true) == true) throw IOException("Unsupported Digest userhash")
        if (p["charset"] != null && !p.getValue("charset").equals("UTF-8", true)) {
            throw IOException("Unsupported Digest character set")
        }
        val qop = p["qop"]?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }
        if (qop != null && "auth" !in qop) throw IOException("Unsupported Digest qop")
        if (!target.startsWith("/") || target.contains('\r') || target.contains('\n')) {
            throw IOException("Invalid Digest request target")
        }
        val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cnonce = hex(random)
        val nc = "00000001"
        fun hash(value: String): String = hex(MessageDigest.getInstance(digestAlgorithm)
            .digest(value.toByteArray(Charsets.UTF_8)))
        val initialHa1 = hash(auth.username + ":" + realm + ":" + auth.password)
        val ha1 = if (algorithm.endsWith("-SESS")) hash(initialHa1 + ":" + nonce + ":" + cnonce)
                  else initialHa1
        val ha2 = hash(method + ":" + target)
        val response = if (qop != null) hash(ha1 + ":" + nonce + ":" + nc +
            ":" + cnonce + ":auth:" + ha2)
            else hash(ha1 + ":" + nonce + ":" + ha2)
        fun q(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val fields = mutableListOf(
            "username=" + q(auth.username),
            "realm=" + q(realm),
            "nonce=" + q(nonce),
            "uri=" + q(target),
            "response=" + q(response),
            "algorithm=" + algorithm
        )
        if (qop != null) {
            fields += "qop=auth"
            fields += "nc=" + nc
            fields += "cnonce=" + q(cnonce)
        } else if (algorithm.endsWith("-SESS")) {
            fields += "cnonce=" + q(cnonce)
        }
        p["opaque"]?.let { fields += "opaque=" + q(it) }
        return "Digest " + fields.joinToString(", ")
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
