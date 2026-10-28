# Chunks decisions: one canonical ledger

## Owner directive: ordered multi-file completion (2026-10-27; #415)

**Owner-approved goal:** Chunks should make ordered bundles (TV seasons, sequel packs and other numbered media) progressively usable in logical sequence without manual micromanagement of each file's static priorities. Automatic suggested ordering and a dynamic completion scheduler are in scope. **Engineering choices subject to evidence:** Normal/Balanced/Finish First/capability-gated Sequential Playback modes; rolling bounded priority windows and front-file stall fallback. **Boundaries:** mode choice is opt-in or disclosed as preference; no guaranteed ordering when swarm availability prevents it; respect manual priority and skipped files; remote rTorrent capability is independently validated; no false physical-device acceptance. Tracked in #415 and canonical PRODUCT/UX/ARCHITECTURE/WORK-QUEUE/SPEC-COVERAGE.
**Owner amendment:** Square is only one of multiple artwork roles; users can separately customize square, poster and banner on any torrent. Provide an aggregated multi-source image gallery plus device upload and explicit image URL input. Allow toggles for applying selected artwork to related episodes/show/season, artist/album, author/books, series/issues or other verified media groups, without overriding narrower manually pinned art. Scope preview and user control required. See [TRANSFER-ARTWORK.md](TRANSFER-ARTWORK.md), #403.

**Owner addition:** Expand decorative artwork/metadata to music, books, magazines, comics, games, apps, porn and other torrent classes. Multiple provider attempts are required after a miss; legal/consented general image search is the last external resort; handsome built-in category art is the terminal fallback. **Provisional implementation** uses provider-specific identity and image scoring, field provenance, independent adult-consent gates, and bounded local cache. This confirms product direction only, not provider licenses or implementation. See [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md), #403.

## 2026-10-27: owner-approved movie/TV transfer artwork

