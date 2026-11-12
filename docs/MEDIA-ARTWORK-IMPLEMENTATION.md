# Media/artwork integration implementation instructions

**Use this as the technical work plan, not a previous-run narrative.** Prior test results, exact source heads and provider explorations are retained in [MEDIA-ARTWORK-IMPLEMENTATION-HISTORY-2026-11-09.md](MEDIA-ARTWORK-IMPLEMENTATION-HISTORY-2026-11-09.md). Product authority: [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md), [next-push UX/media contract](NEXT-PUSH-UX-RECONCILIATION-2026-11-09.md). Current priority: [WORK-QUEUE](WORK-QUEUE.md) #1453.

## First repair feature placement

1. Remove `TransferAutomaticMetadataToggle` from `TransferMediaDetailSection.kt` for **active transfers**. Keep the full Details correction and manual artwork picker.
2. Put one default-checked “Automatically identify and fetch artwork & metadata” choice in the **single Universal Add** workflow, only when both global collection and display are enabled. Hide it when globally off. Reuse `AddArtworkChoice` and `TransferMediaController.prepareForAdd`; persist explicit exclusion *before admission* under exact `UnifiedTransferKey`.
3. Make the **global artwork collection/display feature preference enabled by default** in Options, and sanitized online title lookup enabled by default for new installs, without an extra permission dialog. Respect explicitly saved OFF, privacy lock, provider configuration, network restrictions, sanitization and immediate revocation.
4. Add phone/remote Add, Share/Open With, restore and rejection tests for checkbox placement, default, opt-out, commit failure and durable exact-job semantics.

## Trace why recognizable items have no posters

1. Given the observed title-only hints “Looks like: Away We Go (2009)” and “Looks like: Hackers (1995)”, trace `MediaRecognizer` → `TransferMediaController.observe/enrichInventory/maybeAutomatic` → `MediaEnrichmentService.lookup` → `MediaCandidateMatcher` → assignment store → `ArtworkRepository` → `TransferRowArtwork`/`MediaMetadataSummary`.
2. Inspect and independently classify: provider consent, global automation enabled, per-job exclusion, active privacy authorization, current vs stale inventory, network connectivity and metered/power-saver state, provider `isConfigured`/rate cooldown, cache TTL, scheduling of offscreen jobs, image fetch/decoding, and UI state observation. Do not assert a cause from the screenshot alone.
3. Trigger bounded, cancelable enrichment for **all authorized eligible visible transfers** on cold launch, remote reconnection and new/external-client inventory additions; do not tie lookup to expanded Details or `LazyColumn` composition. Reuse saved assignments and artwork cache; respect provider backoff and avoid duplicate network calls.
4. Auto-commit only confident provider-backed identities, preserving entity type/year/edition disambiguation, exact environment/hash assignment and manual poster overrides. Treat local filename-derived title as an unverified recognition until corroborated. For ambiguous/offline/unconsented results, render a specific truthful reason without a mandatory Identify step; keep generic fallback art where appropriate.
5. Allow explicit manual **Change identification or artwork** from Details. Never restart an excluded transfer's enrichment, overwrite a manual choice or silently disclose raw paths, trackers, passkeys or private source data.

## Proof required before merge

- Native installed phone and authenticated rTorrent Add: default checked, disabled global hides choice, opt-out persists across process restart and saved server reconnect; active Details has **no toggle**.
- Real authorized movie/year lookup: confidently matched media and actual poster appear in All/device/server lists and full Details without Identify taps. Repeat after restart, cached offline restore, and manual poster replacement.
- Negative controls: explicit global online lookup OFF, exact-job exclusion, locked/offline/ambiguous title, unavailable provider, metered/power-saver policy, provider 401/429/cooldown, stale observation and two environments with same hash. Confirm no unauthorized outbound calls and no mutation of BitTorrent jobs or backing data.
- Unit/provider fixture + real external-provider + installed screenshot/semantics tiers must be distinguished; test exact changed head and document outcomes in [QUALITY-LEDGER](QUALITY-LEDGER.md).

## Dispatch-based ownership and integration boundaries

Chunks has no fixed agent roster or permanent lanes. The current owner dispatch defines scope, and simultaneous agents coordinate only actual file or API overlaps. Universal Add's **one admission coordinator** owns the exact-job opt-out before any engine submission. The **one canonical Transfers host** supplies fresh complete phone/remote inventory with independently revocable exact-profile/privacy witness; the media controller remains the single provider/assignment/artwork executor. Serialize competing edits to `CombinedTransfersScreen`, MainActivity/Add, media graph and persistence rather than introducing parallel executors. Integrate #1522 revocable sessions before its #1527 canonical UI caller; reconcile #1527's Combined overlap with the active #1528 navigation branch, including remembered-login startup and mismatched row/source identity refusals. The independent integrator qualifies exact-head source, actual executed native screens, and real-provider evidence separately. Follow [dispatch coordination](AGENT-LANES.md), [WORK-QUEUE](WORK-QUEUE.md) and [CI proof policy](CI-POLICY.md).
