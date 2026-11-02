# Lossless local media-name interpretation: standalone parser checkpoint (2026-11-02)

**Status: isolated implementation proposal, no feature integration or provider acceptance.** See [shared media identity](../SHARED-MEDIA-IDENTITY-AND-SIMILAR-TRANSFERS.md) for the normative consumer contract, [transfer artwork](../TRANSFER-ARTWORK.md) for metadata-consent boundaries, and [provider registry](../MEDIA-PROVIDER-REGISTRY.md) for third-party facts. This module must eventually be shared by all metadata consumers, rather than encouraging each UI or rename feature to invent its own tokenizer.

## Source and behavior

`MediaNameInterpreter` is pure Kotlin and operates solely on a caller-supplied filename or transfer-name string. It does not access files, a torrent engine, Android permissions, remote RPC, media catalogs, credentials, trackers or external services. It preserves the raw input, including its path prefix and script, and attaches typed token spans using **UTF-16 offsets back into the original string**. Its separately derived `titleCandidate` is only a local search hint, never an identifier or an automatic rename.

**Version 1 deliberately narrow grammar:**
- Media-container extensions suggest only broad categories (video/audio/book/comic/archive/software). A video file is *not automatically a movie*; a PDF may represent other document types; an unrecognized extension stays unknown.
- Strongly delimited `S02E07`, `S02E07-E08` and `2x07` suggest episode coordinates. Ranges retain their endpoints only; there is no claim that all intermediate episodes exist, and a range greater than 24 emits `episode-range-requires-file-inventory`.
- In named comic files, `Issue_331` / `#331` can suggest a comic-issue number. A comic *collection* and an individual issue remain distinct. Multiple issue or episode tokens produce an ambiguity instead of silently taking the first.
- Isolated 1900–2099 years suggest a release-year candidate only if unique; `1987-1998` is tagged as a **range**, not mistaken for release year or all-year coverage. Multiple release years flag an ambiguity rather than a verified date.
- Release-technique markers (1080p, HEVC, WEB-DL, etc.) are tagged with raw spans. Numbered names like `BTTF2` and `BTTF3` remain different strings; seasons/episode code and Unicode accents are preserved.

The parser currently does **not** implement automatic multilingual semantic translation, metadata ID reconciliation, mass torrent-file-tree enumeration, person credits, character graph traversal, privacy-aware consent, local media tags, or exact scoped transfer identity. External adapters must validate those separately. In particular, a filename match cannot become a strong Similar Transfers relationship or authorize a physical rename, move or remote operation. Callers must never upload the parser's raw input or path to a provider; sanitized candidate lookup is a separate explicit opt-in policy.

## Tests and evidence

13 local host JVM tests compiled/passed with `kotlinc` and lightweight JUnit-compatible stubs: Unicode path-offset roundtrip, Babylon 5 episode syntax, single and large episode ranges, 2x grammar and negative resolution text, Hulk issue/year collection distinction, comic versus noncomic numeric suffix, major media extensions, BTTF sequel numbers, unknown extensions, contradictory years, multiple episode/issue tokens and empty input. These are **pure algorithmic evidence only**; the exact PR head still requires normal Gradle/CI qualification and later installed Android accessibility and performance measurements.

## Integration direction (deferred behind P0)

An adapter between the canonical `UnifiedTransferObservation` / safely available file-tree members and this versioned parser may produce cached, scoped *candidates*. Only a separately verified provider identity/crosswalk may promote an entity to the read-only [#1031](https://github.com/gamesbyian/Chunks/pull/1031) transfer index and [#1053](https://github.com/gamesbyian/Chunks/pull/1053) relationship-evidence graph. Keep unconfirmed episode/file ranges out of measured comic/story coverage. Re-index derived records when the parser version changes, without changing user aliases, backing files, transfer hashes, tracker state or remote sessions.

**Remaining design gaps:** exact multi-file coverage; tricky anime ordinal and localized season grammars; safe Unicode compatibility normalization alongside lossless original; movies with years in titles; subtitle/release-group boundaries; duplicate/fake release tokens; locale-specific chapter/issue numbering; embedded tags; input size/resource limits; field-level provenance; confidence calibration. Add real corpus-backed golden cases before enabling the parser in production, and prefer conservative unknowns over plausible but invented matches.
