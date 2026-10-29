# BitTorrent conceptual-map research validation: disconfirmation, mobile lifecycle, accessibility and discontinuance

**As of:** 2026-10-29  
**Class:** Independent, non-normative external research  
**Purpose:** Exploit research opportunities exposed by the human-centered feature graph, explicitly **look for evidence that falsifies its missing-capability assumptions**, and describe new conceptual dependencies and research paths.

**Companion artifacts:** [conceptual map](BITTORRENT-HUMAN-CONCEPT-MAP-2026-10-29.md), [typed graph](BITTORRENT-CONCEPT-GRAPH-2026-10-29.json), [existing 240-feature inventory](../BITTORRENT-ECOSYSTEM-FEATURE-INVENTORY-2026-10-29.md), [240-activity reference](../BITTORRENT-USER-GOALS-AND-WORKFLOWS-2026-10-29.md), and [multilingual aversion evidence](../BITTORRENT-USER-AVERSION-RESEARCH-2026-10-29.md).

**What changed in our confidence?** Several H concepts are not novel at the level originally implied. In particular, qui already documents sophisticated cross-seeding, cross-instance orphan protection, pre-deletion preview, dry-run automation, restore previews and post-export verification. This **reduces the novelty claim**, but not necessarily the interest of a *coherent full end-to-end* human experience across clients and devices. Product capabilities are version-, deployment-, filesystem-access- and provider-specific. A primary feature description establishes a **documented implementation**, not empirical reliability in all configurations.

## 1. New evidence: direct capability disconfirmation

| Hypothesis | Earlier exploratory wording | Documented existing capability | Revised research status |
|---|---|---|---|
| H07–H08 (content identity/dependencies) | Recover one underlying body of bytes across multiple torrent identities | qui supports direct reuse, hardlink and reflink modes and matching conditions (V01) | **Partially implemented in existing tools**; cross-system library/data provenance remains broader |
| H10 (portable migration) | Move session state with correct paths and per-torrent properties | qui backs up and restores torrents/settings with preview modes, and exports registrations across qBittorrent instances (V04–V05) | **Not novel as stated in general form**; check per-path and cross-engine semantics |
| H19/H50 (consequence preview) | Show what a dangerous rule or batch operation would do | qui automations support dry-run; backup restore previews exact changes; orphan cleanup previews files (V02–V04) | **Documented implementations exist** in specific surfaces |
| H20 (safe cleanup) | Prevent removal of bytes still needed by another torrent | qui offers cross-seed-aware deletion and cross-instance ownership checking (V02–V03) | **Documented partial/composite solution**; disabled instances and external library references remain caveats |
| H21 (reversibility) | Restore from erroneous destructive operations | qui restore modes can recover registrations, labels and category state (V04); previews reduce risk | **Partial**: no demonstration of universal payload undelete |
| H24/H35 (automation partial-failure recovery) | Recover from a lost or partially completed remote mutation | qui's export rule checks destination addition, can remove unhealthy target registrations and provides activity history (V05) | **Documented partial solution**; not a transaction across independent file stores |
| H31 (multi-instance setup/target) | Confirm connection and capabilities per remote | qui connection testing and multi-instance controls exist (V06) | **Documented partial**; provider and daemon differences persist |
| H39 (compatible content search and repair) | Find and reuse equivalent bytes while preventing unsafe mismatches | qui and cross-seed include matching, full recheck, and detailed path/permissions guidance (V01, V07) | **Substantial implemented precedent**, not universal scarcity recovery |
| H46 (dataset/research provenance) | Version, subset and integrity context for datasets | Academic Torrents documents versioned data, infohash and long-term sharing (V19–V20) | **Partial existing platform capabilities**; subset-specific reproducibility manifests remain separate |
| H57 (sustainable phone seeding) | Honor real battery/metered/network constraints | LibreTorrent documents battery/network scheduling and Android-specific controls (V13–V14) | **Partial implementation**; OS constraints and demand are independent |

### 1.1 A hidden qualification with real consequences

