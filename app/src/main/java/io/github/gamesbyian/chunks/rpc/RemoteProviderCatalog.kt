package io.github.gamesbyian.chunks.rpc

/**
 * Reviewed, data-only provider hints for hostname-only Smart Connect.
 *
 * Entries are convenience templates, never proof of compatibility: every
 * candidate still has to pass the read-only version + main-view probe.
 * Hosts match on whole DNS labels (`host == domain` or `*.domain`), never
 * substrings, so `evilwhatbox.ca` or `whatbox.ca.example` do not match.
 * Updates ship only with reviewed app releases; nothing is downloaded.
 */
object RemoteProviderCatalog {
    enum class Requirement {
        NONE,
        /** Needs a safe account name; `{account}` is substituted. */
        ACCOUNT,
        /** Only when the account is the host's first label (user.provider). */
        ACCOUNT_IS_HOST_LABEL
    }

    data class PathTemplate(
        val path: String,
        val description: String,
        val requires: Requirement = Requirement.NONE
    )

    data class Provider(
        val id: String,
        val name: String,
        val reference: String,
        val reviewed: String,
        val domains: List<String>,
        /** Optional first-label prefix, e.g. RapidSeedbox `rt-*` hosts. */
        val hostLabelPrefix: String? = null,
        /** Empty: fall back to the bounded generic list. */
        val templates: List<PathTemplate> = emptyList(),
        /** Shown before connecting; never contains credentials. */
        val tip: String? = null,
        /** False until a real Android-to-provider journey is recorded. */
        val liveAccepted: Boolean = false
    ) {
        fun matches(host: String): Boolean {
            val h = host.lowercase().trimEnd('.')
            val domainHit = domains.any { h == it || h.endsWith(".$it") }
            return domainHit && (hostLabelPrefix == null || h.startsWith(hostLabelPrefix))
        }
    }

    private const val HTTPRPC = "/rutorrent/plugins/httprpc/action.php"

    val generic = listOf(
        PathTemplate(HTTPRPC, "ruTorrent HTTP-RPC"),
        PathTemplate("/RPC2", "Common direct SCGI"),
        PathTemplate("/xmlrpc", "Common XML-RPC"),
        PathTemplate("/{account}", "Account SCGI mount", Requirement.ACCOUNT)
    )