**Owner direction:** Decorate local and rTorrent transfer cards/details with recognizable preferably-square movie/TV artwork reflecting the supplied colorful multi-phone mockup. This is approved desired UI behavior, **not** approval of a particular provider, account/paid API, third-party terms or silent network lookups. **Provisional engineering direction:** native-square Fanart.tv/TheTVDB first, constrained TMDB/crop fallback, opt-in privacy-safe enrichment, confidence threshold and user correction, offline icons/cache. Provider choice and implementation gated by research. [TRANSFER-ARTWORK.md](TRANSFER-ARTWORK.md), [#403](https://github.com/gamesbyian/Chunks/issues/403). **Planned, not shipped.**

Reconciled 2026-10-26 after current implementation/evidence review. **Owner** means explicitly requested; **Adopted (provisional)** is an engineering direction that can change after evidence; **Open** needs validation or decision. Implementing an assumption does not retroactively make it owner-approved.

| ID | Status | Decision and boundaries | Reopen condition |
|---|---|---|---|
| D-ICON-SVG-20261027 | Owner | The transparent `docs/design/approved/chunks-app-icon-master.svg` is the canonical editable Chunks app icon, without a baked-in launcher bezel. Keep the owner PNG unchanged for comparison only. Android consumes a generator-derived native VectorDrawable from the SVG; no PNG in shipping launcher resources. Regenerate the derivative on SVG changes and check parity in CI/Gradle. | Owner supplies revised source or requests different branding. Physical adaptive-mask acceptance remains separate. |
| D-LOCK-20261027 | Owner | Optional privacy lock for viewing/controlling local transfers and separate remote profiles. Once enabled, default once per app process session, customizable for security/convenience; use Android device-supported biometrics and device credentials. Remember remote server credentials by default with opt-out; UI unlock remains independent of server login. | Revisit after handset security/UX proof; contract in TRANSFER-PRIVACY-LOCK.md |
| D01 | Owner | Chunks, Android-native BitTorrent client plus bulletproof rTorrent remote | Owner change |
| D-DEST-20261027 | Owner | At Add, selecting a transfer-specific destination must always be obvious, never mandatory. Default local target is user-visible Downloads/Chunks Downloads, created on Android device only; remote uses server's configured default, never creates Chunks-branded server folders. Easy scoped reuse of recent successfully used destinations. | Android scoped-storage/native-engine validation and per-profile remote default acceptance remain open; supersedes D02's compulsory wording |
| D-DOWNLOADS-20261027 | Owner | Use Android's standard shared Downloads location as default parent for local Chunks Downloads, usually aligning with Chrome's default; support independent remembered alternative locations including tested SD cards; never rely on Chrome's private preference. | Android public-storage/native torrent write-seed support must be demonstrated; cannot infer Chrome's custom folder |
| D-XFER-20261027 | Owner | Transfer details expose local↔remote Copy and, when safe, Move; distinguish metadata-only Add, real payload copy and verified migration; source cleanup separately authorized. | Requires remote filesystem capability and target payload/hash/resume/seeding proof |
| D-ARCHIVE-20261027 | Owner | Offer deletion of source archives whose extraction is verified in individual transfer Remove journeys and cleanup screens, with explicit warning of torrent breakage. | Requires provenance/output verification, safe exact-file delete and distinct opt-in |
| D-BATCH-XFER-20261027 | Owner | Extend local↔remote torrent metadata/payload Copy and safe Move to easily selected batches from queues, searches and Collections, with aggregate progress and per-transfer recovery. | No source deletion absent per-item target verification and explicit approval; prove scale and partial failures |
| D02 | Owner | Show an optional download-folder choice at Add while preselecting the environment default; support live move and safe local-copy renames | Implementation must transparently disclose safe limits; never promise universal zero disk-I/O interruption |
| D03 | Owner | Smart suggestions across TV/movies/music/Star Trek/Spider-Man/ebooks/comics and emergent categories | Consent/privacy architecture |
| D04 | Owner | Modern, colourful, irreverent, generous-touch yet expert-grade UX | Evaluate design on real device |
| D05 | Adopted (provisional) | Kotlin + Compose and typed command application core; initial Gradle shell exists | Android lifecycle/toolchain evidence |
| D06 | Adopted (provisional) | Legacy XML-RPC protected HTTPS baseline; JSON-RPC only when real supported, with build/transport capability discovery | Server compatibility matrix |
| D07 | Adopted (provisional) | Optional restricted Companion for server filesystem transactions; never required for everyday rTorrent remote | Managed seedbox availability and alternative validated FS access |
| D08 | Adopted (provisional) | Transfers, Places, Collections; display aliases separate from backed files and published library copies | User journey evidence |
| D09 | Adopted (provisional) | Explainable deterministic classification, optionally richer; confidence is not permission to move | Privacy and adversarial evaluation |
| D10 | Adopted | Single live queue/decision ledger, exact evidence classes, one lean CI validation path, focused PRs | Only change for observed complexity |
| D11 | Open | Source-code license and release/distribution policy (sideload/Play) | Third-party code and permission audit |
| D12 | Open | Companion implementation language and deployment package | Throwaway volume/host prototype |
| D13 | Binding provisional; physical acceptance open | libtorrent4j 2.1.0-39 adopted for local engine and the dataSync foreground service is merged; long-running physical device acceptance remains open | Physical Android tests |
| D14 | Adopted (provisional) | Phase order: real-server read-only → companion-free remote controls with explicit Place → safe organization/remote storage proof → local engine → independently accepted release | Critical-path evidence |
| D15 | Adopted (provisional) | No automatic retry of uncertain destructive operation. Durable server ledger and fencing where available; otherwise refuse unsafe mutation | Independent crash/fault testing |
| D16 | Adopted (provisional) | Separate category recognition, destination scoring, user-approved rule and filesystem execution authorization | Actual classification fixtures and usability |
| D17 | Adopted (provisional) | Any active move may require safe I/O quiescence; no unconditional continuous-throughput guarantee | File-handle, volume and engine behavior proof |
| D18 | Open | Minimum target rTorrent version and precise supported proxy auth schemes | Actual deployed environments and tests |
| D19 | Open | External filesystem adapters for non-installable seedboxes: SFTP/SSH/provider APIs vs companion | Managed-host capability survey |
| D20 | Adopted | Default is no auto-uploaded logs, private torrent inventory or third-party metadata lookups | Explicit owner choice |

Historical alternatives remain in critical review and research, not in competing plan files. Track implementation in WORK-QUEUE and truth in QUALITY-LEDGER.

## Owner requirements added 2026-10-24 (partially implemented)

- **D21 | Owner:** Device storage use cap with sane adjustable warning thresholds; compact allocation and cleanup UX. Proposed 70/85/95% warnings and pausing local download writes at cap are provisional until Android device proof. Keep remote quota semantically distinct.
- **D22 | Owner:** Explicit, comprehensible advance warning whenever chosen deletion can break a torrent, including source archive after extraction. Destructive approval is never inferred from an organization rule or extraction success.
- **D23 | Owner:** Manual archive extraction and intuitive opt-in auto-extraction. **Adopted provisional defaults:** opt-in off, keep seeding originals, capability-tested safe formats and separate extracted output objects.

See [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md) and issue #70 for the comprehensive contract. These entries do not constitute implementation evidence.

## Naming decisions after storage D21–D23 (2026-10-24)

- **D24 | Owner:** Editable per-transfer device-local Chunks display alias, distinct from torrent name and file path, preserving engine hash, source data and seeding; original remains searchable and inspectable.
- **D25 | Adopted provisional:** Source-only, explained filename-cleanup suggestions are editable drafts, never auto-applied. Unknown extensions abstain. Unsourced guest/episode titles cannot be inferred from release tokens.
- **D26 | Partly adopted:** Queue-wide first-100 source-only proposal review/conditional Undo merged (#98). Naming styles, large-scale paging, external opt-in enrichment, per-file aliases and cross-device sync remain separate open decisions. None authorize physical renames.

Pre-alpha alias slice merged in [#74](https://github.com/gamesbyian/Chunks/pull/74); #7 continues to own backing-file rename and library copy safety.

## Reconciled operational decisions (2026-10-26)

- **D27 | Owner:** Named development Seedhost torrent inventory is nonconfidential for nonsecret fixtures/native QA, and targeted destructive **torrent** tests are permitted with preflight and exact postconditions. No passwords, passkeys, unrelated servers or speculative file deletion.
- **D28 | Implemented narrowly:** Manual bounded ZIP extraction into a **new SAF folder** preserves its source; finished local transfers can make independent verified SAF library copies (#119/#120/#147). Neither permits arbitrary backing rename, remote deletion, auto-extract or direct SAF-based torrent piece storage.
- **D29 | Partly adopted (2026-10-27):** User chooses default-ON remembered remote login, with visible opt-out and Forget. Merged #232 encrypts one remembered profile using Android Keystore, unbacked-up device-private ciphertext, and pre-fills manual reconnection. Additional profiles, rotation/biometric privacy lock, signed distribution, minimum Android support and third-party licenses remain open (#10).
- **D30 | Open:** Real per-volume caps, physical reclaim accounting, native cleanup and opt-in extraction require device write-path, provider, journaling and physical acceptance (#70).
- **D31 | Adopted:** rTorrent compatibility is build/proxy specific: Ubuntu 0.9.8 + Arch 0.16.x pass scoped disposable tests; Alpine 0.15.2 unresolved (#151); these are not device evidence.