qui's **Export to Instance** action exports a torrent *registration*, not the payload. Its documentation expressly says target bytes should already exist, and the action defaults to **skip hash checking**. It subsequently checks that the target appears and looks healthy. That is good evidence for post-action verification of *registration state*, but **not a proof that actual content bytes were copied or hash-verified**. Thus H02, H10, H17 and H35 need more precise outcome claims, not simply a “yes/no exists” label. [V05]

qui's orphan scan may be conservative across actively connected instances with local filesystem access, but it does **not** promise safety for disabled overlapping instances or directories beyond configured roots. Its documentation specifies partial-scan and failsafe cases. Thus H08/H20's *cross-boundary* edge still matters, without pretending dependency-aware cleanup itself is undiscovered. [V03]

## 2. Android: user intent collides with platform lifecycle

**This is a distinct conceptual boundary, not merely another download option.**

A user may mean: “Start my phone seeding this and continue until I stop it.” Android's actual ability to execute long-running work varies by app target API, service type, user initiation, foreground/OS restrictions, battery/network state and distribution channel.

- Android 15+ target apps using `dataSync` or `mediaProcessing` foreground services receive a cumulative six-hour-per-24-hour background execution budget for those **service types**. Bringing the app foreground resets the time budget under the documented semantics; the app must stop on timeout. **This is not a blanket six-hour limit on all BitTorrent operation.** [V08]
- Android 14's user-initiated data transfer jobs are a supported alternative for some longer user-triggered transfers, with notification and OS-health constraints. WorkManager has different rescheduling/interruption semantics. Neither should be declared universally suitable for perpetual seeding without evaluating the actual workload and target OS. [V09–V10]
- Android 11+ document-tree picker cannot grant access to every location (including volume roots or the Downloads directory as a tree); all-files access has separate app/Google Play policy implications. The Play build of LibreTorrent explicitly documents different file-access powers compared with F-Droid/direct APK distribution. [V11–V13]
- Existing Android torrent apps already support phone-specific scheduling and features, so the missing concept is **reliable disclosure and reconciliation of user intent versus what the OS will permit**. [V13–V14]

**Candidate map additions:** H67 (long-running transfer/seeding survivability and interruption explanation) and H68 (location-/app-variant-specific storage capability and repair). Each should connect to A20.11, A17.07, A02.07, A10.11, F03.17 and F14.07 where appropriate.

## 3. Accessibility: statuses are data, not decoration

A torrent interface can have screen-reader-accessible navigation yet still hide *the information necessary to make a decision*. This is a genuine **feature-to-cognitive-access edge**, not just colors/themes.

- A blind Transmission user describes basic uploaded/downloaded and other torrent detail values not reachable or clearly readable via keyboard/screen reader (V15). A historical qBittorrent user reported an NVDA/JAWS/Narrator feedback problem in a particular version (V16); closed status and age prevent attributing that to modern releases.
- A 2026 UNIT3D tracker issue describes statistics announced as unlabeled numbers and status/freeleech icons that are silent to a screen reader (V17). A 2026 Transmission issue concerns progress colors that are difficult to distinguish for a colorblind person (V18).
- LibreTorrent's July 2026 release notes include improvements to accessibility and independent language choice, demonstrating that part of this capability is actively implemented, not universally absent (V14).

**Distinct cognitive demand:** understanding *which status a number denotes* and *which action a visual badge implies*, without depending on color, icon shape, hover text or a visually inferred grid location.

**Candidate map addition:** H69, verified accessible status/detail semantics and parity for critical controls across text, touch, keyboard and screen reader. Generic “supports accessibility” (F14.09) does not guarantee this. The map should store task-specific evidence and not infer that an individual accessibility report applies to all platforms or versions.

## 4. Publishing, creators and research use

Academic Torrents' publisher guide confirms the separate stages of dataset assembly, creation of the torrent descriptor, publishing metadata, maintaining seed nodes, and optionally using a backup URL. That means “torrent created successfully” is not the same as “the scholarly community can retrieve and verify the intended dataset.” [V19–V20]

