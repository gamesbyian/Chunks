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
| D13 | Open | Local libtorrent native binding and Android long-running service classification | Physical Android tests |
| D14 | Adopted (provisional) | Phase order: real-server read-only → companion-free remote controls with explicit Place → safe organization/remote storage proof → local engine → independently accepted release | Critical-path evidence |
| D15 | Adopted (provisional) | No automatic retry of uncertain destructive operation. Durable server ledger and fencing where available; otherwise refuse unsafe mutation | Independent crash/fault testing |
| D16 | Adopted (provisional) | Separate category recognition, destination scoring, user-approved rule and filesystem execution authorization | Actual classification fixtures and usability |
| D17 | Adopted (provisional) | Any active move may require safe I/O quiescence; no unconditional continuous-throughput guarantee | File-handle, volume and engine behavior proof |
| D18 | Open | Minimum target rTorrent version and precise supported proxy auth schemes | Actual deployed environments and tests |
| D19 | Open | External filesystem adapters for non-installable seedboxes: SFTP/SSH/provider APIs vs companion | Managed-host capability survey |
| D20 | Adopted | Default is no auto-uploaded logs, private torrent inventory or third-party metadata lookups | Explicit owner choice |
| D21 | Owner | Editable per-transfer Chunks display aliases, independent of original torrent name/backing bytes; smart locally generated clean-name recommendations, editable and never automatically applied | Engineering must preserve server-scoped identity, persistence, truthful suggestion provenance and intact seeding |

Historical alternatives remain in critical review and research, not in competing plan files. Track implementation in WORK-QUEUE and truth in QUALITY-LEDGER.
