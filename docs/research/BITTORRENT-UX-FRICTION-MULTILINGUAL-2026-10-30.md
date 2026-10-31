# BitTorrent UX friction: multilingual evidence and Chunks opportunities (2026-10-30)

**Status:** non-normative qualitative research, not implemented behaviour, not an independent work queue.  
**Scope:** experienced-user complaints, repeated manual actions, discoverability, remote/client parity, misleading states, file operations, safe defaults and mobile ergonomics.  
**Provenance:** owner-requested two-pass research on 2026-10-30. The initial exploratory pass preceded this expanded search. URLs below are public source references; source descriptions are paraphrases, and reports may reflect older software/versions. Never infer population prevalence or current Chunks implementation from another project's issue.

## The framing

The primary question is **what people repeatedly stop to understand, hunt down, work around or explain to another person**, including technically sophisticated people. Do not start from a list of features we already know to ask about. In particular, search for:
1. Confusion without technical vocabulary: "why did it", "where did it go", "is that normal".
2. Repetitive effort and workarounds: "every time I have to", "wrote a script", "the trick is".
3. Migration regrets, rollbacks and "switched back".
4. Apologies, help requests and socially corrected assumptions.
5. Positive reviews with one recurring complaint.
6. Surprises that arise because a setting or status is technically accurate but semantically unclear.
7. Remote mismatches between requests, acknowledgements, actual server state and visible result.

**Hypothesis, not statistical finding:** seasoned users commonly accept high configurability while objecting to high *interaction cost*. Chunks should maintain technical depth with predictable default behaviour and contextual access to controls. A setting's canonical home must not be its only discoverable entry point.

## Findings and evidence, first exploratory pass

First-pass notes preserved here, with directly identifiable public sources linked where possible. Where a source URL was not retained in that pass, mark the example **reverification needed** rather than fabricating an anchor.