Important distinctive goals beyond consuming media:

- **Publisher distribution readiness:** can recipients with an ordinary client get the content *now*, and is the original seed still reachable? H37 remains a meaningful research question, not evidence of an unimplemented public tracker.
- **Research reproducibility:** does a downstream paper/pipeline identify the exact dataset version, subset selected and integrity checks used? An infohash identifies a specific descriptor, but it is not in itself an audit trail of the researcher's choices. H46.
- **Community trust and rights:** a piece hash verifies consistency with the torrent's metainfo, **not who authored or is permitted to distribute the content**. H42/H60.
- **Creator workflow:** deciding how to share and verifying a recipient got the file should not assume the person has a tracker account, a stable public server, or advanced knowledge of piece sizing. H37/H55/H62.

Tribler's historical decentralized social/search and privacy work is a counterexample to treating social trust and discovery as entirely blank feature space, but historical experimental features must not be assumed present in the latest released app. [V21]

## 5. Discontinuance: separate quitting, switching and being finished

A 2025 review in the *European Journal of Information Systems* defines **seven types of technology discontinuance**: overuse, overload, proficiency, disenchantment, social, circumstantial and **solution**. The last is particularly helpful for the BitTorrent map: a person may stop using a client because they successfully got the desired file and have no further need. That is success, not product failure. A person who switches *between* two torrent clients remains a torrent user and should not be coded as quitting BitTorrent. [V22]

The broader critical-incident research analyzes hundreds of everyday technology breakdown accounts across cognitive, emotional and behavioral consequences. It supports exploring failed actions and remembered trust without assuming every resulting workaround is irrational. It is **not** data about BitTorrent user prevalence. [V23]

| Exit or change of use | Possible human meaning | Existing map example | What would validate it |
|---|---|---|---|
| **Solution discontinuance** | Successfully retrieved a one-off Linux ISO, no more torrent need | J01 then A20.01 | User reports task complete, no unmet goal |
| **Overload discontinuance** | Setup/service maintenance no longer worth attention | N02, N10 | Last failed/avoided step and perceived overhead |
| **Proficiency discontinuance** | Technical controls inaccessible or became incomprehensible | N02, N13 | Think-aloud and skill-fit observation |
| **Disenchantment discontinuance** | Outcomes poor relative to cost over repeated tries | N05, N10, N11 | Repeated unmet needs, alternatives considered |
| **Social discontinuance** | Tracker governance, exclusion or hostility ends participation | N07, N14 | User account and site context |
| **Circumstantial discontinuance** | Network plan, living situation, device or needs change | N06, N15 | Time-bounded changed external constraint |
| **Overuse discontinuance** | Excessive perceived burden or compulsive usage outweighs value | Less directly documented for torrents | Requires distinct human evidence; do not infer from frequent checking |
| **Switching** (not discontinuance of BitTorrent) | Replaces one client with another while continuing same behavior | N11 then A16.05 | Continued BT use on different product |

**Three useful graph nodes:** `N19` intentional successful stop after goal attainment; `N20` switch software while continuing BitTorrent; `N21` temporary circumstantial suspension. These are **possible categories**, not observational counts.

## 6. Research questions with the highest information gain

These are research priorities, **not development priorities**:

