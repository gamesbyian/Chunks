# Transfer Details: unified identity-first screen (owner-approved, 2026-11-15)

**Clarification (owner, 2026-11-15):** The visible Details environment indicator displays **only the chosen short connection name**, e.g. colour dot + **Wood**, or **Local**. Never repeat `Environment: Server · Wood · <hostname/RPC path>` or any `Server · Wood` qualifier in the consolidated identity or other duplicate Details origin banner. Keep exact stable ID and full endpoint available via Connections → named profile → Manage/diagnostics; the short label is presentation only and must not be used as a network or authority key. Media artwork follows [category-specific natural aspect ratios](TRANSFER-ARTWORK-ASPECT-RATIOS.md), rather than mandating portrait for every media type.


**Status:** binding target design, not a claim of implementation or physical APK acceptance. This document governs the single full Transfer Details route for Local and every named remote profile. It complements [compact Transfers](TRANSFERS-COMPACT-LIST-FIRST.md), [UX](../UX.md), [PRODUCT](../PRODUCT.md), [TRANSFER-ARTWORK](../TRANSFER-ARTWORK.md), [media integration](../MEDIA-ARTWORK-IMPLEMENTATION.md) and [work queue](../WORK-QUEUE.md). Supersede earlier drawings or screenshot expectations that show a redundant Back to transfers button, a Transfer details heading, or a separate lower Artwork & media information card.

## Primary screen hierarchy

1. Existing compact **chunks.** wordmark, then immediately **one unified transfer identity section**. Do not display either **Back to transfers** or **Transfer details** at the top. These consume valuable first-viewport space without adding identity or navigation.
2. **Media presentation, when available:** a portrait poster in Details, identified media title, media type/year and succinct automatic/manual/provider/uncertain identification provenance. Show a suitable neutral placeholder when art is unavailable; never invent a match, art, or year. Do not wait for artwork enrichment to show the rest of Details.
3. **The original full torrent/release name**, independently of the identified media title or editable list alias, with wrapping/copyability as appropriate. Show the current list alias distinctly when different; preserve exact source identity for troubleshooting and safety.
4. **Concise last-observed transfer state** with honest freshness/unknown status and **short environment name** (e.g. Wood, Local), with consistent environment-colour indication when enabled. Do not print the complete SCGI/XML-RPC/ruTorrent endpoint or a hostname/path in this top section; any legitimately needed connection diagnostics belong to a secondary detail or Connections. Avoid leaking private endpoints in default screenshots.
5. **Three distinct, always-discoverable presentation actions grouped beneath the identity:** **Change list name**, **Edit identification**, **Choose artwork**. All must remain available even where recognition has failed or art is absent. Choosing alternate/provider/custom artwork must not require changing a correct media identity. Editing identity must not silently clobber the existing user's explicit artwork override; preserve provenance and override priority. Preserve existing manual-choice functionality and consent gates. Do not reintroduce the Add-only per-transfer automatic-enrichment checkbox.
6. Under this single identity header, place progress, live/last-observed rates, peers, ratio, files, collections, transfer operations and other specialist information in the existing appropriate sections. **Remove the lower separate “Artwork & media information” display** once its fields/actions are integrated; do not duplicate it or remove independent technical inspection tools.
7. Keep the **persistent one-row Transfers / + Add / Connections / Options footer** at all scroll positions. Transfers in this footer is the primary return path; preserve the prior list scope, query, filters and scroll position when returning from Details. No duplicated in-content back button.

## Responsive and accessibility behavior

- At 320dp and 412dp portrait, keep the combined identity near the top and use a compact poster/title row where space permits. Let long titles and original release names wrap without overlap or clipping. The poster must not crowd out readable text.
- Group the three actions in a compact wrapping/two- or three-column arrangement according to available width and text scale. **Never hide Choose artwork behind Edit identification**, reduce buttons below 48dp targets, or require horizontal page scrolling. Preserve keyboard/TalkBack names, visible focus, 200% text, and accurate reading order.
- Distinguish media match source and certainty from transfer status; distinguish current engine observation from stale or unconfirmed activity. Unknown/missing artwork must not produce a blank identity card or unresponsive action.
- For unrecognized/non-media transfers, still show original/list name, status, environment, optional artwork and the three actions with truthful correction affordances; no fabricated movie fields.
- Keep local and all remote profiles on the same component/structure; data and capabilities vary, not navigation.

## Implementation guidance

Audit the current native full Details composables and existing media-detail section. **Relocate/reuse the existing media identity/poster and its change/selection handlers**, rather than implementing parallel metadata models or discarding existing override logic. Merge with the existing Details title/status/environment presentation; remove former heading/back button and the duplicate lower artwork section only after its content and actions are migrated. Do not move the technical MediaInfo/inspection feature into the identity header.

## Acceptance

- On an identified real remote movie, the **first viewport** beneath the wordmark contains poster, identified media title/year/provider, original release name, observed status, short connection name and **all three** editing controls (allow normal vertical flow where accessibility text size makes full first-viewport fitting impossible). None is repeated in a lower Artwork & media information section.
- For local, unidentified, offline, stale, wrong-match, user-custom-poster, and title-alias cases, the same structure is functional; no mistaken provider certainty or silent override loss.
- Footer Transfers returns to the previous list scope, search/filter and position; all four footer destinations remain accessible and the view needs no in-content Back control.
- Pixel 6 Pro installed screenshots plus 320dp/412dp and 200% font, TalkBack/keyboard, long-name wrapping and three-button taps validate layout. Test handlers and persistence as well as visuals. Documentation alone is **not** implementation acceptance.
