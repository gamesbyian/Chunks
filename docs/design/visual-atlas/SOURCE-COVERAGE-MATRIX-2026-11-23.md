# Chunks native visual atlas: source-to-capture coverage matrix

**2026-11-23 source snapshot.** Runtime source: [`MainActivity.kt`](../../../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) `Section` enum and branch selection, [`AuthenticatedTransfersFooter.kt`](../../../app/src/main/java/io/github/gamesbyian/chunks/AuthenticatedTransfersFooter.kt), native `ChunksMainActivityAtlasTest`, `CombinedDetailIdentityInstrumentedTest`, and screen composables named below. This matrix is an **investigation and acquisition checklist**; it does **not** claim exhaustive states, a completed atlas, or accepted physical UI.

Related: [owner's full plan](../../VISUAL-SPECIMEN-ATLAS-PLAN.md) · [17-frame genuine native pilot receipt](PILOT-2026-11-23.md) · [current proof experiment #1977](https://github.com/gamesbyian/Chunks/pull/1977).

## Root and tool route classification

`MainActivity.Section` contains **16 identifiers**, but some are tool destinations or embedded panes. A tab bar root is not a one-to-one mapping to these section names. Every distinct below-the-fold or modal state is a candidate for a **single taller representative screenshot** rather than one image for every text value.

| Source section(s) | Primary rendered surface / example | Native pilot | Open representative states |
| --- | --- | --- | --- |
| `COMBINED`, `TRANSFERS`, `LOCAL` | `CombinedTransfersScreen`, `LocalEngineScreen`, remote list/detail | P02, P09, P10/P11 partial | genuinely empty local/server; local-only; saved search, sort, scope/filter; expanded row; multi-account; full Details |
| `ADD_CHOOSE` | shared Add route; `UniversalAddForm`, `ProtocolAddScreen` | P12/P13 isolated | five-client selection, source types, destination chooser, disabled/valid Add, priority, artwork consent, retained receipt |
| `SERVER` | `ConnectionScreen`, `ConnectionRoster` | P03 | truly disconnected, connect/edit profiles, remembered login, read-only unavailable/recovering/error, explicit connection selection |
| `OPTIONS` | categorized searchable options and scope-aware switches | P07 | advanced sections, controls/toggles, privacy, help/disclosure, accessibility |
| `PLACES` | exact local or server folder browser/Place chooser | P04 | server folders/read-only, requested folder/create, permission denial, selection |
| `ORGANIZE` | manually managed collections and identification suggestions | P06 | empty/edit/populated, selections, manual override |
| `NAMES` | display-name / alias review | P10/P11 isolated details, P02 named row | edit alias, manual-overrides-suggestion, duplicate/name validations |
| `SEEDING` | `SeedingRulesScreen` | **none** | disabled/enabled rule, client controls, incompatibility readback |
| `MEDIA` | `MediaSettingsScreen` | P08, P09 | consent on/off, poster variants, missing/unrecognized matching |
| `ROUTING` | `SmartRoutingSettingsScreen` | **none** | suggestions, per-client default Place, invalid/denied move |
| `CREATE` | `CreateTorrentScreen` | **none** | new torrent source/folder, review before publication |
| `ARCHIVES` | `LocalZipReviewScreen` | P17 isolated panel | ZIP inspection/recovery/uncertainty, disabled/excluded file operations |
| `SNAPSHOT`, `SNAPSHOT_INSPECT` | snapshot export/review and `SnapshotDocumentInspectScreen` | P15 isolated confirmation | complete inventory, missing-only preview, exact remote current-priority review, explicit Local priority application, alias restoration |

The enum above includes **16 source identifiers** (TRANSFERS, COMBINED, LOCAL, ADD_CHOOSE, SERVER, OPTIONS, PLACES, ORGANIZE, NAMES, SEEDING, MEDIA, ROUTING, CREATE, ARCHIVES, SNAPSHOT, SNAPSHOT_INSPECT); the grouped table has 13 rows. Do not count branches as separate screen designs until inspection.

## Working set-cover target (16 final specimens)

These targets identify the **minimum essential visual facts** for a useful owner audit. They may be replaced by a better evidence-bearing composition; the count is a layout budget, not an assertion that the image exists.

| Target | Screenshot subject | Important components and variants | Existing evidence |
| --- | --- | --- | --- |
| A01 | Welcome + genuine empty inventory | first run, header, empty cards, four fixed root tabs | P01 Welcome only |
| A02 | Multi-client populated Transfers | colored environment stripes, rows, progress, ratios/speeds, artwork, scope toggles/search/sort | P02/P09, partial |
| A03 | Local-only Transfers and expanded row | local badges, detail action, selection, filter and stopped/running | none |
| A04 | Real full Details top-to-bottom | large name, original name, tags, portrait art, progress, folder/path, client origin | P10/P11 **isolated 3-tab test host**, not full app |
| A05 | Tall multi-file Details/priorities | 85 files, file search, priority toggle, page states, footer, long paths | P05, [#1977](https://github.com/gamesbyian/Chunks/pull/1977) real-height candidate untested |
| A06 | Local Add with source and Place | file/magnet/link, chosen folder, auto start, per-file priority, source archival, button enabled/disabled | P12/P13 isolated |
| A07 | rTorrent Add | remote save folder, server alias, source/consent, destination validation, Add result | P12 isolated |
| A08 | Three protocol Add variants | Transmission, qBittorrent, Deluge; shared controls and variant chooser | none |
| A09 | Connections roster and editing | connected/offline/disconnected, profile labels, remember/biometric, fields/failures | P03 connected only |
| A10 | Options index plus nested settings | cards, switches, text fields, confirmation/disclosure | P07/P08 |
| A11 | Artwork identification and corrections | missing art, square list/portrait detail, search/edit consent and poster choice | P09, partial |
| A12 | Place and collection tools | local/server folder explorer; empty vs populated collections, inline warnings | P04/P06 |
| A13 | Seeding and smart routing | rule cards, rate/ratio fields and disabled states | none |
| A14 | Snapshot export, compare and details | chosen file, read-only diff, exact alias/priority states, stale refusal | P15 confirmation only |
| A15 | Additional tools | ZIP review, Create, manual naming and archive chooser | P17 ZIP only |
| A16 | Dialog/feedback specimen | remove confirmation, Delete Data unavailable, confirmation/cancel/error/success, representative menus | P16/P15 |

**Acquisition gates:** each selected final image must identify its genuine owning native test route, exact source commit, widget/app shell, consent/mock vs real operations, resolution/DPI/font and full file hash. No AI reconstruction, status text substitution, fake scroll extension, or synthetic use of a white test fixture as a dark full-app screen.

## Required cross-cutting element checklist

- **Navigation and typography:** persistent four-root footer in **assembled MainActivity**, active selected destination, disabled state, very long unbroken names, 320dp / 412dp width, 100% and 200% font; clipped/overflow assertions
- **Transfer presentation:** square artwork in list, tall poster in Details, status colors and environment tint, progress, rate/ratio, media identification and fallback icons, cards collapsed/expanded
- **Input and actions:** radio choice, checkbox, switch, text/select/search, file source picker, remote folder choice/create, Add/Start/Stop/Recheck, confirm/cancel, disabled explanations, overflow menus
- **Overlays and failure modes:** dialogs, Android IME, external file provider, offline/account unavailable, stale inventory, safe read-only, uncertain outcome, error message and positive confirmation
- **Privacy/security:** screenshot fixtures must use synthetic public transfer names/paths; protect raw server paths, credentials, token/URL material, privacy contacts; never call destructive RPC just to capture an icon
- **Tall screenshots:** prefer a real taller native viewport with original pixel geometry; if stitching real scroll frames, retain every segment plus offsets and verify no duplicate/missing rows. A simple stretched/upsampled PNG fails.

## Immediate next route

The native [#1977](https://github.com/gamesbyian/Chunks/pull/1977) source-exact test campaign aims to deliver the first verified tall 1080×4200 frame plus alias UI behavior while retaining all existing installed Add assertions. **No tall image is accepted until the exact head passes and the resulting raw screenshot height and content are inspected.** Prioritize untouched missing A03/A08/A13/A14/A15 screen families next, then review an assembled 16-image contact sheet with the owner.