| ID | User-observed friction | Research interpretation / proposed Chunks treatment | Evidence |
|---|---|---|---|
| F01 | Added torrents disappear from visible list under filters; successful background activity conflicts with empty UI | Always show active filter chips, hidden-result count and **Show all**; Add points to exact destination list | First-pass Reddit + issue leads; independent reverification needed |
| F02 | Users interpret *stalled* as broken rather than idle, while *Errored* conveys no cause | Pair plain-language explanation and recommended next step with precise raw state, never fake a diagnosis | [qBittorrent issue #23442](https://github.com/qbittorrent/qBittorrent/issues/23442) |
| F03 | Search works within the torrent list or a single torrent but not across files in all transfers | Collection-wide file search by name/type/size/path, constrained bulk file-priority/repair actions | First-pass Reddit lead; source URL reverification needed |
| F04 | Transdrone users want remembered paths, separate incomplete/completed locations, adding paused, folder hierarchy | Per-server recent/default Places; inherit known scope; show actual file tree; optional Start setting | First-pass Google Play review lead; source URL reverification needed |
| F05 | Network-interface binding is tucked under Advanced rather than Connection, repeatedly requiring community explanations | Contextual entry from connection/privacy diagnostics and setup; show actual effective interface and limits | [qBittorrent #20483](https://github.com/qbittorrent/qBittorrent/issues/20483) |
| F06 | Oversized Add dialog makes Add control hard to reach even on large displays | Persistent primary Add action, safe populated defaults, optional expanded controls | First-pass qBittorrent issue lead; source URL reverification needed |
| F07 | Some settings fail silently, and remote applications claim save without applied change | Requested / acknowledged / observed / refused / uncertain stages, scoped reason and safe reconciliation | [Flood #1047](https://github.com/jesec/flood/issues/1047) |
| F08 | Destructive checkbox defaults unset/unclear across contexts | Default to retaining data, distinguish Remove Job / Delete Data / Move Files and preview exact targets | First-pass Russian LibreTorrent review and ruTorrent support leads; reverification needed |
| F09 | Capabilities present in desktop client unavailable/undiscoverable via WebUI or remote | Capability-driven consistent primary placement, explain restrictions, no pretend controls | First-pass qBittorrent WebUI feature lead; reverification needed |
| F10 | Colour arrows and icon-only signals require looking up meaning | Pair icons with words, accessible descriptions, tooltips and high-contrast independent signal | First-pass ruTorrent lead; reverification needed |
| F11 | Per-file priority UIs struggle with folders, sorting, bulk selection and huge lists | Efficient hierarchical content tree, parent-level priorities, search and stable bulk selection | [VueTorrent #1456](https://github.com/VueTorrent/VueTorrent/issues/1456) |
| F12 | Sorting/filtering choices can't be recreated, combine non-obviously or behave inconsistently | Saved, intelligible sort/filter; explicit precedence and clear conditions | First-pass qBittorrent feedback leads; reverification needed |
| F13 | Context menu operations move deeper and become hard to find on touch devices | In-situ visible primary actions and labelled secondary menu, stable operation semantics | First-pass ruTorrent and qBittorrent discussion leads; reverification needed |
| F14 | Accessibility failures hide essential properties from screen readers | TalkBack-first semantic focus/announcements, accessible details, 48dp class targets, 200% font | First-pass Transmission issue lead; reverification needed |
| F15 | Horizontal lists and large panels obstruct ordinary gestures/content | Adaptive panes, control visibility, independent scroll, virtualize large data | [Flood changelog](https://github.com/jesec/flood/blob/master/CHANGELOG.md) |
| F16 | Local/remote profile confusion, especially switching server context or actions | Strict identity, visible environment/server names and explicit target preview | First-pass seedbox discussion; reverification needed |
| F17 | Downloading may be occurring but user cannot find physical files or identify destination | Prominent current Place and Open/Move when actually supported; distinguish job data/library copies | First-pass German LibreTorrent review and Russian forum, cross-check [4PDA thread](https://4pda.to/forum/index.php?showtopic=778908&st=120) |
| F18 | Advanced users want common All/Downloading/Finished views with safe delete checkbox | Familiar task-based views without concealing expert sorting/filter depth | First-pass Russian LibreTorrent review; reverification needed |
| F19 | Magnet add succeeds but no visible task, notification may diverge | Transfer intake receipt plus idempotent scoped list selection, honest deferred-metadata state | First-pass Spanish/Chinese LibreTorrent reviews; reverification needed |
| F20 | Zero upload and no downloads are misdiagnosed with port, tracker, firewall, VPN, peer and demand guesses | Non-destructive contextual diagnostics separate swarm supply, demand, peers, queue, network, permissions | [qBittorrent subreddit report](https://www.reddit.com/r/qBittorrent/comments/gnga6j) |
| F21 | Partially selected torrents may create seemingly unwanted adjacent files | Explain piece spanning and safe backing storage before selection; never claim that unselected files generate no bytes | First-pass Portuguese BiglyBT review; reverification needed |

## Additional verified or directly located evidence from the expanded pass

The expanded pass searched English and non-English behavioural phrases, migration stories, old-version rollbacks, issue/changelog history, forum troubleshooting and app reviews. **Sources are examples of expectations, not verified defect rates.** Some are support explanations or vendor release histories rather than direct complaints, identified as such.

| ID | Evidence & interpretation | Chunks idea / acceptance probe |
|---|---|---|
| E01 | [qBittorrent WebUI user, Jan 2025](https://www.reddit.com/r/qBittorrent/comments/1htjidv): Add with checked Start still transitions metadata -> Stopped; user must start manually each time. | Add result distinguishes choice, policy and actual observed status; concise reason why not started and one safe manual action. |
| E02 | [qBittorrent queue options #21284](https://github.com/qbittorrent/qBittorrent/issues/21284): "do not count slow torrents" unexpectedly allows every torrent to download; metadata gathering and transfer queueing are different concepts. | Goal-oriented explanation and a preview/summary of queue rule consequences, no false expectation. |
| E03 | [qBittorrent #24554](https://github.com/qbittorrent/qBittorrent/issues/24554): maximum concurrent checking value 0 silently prevents checks, leaving Checking 0%; higher values can mislead about performance. | Use named Off/Auto/Fixed if supported; invalid/exceptional numeric values explained on entry and in blocked-state diagnostic. |
| E04 | [qBittorrent #23442](https://github.com/qbittorrent/qBittorrent/issues/23442): "stalled uploading" is simply idle with no interested peer; users interpret as fault. | Human state "Ready to share; nobody requesting data" + raw protocol status. |
| E05 | [qBittorrent WebUI Reddit discussion, Sep 2026](https://www.reddit.com/r/qBittorrent/comments/1wsrdxz/qbittorrent_v524_and_v530rc1_release/): older version required individual confirmation/category for each item in a batch; shared Add dialog reinstated. | One batch review by default, override individual job only if needed, keep pending states and scope clear. |
| E06 | [qBittorrent #22308](https://github.com/qbittorrent/qBittorrent/issues/22308): some jobs repeatedly recheck or lose progress after restart, prompting re-add/reinstall. | Preserve session intent/verified identity; reject unsafe restore, explain discrepancy without silently redownloading or promising intact data. |
| E07 | [qBittorrent Reddit migration/recheck thread](https://www.reddit.com/r/qBittorrent/comments/12qhwtu/torrent_starts_being_redownloaded_when_i_recheck/): user must select parent directory, not the actual subfolder, to reuse existing data. | Explain expected root mapping and candidate matching; verified hashes before adoption; never overwrite foreign bytes. |
| E08 | [qBittorrent Reddit recheck workaround](https://www.reddit.com/r/qBittorrent/comments/1cf9f03/force_recheck_doesnt_work_have_to_reload_torrent/): no evidence recheck happened; workaround export/delete/re-add loses metadata. | Receipt for queued, running, finished and refused check including location and bytes; safe recovery path. |
| E09 | [qBittorrent Reddit 2024](https://www.reddit.com/r/qBittorrent/comments/1duo6or): users manually close before shutdown, recheck large libraries, and cannot restore destinations from exported torrent files. | Durable snapshot of torrent *definitions* plus scoped paths/policies, verify backing data separately; lifecycle completion visibility. |
| E10 | [qBittorrent Reddit 2025](https://www.reddit.com/r/qBittorrent/comments/1k3nlfq): VPN may initialize after client, requiring a manual restart. | Non-invasive connection diagnosis, safe network-wait state, no automatic insecure fallback. |
| E11 | [qBittorrent #24661](https://github.com/qbittorrent/qbittorrent/issues/24661): GUI with many content files lags enough to make users downgrade. | Virtualized file trees, bounded re-renders and performance tests at thousands of items; responsiveness is UX correctness. |
| E12 | [Flood #1047](https://github.com/jesec/flood/issues/1047): interface says "Settings saved successfully" while underlying rTorrent port unchanged. | Never equate UI save, RPC ack and effective server state. Verify readback and report pending/requires restart/uncertain correctly. |
| E13 | [Flood issues index](https://github.com/jesec/flood/issues): queued-for-seeding state invisible, wrong seeding ETA, location filters leak unrelated results, ambiguous 403, settings/filter defects. | Cross-engine state vocabulary and honest unknown; distinguish unsupported and unavailable telemetry. |
| E14 | [Flood changelog](https://github.com/jesec/flood/blob/master/CHANGELOG.md): adds tag-based preferred destination, last-used fallback, last-used Add tab and stronger rTorrent Add acknowledgements. | Treat shipped competing improvements as validation of convenience value, not proof of remaining bug; design safe per-scope fallbacks. |
| E15 | [rTorrent user moving 20-70GB to NAS](https://www.reddit.com/r/seedboxes/comments/1vfzmzc/q_rtorrent_blocking_main_event_loop_dropping/): synchronous post-download movement stalls RPC and torrent connections. | Remote progress vs unresponsive reason; copy/move is separate authenticated action; no blind retry on timeout. |
| E16 | [seedbox provider Oct 23, 2026 upgrade notice](https://blog.seedboxes.cc/legacy-torrent-client-upgrades-and-app-retirements/): ruTorrent v3 -> v4 URL changes and separate File Manager/automation replacements. | Capability and endpoint validation, stable profile semantics, explain where previously expected functions now live. |
| E17 | [Russian 4PDA LibreTorrent thread](https://4pda.to/forum/index.php?showtopic=778908&st=120): user asks for selected-file size and live storage free space during Add; torrent vanishes even under All after pause/restart; developer initially suspects filter. | Show selected size and trustworthy capacity/unknown alongside Place, and reconcile list state independently of filter. |
| E18 | [Russian Habr comment](https://habr.com/ru/articles/356682/comments/): complains about cross-file piece storage and client UI/preferences while switching away from legacy uTorrent. | Correct partial-file disclosure and safe selection; do not promise byte-for-byte absence outside selected files. |
| E19 | [French hardware forum user](https://forum.hardware.fr/hfr/electroniquedomotiquediy/nano-ordinateur_microcontroleurs_fpga/unique-euros-raspberry-sujet_9_937.htm): Plex can read external disk while qBittorrent cannot write; long ownership/permissions workaround. | Per-Place capability test and actionable permission explanation, never infer write authority from read access. |
| E20 | [Chinese NAS/qBittorrent guide](https://www.zhihu.com/tardis/zm/art/1965417116032296514): tutorial expects readers not to know where NAS download folder is, and documents a persistent unauthorized login redirect issue (author report). | Human named Place, real storage location, exact remote endpoint, helpful connection error. This is guide evidence, not a direct UX complaint corpus. |
| E21 | [German Unraid forum report](https://forums.unraid.net/profile/143435-gargamel2/content/?type=forums_topic_post): user spent hours finding hidden container UMASK option to make storage usable. | Explain permissions and actionable configuration only within user-authorized scope; Chunks cannot change server container privileges. |
| E22 | [Transgui README](https://github.com/transmission-remote-gui/transgui): several useful settings only exist in .ini rather than GUI; includes fixed shortcuts to show All, Downloading, Completed etc. | Settings lookup/search and clear remote feature capability; avoid forcing obscure config-file editing for core tasks. This is documentation evidence. |
| E23 | [Transgui releases](https://github.com/transmission-remote-gui/transgui/releases): added touch-sized controls, clear-search X, persistence, server names and disabled auto-start warning. | Use competitor change history as affordance ideas; version-specific fixes not counted as currently unresolved bugs. |
| E24 | [VueTorrent content cluster #1456](https://github.com/VueTorrent/VueTorrent/issues/1456): small mobile content view, sorting by name/size, size filtering, bulk selection, folder priorities and large-tree performance all requested. | A single integrated file browser/priority model, tested on phone and large torrents. |
| E25 | [qBittorrent #21987](https://github.com/qbittorrent/qBittorrent/issues/21987): recheck can rename a completed file and require a second check with partial filename extension, especially during cross-seeding. | Explain which physical files a recheck will touch; no destructive recheck of unknown-owned bytes; avoid unexpected renames. |
| E26 | [French NAS forum discussion](https://forum.hardware.fr/hfr/OSAlternatifs/Installation/unique-openmediavault-utilisateurs-sujet_72970_179.htm): after restart, container appears to write onto SD system volume instead of the configured shared disk. | Verify actual destination mount and free space, not path string alone; refuse unsafe fallback where actual volume identity changed. |
| E27 | [qBittorrent #24114](https://github.com/qbittorrent/qBittorrent/issues/24114): reporter surprises about binding to multiple interfaces; maintainer clarifies wildcard semantics. | Expose exact effective interfaces/unknowns, do not imply VPN protections not independently verified. |
| E28 | [qBittorrent Reddit](https://www.reddit.com/r/qBittorrent/comments/kumbas): wants new torrents prioritized automatically, finds manual speed/priority overrides and nonobvious global-vs-transfer effects. | Contextual explanation of winning queue rule, manual override and next action; apply only if engine supports it. |
| E29 | [qBittorrent Reddit setup reset](https://www.reddit.com/r/qBittorrent/comments/1hb50yv/how_to_restore_to_factory_settings/): resetting config requires finding hidden filesystem folders, leading to confusion over different paths. | Clear settings reset/export/import with separate preserved jobs/files/credentials and exact scope, no automatic destructive reset. |
| E30 | [German-language BitTorrent FAQ](https://www.bittorrent-faq.de/): a dedicated FAQ section asks where downloaded files went. | Always expose actual Place and Open in system app where accessible. FAQ presence is indirect evidence only. |

| E31 | [Polish QNAP Deluge support thread](https://forum.qnap.net.pl/threads/konfiguracja-programu-deluge-prosze-o-pomoc.37061/): user changes download location yet sees no files and download stops after seconds; reply suspects configuration was never saved. | Effective path and verified write capability with a clear distinction between submitted configuration and observed destination. |
| E32 | [Polish Transmission router thread](https://openlinksys.info/forum/viewthread.php?rowstart=20&thread_id=18070): UI reports ample external space when user selects /nas, but actual download writes into tiny router-internal storage. | Treat device/mount identity and proven write destination as separate from displayed string and estimated capacity. |
| E33 | [Polish Transmission startup forum](https://openlinksys.info/forum/viewthread.php?pid=133804&thread_id=18070): start happens before drive mount, so service loads defaults; users repeatedly toggle service after reboot. | Read-only mount readiness detection, pending state and clear next actions; never silently fall back to another filesystem. |
| E34 | [Polish Transmission settings discussion](https://eko.one.pl/forum/viewtopic.php?id=16657): GUI changes to peer limits appear ineffective, attempts to edit settings files fail, user hunts through multiple configuration locations. | Tell users where an effective setting actually lives; for remotes, verify whether the daemon accepted and applied it. |
| E35 | [Turkish Technopat discussion](https://www.technopat.net/sosyal/konu/bittorrent-uzerinden-indirme-yaparken-anlik-donmalar-nasil-duzeltilir.3192025/): user exits and relaunches a torrent client to recover from recurring system freezes; suspected security-software interactions remain unverified. | Performance/health instrumentation and honest inability to diagnose from transfer status alone; do not claim an antivirus cause. |
| E36 | [Spanish ForoCoches January 2026 automation discussion](https://forocoches.com/foro/index.php/showthread.php?p=508056178): user wants to choose release quality, codec and subtitle/audio precisely but manual Prowlarr -> qBittorrent -> category chain defeats automation. | Optional human-approved discovery-to-Add handoff that preserves chosen source and destination, without forced auto-select. |
| E37 | [qBittorrent #12648](https://github.com/qbittorrent/qBittorrent/issues/12648): deleting an unselected adjacent file can make a completed file incomplete after recheck because two files share a piece. | Surface cross-file piece coupling and safe deletion consequences; never promise that partial selection prevents collateral dependency. |
| E38 | [qBittorrent #9873](https://github.com/qbittorrent/qBittorrent/issues/9873): user describes previously downloaded files overwritten during recheck/re-download after a drive disconnect. | Strong corroboration of Chunks #568 stakes: protect previously existing foreign bytes with owner-scoped atomic native writer, not UI-only confirmation. |
| E39 | [TrueNAS user discussion](https://www.truenas.com/community/threads/qbittorrent-torrents-not-saved-sometimes-after-restart.116455/): recent jobs and their paths disappear after restart, requiring manual re-add. | Journal transfer definitions and confirmations durably, and distinguish restored jobs from provably intact bytes. |

## Multilingual discovery matrix and signal quality

Run patterns with *client names removed as well as inserted*, and across mobile reviews, legacy forums, GitHub issues, Reddit, platform-language communities. Don't treat equivalent strings as sufficient coverage: search retrieval heavily favours official English documentation.

| Language | Behavioural seed terms | Signal this pass |
|---|---|---|
| English | "every time I have to", "switched back because", "am I missing something", "wrote a script", "finally figured out", "regression" | Strong: first-hand Reddit, issues, version rollback |
| Russian | "каждый раз приходится", "неудобно", "почему пропал", "не хватает", "где найти" | Strong older first-hand 4PDA; Habr comparison |
| Simplified Chinese | "每次都要", "麻烦", "为什么", "找不到", "怎么设置" | Moderate: mostly NAS tutorials and configuration tips; direct forum sample still thin |
| French | "à chaque fois", "obligé", "je ne trouve pas", "pénible" | Moderate: first-hand home-server setup and storage problems |
| German | "jedes Mal", "umständlich", "wo finde ich", "hat mich Stunden gekostet" | Moderate: forum reports and FAQ |
| Spanish | "cada vez", "tengo que", "no encuentro", "no aparece" | Moderate: one detailed 2026 automation tradeoff from home-media stack; first-pass Play-review leads still require source verification |
| Brazilian Portuguese | "toda vez", "tenho que", "não aparece", "não encontro" | Sparse: first-pass reviews/thread leads; expanded queries dominated by English |
| Japanese | "毎回", "面倒", "手動", "どこ", "見つからない" | Sparse: product/docs pages, no strong new first-person complaints verified |
| Polish | "za każdym razem", "muszę", "nie mogę znaleźć", "uciążliwe" | Strong older first-person QNAP/router/Transmission reports about destinations, start order and settings persistence |
| Turkish | "her seferinde", "zorunda", "bulamıyorum", "karışık" | One first-person freeze/restart report; unclear attribution to torrent client, weak UX-specific coverage |
| Italian | "ogni volta", "devo", "fastidioso", "non trovo" | Expanded attempt; no credible direct complaint added |

**Interpretation discipline:** No quantified cross-language prevalence; language coverage != source validity. Separate firsthand user complaints, maintainer issue reports, documentation/changelogs, vendor guidance, and hypotheses. An issue labelled open does not establish defect reproduction. Older fixed defects still reveal a valuable expectation but should not be described as currently broken.

### Further latent-signal prompts

- Workaround discovery: "export/re-add", "restart fixes", "batch manually", "custom script", "special URL", "right click on phone".
- Negative surprise: "I thought", "why did it remove", "where are my files", "can't tell if", "it says success but".
- Migration: "downgraded", "went back", "miss the old", "used to be easy".
- Settings layout: "which tab", "not where expected", "moved", "works on desktop not web", "can't change via remote".
- Productive annoyance: positive four/five-star app reviews with "but", "wish it had", "one thing".
- Recoverability: reconnect, loss of mount, renamed folder, recheck and data-ownership ambiguity.
- Logs/release notes: fixes for wrong icon, missing progress, misleading status, dropped queue action, large-list rendering.
- Issue comments: users disagreeing about what options should do and what **active**, **paused**, **stalled**, **checking**, **queued**, **stopped**, or **complete** mean.

## Chunks UX recommendations (proposals; canonical contracts govern)

**Priority principle:** fix G0 destructive storage (#568) and foundational G1/G2 contract correctness first. New research does not authorize diverting engineers from required release gates. Improvements below should be folded into existing canonical UX/domain contracts and prioritized after prerequisite safety, unless they directly prevent unsafe actions or false successes.

### R01: Plain language + technical truth in the same context
Every transfer state should show (a) human effect, (b) measured/verified reason or explicit "unknown", (c) available safe next step, (d) raw engine state in secondary details. "Stalled" must not look like an error merely because no peer is interested. Never imply a cause where the engine lacks evidence.

### R02: Action receipt and observed outcome
On Add, Start, Stop, Remove, Move, Recheck and remote Save: show exact environment, job identity, requested action, acknowledgement, confirmed postcondition and uncertainty/refusal. A timed-out remote command is **uncertain**, never automatically retried. An Add should give the job's exact location/filter route; an unavailable environment must not silently redirect.

### R03: Contextual settings without duplication
Each setting has one semantic definition and explicit scope (all configured profiles / selected environment / single transfer / batch). Entry points may appear in Settings, Add, Transfer Details, diagnostics, effective-policy explanation, and relevant file view. All open the same authoritative control, showing inherited default, exception/override and applicability. Search settings by user task, symptom and technical name.

### R04: Frictionless ordinary Add
Show verified per-environment default Place, actual selected and required size/free space only if measured and trustworthy, selected-file preview, policy and start state. Ordinary action visible without scrolling; optional deeper controls remain accessible, not mandatory. Bulk Add uses shared settings with explicit individual overrides, no N-dialog treadmill. Never assume a path's apparent cleanliness permits foreign-byte overwrite.

### R05: First-class file finder and batch content operations
File search should span transfers where permitted, not just torrent titles or one selected torrent. Results preserve transfer/environment identity, actual backing file or published copy, size/type/priority and selectable batch operations. Explain operations that affect shared pieces, existing backing data, seeding or other jobs. Optimize thousands of files with virtualization and 200% font.

### R06: Visible and recoverable list state
Active filters are explicit chips; hidden-result count and Show all are immediate. Persist user-intended per-environment filter/sort only, with sensible session migration. Never present "empty" as a genuine empty server response if background jobs or filters disagree. Distinguish loading, offline, privacy locked, permission denied and verified empty.

### R07: Durable choices, not permanent wizard obligations
Remember per-engine recent valid Places, Add start preference where appropriate, last useful filter/sort, last Add tab and explicit local-vs-remote scope. Never silently carry one server's path, auth or capabilities to another. Reset/forget behaviour is clear.

### R08: Safe destructive operations
Distinct words and UI for Remove Job, Delete Payload, Move Backing File, Publish/Copy Payload, Copy/Move Transfer Definition, Clean Extracted Output. Unowned or uncertain bytes never deleted. Default conservatively, show exact targets and ownership, safely refuse when not provable. Distinguish filesystem movement from an RPC setting change.

### R09: Capacity and environment health
Display actual authorized Place, accessible/not writable/full/unmounted/unknown states and verified capacity where exposed. Do not infer remote free space from a guessed mount, and do not pretend remote rTorrent XML-RPC grants file read/write authority. Explain VPN/port/peers/tracker/queue limitations without claiming diagnostics prove privacy or reachability.

### R10: Robust remote parity
Shared controls/labels wherever capabilities truly match. Unsupported actions remain discoverable with truthful explanation; saved settings are read back where possible. Show actual server/profile, session principal and status freshness; preserve account/privacy lock invariants. Remote File Manager and non-core seedbox administration remain secondary.

### R11: Good mobile and accessibility ergonomics
Persistent primary actions; obvious affordances, named scopes and readable icon labels; 48dp-class touch targets, 200% font, TalkBack, keyboard, high contrast, focus restoration, long content lists without trapped horizontal scroll. Put critical information in compact and secondary sections, not a forced expert questionnaire.

### R12: State-specific user testing
Write scripted tests around predictable user intentions:
- "I added a torrent. Where is it? Why hasn't it started?"
- "This says stalled. Is something wrong?"
- "The Add form won't let me reach Add."
- "I chose one file. Why are there extra bytes/files?"
- "I changed the remote setting. Did it actually change?"
- "I switched server. Which copy of this identical hash am I touching?"
- "I moved the folder; can you find my existing data without overwriting anything?"
- "I have hundreds of torrents; find all .srt files and prioritize the selected subset."
- "I changed the queue limit and every transfer started."
- "Does this seeding rule apply here, and which setting wins?"
- "I filtered by status; why are my transfers missing?"
- "I restarted my phone/daemon while a write was pending."
Each test needs exact scope, expected visible state, no cross-environment mutation and explicit evidence tier.

## Prioritization suggestions, **not the active queue**

- **G0 / safety-congruent:** truthful Add/Remove readback, ownership and capacity refusal, no stale wrong-server mutation, no misleading native write success, preserving user data (#568 and existing security/remote work).
- **G1/G2 / foundational UX:** scope, state explanations, transparent filters, usable Add, action receipts, accessibility, real remote outcomes, settings search/contextual navigation.
- **After prerequisites:** cross-transfer file search/priorities, more sophisticated saved views, diagnostics guidance, optional automation, deep consistency refinements.
- Avoid independent duplicate work queue or reopening already planned features without checking [SPEC-COVERAGE](../SPEC-COVERAGE.md).

## Relation to canonical Chunks documents

- [PRODUCT](../PRODUCT.md): intended capabilities and safe default behaviour.
- [UX](../UX.md): user-visible interaction authority.
- [UNIFIED-ENVIRONMENTS](../UNIFIED-ENVIRONMENTS.md): scoped environment and setting invariants.
- [TASK-OUTCOMES-AND-CONTINUITY](../TASK-OUTCOMES-AND-CONTINUITY.md): action receipts, pending external tasks.
- [EFFECTIVE-POLICY-EXPLANATIONS](../EFFECTIVE-POLICY-EXPLANATIONS.md): rule winner/override/conflict.
- [STORAGE-AND-EXTRACTION](../STORAGE-AND-EXTRACTION.md): destructive operations and native ownership.
- [WORK-QUEUE](../WORK-QUEUE.md): sole active release priority; #568 G0 remains red.
- [QUALITY-LEDGER](../QUALITY-LEDGER.md): evidence only, not ideas.
- [BITTORRENT-USER-AVERSION-RESEARCH-2026-10-29](../BITTORRENT-USER-AVERSION-RESEARCH-2026-10-29.md): broader adoption/non-use research.

## Reconciliation against live Chunks, 2026-10-30 (design delta, not a product audit)

Inspected the repository's canonical [UX](../UX.md), [PRODUCT](../PRODUCT.md), [UNIFIED-ENVIRONMENTS](../UNIFIED-ENVIRONMENTS.md), [INTAKE-QUEUE-AND-POLICIES](../INTAKE-QUEUE-AND-POLICIES.md), [FILES-PROTOCOL-AND-DIAGNOSTICS](../FILES-PROTOCOL-AND-DIAGNOSTICS.md), [TASK-OUTCOMES-AND-CONTINUITY](../TASK-OUTCOMES-AND-CONTINUITY.md), [EFFECTIVE-POLICY-EXPLANATIONS](../EFFECTIVE-POLICY-EXPLANATIONS.md), and current local/combined Compose sources. Also inspected the in-flight [PR #783](https://github.com/gamesbyian/Chunks/pull/783), which already specifies visible, auto-resolved controls and skip-safe downstream continuity. The report must **not** be interpreted as showing these capabilities are entirely missing from Chunks.

| Existing coverage / source state | Actual research-derived clarification | Canonical owner |
|---|---|---|
| Compose local and combined transfer screens already expose per-list filtering and sorting, and local UI distinguishes **no phone transfers** from **no search matches**. Combined code has explicit selected-environment statuses and saved-profile readback restrictions. | Explicit active filter summary, scoped hidden-result counts when known, one-tap reset, and a verified Add's route into a filtered list remain separately unproven. | [UX](../UX.md#findability-and-truthful-outcomes-in-everyday-torrent-journeys-binding-ux-research-informed-2026-10-30) |
| Existing diagnostics, queue/stall distinctions, limited transfer observations, scoped operation receipts and policy explanations already exist. | Require one succinct **what it means / what is known or unknown / what can be done now** path for idle/blocked/errored states; avoid diagnosing peer demand from rate zero. Clarify 0-limit semantics and queue-change consequences. | [UX](../UX.md#shared-interaction-grammar), [intake](../INTAKE-QUEUE-AND-POLICIES.md#queue-retries-history-and-liveness) |
| Individual transfer file listing and priority operations have bounded existing slices; general indexed file operations are separately specified. | New *cross-transfer* file metadata search, with explicit scope, strict per-result identity, efficient list/batch handling, and no assumption of remote payload-byte access. | [Files/protocol](../FILES-PROTOCOL-AND-DIAGNOSTICS.md#collection-wide-file-metadata-search-and-batch-selection) |
| [PR #783](https://github.com/gamesbyian/Chunks/pull/783) is already changing product/UX/seeding rules and the queue to make optional decisions visible, automatic and correct. | Contextual symptom-to-setting entry points supplement that PR; there must remain a single authoritative value and explicit source/override/scope. Merge/rebase PRs without replacing #783's owner correction. | [UX](../UX.md#filter-and-settings-findability) |
| Native ownership hazard #568 is still a release blocker, even if individual safe checks and diagnostics pass; real foreign-file writes have been observed. | Storage/permission findings are corroborating evidence for *existing G0 work*, not authority to implement a new unsafe file-management convenience feature. | [Storage](../STORAGE-AND-EXTRACTION.md), [active queue](../WORK-QUEUE.md) |

**Editorial note (2026-10-31):** The following and the preceding #783 row describe the original proposal. #783 and #795 were closed unmerged; their reconciled specifications were integrated through [#797](https://github.com/gamesbyian/Chunks/pull/797) and [#799](https://github.com/gamesbyian/Chunks/pull/799). This does not establish runtime acceptance.

**Design/priorities edited in this research PR:** [UX](../UX.md), [PRODUCT](../PRODUCT.md), [UNIFIED-ENVIRONMENTS](../UNIFIED-ENVIRONMENTS.md), [FILES-PROTOCOL-AND-DIAGNOSTICS](../FILES-PROTOCOL-AND-DIAGNOSTICS.md), [INTAKE-QUEUE-AND-POLICIES](../INTAKE-QUEUE-AND-POLICIES.md), [DECISIONS](../DECISIONS.md), [SPEC-COVERAGE](../SPEC-COVERAGE.md) and [WORK-QUEUE](../WORK-QUEUE.md). Those files are normative according to the [authoring standard](../DOCUMENTATION-STANDARD.md); this report remains source evidence, methodological rationale and a record of the design delta. Changes are **proposed in PR #795, not yet merged or feature-tested**, and depend on reconciling overlap with #783. No Android runtime code or protected host/physical tests were changed or executed here.

## Follow-up research gaps, no fabricated conclusions

1. Re-find and preserve URLs/quotes for initial Spanish, Portuguese, German, Japanese, Russian and Chinese Play Store reviews, plus Reddit file-search and filter-confusion threads. Search review pagination/version/date and archive stable links where possible.
2. Seek more direct first-person evidence from Japanese, Polish, Turkish, Italian and non-English seedbox communities. Count valid *firsthand* reports separately from indexed docs.
3. Capture smartphone-only rTorrent/Transdrone/ruTorrent paths, lost connection contexts and wrong-profile targeting.
4. Cross-check each recommendation against current Chunks plans/spec coverage before filing a duplicate issue.
5. Run user-intent walkthroughs on actual Chunks screens after P0 safety and exact-head UI readiness, with accessibility and physical Android acceptance; screenshots and hypotheses are not proof.

**No app code, CI, security/privacy state, mainline queue ordering or actual user data is changed by this report.**