1. **Full-cycle phone transfer:** on Android versions and distribution builds, which background execution mode can preserve long-lived download/seeding intent, and how exactly can the user observe OS interruption? Need target-SDK and device-specific tests, not just documentation.
2. **Ability versus visibility:** on a screen reader, can a person inspect name, percent of wanted files, upload, tracker obligations, remote instance identity, error cause and destructive consequences *without visual inference*? Use task scripts and screen-reader recordings.
3. **Actual data versus registration:** test who owns the backing bytes across qBittorrent, qui, seedbox file managers, Sonarr/Radarr, rclone and any disabled instance. Simulate external renames and failed moves. Avoid destructive experiments on irreplaceable real data.
4. **Abandonment vs solution:** recruit people who stopped torrenting because they achieved their objective, as well as those who quit from aversion, cost or technical failure. “Why did you stop?” is not enough; ask what task they were trying to complete.
5. **Publisher/recipient pairs:** an original creator shares an authorized large release; record creation, initial-seed readiness, recipient first packet, 100%-downloaded state and private communication. Distinguish the shared link from verified delivery.
6. **Uncertainty calibration:** compare a technical “99.9% stalled” screen with an account of available pieces and honest prediction limits. Validate user decisions and confidence, not whether a more detailed chart merely looks sophisticated.
7. **Product disconfirmation beyond qui:** investigate BiglyBT, Deluge plugins, ruTorrent scripting, qBittorrent and Transmission API, Tribler, Android apps and provider control panels for H10/H19/H20/H21/H24/H31/H35/H37/H42/H55–H69.
8. **Representation bias:** source set favors English-speaking self-hosters and public complaints; recruit creators, researchers, non-media users, low-resource households, people outside private tracker communities, and speakers in languages missing from the earlier sampling.

**Verification method:** record exact product/version and hosting mode; retrieve primary feature documentation and implementation evidence; exercise a goal-level workflow end-to-end where feasible; classify each H as `documented_existing_partial`, `documented_existing_complete_in_scope`, `implementation_specific_gap`, `source_only_not_verified`, `external_constraint`, or `not_yet_checked`. Do not infer market-wide absence.

## 7. Evidence register (individually traceable)

