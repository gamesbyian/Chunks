package io.github.gamesbyian.chunks.media.artwork

import io.github.gamesbyian.chunks.media.MediaHostPolicy
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Policy for a user-typed "Use image URL". The phone must never be turned into a probe of the
 * user's LAN, seedbox admin panel or router: HTTPS only, public DNS names only, and every resolved
 * address (on every redirect hop) must be globally routable. A URL is not proof of licence.
 */
object UserImageUrlPolicy {
    sealed class Check {
        data class Ok(val url: String) : Check()
        data class Refused(val reason: String) : Check()
    }

    fun check(raw: String): Check {
        val text = raw.trim()
        if (text.length > 2_000) return Check.Refused("Link is too long")
        val uri = runCatching { URI(text) }.getOrNull() ?: return Check.Refused("Not a valid link")
        if (!"https".equals(uri.scheme, ignoreCase = true)) return Check.Refused("Only https:// image links are supported")
        if (uri.rawUserInfo != null) return Check.Refused("Links with credentials are not allowed")
        if (uri.port != -1 && uri.port != 443) return Check.Refused("Only the standard HTTPS port is allowed")
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return Check.Refused("Link has no host")
        if (!isPublicHostName(host)) return Check.Refused("Local or private addresses are not allowed")
        return Check.Ok(uri.toString())
    }

    /** Syntactic screen; [hostPolicy] additionally checks resolved addresses at request time. */
    fun isPublicHostName(host: String): Boolean {
        if (host.isEmpty() || host == "localhost" || !host.contains('.')) return false
        if (listOf(".local", ".lan", ".home", ".internal", ".localhost", ".arpa", ".onion", ".corp", ".home.arpa").any { host.endsWith(it) }) return false
        // IP literals are refused outright: users paste names, and literals are the common SSRF vector.
        if (host.startsWith("[") || host.matches(Regex("[0-9.]+")) || host.matches(Regex("[0-9a-f:]+"))) return false
        return host.matches(Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+"))
    }

    fun isPublicAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address.map { it.toInt() and 0xFF }
        return when (a) {
            is Inet4Address -> !(b[0] == 0 || b[0] == 10 || b[0] == 127 || (b[0] == 100 && b[1] in 64..127) ||
                (b[0] == 169 && b[1] == 254) || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 192 && b[1] == 0 && b[2] == 0) || (b[0] == 198 && b[1] in 18..19) || b[0] >= 224)
            is Inet6Address -> !((b[0] and 0xFE) == 0xFC || (b[0] == 0xFE && (b[1] and 0xC0) == 0x80) ||
                (b.take(10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF && !isPublicAddress(InetAddress.getByAddress(a.address.copyOfRange(12, 16)))))
            else -> false
        }
    }

    /**
     * Host policy for the request and every redirect: public name and *all* resolved addresses public.
     * [resolve] is injectable for tests. On direct connections the connected peer is re-checked too, so
     * a rebinding resolver cannot swap in a LAN address between this check and the connection.
     */
    fun hostPolicy(resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): MediaHostPolicy =
        object : MediaHostPolicy {
            override fun allows(host: String): Boolean {
                val h = host.lowercase(Locale.ROOT).trimEnd('.')
                return isPublicHostName(h) && runCatching { resolve(h) }.getOrNull()?.let { list -> list.isNotEmpty() && list.all(::isPublicAddress) } == true
            }
            /** The actually connected peer is checked too, closing the re-resolution (rebinding) window. */
            override fun allowsPeer(address: InetAddress): Boolean = isPublicAddress(address)
            override val restrictsPeers: Boolean get() = true
        }
}
