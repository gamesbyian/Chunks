# Chunks decisions: one canonical ledger

Updated 2026-10-24 after critical review. **Owner** means explicitly requested; **Adopted (provisional)** is an engineering direction that can change after evidence; **Open** needs validation or decision. Implementing an assumption does not retroactively make it owner-approved.

| ID | Status | Decision and boundaries | Reopen condition |
|---|---|---|---|
| D01 | Owner | Chunks, Android-native BitTorrent client plus bulletproof rTorrent remote | Owner change |
| D02 | Owner | Choose a download folder at Add; support live move and safe local-copy renames | Implementation must transparently disclose safe limits; never promise universal zero disk-I/O interruption |
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
| D13 | Binding provisional; service open | libtorrent4j 2.1.0-39 adopted for the local engine core (loopback JVM + emulator transfer evidence); Android long-running service classification still open | Physical Android tests |
| D14 | Adopted (provisional) | Phase order: real-server read-only → companion-free remote controls with explicit Place → safe organization/remote storage proof → local engine → independently accepted release | Critical-path evidence |
| D15 | Adopted (provisional) | No automatic retry of uncertain destructive operation. Durable server ledger and fencing where available; otherwise refuse unsafe mutation | Independent crash/fault testing |
| D16 | Adopted (provisional) | Separate category recognition, destination scoring, user-approved rule and filesystem execution authorization | Actual classification fixtures and usability |
| D17 | Adopted (provisional) | Any active move may require safe I/O quiescence; no unconditional continuous-throughput guarantee | File-handle, volume and engine behavior proof |
| D18 | Open | Minimum target rTorrent version and precise supported proxy auth schemes | Actual deployed environments and tests |
| D19 | Open | External filesystem adapters for non-installable seedboxes: SFTP/SSH/provider APIs vs companion | Managed-host capability survey |
| D20 | Adopted | Default is no auto-uploaded logs, private torrent inventory or third-party metadata lookups | Explicit owner choice |

Historical alternatives remain in critical review and research, not in competing plan files. Track implementation in WORK-QUEUE and truth in QUALITY-LEDGER.

## Owner requirements added 2026-10-24 (awaiting implementation)

- **D21 | Owner:** Device storage use cap with sane adjustable warning thresholds; compact allocation and cleanup UX. Proposed 70/85/95% warnings and pausing local download writes at cap are provisional until Android device proof. Keep remote quota semantically distinct.
- **D22 | Owner:** Explicit, comprehensible advance warning whenever chosen deletion can break a torrent, including source archive after extraction. Destructive approval is never inferred from an organization rule or extraction success.
- **D23 | Owner:** Manual archive extraction and intuitive opt-in auto-extraction. **Adopted provisional defaults:** opt-in off, keep seeding originals, capability-tested safe formats and separate extracted output objects.

See [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md) and issue #70 for the comprehensive contract. These entries do not constitute implementation evidence.

## Naming decisions after storage D21–D23 (2026-10-24)

- **D24 | Owner:** Editable per-transfer device-local Chunks display alias, distinct from torrent name and file path, preserving engine hash, source data and seeding; original remains searchable and inspectable.
- **D25 | Adopted provisional:** Source-only, explained filename-cleanup suggestions are editable drafts, never auto-applied. Unknown extensions abstain. Unsourced guest/episode titles cannot be inferred from release tokens.
- **D26 | Open:** Queue-wide batch recommendation review, naming styles, opt-in media enrichment, cross-device alias sync and per-file aliases require independent consent, data-model and acceptance decisions. None authorize physical renames.

Pre-alpha alias slice merged in [#74](https://github.com/gamesbyian/Chunks/pull/74); #7 continues to own backing-file rename and library copy safety.
