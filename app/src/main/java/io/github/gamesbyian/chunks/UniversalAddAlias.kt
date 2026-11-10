package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.TransferDisplayNames

/** Blank means keep original; only a validated user draft can be frozen into a new Add. */
internal fun normalizedUniversalAddAlias(raw: String): String? =
    if (raw.isBlank()) null else TransferDisplayNames.validateAlias(raw)

internal fun validUniversalAddAlias(raw: String): Boolean =
    runCatching { normalizedUniversalAddAlias(raw) }.isSuccess

/** Never persist an alias if the observed hash is unrelated to this Add ticket. */
internal fun exactNewAddAlias(
    frozenAlias: String?,
    submittedHash: String,
    verifiedNewHash: String?
): String? =
    frozenAlias?.takeIf {
        verifiedNewHash != null &&
            submittedHash.equals(verifiedNewHash, ignoreCase = true) &&
            Regex("[0-9a-fA-F]{40}").matches(submittedHash)
    }
