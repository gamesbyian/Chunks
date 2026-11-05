package io.github.gamesbyian.chunks.media

/**
 * Per-provider capabilities and terms (MEDIA-PROVIDER-REGISTRY "Contract for provider adapters").
 * Rates are the provider's documented limits, applied per device; they are never shared or pooled.
 */
data class MediaProviderInfo(
    val id: String,
    val displayName: String,
    val kinds: Set<MediaKind>,
    val license: String,
    val attribution: String,
    val termsUrl: String,
    val minIntervalMs: Long,
    val apiHosts: Set<String>,
    val artworkHosts: Set<String>,
    val requiresUserKey: Boolean = false,
    val providesArtwork: Boolean = true,
    /** Default-on once the user has granted online lookup consent. */
    val enabledByDefault: Boolean = true
) {
    init { require(id.matches(Regex("[a-z][a-z0-9-]*"))) }
    val apiHostPolicy: MediaHostPolicy get() = mediaHostPolicy(apiHosts)
    val artworkHostPolicy: MediaHostPolicy get() = mediaHostPolicy(artworkHosts)
}

sealed class MediaProviderOutcome {
    data class Found(val candidates: List<MediaCandidate>) : MediaProviderOutcome()
    object NotFound : MediaProviderOutcome()
    data class RateLimited(val retryAfterMs: Long) : MediaProviderOutcome()
    /** 401/403 or missing key: pause provider and ask the user to check configuration. */
    object Unauthorized : MediaProviderOutcome()
    data class Failed(val reason: String, val retryable: Boolean) : MediaProviderOutcome()
    data class BadSchema(val reason: String) : MediaProviderOutcome()
}

interface MediaMetadataProvider {
    val info: MediaProviderInfo

    /** Whether this provider is configured (e.g. user key present). */
    fun isConfigured(): Boolean = true

    fun supports(query: MediaLookupQuery): Boolean = query.kind in info.kinds

    /** Search with a sanitized query. Must not throw; network errors map to [MediaProviderOutcome.Failed]. */
    fun search(query: MediaLookupQuery, http: MediaHttp): MediaProviderOutcome

    /**
     * Optional second step for a confidently matched record, e.g. episode or season details.
     * Returns the refined candidate, or the original on any failure (metadata stays independently useful).
     */
    fun refine(candidate: MediaCandidate, query: MediaLookupQuery, http: MediaHttp): MediaCandidate = candidate
}

/** Shared status → outcome mapping and safe JSON fetching for adapters. */
internal object ProviderHttp {
    fun fetchJson(http: MediaHttp, info: MediaProviderInfo, url: String, headers: Map<String, String> = emptyMap(),
                  now: () -> Long = System::currentTimeMillis): Pair<Json?, MediaProviderOutcome?> {
        val resp = try {
            http.get(url, info.apiHostPolicy, mapOf("Accept" to "application/json") + headers, maxBytes = 1_500_000)
        } catch (e: MediaHttpException) {
            return null to MediaProviderOutcome.Failed(when (e.failure) {
                MediaHttpFailure.OFFLINE -> "Offline"
                MediaHttpFailure.TIMEOUT -> "Timed out"
                MediaHttpFailure.TOO_LARGE -> "Response too large"
                else -> "Network error"
            }, retryable = e.failure != MediaHttpFailure.TOO_LARGE && e.failure != MediaHttpFailure.BLOCKED_HOST)
        }
        when (resp.status) {
            200 -> {}
            404 -> return null to MediaProviderOutcome.NotFound
            401, 403 -> return null to MediaProviderOutcome.Unauthorized
            429 -> return null to MediaProviderOutcome.RateLimited(retryAfterMs(resp.header("Retry-After"), now()) ?: 30_000)
            503 -> return null to MediaProviderOutcome.RateLimited(retryAfterMs(resp.header("Retry-After"), now()) ?: 60_000)
            in 500..599 -> return null to MediaProviderOutcome.Failed("Provider error ${resp.status}", retryable = true)
            else -> return null to MediaProviderOutcome.Failed("Unexpected status ${resp.status}", retryable = false)
        }
        return try { Json.parse(resp.text) to null } catch (e: MediaJsonException) {
            null to MediaProviderOutcome.BadSchema("Malformed JSON")
        }
    }

    /** Retry-After is delta-seconds or an HTTP-date; clamp to [1s, 1h]. */
    fun retryAfterMs(value: String?, nowMs: Long): Long? {
        val v = value?.trim() ?: return null
        v.toLongOrNull()?.let { return (it * 1000).coerceIn(1_000, 3_600_000) }
        return try {
            val t = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (t - nowMs).coerceIn(1_000, 3_600_000)
        } catch (e: Exception) { null }
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun year(date: String?): Int? = date?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()?.takeIf { it in 1870..2100 }

    /** Provider overviews may contain HTML; display only plain text. */
    fun plain(html: String?): String? = html?.replace(Regex("<[^>]{0,200}>"), "")?.replace("&amp;", "&")
        ?.replace("&quot;", "\"")?.replace("&#39;", "'")?.replace("&lt;", "<")?.replace("&gt;", ">")
        ?.replace(Regex("\\s+"), " ")?.trim()?.take(1200)?.ifEmpty { null }
}