| ID | Source | Evidence type | Existing feature references | H research hypotheses | Assessment |
|---|---|---|---|---|---|
| **V01** | [qui: cross-seed overview](https://getqui.com/docs/features/cross-seed/overview/) | primary_implementation_docs | F03.20, F06.11, F15.15 | H07, H08, H39, H45 | documented_partial |
| **V02** | [qui: automation rule engine](https://getqui.com/docs/features/automations/) | primary_implementation_docs | F12.09, F12.10, F13.11 | H19, H20, H24, H50, H52 | documented_partial |
| **V03** | [qui: orphan scan](https://getqui.com/docs/features/orphan-scan/) | primary_implementation_docs | F03.19, F03.20, F03.22 | H08, H11, H20, H21 | documented_partial |
| **V04** | [qui: backup and restoration modes](https://getqui.com/docs/features/backups/) | primary_implementation_docs | F02.17, F11.12 | H10, H19, H21, H23, H61 | documented_partial |
| **V05** | [qui: export torrent registration to instance](https://getqui.com/docs/features/automations/) | primary_implementation_docs | F02.16, F10.08 | H02, H10, H17, H35, H61 | documented_partial |
| **V06** | [qui: multi-instance remote WebUI](https://getqui.com/docs/intro/) | primary_implementation_docs | F10.04, F10.05, F10.06 | H31, H33, H34 | documented_partial |
| **V07** | [cross-seed: FAQ and data matching](https://www.cross-seed.org/docs/basics/faq-troubleshooting) | maintainer_documentation | F03.24, F06.11, F15.15 | H05, H09, H22, H39 | documented_partial |
| **V08** | [Android: foreground service timeout](https://developer.android.com/develop/background-work/services/fgs/timeout) | authoritative_platform | F14.07, F05.07 | H67 | new_external_constraint |
| **V09** | [Android: user-initiated data transfer job](https://developer.android.com/develop/background-work/background-tasks/uidt) | authoritative_platform | F14.07, F05.07 | H67 | existing_platform_mechanism |
| **V10** | [Android: background transfer alternatives](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options) | authoritative_platform | F14.07, F05.07 | H67 | existing_platform_mechanism |
| **V11** | [Android: SAF directory restrictions](https://developer.android.com/training/data-storage/shared/documents-files) | authoritative_platform | F03.17, F14.06 | H68 | new_external_constraint |
| **V12** | [Android: manage all files and Play policy](https://developer.android.com/training/data-storage/manage-all-files) | authoritative_platform | F03.17, F14.06 | H68 | new_external_constraint |
| **V13** | [LibreTorrent README and Android variants](https://github.com/proninyaroslav/libretorrent/blob/master/README.md) | primary_implementation_docs | F03.17, F14.05, F05.07 | H67, H68 | existing_partial |
| **V14** | [LibreTorrent release notes 2026](https://github.com/proninyaroslav/libretorrent/blob/master/NEWS.md) | primary_release_notes | F14.08, F14.09, F13.03 | H68, H69, H64 | existing_partial |
| **V15** | [Blind Transmission user: unreadable torrent details](https://github.com/transmission/transmission/issues/6512) | first_person_accessibility_report | F14.09, F08.03 | H69 | reported_accessibility_gap |
| **V16** | [Blind qBittorrent user: NVDA interface](https://github.com/qbittorrent/qBittorrent/issues/11031) | first_person_accessibility_report | F14.09, F14.01 | H69 | historical_report |
| **V17** | [UNIT3D tracker inaccessible statistics and icons](https://github.com/HDInnovations/UNIT3D/issues/5492) | accessibility_issue_report | F14.09, F13.05 | H69, H59 | reported_accessibility_gap |
| **V18** | [Transmission September 2026 progress color report](https://github.com/transmission/transmission/issues/9104) | first_person_accessibility_report | F14.08, F14.09 | H69 | reported_accessibility_gap |
| **V19** | [Academic Torrents: first steps](https://academictorrents.com/docs/getting-started.html) | publisher_operations_manual | F01.12, F01.17, F03.13 | H37, H46, H47, H66 | documented_partial |
| **V20** | [Academic Torrents: upload data](https://academictorrents.com/docs/uploading.html) | publisher_operations_manual | F01.12, F01.13, F01.17 | H37, H46, H47 | documented_partial |
| **V21** | [Tribler research and P2P social model](https://github.com/Tribler/tribler/wiki/) | primary_project_research_archive | F06.12, F15.04, F04.20 | H40, H42, H60 | existing_related |
| **V22** | [Technology discontinuance seven types (2025)](https://www.tandfonline.com/doi/full/10.1080/0960085X.2025.2516427) | peer_reviewed_cross_domain | F14.12 | H55, H61, H64 | theory_not_product |
| **V23** | [Critical digital breakdown incidents](https://link.springer.com/article/10.1007/s00779-018-1184-8) | peer_reviewed_cross_domain | F08.08, F14.12 | H53, H61, H64 | theory_not_product |
| **V24** | [qBittorrent missing-file recovery guide](https://github.com/qbittorrent/qBittorrent/wiki/Status-of-Missing-files-at-start-up-or-after-restart) | primary_support_guide | F03.19, F03.15 | H09, H22 | documented_partial |
| **V25** | [Android foreground service types and boot rules](https://developer.android.com/develop/background-work/services/fgs/service-types) | authoritative_platform | F14.07, F05.07 | H67 | new_external_constraint |

### 7.1 Source-specific findings

**V01. qui: cross-seed overview:** Supports reuse of data via direct path, hardlink and reflink; exact matching, validation, cross-instance lookup. Not a universal job/file/library dependency view.

**V02. qui: automation rule engine:** Rule conditions and dry-run, cross-seed-aware deletion and hardlink protections. Limits depend on local filesystem access, mode and source.

**V03. qui: orphan scan:** Owner-aware scanning, preview, cross-instance active-job protection; inaccessible scans fail/partial, changed paths can be skipped. Not a guarantee across disabled instances or external library uses.

**V04. qui: backup and restoration modes:** Incremental/overwrite/complete restore and preview before changes, including per-torrent state. Not automatic recovery of all filesystem payload bytes.

**V05. qui: export torrent registration to instance:** Exports metafile/job, tests appearance and health; target data must exist and default skip-checking is enabled. This does NOT transfer payload or prove hash-correct bytes.

**V06. qui: multi-instance remote WebUI:** Manages multiple qBittorrent instances; some operations need local filesystem access and shared mounts. Not evidence of rTorrent heterogeneity or full cross-server privilege map.

**V07. cross-seed: FAQ and data matching:** Explains conflicting paths, stale torrentDir, permission mismatch and piece checks. Workaround and diagnosis, not a complete human-centered automatic repair.

**V08. Android: foreground service timeout:** Android 15+ target imposes six-hour/24h background time budget on dataSync/mediaProcessing FGS; cannot claim all torrent services are constrained identically.

**V09. Android: user-initiated data transfer job:** UIDT jobs introduced Android 14; require user initiation and notification, subject to OS conditions. Need workload-specific suitability analysis.

**V10. Android: background transfer alternatives:** Distinguishes user-initiated transfer, WorkManager and foreground service. WorkManager may be interrupted and retried; not a drop-in solution for indefinite seeding.

**V11. Android: SAF directory restrictions:** On Android 11+ ACTION_OPEN_DOCUMENT_TREE cannot grant roots and Downloads tree and has Android/data restrictions; permissions are not fungible with all-files access.

**V12. Android: manage all files and Play policy:** Broad file access is specially gated and scrutinized by Google Play policy, distinct from file-picker scope. Policy varies by app eligibility.

**V13. LibreTorrent README and Android variants:** Documents Play-store variant with limited filesystem access; F-Droid/direct APK differences and Android scheduling. Not all versions identical.

**V14. LibreTorrent release notes 2026:** July 2026 version 4.1 improves accessibility, locale overrides, file search and network policy, and fixes permission handling; verify variant/version.

**V15. Blind Transmission user: unreadable torrent details:** First-person blind user describes difficulty reading have/uploaded/downloaded in properties dialog via screen reader. Issue, not a cross-version audit.

**V16. Blind qBittorrent user: NVDA interface:** 2019 NVDA/JAWS/Narrator problem; closed issue does not establish present status.

**V17. UNIT3D tracker inaccessible statistics and icons:** Labels and title-only icons cause unlabeled tracker ratios and freeleech statuses; reported in 2026 and may be corrected later.

**V18. Transmission September 2026 progress color report:** Color distinction in progress was reported hard for colorblind user. Individual issue, not measured prevalence.

**V19. Academic Torrents: first steps:** Dataset sharing means preparing metadata and files, posting torrent, maintaining seed; includes webseed and versioning context.

**V20. Academic Torrents: upload data:** Torrent creation and upload to index require preserving a live seed or backup URL, not just sharing a magnet.

**V21. Tribler research and P2P social model:** Decentralized discovery and social trust experiments; historical research includes obsolete features and may not reflect current builds.

**V22. Technology discontinuance seven types (2025):** Seven exit types include solution discontinuance: leaving after task success is not the same as negative abandonment. Not a BitTorrent population study.

**V23. Critical digital breakdown incidents:** 292 narratives of digital breakdown; trust/control, material constraints and past updates can affect cognition, affect and future behavior. Not BitTorrent-specific.

**V24. qBittorrent missing-file recovery guide:** Explains errors from externally moved paths or unmounted storage and specific recovery sequence, but no automatic arbitrary mapping.

**V25. Android foreground service types and boot rules:** Android 14+ requires declared FGS type; Android 15+ targeted dataSync cannot start from BOOT_COMPLETED; not universal across all service types.


## 8. Limits and provenance

The map's existing F and A identifiers remain unchanged; this document is an external research overlay and does not create or authorize product requirements. **H labels identify hypotheses** from the conceptual map, not a commitment that any feature is truly new. Every cited implementation applies to a particular version/deployment or documented context, not the entire BitTorrent ecosystem.

Studies of app discontinuance and critical incidents are general technology research, not empirical BitTorrent adoption measurements. Feature requests, opened issues and first-person accounts do not establish frequency. A resolved bug does not prove it is resolved in all clients. Platform documents describe valid API and OS behavior but cannot alone verify a specific torrent app's architecture or reliability.

This research does not modify existing product contracts, specs, plans, agent work queue or implementation. Its outputs are falsifiable statements, concrete new research relationships and a narrowed list of questions worth further testing.