    val providers = listOf(
        Provider(
            id = "seedhost", name = "SeedHost",
            reference = "https://www.seedhost.eu/client-area/knowledgebase/299/Transdrone-basic-configuration.html",
            reviewed = "2026-10-28", domains = listOf("seedhost.eu"),
            templates = listOf(
                // Some installations put the account in the host, others in
                // the path. Keep the exact host the user entered.
                PathTemplate(HTTPRPC, "SeedHost ruTorrent", Requirement.ACCOUNT_IS_HOST_LABEL),
                PathTemplate("/RPC1", "SeedHost direct RPC", Requirement.ACCOUNT_IS_HOST_LABEL),
                PathTemplate("/{account}$HTTPRPC", "SeedHost account ruTorrent", Requirement.ACCOUNT),
                PathTemplate("/{account}/RPC1", "SeedHost account SCGI", Requirement.ACCOUNT),
                PathTemplate(HTTPRPC, "SeedHost ruTorrent"),
                PathTemplate("/RPC1", "SeedHost direct RPC")
            ),
            tip = "Numbered instances (/rutorrent2/, /rutorrent3/) are separate: paste that instance's full address."
        ),
        Provider(
            id = "ultra", name = "Ultra.cc",
            reference = "https://docs.ultra.cc/misc-guides/mobile-apps",
            reviewed = "2026-10-28", domains = listOf("usbx.me"),
            templates = listOf(PathTemplate("/RPC2", "Ultra.cc direct RPC"),
                PathTemplate(HTTPRPC, "ruTorrent HTTP-RPC"))
        ),
        Provider(
            id = "whatbox", name = "Whatbox",
            reference = "https://whatbox.ca/wiki/editing_rtorrentrc",
            reviewed = "2026-10-28", domains = listOf("whatbox.ca"),
            templates = listOf(PathTemplate("/xmlrpc", "Whatbox XML-RPC"),
                PathTemplate(HTTPRPC, "ruTorrent HTTP-RPC"))
        ),
        Provider(
            id = "appbox", name = "Appbox",
            reference = "https://billing.appbox.co/knowledgebase/166/ruTorrent-and-Transdroid.html?language=english",
            reviewed = "2026-10-28", domains = listOf("appboxes.co"),
            templates = listOf(PathTemplate("/RPC2", "Appbox direct RPC"),
                PathTemplate(HTTPRPC, "ruTorrent HTTP-RPC"))
        ),
        Provider(
            // The dedicated HTTPS rt-* service uses rpc.php, not httprpc.
            id = "rapidseedbox", name = "RapidSeedbox",
            reference = "https://help.rapidseedbox.com/en/articles/8504545-connecting-your-home-sonarr-radarr-to-rsb-s-deluge-and-rutorrent",
            reviewed = "2026-10-28", domains = listOf("seedbox.vip"), hostLabelPrefix = "rt-",
            templates = listOf(PathTemplate("/plugins/rpc/rpc.php", "RapidSeedbox ruTorrent RPC"),
                PathTemplate("/rutorrent/plugins/rpc/rpc.php", "RapidSeedbox prefixed RPC"))
        ),
        Provider(
            // The slot name must come from a pasted URL; the HTTP login is
            // often literally "rutorrent", so hostname-only stays generic.
            id = "feral", name = "Feral Hosting",
            reference = "https://www.feralhosting.com/wiki/software/transdrone",
            reviewed = "2026-10-28", domains = listOf("feralhosting.com"),
            tip = "Paste your full ruTorrent address (https://server.feralhosting.com/slot/rutorrent/) " +
                "so Chunks can use your slot's own RPC gateway."
        ),
        Provider(
            id = "bytesized", name = "Bytesized Hosting",
            reference = "https://bytesized-hosting.com/pages/remote-torrent-adder",
            reviewed = "2026-10-28", domains = listOf("bysh.me"),
            tip = "Paste the full ruTorrent address from your Bytesized dashboard; its RPC route is not verified yet."
        )
    )

    fun match(host: String?): Provider? =
        host?.takeIf { it.isNotBlank() }?.let { h -> providers.firstOrNull { it.matches(h) } }

    /**
     * One-line, credential-free note for the Connect form, or null when the
     * input is unparsable or the host is not in the catalog. Says plainly
     * that a match is a documented hint, not live provider acceptance.
     */
    fun hint(input: String): String? {
        val raw = input.trim().takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return null
        val uri = try { java.net.URI(if ("://" in raw) raw else "https://$raw") } catch (_: Exception) { return null }
        if (uri.rawUserInfo != null) return null
        val provider = match(uri.host) ?: return null
        val hostnameOnly = uri.rawPath.orEmpty().trim('/').isEmpty()
        return buildString {
            append("Recognised ${provider.name}. ")
            if (hostnameOnly && provider.templates.isNotEmpty())
                append("Chunks will try its documented addresses first, read-only. ")
            provider.tip?.let { append(it).append(' ') }
            append("Based on provider docs reviewed ${provider.reviewed}")
            append(if (provider.liveAccepted) "." else "; not yet tested by Chunks with this provider.")
        }
    }

    /** Ordered templates for a hostname-only input; deduplication is the caller's. */
    fun plan(host: String, account: String?): List<Pair<String, String>> {
        val h = host.lowercase()
        val provider = match(h)
        val templates = provider?.templates?.takeIf { it.isNotEmpty() } ?: generic
        return templates.mapNotNull { t ->
            when (t.requires) {
                Requirement.NONE -> t.path to t.description
                Requirement.ACCOUNT -> account?.let { t.path.replace("{account}", it) to t.description }
                Requirement.ACCOUNT_IS_HOST_LABEL ->
                    if (account != null && h.startsWith(account.lowercase() + ".")) t.path to t.description else null
            }
        }
    }
}
