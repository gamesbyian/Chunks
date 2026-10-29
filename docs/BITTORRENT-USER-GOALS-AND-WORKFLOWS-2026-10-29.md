# How people use BitTorrent clients, remotes and seedbox tools

**As-of:** 2026-10-29  
**Class:** Descriptive external research reference; non-normative  
**Audience:** Researchers, designers, writers and developers seeking a realistic, end-to-end understanding of user behavior  
**Scope:** 36 complete example journeys and 240 reusable micro-workflows, with typed relationships to the separate 240-feature catalogue. Activities before, during, between and after interactions with BitTorrent clients, remote controllers, seedbox platforms, file managers, indexers, library software and associated automation. It includes common, intermittent and exceptional activity.

**Navigation:** Sections 1–18 explain goals, user journeys and behavior; Section 19 is a 240-activity atlas; Sections 20–22 provide a conceptual-map relationship model and crosswalk; Sections 23–24 discuss limitations and sources.

**Boundary:** This document describes behaviors, goals, activity chains and user mental models. It makes no comparison against any application in this repository, proposes no product requirements, prioritizes no engineering work, and does not assess implementation or UX. The separate external feature catalogue is complementary, but this document stands on its own.

## 1. How to interpret the evidence

This is **qualitative workflow research**, synthesized from primary software manuals and API instructions, publisher distribution guides, seedbox provider operational documentation, automation software workflows, and people describing what they actually do on community forums. The numbered source register appears at the end. Source codes such as **S13** refer to that register.

The evidence differs in strength:

- **D, documented workflow:** A vendor, open-source maintainer, publisher or host explicitly shows an operation, sequence or dependency. It establishes a supported workflow, **not how many people use it**.
- **U, user-described behavior:** A person describes an existing habit, goal, frustration, exception or transition. It establishes an illustrative use case, **not a representative population estimate**.
- **I, analytical synthesis:** A reasonable sequence, motivation, taxonomy or implication derived from several documented operations. It is a model of possible behavior, **not observed telemetry**.
- **C, contextual pattern:** A supported general statement about the mechanism or environment, with no claim of measured prevalence.

Words such as **frequent**, **occasional**, **rare** and **once-per-setup** refer to an activity's *likely recurrence for someone following a particular workflow*. They are contextual labels, not frequencies measured across all BitTorrent users. For example, inspecting trackers is exceptional for a casual downloader, but potentially daily for a tracker operator. The sources have a selection bias toward engaged users, self-hosters, maintainers and people seeking help. Silent, uncomplicated use is naturally underrepresented. Neither product manuals nor forum threads constitute population research.

A person's goal may involve copyrighted entertainment, publicly licensed downloads, their own recordings, game builds, scientific data or privately shared files. The transfer mechanics and interaction sequence often overlap; permissions, hosting rules and tracker policies do not. This document describes motivations without treating every purpose as licensed or every use as infringing.

### 1.1 The key analytical distinction

**A successful BitTorrent job is often an intermediate milestone, not the user's desired end state.** A completed file may still need importing, inspecting, transferring, playing, installing, analyzing, sharing, backing up, or long-term seeding. Conversely, an always-on seeder can be fulfilling its purpose without a person opening an interface for weeks. Examples are documented in Ubuntu downloads, Academic Torrents, Internet Archive, Sonarr and seedbox workflows (S02–S04, S13–S14, S20–S23).

## 2. What people ultimately want

| Human goal or desired result | What BitTorrent contributes | Possible next step *outside* the torrent client | Nature of work |
|---|---|---|---|
| Obtain a particular file or folder | Transfer verified content from peers | Open, install, read, watch or archive | Acquisition; consumption |
| Obtain a large package cheaply and reliably | Multi-source distribution, resume and integrity checks | Unpack, extract a subset, run a pipeline | Acquisition; technical |
| Choose only relevant items in a collection | File selection and priority within a torrent | Consume selected items; keep other items skipped | Curation; triage |
| Watch or listen soon | Download playable pieces or complete a media item | External player, casting or library | Consumption; scheduling |
| Maintain a personal media collection | Obtain, import and track media files | Rename, subtitle, poster/artwork, scan server | Curation; organizational |
| Keep a scarce file available to others | Serve verified bytes from a stable machine | Monitor retention, replenish or migrate a seed | Stewardship; communal |
| Share original content with a group | Produce torrent metadata and initial seed | Publish link/metafile, communicate to recipients | Creative; publishing |
| Distribute a software release | Offload bandwidth and offer resilient mirrors | Install or validate a release | Technical; distribution |
| Distribute or reproduce a scientific dataset | Replicate durable, verifiable bundles | Compute, compare, cite and publish results | Research; preservation |
| Fulfill obligations on a private tracker | Continue sharing and meet site-specific rules | Audit ratio, time, credits and account status | Compliance; stewardship |
| Continue seeding across several trackers | Reuse same verified payload for multiple jobs | Organize references, hardlinks and storage | Optimization; technical |
| Run downloads while away from home | Daemon or seedbox acts while phone is only a controller | Later retrieve or consume data on chosen device | Delegation; remote control |
| Avoid a home machine being left on | Hosted or headless always-on operation | Retrieve files on demand; monitor cost | Convenience; infrastructure |
| Minimize manual repetitive work | Automation consumes feeds and coordinates clients | Review exceptions; use final library | Orchestration; maintenance |
| Manage limited disk, traffic or money | Rate, quota and retention choices | Move/archive/delete with reference awareness | Resource management |
| Recover from an interrupted or mistaken operation | Recheck, reconnect, map paths, diagnose | Continue downloading or seeding without lost bytes | Repair; technical |
| Run a household/shared service | Multiple users, libraries, requests and endpoint control | Approve requests and troubleshoot playback | Administrative; service |
| Learn about protocols or tune performance | Expose peers, trackers, port status and logs | Test a hypothesis, change network configuration | Diagnostic; educational |

**Notable contrasts:** Some people enjoy hands-on cataloguing and filenames; others seek a fully unattended intake-to-library flow (S20, S22). Some maximize early upload credit; others aim at permanent preservation (S25–S26). Others only open a client once to obtain an installer. These are different goals, not different levels of expertise.

## 3. What each kind of software is hired to do

People ordinarily understand their workflow in terms of *jobs*, even if they do not distinguish the underlying services perfectly.

| Tool or system role | What the person wants it to accomplish | Typical actions | What it does *not* necessarily do |
|---|---|---|---|
| Browser, search/index site, social link | Find something worth obtaining or sharing | Search, compare results, inspect info and provenance, copy a magnet | Transfer payload data |
| Torrent client on phone/PC | Obtain or serve data on that device | Add, select, queue, monitor, verify, seed, remove | Control a seedbox unless remote capability exists |
| Always-on torrent daemon | Continue work without interactive desktop | Run queue, seed, accept scripted/API commands | Present a usable interface to every device |
| Remote-control app or WebUI | Tell a different machine what to do; know what happened | Connect, add, pause, prioritize, inspect, adjust policy | Make the remote payload available on the phone |
| Seedbox host control panel | Manage a rented computing/storage service | Install/restart apps, inspect quota, recover credentials, open app links | Replace each app's specific torrent operations |
| Remote filesystem tool | Reach the *bytes* and directories on another machine | Browse, copy, retrieve, rename, move, archive | Automatically update torrent job file mappings |
| RSS/indexer tool | Locate content according to saved criteria | Subscribe, search, filter and hand off an intake event | Independently download the payload |
| Automation/orchestration sidecar | Perform repeatable work at scale | Trigger, classify, retry, cross-seed, notify, clean | Guarantee that every chained operation succeeded |
| Media/library manager | Turn completed downloads into usable catalog entries | Match, rename, hardlink/copy, subtitle, upgrade | Necessarily delete the seeding copy |
| Media server/player | Allow the actual intended enjoyment or use | Scan library, index, stream, play | Operate the peer swarm |
| Archive / backup / sync tool | Preserve or move bytes elsewhere | Synchronize, checksum, restore, mirror | Preserve the original torrent session automatically |
| Tracker/community portal | Make releases findable and apply membership rules | Publish metadata, enforce upload/seed policies | Host all payload bytes |

The distinction between **remote torrent job**, **actual payload storage**, **remote-control connection**, **file-transfer connection**, and **view of a media library** is foundational. A command that starts a job on a remote daemon does not also retrieve the data to the device running the command. A file visible in a media library need not be the same directory entry being seeded (S05–S10, S13–S14, S31).

## 4. A complete journey is a loop, not a single download

A common conceptual sequence is:

**Need or idea → discovery or preparation → selection and trust judgment → choose destination/engine → submit job → peer discovery/metadata → download/verify → consume or distribute → continue seeding → retain, archive, relocate or retire → discover a new need.**

Each arrow is conditional. For a publisher, preparation precedes a new seed. For an automated library, discovery and submission are performed by software. For a cross-seeder, download may be skipped because compatible bytes already exist. For a remote user, submission happens on a phone while storage and seeding happen elsewhere.

### 4.1 Pre-BitTorrent activity

1. Recognize a need: a Linux installer, reference dataset, recording, update, collection, backup, personal project or request from someone else.
2. Decide whether BitTorrent is appropriate instead of an ordinary download, streaming service, cloud storage, direct FTP/SFTP or physical media.
3. Find candidates through an official publisher, indexer, tracker, shared magnet, forum, feed, library manager or another person.
4. Judge suitability: precise title/version, edition, codec, language, resolution, size, file inventory, expected completeness, source reputation, license and local available space.
5. Consider consequences: data allowance, private tracker rules, whether a torrent will reveal an IP address to peers, risk of untrusted executable content, and how long sharing should continue.
6. If remote: choose which engine/device has appropriate disk, network, connectivity and access to the eventual destination. A person may never explicitly think of these as “engine selection.”
7. Prepare only if needed: credentials, service connection, destination folders, VPN/interface, file-manager access, streaming/library mapping, indexer profile, external disk.
8. In a creative/publishing workflow: finish and name source files, decide public/private audience and tracker/magnet distribution, and verify that the bytes are actually ready to seed.

**Typical pre-entry artifacts:** desired title, search result, magnet link, small .torrent file, selected folder, existing data, saved feed rule, private tracker credentials or a person-to-person share message. (S01–S04, S12, S14, S20–S23.)

### 4.2 Interactive software entry

Users arrive in the torrent software by more than one route:

- **Action-led entry:** tap a magnet in a browser; share a torrent file from Files; open a downloaded .torrent; use an “add to remote” action; accept a download from another app.
- **Status-led entry:** tap an “added,” “completed,” “error” or quota notification; open a homescreen icon specifically to see whether the job is done.
- **Navigation-led entry:** open remote WebUI, saved seedbox dashboard bookmark, CLI or a unified media manager; choose a server or view.
- **Re-entry after absence:** return to a persistent list after hours, days or weeks and first reestablish whether the selected daemon is online and information is current.
- **Administrative entry:** visit a provider control panel only because an app will not open, a service needs installation/restart, login details are needed, quota is near its cap or a bill/renewal matters.
- **Machine-led entry:** scheduler, RSS/IRC watcher, indexer, webhook or library manager submits an API job without a human opening any client UI.

A first-run installation session and an ordinary “open link, check progress, leave” session have different work content and vastly different durations (S01, S05–S10, S18–S21).

### 4.3 While the transfer runs

A torrent's piece exchange and disk writing are usually unattended. People may:

- Continue browsing, gaming, working, sleeping or using another device; the client remains in the background.
- Make a quick periodic check of name, status, progress, speed, ETA, remaining bytes and active peers.
- Prefer one episode or file, change a category/destination, apply a speed cap or reorder the queue because circumstances have changed.
- Check whether there are enough connected peers, whether the tracker is working, or why metadata has not appeared.
- Start consuming a finished subset while the rest of the torrent remains active; a downloaded percentage and “ready to watch” are separate judgments.
- Open the actual content only after it is ready, sometimes before the job reaches 100%.
- Ignore the whole system until a completion/error notification or downstream library event occurs.

The absence of UI interaction is normal and sometimes the *desired* operation. A continuously seeding library may be actively valuable while it attracts little attention (S05–S08, S24–S25, S35).

### 4.4 Post-transfer activity

Possible actions include:

1. Inspect file tree, verify intended files, open or preview, scan untrusted downloads and check integrity/signatures when relevant.
2. Install or execute an application, watch/listen/read/play, or process research data.
3. Extract an archive, retain auxiliary subtitles/NFO files, or discard unwanted extras.
4. Organize naming and directories, attach descriptions and place the contents in a media/library/archive collection.
5. Copy or synchronize completed files from remote seedbox to phone, PC, NAS, cloud or backup. This is a *second* transfer using a potentially different protocol.
6. Allow media managers to import via hardlink or copy while the original torrent-backed files remain available to seed.
7. Leave the torrent job running for additional share ratio, required seed time, community preservation or simply an indefinite “keep available” policy.
8. Review space/traffic usage; clean up duplicate *references*, staging files, archives or content only when the intended continued sharing/retention is understood.
9. Keep or export the torrent metadata and job configuration so the same verified bytes can be reseeded later.
10. Share a new magnet/file with other people and continue initial seeding when publishing.

**“100%” can mark the start of the post-download workflow, not the end of the user's goal** (S03, S10–S15, S20–S23).

### 4.5 Re-entry and ultimate exit

A user may **leave the screen** after submission while the job continues; **leave the app foreground** while an Android foreground service remains; **close a remote controller** while the seedbox keeps running; **stop a job** while preserving data; **remove a job** while preserving data; **delete data** while optionally preserving a torrent metadata file; or **cancel the whole service** after moving content and backups elsewhere. These are materially different departures. No single “Done” action covers them all.

## 5. Work vocabulary: classify what the person is actually doing

These categories overlap. One task can be both administrative and technical, and the same button can serve creative, custodial or acquisitive goals.

| Kind of work | Central question | Representative activities | What completion means |
|---|---|---|---|
| **Acquisitive** | How do I get the desired content? | Find, select, add, prioritize, retrieve | Content is available where needed |
| **Distributive** | How do others get what I have? | Create torrent, announce, seed, post link | Recipients can obtain verified bytes |
| **Curatorial** | Which things belong together and how should they be described? | Choose edition, rename, categorize, tag, curate packs | Content is understandable and retrievable |
| **Creative / publishing** | How do I produce and release something? | Prepare original content, package, write description, publish | Release is presented and reachable |
| **Consumptive** | Can I actually use it? | Preview, install, read, play, execute, process | Intended media/software/data works |
| **Technical** | Are systems and files behaving correctly? | Diagnose peers, ports, paths, hash checks, APIs | Root cause is established or functionality restored |
| **Administrative** | What services/accounts/assets may I operate? | Credentials, applications, renewals, plan and role settings | Infrastructure is configured and authorized |
| **Operational** | Is routine work flowing? | Queue, monitor, schedule, handle errors, verify end states | Work progresses without intervention |
| **Orchestrational** | How do independent tools cooperate? | Connect indexers, downloaders, importers, notifications | Handoffs complete in proper order |
| **Custodial / preservation** | Will this remain available over time? | Long-term seed, mirror, back up, migrate | Retention and redundancy goals hold |
| **Resource-economic** | How do I use finite space, speed, traffic and money? | Quotas, rate caps, eviction, media tiers | Budget stays within chosen bounds |
| **Community / contractual** | What expectations accompany participation? | Tracker rules, seed time, ratio, credits, sharing | Participation obligations are satisfied |
| **Risk / privacy** | What could leak, break or be lost? | VPN binding, certificate trust, data deletion, authenticity | Risk is understood and appropriately constrained |
| **Diagnostic / restorative** | What changed, and can I repair it? | Lost files, bad mappings, unregistered jobs, recheck | Previous useful state is recovered |
| **Educational / exploratory** | How does this technology behave? | Inspect swarms, test clients, experiment with settings | Understanding, not necessarily a file, is gained |

### 5.1 Additional useful dimensions

For every action, it is helpful to distinguish **who initiated it** (person, rule, remote app, API), **where it runs** (phone, desktop, hosted box, indexer, downstream library), **what it acts upon** (job, metadata, payload bytes, account, view), **how long it lasts** (instant command, session, unattended days), **whether it is reversible** (view/filter versus file deletion), and **how success is observed** (requested, acknowledged, actively executing, verified).

An additional dimension is **attention cost**: a task may be technically trivial but require multiple context switches; another may be technically sophisticated yet run automatically for months. The user describing manual tracker/seedbox/Plex administration explicitly wants fewer service changes, while another user prefers to organize manually (S20, S22).

### 5.2 Important objects that users can conflate

- **Discovery page / indexer result:** a claim or description that points to a torrent, not necessarily a trusted payload.
- **Magnet URI / .torrent metafile:** a descriptor for locating/validating data, not the media or dataset bytes.
- **Torrent job registration:** a client's record that knows the infohash, priorities, trackers, status, destination and resume state.
- **Payload / backing file:** actual bytes on a particular filesystem; may survive a job removal or be shared by other jobs.
- **Destination path:** an address meaningful *to the engine or host executing the operation*. A path on a phone is not automatically a path on a seedbox.
- **Library copy / hardlink / symlink:** another way to reach or retain content; not necessarily another full set of bytes.
- **Remote UI / RPC connection:** commands and state observation; not the same network route as data retrieval.
- **Tracker account / host account / RPC login:** different identities, obligations and secrets.
- **Session completion / job completion / content usability / preservation completion:** four different end states.

Confusion between these objects explains many seemingly paradoxical questions: “Why is the file on my seedbox but not my phone?”, “Why does Plex see it but the torrent stopped seeding?”, “Why did renaming the folder make a completed torrent incomplete?”, and “Why did deleting the torrent not free the space I expected?” (S05, S10–S14, S22, S27, S36).

## 6. Journey library: concrete activities, in likely order

**Reading key:** Each journey is an illustrative ordered activity chain. The beginning and end are user goals, not necessarily client login/logout. A “likely order” denotes a coherent path, not a universal script. **J** identifiers are stable references for this research document, not work items. Evidence codes link to the source register.

### J01 · One-off public software image

- **Trigger / intent:** Need a known installer or release; a publisher offers a torrent.
- **Likely order:** Open publisher page → choose image/version → click .torrent/magnet → inspect size and save location → add/start → let it complete → verify/open/install → optionally seed.
- **Tools and handoffs:** Publisher/browser → local client → operating-system installer.
- **User-visible success / exit:** Installer is intact and usable; client may keep seeding after the user closes the window.
- **Recurrence and work character:** episodic; acquisitive, technical.
- **Evidence:** S01 S02. **Classification:** interpretive sequence based on documented and user-described components.

### J02 · Download only part of a large package

- **Trigger / intent:** Need one item from a multi-file bundle and do not want the rest.
- **Likely order:** Inspect content tree before starting, or once magnet metadata loads → select wanted files → set priorities → monitor wanted progress → retrieve/use chosen content → optionally retain selection.
- **Tools and handoffs:** Indexer or publisher → client file tree → player/file manager.
- **User-visible success / exit:** Required files are present; total torrent may show incomplete by design.
- **Recurrence and work character:** episodic; acquisitive, curatorial.
- **Evidence:** S01 S03. **Classification:** interpretive sequence based on documented and user-described components.

### J03 · View a particular episode before the rest

- **Trigger / intent:** Want first-in-order playback from an entire season/package.
- **Likely order:** Choose season pack → inspect episode names → select high-priority/sequence for early episodes if available → track first episode specifically → watch → leave remaining package in queue.
- **Tools and handoffs:** Discovery → client file priority/piece scheduler → player.
- **User-visible success / exit:** The particular episode is usable, independent of total-pack percentage.
- **Recurrence and work character:** episodic; consumptive, scheduling, curation.
- **Evidence:** S03 S20. **Classification:** interpretive sequence based on documented and user-described components.

### J04 · Quick mobile submission to a home daemon

- **Trigger / intent:** Encounter useful magnet on phone away from home.
- **Likely order:** Open link or share to remote controller → select saved home server → inspect desired files/destination → submit → get acknowledgment → exit mobile app → later check or consume on home system.
- **Tools and handoffs:** Phone browser → remote app → home daemon; later home filesystem.
- **User-visible success / exit:** Remote job begins and continues without phone retaining the torrent payload.
- **Recurrence and work character:** frequent for remote-heavy users; delegation, acquisitive.
- **Evidence:** S07 S21 S30. **Classification:** interpretive sequence based on documented and user-described components.

### J05 · Quick mobile submission to a seedbox

- **Trigger / intent:** Find a torrent while away from any desktop and want it working remotely.
- **Likely order:** Copy/share torrent → remote app or web UI → choose seedbox profile → authenticate if necessary → add with remote destination and category → view immediate status → leave → later retrieve/stream data.
- **Tools and handoffs:** Phone → RPC/WebUI → hosted torrent daemon → file-transfer/media tool.
- **User-visible success / exit:** Server performs transfer autonomously; mobile screen does not have to remain open.
- **Recurrence and work character:** frequent for remote-heavy users; remote administration.
- **Evidence:** S07 S09 S18 S20. **Classification:** interpretive sequence based on documented and user-described components.

### J06 · Status glance without changing anything

- **Trigger / intent:** Need reassurance that downloads are progressing or that the server is online.
- **Likely order:** Open app/notification → choose instance → filter current jobs → inspect speed, progress, ETA and error state → close or swipe away.
- **Tools and handoffs:** Remote or local transfer list; notification.
- **User-visible success / exit:** Confidence about current state; no mutation necessary.
- **Recurrence and work character:** often per active job; observational.
- **Evidence:** S06 S07 S24. **Classification:** interpretive sequence based on documented and user-described components.

### J07 · Continue work while the app is absent

- **Trigger / intent:** Need file later, not immediately.
- **Likely order:** Start torrent or configure auto intake → close browser/controller, sleep, work or travel → daemon/foreground service runs in background → check when needed.
- **Tools and handoffs:** Local background daemon or hosted engine.
- **User-visible success / exit:** An unattended future state, not a completed UI session.
- **Recurrence and work character:** continuous; delegation, operational.
- **Evidence:** S05 S07 S08. **Classification:** interpretive sequence based on documented and user-described components.

### J08 · Speed or bandwidth changes because of other activity

- **Trigger / intent:** Streaming, gaming, calls or capped connection need bandwidth.
- **Likely order:** Inspect competing usage → apply temporary per-job/global limit or alternate profile → continue other task → later undo or let scheduler restore normal limits.
- **Tools and handoffs:** Client scheduler/rate control → unrelated apps/network.
- **User-visible success / exit:** Primary current activity remains usable while torrenting continues.
- **Recurrence and work character:** occasional; operational, resource-economic.
- **Evidence:** S05 S06. **Classification:** interpretive sequence based on documented and user-described components.

### J09 · Monitor or repair a stalled torrent

- **Trigger / intent:** Nothing is moving, despite an apparently available file.
- **Likely order:** Inspect metadata availability and peers → review tracker messages and queue/disk/port errors → compare to another known working torrent → adjust only identified constraint → retry/reannounce/recheck → verify actual progress.
- **Tools and handoffs:** Torrent client → tracker/peer diagnostics → network/server settings.
- **User-visible success / exit:** Root cause found or confirmed lack of available useful peers; transfer resumes if possible.
- **Recurrence and work character:** exceptional; technical, diagnostic.
- **Evidence:** S28 S05. **Classification:** interpretive sequence based on documented and user-described components.

### J10 · Complete a download and keep sharing

- **Trigger / intent:** Finished downloading, wants reciprocity or a ratio goal.
- **Likely order:** Observe completion → continue running → view uploads/ratio/seed time occasionally → stop or retire according to personal policy and tracker rules.
- **Tools and handoffs:** Client background seeding → account rules if relevant.
- **User-visible success / exit:** Chosen sharing obligation, not just the download, is complete.
- **Recurrence and work character:** continuous with periodic checks; custodial, community.
- **Evidence:** S35 S25 S26. **Classification:** interpretive sequence based on documented and user-described components.

### J11 · Preserve a scarce item long-term

- **Trigger / intent:** Want content to remain downloadable for others.
- **Likely order:** Obtain verified bytes → arrange reliable disk/network/uptime → leave job seeded → occasionally check disk/peer/tracker health → move or back up without breaking references.
- **Tools and handoffs:** Client/seedbox → long-term storage and monitoring.
- **User-visible success / exit:** Availability persists over an extended period.
- **Recurrence and work character:** long-lived, low-attention; custodial.
- **Evidence:** S04 S25 S34. **Classification:** interpretive sequence based on documented and user-described components.

### J12 · Maintain a private tracker's standing

- **Trigger / intent:** Download access depends on site-specific requirements.
- **Likely order:** Read site's rules → choose content and seeding policy → download or cross-seed → monitor time, ratio, credits and tracker status → retain until applicable requirements met.
- **Tools and handoffs:** Tracker site/account → torrent client → optional automation.
- **User-visible success / exit:** No rule violation and desired availability/standing; requirements vary by site.
- **Recurrence and work character:** ongoing; contractual, community, economic.
- **Evidence:** S26 S29. **Classification:** interpretive sequence based on documented and user-described components.

### J13 · Reuse verified data across trackers

- **Trigger / intent:** Already have full data and want to seed it in another compatible swarm.
- **Likely order:** Discover matching release → verify file list/size/hash/layout constraints → add compatible torrent against existing data or link directory → hash check → seed → monitor obligations for each job.
- **Tools and handoffs:** Cross-seed/indexer → file mapping/hardlinks → client(s) → tracker(s).
- **User-visible success / exit:** Several valid torrent jobs can serve the existing data without unnecessary redownload.
- **Recurrence and work character:** periodic batch; technical, preservation, community.
- **Evidence:** S15 S16 S29. **Classification:** interpretive sequence based on documented and user-described components.

### J14 · Build a media-library item by hand

- **Trigger / intent:** Want a usable media collection and enjoy or tolerate manual curation.
- **Likely order:** Find title/release → add torrent → wait for completion → inspect actual files → copy/move/rename appropriately → add subtitle/artwork if desired → scan Plex/Jellyfin/Emby → play.
- **Tools and handoffs:** Tracker → torrent WebUI → file manager → media library/server.
- **User-visible success / exit:** The desired library item appears and plays with sensible metadata.
- **Recurrence and work character:** routine for manual curators; curation, consumptive.
- **Evidence:** S20 S22. **Classification:** interpretive sequence based on documented and user-described components.

### J15 · Automate series/movie acquisition

- **Trigger / intent:** Want desired media to arrive without repeated manual searches.
- **Likely order:** Configure indexer(s), series/quality rule, client connection, category and library path once → monitor feed → manager grabs candidate → client downloads → manager checks completion → hardlinks/copies/renames/imports → library refreshes.
- **Tools and handoffs:** Indexer/Prowlarr → Sonarr/Radarr → torrent client → filesystem → player.
- **User-visible success / exit:** Requested item lands correctly in library with exceptions surfaced; torrent may still seed.
- **Recurrence and work character:** setup rare, auto operations frequent; orchestration, administrative.
- **Evidence:** S13 S14 S08. **Classification:** interpretive sequence based on documented and user-described components.

### J16 · Transfer files from rented seedbox to local machine

- **Trigger / intent:** Torrent data is on rented storage; user wants an offline/local copy.
- **Likely order:** Wait for enough data → open FTPS/SFTP/LFTP/WebDAV/HTTP/rclone connection → choose correct remote folder → pull to PC/NAS/phone → confirm integrity → use/organize local copy → decide independently whether remote seed continues.
- **Tools and handoffs:** Seedbox torrent client → file-transfer tool → local filesystem.
- **User-visible success / exit:** Local bytes exist and are usable; remote job/file retention is a separate choice.
- **Recurrence and work character:** per completed job or scheduled; file movement, operations.
- **Evidence:** S10 S23 S31. **Classification:** interpretive sequence based on documented and user-described components.

### J17 · Synchronize seedbox content automatically

- **Trigger / intent:** Repeated manual downloads are a nuisance.
- **Likely order:** Configure synced folders and credentials on both ends → initial sync → let new content appear remotely → background sync copies changed data → verify downstream consumer sees content → handle conflict/quota/error.
- **Tools and handoffs:** Seedbox storage → Syncthing/rclone → local NAS/server.
- **User-visible success / exit:** Remote-to-local content propagation works repeatedly, with exceptions detectable.
- **Recurrence and work character:** setup rare, ongoing continuous; orchestration.
- **Evidence:** S10 S22 S32. **Classification:** interpretive sequence based on documented and user-described components.

### J18 · Avoid moving data while keeping a media library

- **Trigger / intent:** Want to seed and present the same media through different paths.
- **Likely order:** Configure download and library folders on same supported filesystem/mount → let importer hardlink rather than copy where valid → see playable library entry → preserve torrent-backed path for seeding.
- **Tools and handoffs:** Torrent client → same-volume hardlink operation → library manager.
- **User-visible success / exit:** Library access and torrent seeding coexist without unnecessary duplicate payload.
- **Recurrence and work character:** ongoing after setup; technical, resource-economic.
- **Evidence:** S13 S14. **Classification:** interpretive sequence based on documented and user-described components.

### J19 · Relocate or rename already-seeding files

- **Trigger / intent:** Need an organized disk or new drive but want to keep sharing.
- **Likely order:** Choose destination → preferably use client's supported relocation/rename, else stop safely and move externally → update job path → recheck → confirm seeding resumes.
- **Tools and handoffs:** Torrent job mapping ↔ file manager/filesystem.
- **User-visible success / exit:** Actual bytes moved and session still knows where they are.
- **Recurrence and work character:** occasional; organizational, restorative.
- **Evidence:** S27 S11. **Classification:** interpretive sequence based on documented and user-described components.

### J20 · Repair transfers broken by external file edits

- **Trigger / intent:** Another app, FTP client or cleanup moved/renamed bytes without informing the torrent engine.
- **Likely order:** Notice missing-file or IO errors → identify moved folders/renamed objects → locate existing files → reconcile exact content/path mapping → recheck hashes → resume jobs → confirm no unintended redownload.
- **Tools and handoffs:** Client error view → FTP/file browser → verification.
- **User-visible success / exit:** Existing data is reused and seeding/download resumes where possible.
- **Recurrence and work character:** exceptional; diagnostic, technical.
- **Evidence:** S27 S36. **Classification:** interpretive sequence based on documented and user-described components.

### J21 · Clear seedbox disk pressure

- **Trigger / intent:** Storage near cap prevents new data or service writes.
- **Likely order:** Read quota/free space → find largest/oldest and duplicate payloads → identify live seeding dependencies, hardlinks and archive copies → move/archive or retire specifically chosen data → recheck quota and affected jobs.
- **Tools and handoffs:** Provider dashboard → client → filesystem tools → backup/other storage.
- **User-visible success / exit:** Enough usable space reclaimed without accidental loss of valued data/seeds.
- **Recurrence and work character:** periodic or exception; economic, custodial, administrative.
- **Evidence:** S17 S09 S11. **Classification:** interpretive sequence based on documented and user-described components.

### J22 · Check host traffic allowance or renewal

- **Trigger / intent:** Concerned about monthly upload, account plan, expiration or price.
- **Likely order:** Open provider panel → inspect network/disk history, allowances and expiry → identify contributing service or transfer → cap, change plan or adapt use → leave apps running where permitted.
- **Tools and handoffs:** Host account portal → client rate limits → billing/support.
- **User-visible success / exit:** Service stays within chosen costs and caps.
- **Recurrence and work character:** monthly or alert-triggered; administrative, resource-economic.
- **Evidence:** S09 S17. **Classification:** interpretive sequence based on documented and user-described components.

### J23 · Set up the first remote seedbox

- **Trigger / intent:** New customer wants a functioning remotely managed service.
- **Likely order:** Choose/provider plan → obtain account and host credentials → install/start client from panel → set strong credentials → get actual WebUI/API URL/path → test remote from browser/phone → choose destination → try licensed small torrent → retrieve result.
- **Tools and handoffs:** Host panel → torrent engine → remote app → FTPS/SFTP.
- **User-visible success / exit:** An end-to-end working cycle, not merely a successful login.
- **Recurrence and work character:** once per provider/client setup; administrative, technical.
- **Evidence:** S09 S18 S19. **Classification:** interpretive sequence based on documented and user-described components.

### J24 · Fix a remote connection or service outage

- **Trigger / intent:** Phone app cannot contact seedbox or remote actions fail.
- **Likely order:** Distinguish internet outage, incorrect hostname/path, wrong credentials, client lockout, certificate, reverse proxy or daemon crash → use provider control panel to inspect/restart/recover → reconnect → verify selected engine/profile.
- **Tools and handoffs:** Remote app → browser/control panel → service admin.
- **User-visible success / exit:** Verified access to intended remote engine, without wrong-server mutations.
- **Recurrence and work character:** exceptional; diagnostic, administrative.
- **Evidence:** S18 S09 S17. **Classification:** interpretive sequence based on documented and user-described components.

### J25 · Publish an original project to peers

- **Trigger / intent:** Creator wants others to obtain an album, video, game build, backup or shared file set.
- **Likely order:** Finish content → settle file/folder layout and release name → select initial seeding location → create .torrent with tracker/private/webseed choices as appropriate → distribute descriptor/link → keep source bytes available → respond to issues.
- **Tools and handoffs:** Editor/creative app → file manager → torrent creator → publishing site/message → seed.
- **User-visible success / exit:** Other participants can discover and obtain the intended original release.
- **Recurrence and work character:** occasional/rare; creative, publishing, stewardship.
- **Evidence:** S12 S04 S34. **Classification:** interpretive sequence based on documented and user-described components.

### J26 · Publish using a hosted seedbox

- **Trigger / intent:** Source material already lives on rented server.
- **Likely order:** Browse remote directory → create .torrent in ruTorrent/file-manager → enter appropriate announce data → save small metafile locally if required → submit to relevant publication endpoint → seed server-resident data.
- **Tools and handoffs:** Seedbox file browser → torrent creator → tracker/site → rTorrent.
- **User-visible success / exit:** Metadata is published while large original data remains on the server.
- **Recurrence and work character:** occasional; publishing, administrative.
- **Evidence:** S12 S11. **Classification:** interpretive sequence based on documented and user-described components.

### J27 · Distribute an official software release

- **Trigger / intent:** Publisher wants low-cost, resilient delivery of a large installer.
- **Likely order:** Prepare signed/verified release and any checksum → create torrent → place link alongside direct mirrors → host initial seed/webseed where appropriate → clients download → retain release for future users.
- **Tools and handoffs:** Build/release system → publisher site → torrent swarm → users.
- **User-visible success / exit:** Valid and verifiable release reaches users and remains available.
- **Recurrence and work character:** release-driven; distribution, technical.
- **Evidence:** S02 S01. **Classification:** interpretive sequence based on documented and user-described components.

### J28 · Obtain, publish or preserve an academic dataset

- **Trigger / intent:** Researcher needs substantial reproducible data or wants to share it.
- **Likely order:** Search academic catalogue → evaluate version, license, size and content → select/download/verify → use for analysis → retain/cite version info → optionally seed/share dataset to sustain availability.
- **Tools and handoffs:** Academic index → client → research storage/analysis → bibliography/repository.
- **User-visible success / exit:** Reproducible research data is locally usable and/or durably distributed.
- **Recurrence and work character:** project-driven; research, custodial.
- **Evidence:** S04 S34. **Classification:** interpretive sequence based on documented and user-described components.

### J29 · Browse public-domain archive contents selectively

- **Trigger / intent:** Need one portion of a larger archival item.
- **Likely order:** Search archive collection → inspect item/files and rights → obtain Archive torrent → select relevant files only → let supported webseeds/peers supply pieces → verify → listen/read/view/organize.
- **Tools and handoffs:** Archive catalogue → client file selection → archive/media app.
- **User-visible success / exit:** Relevant content accessible without retrieving whole collection.
- **Recurrence and work character:** project-driven; research, curatorial.
- **Evidence:** S03. **Classification:** interpretive sequence based on documented and user-described components.

### J30 · Automate intake from a feed or announcement channel

- **Trigger / intent:** Wants future matching releases to arrive without remembering to search.
- **Likely order:** Define source/feed and criteria → connect indexer/IRC rule engine to target client → test sample → allow background matching → auto submit → review successes and false positives → revise rules.
- **Tools and handoffs:** RSS/IRC/indexer → autobrr or client rules → torrent daemon.
- **User-visible success / exit:** Matching content is submitted and managed with tolerable false positives.
- **Recurrence and work character:** initial setup rare, continuous intake; automation, operational.
- **Evidence:** S19 S13. **Classification:** interpretive sequence based on documented and user-described components.

### J31 · Manage large numbers of existing torrents

- **Trigger / intent:** Hundreds/thousands of seeded items obscure active work.
- **Likely order:** Open dashboard → choose status/tag/tracker/category filter → sort by errors, space, activity or age → select group → apply supported bulk action → return to filtered active view.
- **Tools and handoffs:** Client WebUI or multi-instance UI; sometimes script/automation.
- **User-visible success / exit:** User sees relevant subset and handles group without scrolling through all jobs.
- **Recurrence and work character:** often in large sessions; curation, operational.
- **Evidence:** S24 S36. **Classification:** interpretive sequence based on documented and user-described components.

### J32 · Move between torrent clients or servers

- **Trigger / intent:** Existing client/service is to be replaced but data must survive.
- **Likely order:** Inventory jobs, metadata, aliases, tracker obligations, save paths and hardlinks → back up client config/state → stage destination client → migrate job registration and path mapping → recheck reused bytes → confirm old and new registrations do not conflict → retire old service when safe.
- **Tools and handoffs:** Old client/host → file tools/backups → new client/host → trackers.
- **User-visible success / exit:** Correct jobs continue seeding or downloading, valued bytes are intact, obsolete host safely retired.
- **Recurrence and work character:** rare major event; technical, restorative, administrative.
- **Evidence:** S36 S27. **Classification:** interpretive sequence based on documented and user-described components.

### J33 · Operate multiple servers intentionally

- **Trigger / intent:** Uses local device plus home NAS/seedbox(s) for different tasks.
- **Likely order:** Choose destination for a new job according to storage/uptime/location/ratio needs → send to selected instance → inspect only that session → keep independent queue, policy and credentials → transfer finished files separately if wanted.
- **Tools and handoffs:** Multi-profile remote → several daemons → storage devices.
- **User-visible success / exit:** Work landed on intended machine, and cross-instance identity is unambiguous.
- **Recurrence and work character:** recurrent for power users; administrative, delegation.
- **Evidence:** S07 S08 S30. **Classification:** interpretive sequence based on documented and user-described components.

### J34 · Recover after a client update or unexpected loss

- **Trigger / intent:** Job progress drops, error appears or app updated badly.
- **Likely order:** Observe mismatch → stop destructive operations → inspect log, config and filesystem → check existing data → restore/recreate registration if needed → run verification → resume and validate state.
- **Tools and handoffs:** Client/daemon → logs → storage → backups.
- **User-visible success / exit:** Recovered usable content and correct torrent status without unnecessary redownload.
- **Recurrence and work character:** rare/exception; restorative, technical.
- **Evidence:** S27 S28 S36. **Classification:** interpretive sequence based on documented and user-described components.

### J35 · Keep an automated service healthy

- **Trigger / intent:** Automation works most days, then an edge case breaks it.
- **Likely order:** Periodically inspect logs, warnings, import failures, free space, auth expiry and stuck jobs → identify failing boundary → rerun bounded step or repair config → observe successful downstream result.
- **Tools and handoffs:** Automation → daemon → importer → provider controls.
- **User-visible success / exit:** Workflow returns to unattended operation.
- **Recurrence and work character:** periodic plus alert-driven; operational, technical.
- **Evidence:** S13 S17 S19. **Classification:** interpretive sequence based on documented and user-described components.

### J36 · Decide to end seeding or close a service

- **Trigger / intent:** Needs space or no longer wishes to operate the session or seedbox.
- **Likely order:** Identify torrents, required seed time, copied data and backup status → stop or remove selected job registrations → separately decide whether to delete remote files → export necessary configuration and retrieve wanted data → cancel subscription or shut down daemon.
- **Tools and handoffs:** Torrent client → remote storage → host panel/account.
- **User-visible success / exit:** Desired content and session records preserved or intentionally retired; service is no longer active.
- **Recurrence and work character:** rare/per-item/end-of-life; administrative, custodial.
- **Evidence:** S10 S17 S35. **Classification:** interpretive sequence based on documented and user-described components.

## 7. Recurrence, duration and where attention goes

There is no single meaningful “average BitTorrent session.” A one-off installer download may produce minutes of interface attention and no later visits. A private-trackers-and-seedbox workflow may produce a short daily scan for many years. A publisher might use the creator UI once a month and seed continuously.

The matrix below is **conditional recurrence**, not population prevalence.

| Activity | Typical cadence *within an applicable workflow* | Attention and timing | Often done in another app? |
|---|---|---|---|
| Copy/open magnet or .torrent | Per new requested acquisition | Seconds, foreground | Browser, chat, finder |
| Inspect contents/destination | At add, sometimes later | Brief but consequential | Client add dialog |
| Check active jobs/ETA | Per download, intermittent | Seconds to minutes | Remote mobile UI, notifications |
| Change priority or bandwidth | When circumstance changes | Brief interrupt | Client/settings |
| Read tracker/peer error | When progress deviates from expectation | Investigative | Client diagnostics |
| Start long-term seeding | Usually automatic at completion | Little or no immediate attention | Background daemon |
| Review seeding ratio/time | Periodic or rule-driven | Small monitoring action | Client, tracker site |
| Retrieve remote payload | Per finished item or on demand | Can take longer than UI session | SFTP/FTPS/rclone/file browser |
| Import media to library | Per item or automated batch | Often no direct attention | Library manager |
| Rename/organize files | Per chosen collection or cleanup batch | Variable, sometimes deliberately enjoyable | File manager/media app |
| Run RSS/indexer ingestion | Continuous after initial configuration | Usually exception-only | Automation service |
| Modify acquisition rules | On false positive/changed interests | Rare relative to rule execution | Indexer/automation |
| Cross-seed existing data | Periodic or automated | Short check or batch process | Cross-seed tools |
| Clean storage/quota | On threshold, periodic review | Longer, higher consequence | Host dashboard/filesystem |
| Create new torrent | Per original release | Uncommon for pure downloaders | Torrent creator/publisher |
| Change remote credentials | First use, expiration or incident | Setup/recovery | Host panel |
| Reconnect remote app | New device, password change or outage | Usually exceptional | Remote settings |
| Service install/update/repair | Initial provisioning, planned maintenance or outage | Higher expertise and risk | Seedbox panel/SSH |
| Recheck hashes | Import, suspected corruption, missing file or migration | May run long; minimal continuous attention | Engine |
| Rename/move seeding payload | Structural reorganization | Occasional and consequential | Client and file manager |
| Change client or seedbox provider | Major migration | Rare but extended | Multiple tools |
| Renew plan/inspect bills | Provider billing cadence | Minutes | Provider account portal |
| Retrieve content for immediate use | Whenever need arises | User's primary focus | File manager/player |
| Let system remain idle/available | Between all active interventions | Hours, days, years | No UI at all |

### 7.1 The four clocks

1. **Human attention clock:** seconds spent deciding, tapping, checking, organizing or repairing.
2. **Torrent engine clock:** minutes or hours spent fetching metadata, transferring pieces, verifying and serving peers.
3. **Downstream content clock:** time until file is playable, installed, catalogued, analyzed or copied locally.
4. **Stewardship clock:** days, months or years of retention, tracker compliance, storage budgeting, preservation and host administration.

The clocks often disagree. A job may finish downloading in minutes but need months of seeding. A remote download can be fast yet its SFTP retrieval slow. An app may show “finished” before a downstream Plex scan. This model is a synthesis of S05, S10, S13, S20–S23 and S35.

### 7.2 High-attention and low-attention moments

**High attention:** choosing a credible source; comparing closely named releases; selecting one file out of a pack; interpreting unexpected 0% or stale progress; resolving paths; deleting data; authorizing remote control; migrating clients; deciding which seed to sacrifice under quota.

**Low attention:** waiting for peers; piece exchange; hashing after already requested; long-term uploading; feed polling; automated download-to-library import; a healthy overnight sync; occasional passive monitoring.

The amount of *work* the software performs is not proportional to the time someone spends watching it. Automation shifts attention from routine execution toward setup, monitoring, exceptions and maintenance.

## 8. Cross-software chains and dependencies

### 8.1 Manual remote-to-consumption chain

**Discover in browser → download small descriptor → open seedbox WebUI → submit torrent → wait → open seedbox file manager or SFTP → locate actual completed payload → retrieve/relocate → open local player or scan media library → possibly leave original torrent seeding.**

A user in S20 describes this almost literally, including repeated visits to separate web pages and manually rescanning Plex. S23 describes a simpler manual pattern using FileZilla. S22 shows that manual handling can also be an intentional preference. Each handoff is an independent step; success upstream does not prove success downstream.

### 8.2 Automated media-library chain

**Define interest/quality → indexer searches or watches feed → manager selects candidate → sends labeled job to downloader API → daemon acquires files → manager notices status and original location → import by hardlink/copy → file naming and library metadata → player availability → seeding continues until separate completion policy.**

The critical handoffs are *metadata → torrent job → payload path → library entry*. A failure at any handoff may look to the end user like “download didn't work” even when the swarm completed. Sonarr's documented category monitoring and hardlink behavior establish this chain (S13–S14).

### 8.3 Publishing chain

**Prepare content → select exact files/folder and audience → generate torrent descriptor → configure announcement/discovery settings → start initial seeder with real bytes → distribute metadata/link → verify someone else can obtain data → continue availability/maintenance.**

Creation of a .torrent does not prove a discoverable peer exists; distribution of a magnet alone does not make data reachable. Seedhost's create-through-ruTorrent guide documents the distinction between large source data and small generated descriptor (S12).

### 8.4 Seedbox-to-local handoff

**Remote engine downloads and seeds → separate secure file-transfer channel moves copies to local system → local library consumes them while remote engine may still seed.**

Tools include FTPS, SFTP, LFTP, HTTP, WebDAV, rclone and sync apps, each with tradeoffs around speed, credential management, traffic quotas, storage mapping and reliability (S10, S31–S32). The user can stop interacting with the *remote controller* without stopping either channel.

### 8.5 Preservation and cross-seeding chain

**Retain verified payload → discover compatible metadata elsewhere → reconcile file layouts → create additional job/reference or supported hardlink → verify identity/content → seed multiple swarms → manage separate requirements.**

Data and job counts can diverge: several torrent entries may refer to one physical body of data. The effect can improve availability, yet automated cross-seed searching places load on indexers/trackers, as cross-seed's own documentation explains (S15–S16, S29).

### 8.6 Service-operations chain

**Host panel controls apps/credentials and capacity → torrent client controls transfers → file manager handles bytes → remote app brings controls to mobile → library manager presents final content.**

When an error occurs, the user must locate which layer failed. A 502 from a reverse proxy, an RPC authentication rejection, a swarm with no available pieces and an absent filesystem path all require different interventions. Provider docs show separate Apps, Connect, usage and file-transfer surfaces (S09, S17–S18).

## 9. Decision points and competing priorities

### 9.1 At discovery

- **Exact item versus an acceptable substitute:** version, language, release group, quality, encoding, age, trusted author or intact package.
- **One file versus whole pack:** selected episode, documentation file, dataset subset or everything for archival value.
- **Torrent versus direct download:** speed, source availability, privacy, repeatability, file integrity, provider restrictions, ability to seed.
- **Immediate versus later:** queue now, pause for night, set future acquisition rule, or skip.
- **Public versus private ecosystem:** different authentication, visibility and participation obligations.

### 9.2 At acquisition

- **Local versus remote machine:** where content ultimately must be and which machine is suitable for continuous seeding.
- **Default path versus a special destination:** mobile download folder, remote seedbox library, incomplete folder, NAS share.
- **Priority versus fairness:** finish the urgently needed file first or maximize swarm-wide completion and overall throughput.
- **Rate versus responsiveness:** saturate available bandwidth or leave capacity for calls, gaming and streaming.
- **Redownload versus reuse:** verify existing data before consuming storage and bandwidth unnecessarily.

### 9.3 At completion

- **Use now versus keep queued for later.**
- **Move/rename original bytes versus make a separate copy/hardlink for a library.**
- **Continue seeding versus reclaim disk/bandwidth.**
- **Keep rare/important content versus follow automated expiration.**
- **Archive locally versus trust a hosted provider for later access.**
- **Share public content broadly versus distribute within a controlled group.**

These tradeoffs are situation-dependent. Private tracker ratio rules may reward retention or early availability; some people care more about preservation than credit, and neither objective can be inferred from a torrent's filename (S25–S26, S35).

## 10. What usually happens off-screen

The software has at least two kinds of “background”:

1. **Protocol background:** peer discovery, announces, block requests, piece verification, upload slots, data flush, resume-state persistence and retries continue without user commands.
2. **Workflow background:** RSS polling, indexer matching, remote-add dispatch, archive extraction, media import, library scans, snapshots, notifications, remote copies, quota monitoring and scheduled service checks.

People may only see their **desired artifact** and a few decisive status changes. They might never open the client if another application initiates and monitors the job. Conversely, power users may deliberately observe peers, error logs or time-series graphs for diagnosis or optimization (S05, S08, S13, S19).

“Do nothing” is a valid state: neither continuous tapping nor busy visible animation is intrinsic to successful torrenting.

## 11. Exceptions and recovery journeys

These are often the highest-cognitive-load interactions despite their lower recurrence.

| Symptom as user experiences it | Possible actual boundary | Natural investigative sequence | Desired recovery |
|---|---|---|---|
| “I clicked the magnet and nothing appeared” | Browser handler, wrong target engine, remote connection, add rejected | Check link handler, target/profile, add confirmation and target list | Correct job visible on intended machine |
| “It says downloading metadata forever” | No reachable metadata peer, incompatible/halted discovery | Check known good torrent, trackers/DHT and network path | Metadata loads or absence diagnosed |
| “0% despite many listed seeds” | Stale tracker counts, no reachable peers, port/VPN/firewall, local app issue | Compare another working job, peer state, tracker errors, logs | Verified peer/data flow |
| “Stuck at 99%” | Remaining rare pieces, deliberately skipped files, IO/hash failure | Check wanted completion, availability, file errors and piece state | Correct remaining content or honest limitation |
| “Finished but not in Plex” | File location vs media library import or scan | Inspect actual file, import path, categories, permissions, scan | Media visible and playable |
| “Downloaded to seedbox, not my phone” | RPC control versus payload transfer | Identify remote file and open SFTP/file-retrieval route | Actual local copy |
| “Renaming stopped seeding” | Engine file mapping out of sync with filesystem | Locate actual files, set location/rename mapping, recheck | Torrent reuses verified bytes |
| “Delete torrent did not free storage” | Remove job only, hardlinked payload, other copies | Inspect actual paths and references | Known space reclaimed without breaking required seeds |
| “Delete media broke seeding” | Library move vs original data; shared hardlink reference | Inspect which path and inode survived, map/recheck | Both intended consumption and seeding restored |
| “Storage says full but I can't find anything” | Hidden files, staging archives, reserved space, shared filesystem, partials | Compare provider quota, download path and filesystem entries | Space ownership understood |
| “Remote works in browser, not in app” | Endpoint, XML-RPC route, cert, auth type, proxy, version | Verify exact base URL/path, identity and auth; inspect error | Correct remote session |
| “Server used to work, now 502/timeout” | Failed process, app upgrade, proxy or quota | Check host panel and service logs, then restart/repair if warranted | Service recovers with data intact |
| “Transfer speed collapsed” | Swarm scarcity, disk bottleneck, network congestion, rate limit or host fair-use | Inspect peers/availability, IO, traffic policy, limits and host health | Bottleneck identified; viable speed returns |
| “Automatic import created duplicates” | Copy instead of hardlink, incompatible mount layout | Inspect file identities, actual storage use and integration paths | Correct library and reference-safe storage |
| “RSS grabbed unwanted content” | Overbroad filters, version/quality matching or indexer changes | Check match/rejection history, rules and source | Future criteria improved without damaging good files |
| “I moved to a new client but lost seed state” | Missing session metadata/path mapping, failed recheck, tracker conditions | Reconcile torrent metadata, path, hashes and per-site registration | Safe migration, resume and retention |

Sources for the underlying patterns: S13–S14, S17–S18, S20–S21, S27–S29, S36. The table is a diagnostic *reasoning model*, not a claim that each particular error has one universal fix.

## 12. Departure, handoff, suspension and completion

People “leave” torrent software in many states. This is especially significant in remote-control contexts.

| Exit pattern | What user believes they finished | What may still be operating |
|---|---|---|
| Close add dialog after submitting | “I sent it” | Metadata lookup and downloading are just starting |
| Dismiss a mobile remote UI | “I checked on it” | Remote engine continues downloading and seeding |
| Leave on-phone client running in background | “I can use the phone normally” | Engine can continue under OS/network/battery constraints |
| Pause one torrent | “Stop this for now” | Bytes and job configuration ordinarily retained |
| Stop all jobs while travelling | “Suspend bandwidth usage” | Jobs and files persist |
| Observe 100% complete | “I got it” | Seeding and post-download automation may continue |
| Finish media import and close manager | “It's in my library” | Original download may seed indefinitely |
| Remove torrent job but keep data | “It's gone from the list” | File bytes remain; other services may use them |
| Remove data explicitly | “Reclaim the disk” | Hardlinks, duplicates or independently published copies may remain |
| Export config and retire old server | “Migration is over” | New daemon must be independently verified |
| Cancel seedbox subscription | “I no longer need this service” | Unretrieved remote-only data may become inaccessible |
| Publish magnet and leave page | “Others can get it now” | Initial seeder must remain available for actual distribution |

Thus **end of interaction**, **end of foreground session**, **end of acquisition**, **end of sharing obligation**, **end of data retention**, and **end of provider relationship** must be tracked separately in a behavioral description.

## 13. User journeys that are predominantly *not* technical

Technical terms are present, but many motives are ordinary human ones:

- **Planning:** “I'll want this installer tomorrow, so start it tonight.”
- **Convenience:** “I found this on my phone; send it to my home machine.”
- **Curation:** “I only care about the first three lectures in this archive.”
- **Collection:** “Put these albums in the right order, with the right names.”
- **Consumption:** “Can I watch this now?”
- **Delegation:** “I want the server to do this while I do something else.”
- **Cooperation:** “Keep my release available for others.”
- **Preservation:** “This rare recording should not disappear.”
- **Budgeting:** “I'm at the disk or traffic limit.”
- **Trust:** “Is this file authentic, and am I comfortable sharing it?”
- **Household coordination:** “Someone requested this for the library.”
- **Creative publishing:** “I made something and want people to obtain it.”
- **Satisfaction of ownership/control:** “I want a copy I can keep, verify and organize independently of a service.”

A user's vocabulary may be “give it to Plex,” “share this,” “it's stuck,” or “I moved the folder,” without reference to RPC, hashes or hardlinks. The technical objects become salient mostly when an ordinary goal stops working.

## 14. User journeys that are predominantly administrative

Administrators and self-hosters may spend substantial effort without adding a single torrent:

- Select provider and plan; evaluate disk, traffic, speed, location, shared versus dedicated and managed versus unmanaged operation.
- Provision a client or multi-app stack, obtain actual URLs/ports, set credentials, learn provider-specific constraints.
- Configure remote access, reverse proxies or secure tunnel and verify service identity.
- Set application restarts, upgrades, support tickets, account password changes and expired certificate recovery.
- Manage family members, users, permissions and service requests.
- Track disk pressure, I/O and fair-use allowances; decide on plan changes.
- Prepare off-site or local backups; test restore and migration.
- Remove old apps and subscriptions while ensuring no unique data depends on them.

A seedbox panel is therefore not simply another torrent list. Its object is the service and its resources. Provider documentation independently exposes installer, app and connection screens and file-transfer channels (S09, S17–S19, S33).

## 15. User journeys that are predominantly creative, curatorial or communal

Publishing a torrent may be a terminal step in an hours-long creative process: recording, editing, writing, mixing, rendering, packaging, proofreading, selecting licenses or writing release notes. A user might enter a torrent-creation interface briefly at the end, then spend days sharing and answering recipients.

A researcher may invest months generating a dataset, then use BitTorrent for versioned dissemination, replication and preservation. An archive volunteer may locate and curate rare public-domain material, select only a few files, help seed them and annotate the collection. The creative/curatorial effort is real even when the torrent program merely moves bytes (S03–S04, S12, S34).

Communal goals can conflict: maximize upload credit quickly, maintain a large seed count, provide rare long-tail availability, reduce tracker API load, share with close friends, or distribute data widely. It is important to describe **which goal** a person is optimizing, rather than presuming one universal etiquette or ratio target (S15–S16, S25–S26, S29).

## 16. Patterns of frustration, friction and invisible work

These are descriptions of friction found in the workflows, not proposals for solving it.

1. **Context switching:** a small desired outcome requires browser, tracker, client, file browser, media manager and server panel in sequence (S20–S23).
2. **Ambiguous completion:** a progress bar can reach 100% while import, retrieval, availability or mandatory seeding remains unfinished (S13–S14, S35).
3. **Hidden location:** a remote job completes on a server, yet the user expected the file on the controlling device (S05, S10, S21).
4. **Split identity:** multiple jobs point to one body of data; one job can be gone while its file remains; a renamed file can exist while a job reports missing (S15, S27, S29).
5. **Overabundant lists:** users keeping thousands of seeds need filtered attention rather than a global chronological list (S24).
6. **Low-leverage repetition:** repeatedly initiating similar acquisitions and imports encourages feeds, saved criteria and automation (S13, S19–S20).
7. **High-risk maintenance:** deletion, relocation, client migration and service retirement involve decisions beyond a routine torrent action (S17, S27, S36).
8. **Misleading diagnostics:** stalled can mean no useful peers, auth/port trouble, queue settings, a disk error or a failed integration (S28).
9. **Responsibility split:** a provider may offer an app and file access, while the person still controls the tracker rules, destination, quota and allowed usage (S09, S17–S18).
10. **Unequal technical confidence:** an ordinary user's desired outcome may need expertise in Linux, permissions, mount identity, RPC versions, and storage hardlinks (S13–S14, S18, S22).

## 17. Things users choose *not* to do

Silence is a meaningful behavior:

- Never visit a torrent client UI because another application handles intake and status.
- Never alter network defaults, unless a failure or privacy requirement triggers it.
- Never create a torrent because the person only acquires published material.
- Never turn off long-term seeding except for maintenance or retirement.
- Never configure automation because occasional manual organization is enjoyable.
- Never copy seedbox files locally because the data is consumed directly through a hosted media server.
- Never operate a seedbox because local BitTorrent is enough.
- Never join a private tracker because the membership/compliance layer is undesirable.
- Never inspect peers or tracker details when the outcome already works.
- Never delete original seeding data while a second library reference exists; alternatively, never keep a finished torrent longer than allowed resources permit.

These alternatives are analytical branches, not claims about prevalence. Absence of action is not evidence of lack of need.

## 18. Synthesis: the enduring activity map

A compact map can locate almost any user action in this ecosystem:

| Stage | User is trying to... | Frequent artifact | Who usually acts |
|---|---|---|---|
| **Intent** | Decide what result they want | Title, release, request, dataset | Person, household request |
| **Discover** | Find a suitable source | Search result, RSS match, magnet | Person, indexer |
| **Authorize** | Choose scope, place, service and risk | Profile, destination, credentials | Person, policy |
| **Delegate** | Start work on chosen engine | Torrent job | Person, remote, automation |
| **Acquire** | Obtain verified data | Pieces, file tree, progress | Engine |
| **Observe** | Decide whether intervention is needed | Status, log, ETA | Person, notification |
| **Correct** | Resolve failures and changing priorities | Recheck, path mapping, rate/queue edit | Person, automation |
| **Deliver** | Make content useful in intended context | Local file, library entry, analysis | Copy/sync/importer, person |
| **Distribute** | Make content useful to others | Seed, tracker announcement, link | Engine, publisher |
| **Maintain** | Keep service/data available and within limits | Storage, quotas, accounts, backups | Person, host, rules |
| **Retire** | Stop work or relinquish resource intentionally | Stopped job, archive, deletion, closed account | Person, policy |

**The principal observable unit is a *goal-driven activity chain*, not a screen, menu or individual torrent job.** Some chains last seconds, some run continuously and some span several distinct applications. A user's first and last contact may be with a browser, phone, file manager, library app or provider account portal, while the torrent daemon does the work unseen.

## 19. Activity atlas for conceptual mapping (240 reusable micro-workflows)

**Purpose of this expansion.** The 36 earlier J-series journeys are deliberately broad examples. For building a conceptual map from the separate product feature inventory, they are insufficient as the only units of analysis. This atlas adds **240 A-series micro-workflows** covering routine, side-branch, intermittent, exceptional and long-running behaviors. These are *reusable patterns*, not 240 distinct personas or 240 newly observed end-to-end sessions. A person may combine several micro-workflows during one larger journey, and one micro-workflow may be served by several different software features.

**IDs and mapping contract:** `A09.03` is a stable micro-workflow. `J23` is one of the preexisting end-to-end example journeys. `F09.03` identifies category 09, item 03 **as numbered in Section B of the external BitTorrent ecosystem feature inventory dated 2026-10-29**; it is a *research cross-reference*, not a stable application requirement ID. Feature tags identify a plausible enabling or explanatory capability, **not a claim that a particular product supports all of them**. `S..` references identify evidence sources. Feature-to-activity edges are reasoned synthesis (`I`), not measured use rates.

**Read each row:** initiating situation/desired result → plausible sequence of actions and resulting state → the external feature concepts that support, expose or explain it. Cadence is relative to users who have this workflow, and is **never** a measured ecosystem frequency. The actor/tool class and work character are shown at the group level to avoid repeating the same metadata 12 times.

**Interpretation:** 'Rare' does not mean unimportant. Deleting the wrong folder or recovering a failed remote service can be more consequential than dozens of frequent status checks. 'Continuous' describes daemon/automation work that may require almost no human attention.

### A01 · Discovery, intent and choosing a source

**Activity character:** acquisitive, evaluative, curatorial. **Typical actor/tools and evidence:** browser/indexer/tracker/publisher; S01 S03 S04 S20 S21 S34 S41. **Temporal pattern:** episodic.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A01.01** | Need a known OS image | Open publisher's torrent listing → compare architecture/version/checksum → obtain its descriptor | F06.01, F06.03, F01.03 | O |
| **A01.02** | Need an unknown title | Search several indexes → filter by format/size/seeders → choose plausible result | F06.01, F06.03, F06.15 | F |
| **A01.03** | Need specific language or codec | Filter and compare release naming/metadata → inspect file list → accept or reject | F06.14, F09.07, F03.01 | O |
| **A01.04** | Need one item from an archive | Browse catalogue → read file manifest → choose subset before or after add | F06.03, F03.01, F03.02 | O |
| **A01.05** | Need newest matching episode | Review RSS rule matches and exclusions → decide automatic or manual grab | F06.04, F06.05, F06.15 | F |
| **A01.06** | Need a specific infohash | Search by hash rather than title → compare with existing job → avoid duplicate | F06.13, F01.09, F08.01 | R |
| **A01.07** | Need a safer original source | Check publisher, metadata and verification information → reject mismatched candidate | F01.19, F08.01, F03.13 | O |
| **A01.08** | Need to compare multiple releases | Compare size, age, quality and active peers → choose one or postpone | F06.03, F06.14, F08.11 | F |
| **A01.09** | Need to discover content socially | Inspect shared ratings/comments or channel post → investigate torrent provenance | F06.12, F15.04 | O |
| **A01.10** | Need publication permissions | Review source license/tracker upload conditions → select allowed sharing channel | F01.17, F13.05 | R |
| **A01.11** | Need to revisit a prior find | Open saved recent search/feed history → compare updated entries → resume decision | F06.04, F07.10, F06.15 | O |
| **A01.12** | Need to stop looking | Compare alternatives against goal → decide ordinary download, torrent or defer entirely | F01.06, F06.15 | O |

### A02 · Adding, identifying and placing a new job

**Activity character:** acquisitive, delegative, operational. **Typical actor/tools and evidence:** client, remote, browser, operating-system share surface; S01 S05 S06 S07 S18 S37 S43. **Temporal pattern:** per acquisition.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A02.01** | A magnet is tapped in browser | Resolve protocol handler → identify target engine → show add confirmation → submit | F01.02, F10.12, F14.06 | F |
| **A02.02** | A small torrent file is received in chat | Open/share file → inspect metainfo → choose location → add | F01.01, F01.07, F14.06 | F |
| **A02.03** | A page links directly to torrent metadata | Paste remote URL → client fetches descriptor → inspect or add job | F01.03, F10.11 | O |
| **A02.04** | Several links need adding | Select or paste batch → choose common destination/category → confirm distinct jobs | F01.04, F02.04 | O |
| **A02.05** | A feed produces a match | Accept matching event → pass descriptor into chosen client → watch acknowledgement | F01.05, F12.02, F06.05 | C |
| **A02.06** | User wants transfer to wait | Set initially stopped/queued or future schedule → verify pending entry exists | F01.08, F02.09 | O |
| **A02.07** | Default folder is wrong | Choose alternate storage before starting → verify engine-visible path | F01.07, F03.07, F03.18 | F |
| **A02.08** | An identical hash already exists | Compare existing instance/job with proposed add → reuse, focus or decline duplicate | F01.09, F08.01, F10.08 | O |
| **A02.09** | Job needs its own rules immediately | Set tag, tracker, rate, share targets and classification at intake | F01.10, F07.01, F05.06 | O |
| **A02.10** | An importer has original .torrent | Restore or add metainfo to existing data path → recheck instead of redownloading | F01.11, F01.15, F03.15 | R |
| **A02.11** | Source is non-torrent download | Select HTTP/FTP/Metalink path → send to multi-protocol tool instead of peer swarm | F01.06, F15.03 | O |
| **A02.12** | Metainfo appears corrupt | Reject unsafe/bad input → explain discrepancy → seek trusted replacement | F01.19, F13.12, F08.08 | R |

### A03 · Metadata, file selection and piece order

**Activity character:** curatorial, scheduling, consumptive, technical. **Typical actor/tools and evidence:** client file tree, player, archive explorer; S03 S05 S13 S37 S43. **Temporal pattern:** per multipart job.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A03.01** | Magnet has no visible files yet | Observe metadata progress → wait for full file tree → select wanted files | F04.07, F03.01, F08.02 | F |
| **A03.02** | Only selected files matter | Mark files and directories wanted/unwanted → check required size → proceed | F03.02, F03.01, F01.07 | F |
| **A03.03** | Important file should finish first | Assign higher file priority → check wanted progress separately | F03.03, F05.12, F08.04 | O |
| **A03.04** | Want beginning of a video soon | Prioritize beginning pieces or sequential mode → try preview when buffered | F03.04, F09.01, F09.02 | O |
| **A03.05** | Want to seek to later scene | Seek in player → adjust piece demand toward required range → buffer/play | F09.02, F03.04, F04.15 | O |
| **A03.06** | File tree has many nested folders | Expand/filter tree → select only intended subtree → verify totals | F03.01, F03.02, F03.16 | O |
| **A03.07** | Selection changed midway | Toggle wanted files → recalculate remaining work → continue without losing valid bytes | F03.02, F08.02, F03.15 | O |
| **A03.08** | A file seems incomplete after 100% | Compare overall, wanted and per-file completion → verify relevant pieces | F08.02, F08.04, F03.15 | R |
| **A03.09** | First and last bytes are needed | Prioritize headers/index data → verify preview readiness rather than total completion | F03.04, F09.01, F04.14 | O |
| **A03.10** | Need to inspect archived contents | Preview NFO/listing or archive tree → decide extraction or file selection | F09.09, F03.11, F03.13 | O |
| **A03.11** | Different BitTorrent formats are encountered | Inspect v1/v2/hybrid identity and client support → select compatible engine | F04.01, F01.14, F10.06 | R |
| **A03.12** | Torrent file layout makes partial reuse hard | Inspect piece/file boundaries → evaluate file layout or creation alignment before reseeding | F03.14, F15.07, F04.14 | R |

### A04 · Watching status, queues and ordinary control

**Activity character:** operational, observational, attentional. **Typical actor/tools and evidence:** transfer list, remote controller, notifications; S05 S06 S07 S24 S37 S43. **Temporal pattern:** repeated throughout active jobs.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A04.01** | Want quick progress reassurance | Open selected instance → scan active states/rates/ETA → close without modifying | F02.01, F08.03, F14.04 | F |
| **A04.02** | Need one torrent among thousands | Filter by status/category → sort by ETA/last activity → open matching detail | F07.03, F07.05, F07.06 | F |
| **A04.03** | Want to resume only one paused job | Open row/action → start selected job → verify state transition | F02.02, F08.03 | F |
| **A04.04** | Want to stop traffic for now | Pause selected jobs or all → verify stopped while retaining data | F02.02, F02.04, F02.18 | O |
| **A04.05** | Urgent job stuck behind queue | Move priority up or force-start → verify active slot changed | F02.05, F02.02, F05.12 | O |
| **A04.06** | Too many simultaneous transfers | Lower active/seed/download slots → observe queue redistribution | F02.06, F05.04 | O |
| **A04.07** | A job is stalled | Inspect stalled state and timeout policy → optionally requeue another | F02.07, F02.08, F05.11 | O |
| **A04.08** | Several related items need same change | Select by category/filter → batch tag/pause/recheck with target review | F02.04, F07.04, F14.02 | O |
| **A04.09** | Completion event arrives | Read notification → verify status → decide retrieve/use/seed/archive next | F02.12, F12.06, F14.07 | F |
| **A04.10** | Looking for what changed overnight | Inspect activity/transfer history → find newly completed or errored jobs | F02.11, F07.10, F08.13 | O |
| **A04.11** | Client was updated or restarted | Reopen → inspect restored session and progress → verify no lost registrations | F02.10, F02.17, F08.08 | R |
| **A04.12** | Need to leave app without ending work | Close window/controller while background engine continues → later check session | F10.01, F14.07, F02.10 | F |

### A05 · Seeding, reciprocity and long-term availability

**Activity character:** custodial, communal, contractual. **Typical actor/tools and evidence:** torrent daemon, tracker account, retention policy; S25 S26 S35 S15 S16. **Temporal pattern:** continuous with periodic inspection.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A05.01** | Just finished downloading | Leave job active for upload → check ratio/time after an interval | F04.16, F05.06, F08.03 | F |
| **A05.02** | Private tracker requires minimum time | Identify site policy → seed until eligible → verify tracker/account status | F04.19, F13.05, F05.06 | O |
| **A05.03** | Want to achieve chosen ratio | Set per-job or group ratio target → allow upload → inspect actual accounting | F05.06, F02.14, F08.03 | O |
| **A05.04** | Few seeders remain | Favor retention of rare torrent → keep host powered/connected → review uptime | F04.16, F08.11, F05.04 | O |
| **A05.05** | Seedbox should upload overnight | Schedule seed/rate policy → leave daemon alone → check later usage | F02.09, F05.02, F11.11 | F |
| **A05.06** | Want to favor cooperative swarms | Inspect demand and seed count → choose retention/queue priority | F08.11, F04.13, F05.04 | O |
| **A05.07** | Need seed health at a glance | Filter seeding jobs → sort by ratio, last activity or errors → handle exceptions | F07.03, F07.05, F08.03 | F |
| **A05.08** | Torrent seeding has ended unexpectedly | Inspect tracker, file state and quota → correct cause → resume upload | F08.05, F03.19, F02.08 | R |
| **A05.09** | Content must remain shareable for years | Create resilient storage/backup plan → verify files and job after migration | F11.14, F03.15, F02.17 | R |
| **A05.10** | Want to seed same data elsewhere | Validate compatible release → create separate job/link → verify no duplicate payload | F06.11, F03.20, F15.15 | O |
| **A05.11** | Small publisher starts first seed | Publish metadata → make entire original payload available → watch first swarm joins | F04.16, F01.12, F15.02 | R |
| **A05.12** | Need to stop seeding while keeping bytes | Stop/remove only job after obligations → confirm backing files remain accessible | F02.03, F02.18, F13.11 | O |

### A06 · Peers, discovery mechanisms and protocol choices

**Activity character:** technical, exploratory, diagnostic. **Typical actor/tools and evidence:** client advanced controls, peer and tracker panels; S05 S28 S37 S43. **Temporal pattern:** exception-driven.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A06.01** | Torrent has zero known peers | Inspect tracker messages/DHT discovery → distinguish no swarm from blocked network | F04.03, F04.05, F08.11 | R |
| **A06.02** | Tracker reports peers but no connections | Inspect NAT/port and transport → compare reachable peers with announced counts | F04.08, F08.07, F08.11 | R |
| **A06.03** | Need to identify a problematic peer | Inspect IP/client/rates → optionally disconnect or block with reason | F04.11, F08.06, F13.09 | R |
| **A06.04** | Public torrent has no tracker success | Check DHT/PEX/local discovery configuration → test public-only fallback | F04.05, F04.06, F04.02 | R |
| **A06.05** | Private tracker forbids peer discovery | Inspect private flag and announce policy → avoid DHT/PEX for that job | F04.19, F04.02, F13.05 | R |
| **A06.06** | Several trackers disagree | Compare announce tiers/status → adjust trackers → reannounce and read response | F04.03, F04.04, F08.05 | R |
| **A06.07** | Need to inspect piece scarcity | View bitfield/availability → decide whether wait, prioritize or find other source | F04.14, F08.02, F04.15 | R |
| **A06.08** | Want to share with browser peers | Choose WebTorrent-compatible distribution → test WebRTC/native interoperability | F04.18, F01.13 | R |
| **A06.09** | Experiencing very slow disk IO | Inspect cache, allocation and activity → change performance setting → remeasure | F04.21, F05.09, F08.10 | R |
| **A06.10** | Want to test encryption settings | Review tracker and peer compatibility → adjust supported transport security → verify connects | F04.09, F13.04, F08.06 | R |
| **A06.11** | Multi-source downloader has alternate sources | Evaluate peers, web seeds and HTTP/FTP sources → choose safe supported combination | F04.04, F04.17, F15.03 | R |
| **A06.12** | Want alternate privacy network | Choose appropriate I2P/onion-supported client → check identity and performance limits | F04.20, F13.02, F13.15 | R |

### A07 · Naming, classification and finding things later

**Activity character:** curatorial, organizational, operational. **Typical actor/tools and evidence:** client list, labels, media tools; S20 S24 S43. **Temporal pattern:** often for large collections.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A07.01** | New job needs classification | Choose category/tags → file under working collection → verify views | F07.01, F07.04, F01.10 | F |
| **A07.02** | Want category-specific storage | Set category save path → classify incoming job → verify actual engine location | F07.02, F03.08, F03.09 | O |
| **A07.03** | Original release name is opaque | Apply readable display alias while retaining metainfo/source identity | F07.07, F03.05 | O |
| **A07.04** | Need release details later | Store comment/notes/source metadata → return through detail view | F07.08, F08.13 | O |
| **A07.05** | Looking for a recently touched job | Open recents or pin list → inspect change history → resume prior task | F07.10, F02.11 | F |
| **A07.06** | List is visually overwhelming | Switch to compact columns/grouped view → emphasize status or tags | F07.12, F14.03 | F |
| **A07.07** | Need saved view for errors | Compose filter for failed/stalled items → save → revisit after monitoring | F07.03, F07.06, F08.08 | O |
| **A07.08** | Many jobs need reclassification | Select group by tracker/path → bulk update tags/category → check resulting groups | F07.09, F07.11, F02.04 | O |
| **A07.09** | Need to distinguish same-named releases | Compare infohash/source path/labels → assign distinct aliases | F07.07, F08.01, F07.01 | O |
| **A07.10** | Need a list by tracker | Group and sort by tracker, last active and ratio → inspect subset | F07.04, F07.05, F08.05 | F |
| **A07.11** | Automatically tag new items | Define tracker/path rule → test match → run classification on future jobs | F07.09, F12.10, F07.01 | O |
| **A07.12** | Want custom dashboard appearance | Select theme, columns or density → save UI choices → continue routine use | F07.12, F14.08, F14.11 | O |

### A08 · Remote control, multiple locations and authority

**Activity character:** delegative, administrative, operational. **Typical actor/tools and evidence:** phone/web/desktop client; local daemon and remote daemon; S07 S18 S21 S30 S37. **Temporal pattern:** frequent remote work, setup occasional.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A08.01** | First time connecting remote app | Obtain host, scheme, port and RPC path → authenticate → verify server identity | F10.03, F10.15, F14.13 | R |
| **A08.02** | More than one remote engine exists | Save distinct profiles → choose intended endpoint → inspect its own jobs | F10.04, F10.08, F10.03 | O |
| **A08.03** | Want unified view of heterogeneous clients | Connect supported protocols → inspect capability differences → perform available action | F10.05, F10.06, F10.02 | O |
| **A08.04** | Phone encounters magnet away from home | Open share target → select remote profile → submit and verify target job | F10.12, F10.11, F01.02 | F |
| **A08.05** | Want browser-only access on travel laptop | Open saved WebUI address → authenticate → inspect/change remote job | F10.01, F10.18, F10.15 | O |
| **A08.06** | Switch from local to remote view | Change current environment → confirm identity → avoid touching other session | F10.08, F10.04, F14.04 | F |
| **A08.07** | Connection drops after command | Treat result as uncertain → refresh/reconnect → reconcile actual remote state | F10.09, F10.10, F13.13 | R |
| **A08.08** | Remote UI wakes after days offline | Load cached list with stale indication → reconnect → replace with fresh state | F10.10, F10.09, F14.10 | O |
| **A08.09** | Server certificate unexpectedly changed | Inspect certificate/host identity → decide trust scoped to endpoint → reconnect | F10.14, F13.08, F13.04 | R |
| **A08.10** | Need remote path operation | Authenticate distinct filesystem channel → identify path permissions → operate on remote data | F10.07, F03.23, F13.06 | O |
| **A08.11** | Need to move job registration to other engine | Export/share metadata and settings → add on destination → independently begin new acquisition | F02.16, F10.05, F01.15 | R |
| **A08.12** | Remote credentials should survive phone replacement | Export encrypted profile backup → restore on new phone → test connection | F10.13, F13.07, F14.11 | R |

### A09 · Provisioning, accounts and infrastructure

**Activity character:** administrative, technical, resource-economic. **Typical actor/tools and evidence:** seedbox portal, SSH, host app manager; S09 S17 S18 S33 S42. **Temporal pattern:** setup, monthly, exception.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A09.01** | Start hosted seedbox from scratch | Choose plan and slot → install torrent app → open control and connection details | F11.01, F11.16, F11.17 | R |
| **A09.02** | Torrent daemon appears offline | Inspect app status/process → restart → verify healthy RPC and resumed jobs | F11.02, F11.04, F10.17 | R |
| **A09.03** | Web interface returns gateway error | Check webserver/proxy → restart or repair approved layer → retry correct URL | F11.06, F11.02, F11.04 | R |
| **A09.04** | Need access from new device | Find service host/ports/credentials in provider panel → configure secure client | F11.03, F11.09, F10.03 | O |
| **A09.05** | User lost client password | Use provider-specific reset/change route → update saved remote credential → test | F11.05, F11.03, F13.07 | R |
| **A09.06** | Need another service like media server | Inspect app catalogue → install/configure → connect required storage path | F11.15, F11.16, F11.13 | O |
| **A09.07** | Need separate household users | Assign roles/permissions and services → test each person's authorized views | F11.05, F11.17, F13.06 | R |
| **A09.08** | Infrastructure security needs review | Check firewall/reverse proxy/TLS/tunnel rules → test access and rejection | F11.06, F11.07, F11.08 | O |
| **A09.09** | Service upgrade is available | Back up settings → upgrade known app → verify API compatibility and state | F11.01, F11.12, F10.06 | O |
| **A09.10** | Need to view remote resource pressure | Open CPU/memory/disk/network charts → correlate load with stalled jobs | F11.10, F08.10, F11.04 | O |
| **A09.11** | Provider renews soon | Check expiry, plan usage and options → renew, change plan or prepare migration | F11.11, F11.17 | O |
| **A09.12** | Manage another slot or tenant | Select correct slot/account → inspect entitlement → act only in intended tenant | F11.17, F10.17, F13.06 | O |

### A10 · Remote files, archives, moves and retrieval

**Activity character:** file operations, curatorial, custodial. **Typical actor/tools and evidence:** remote file manager, FTP/SFTP, browser, media tools; S10 S11 S23 S31 S38. **Temporal pattern:** per completed item or repair.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A10.01** | Need files on phone, not on seedbox | Locate payload on remote server → download through file channel → open local copy | F03.23, F03.10, F10.07 | F |
| **A10.02** | Want to move finished media into a folder | Confirm file ownership/seeding → relocate via supported engine/file tool → verify actual path | F03.07, F03.08, F03.21 | O |
| **A10.03** | Want new name without losing seeding | Decide alias versus backing rename → perform correct operation → verify job mapping | F03.05, F03.06, F03.16 | O |
| **A10.04** | Need to unpack release archive | Inspect extension/trust → extract on permitted storage → inspect results | F03.11, F03.12, F13.12 | F |
| **A10.05** | Archive is encrypted or nested | Supply permitted password → extract recursive layers with space checks → validate output | F03.12, F03.18, F03.13 | O |
| **A10.06** | Need screenshots without copying video | Request remote thumbnails or media analysis → inspect output → choose full retrieval | F09.06, F15.11, F03.23 | O |
| **A10.07** | Want to validate downloaded data | Compare checksums and info files → inspect discrepancy → use trusted content | F03.13, F03.15, F09.09 | O |
| **A10.08** | Need one file over slow link | Select only wanted remote file → download with resume → verify copied bytes | F03.10, F03.23, F11.14 | O |
| **A10.09** | Want long-running remote-to-local sync | Configure SFTP/rclone/Syncthing paths → monitor sync → check duplicates/conflicts | F11.14, F12.12, F03.23 | C |
| **A10.10** | Need to clean staging folders | Inspect archives/partial and dependent job refs → move to trash or keep → verify space | F03.19, F03.22, F03.18 | O |
| **A10.11** | Want to transfer across filesystems | Select destination → copy or move safely → check checksum → update any active mapping | F03.21, F03.24, F03.15 | O |
| **A10.12** | Remote and local paths look different | Reconcile container/host/mount paths → translate location for downstream tool | F03.24, F10.07, F11.13 | R |

### A11 · Automated intake, scripts and rule maintenance

**Activity character:** orchestrational, operational, diagnostic. **Typical actor/tools and evidence:** RSS/IRC/indexer, plugins, watch folder, APIs; S13 S14 S19 S37 S43 S44. **Temporal pattern:** once setup, continuous execution.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A11.01** | Want an RSS feed polled regularly | Subscribe → refresh and preview → mark read or enqueue selected matches | F06.04, F12.02, F01.05 | C |
| **A11.02** | Need a precise RSS rule | Specify include/exclude/quality → preview matches → enable auto-download | F06.05, F06.14, F12.02 | O |
| **A11.03** | Want early IRC-announced additions | Authenticate announcement source → filter release → dispatch to chosen client | F06.10, F12.02, F01.05 | C |
| **A11.04** | Use multiple indexers for same rule | Normalize Torznab providers → sync definitions → test one manual query | F06.06, F06.07, F06.09 | O |
| **A11.05** | Private search site needs login | Configure its credentials/proxy → test query → handle auth changes safely | F06.08, F01.18, F13.05 | O |
| **A11.06** | Use watched torrent folder | Place small .torrent in directory → watcher notices → adds according to rules | F12.01, F01.05 | C |
| **A11.07** | Completion should trigger next task | Configure event hook → download completes → script/extractor/importer runs → verify outcome | F12.03, F12.07, F12.08 | C |
| **A11.08** | Need a custom automation integration | Read API/plugin capabilities → implement filter/script → test against known job | F12.04, F12.05, F12.10 | R |
| **A11.09** | Rule grabbed wrong match | Inspect event/match history → edit condition → test sample before reenabling | F12.13, F06.15, F06.05 | O |
| **A11.10** | Want housekeeping policy | Define tag/ratio/age/orphan rule → dry-run where supported → review affected jobs | F12.09, F12.10, F13.11 | O |
| **A11.11** | Want failure watchdog | Define retry/throttle schedule → detect failure → requeue and verify progress | F12.11, F12.14, F02.08 | C |
| **A11.12** | Need alerts in another app | Connect notification integration → select meaningful events → inspect delivery logs | F12.06, F12.13, F14.07 | O |

### A12 · Media, preview, playback and library consumption

**Activity character:** consumptive, curatorial, orchestrational. **Typical actor/tools and evidence:** player, media manager, device cast, seedbox; S13 S14 S20 S22 S38. **Temporal pattern:** per consumed item.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A12.01** | Want to preview before full completion | Select playable file → buffer pieces → open player → observe whether enough data exists | F09.01, F09.02, F03.04 | O |
| **A12.02** | Want finished video to appear in library | Detect completion → import/hardlink/copy and rename → media server scan → play | F09.11, F09.12, F12.08 | F |
| **A12.03** | Player is on TV rather than torrent device | Choose cast target → stream/transcode as supported → confirm target playback | F09.04, F09.05, F15.10 | O |
| **A12.04** | Want detailed media quality before keeping | Inspect codec/container/audio tracks → compare needs → decide retain or replace | F09.07, F09.10, F06.14 | O |
| **A12.05** | Archive contains subtitles/NFO | Inspect text and auxiliary files → choose retention and matching media item | F09.09, F03.01, F09.10 | O |
| **A12.06** | Media library shows wrong title | Inspect file name and metadata → adjust curated label → rescan library | F09.10, F09.11, F07.07 | O |
| **A12.07** | Want screenshot before retrieval | Generate remote stills → evaluate visual content → choose download | F09.06, F03.23 | O |
| **A12.08** | Want spectrogram to inspect audio | Analyze audio file → inspect spectrogram/metadata → compare with expected source | F09.08, F15.11 | R |
| **A12.09** | Need compact output for another device | Choose compatible transcode target → process data → play copied/streamed version | F09.05, F15.10 | O |
| **A12.10** | Want queue of music/videos | Open built-in library/playlist → select item → continue listening while job seeds | F09.03, F04.16 | O |
| **A12.11** | Household member makes a request | Receive media request → search/approve/grab → import → notify/serve | F09.12, F06.01, F12.06 | O |
| **A12.12** | Hardlink import must preserve seeding | Check same-filesystem paths → create separate library link → verify both readers and uploader | F03.20, F09.11, F15.15 | O |

### A13 · Publishing, original distribution and community sharing

**Activity character:** creative, distributive, custodial. **Typical actor/tools and evidence:** torrent creator, publisher, tracker, file manager; S02 S03 S04 S12 S34 S41. **Temporal pattern:** per original release.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A13.01** | Have a file to share with a friend | Prepare original file → create metainfo/link → seed → check recipient can connect | F01.12, F01.16, F15.01 | R |
| **A13.02** | Have a large directory to publish | Freeze final folder layout → choose piece size → create metainfo → seed from same bytes | F01.12, F01.14, F03.14 | R |
| **A13.03** | Need tracker-restricted private release | Read site rules → mark private and enter announce credentials → upload metadata → seed | F01.13, F04.19, F13.05 | R |
| **A13.04** | Want trackerless sharing | Choose DHT-compatible public torrent/magnet → announce availability through peers | F01.13, F04.02, F04.05 | R |
| **A13.05** | Need browser-compatible sharing | Build WebTorrent-supported release → test peer discovery and browser playback | F04.18, F01.13, F09.01 | R |
| **A13.06** | Need older client compatibility | Choose v1/v2/hybrid metadata → validate target client support → test download | F01.14, F04.01, F10.06 | R |
| **A13.07** | Want distribution via site/indexer | Prepare title, category and description → submit permitted metainfo → verify listing | F01.17, F06.01, F07.08 | R |
| **A13.08** | Want resilient official download | Seed official release plus webseed/mirrors → publish checksums → verify consumer retrieval | F04.04, F03.13, F01.13 | R |
| **A13.09** | Want initial upload not to waste bandwidth | Choose supported initial/super seed mode → watch distribution to early peers | F15.02, F04.16, F08.06 | R |
| **A13.10** | Want live publishing status | Inspect peer counts, bytes served and tracker announce → maintain seed until stable | F08.03, F08.05, F04.16 | O |
| **A13.11** | Need new version of published data | Prepare new immutable file set → create new metainfo → distinguish versions for recipients | F01.12, F01.11, F07.08 | O |
| **A13.12** | Audience reports they cannot download | Inspect announce/peer reachability and their metadata → repair initial seeding path | F08.07, F04.03, F08.11 | R |

### A14 · Privacy, credentials, trust and permissions

**Activity character:** risk, governance, technical. **Typical actor/tools and evidence:** VPN, remote auth, tracker, storage, host; S09 S18 S37 S43. **Temporal pattern:** initial and exception.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A14.01** | Avoid leaking peer traffic outside VPN | Bind daemon to intended network interface → disconnect VPN deliberately → confirm no fallback | F13.03, F05.08, F13.15 | O |
| **A14.02** | Separate tracker and peer proxy policy | Configure scoped proxies → verify which requests take which routes | F13.01, F13.02, F04.09 | R |
| **A14.03** | Use remote HTTPS safely | Validate hostname, scheme and certificate chain → authenticate → avoid unsafe downgrade | F13.04, F10.15, F13.08 | O |
| **A14.04** | Endpoint has self-signed certificate | Inspect fingerprint/identity → explicitly trust only intended server if acceptable | F13.08, F10.14, F10.03 | R |
| **A14.05** | Tracker login/private passkey is involved | Store credentials only in proper scope → test announce → avoid exposing descriptor | F13.05, F04.19, F01.18 | O |
| **A14.06** | Phone profile backup is exported | Encrypt/transfer profile data → restore privately → revoke obsolete stored copies | F13.07, F10.13 | R |
| **A14.07** | Shared host has multiple accounts | Check roles for RPC and file operations → act only as authorized user | F13.06, F11.05, F11.17 | O |
| **A14.08** | Peer is considered unwanted | Inspect behavior/IP information → block scoped peer/range → monitor side effects | F13.09, F04.11, F04.12 | R |
| **A14.09** | Public and private networks should be isolated | Separate interfaces/sessions → test profile constraints → monitor traffic routes | F13.10, F05.10, F05.08 | R |
| **A14.10** | Download contains risky archive | Inspect provenance and content paths → unpack with protection/space limits | F13.12, F01.19, F03.12 | O |
| **A14.11** | About to remove files or reinstall service | Review target and consequences → confirm specific destructive action → verify state | F13.11, F02.18, F11.12 | O |
| **A14.12** | Want to know what changed after suspicious error | Inspect audit and activity logs → identify actor/command → recover trustworthy state | F13.14, F13.13, F08.13 | R |

### A15 · Connectivity, diagnostics and controlled troubleshooting

**Activity character:** technical, diagnostic, exploratory. **Typical actor/tools and evidence:** tracker panel, system network, client logs, provider dashboard; S28 S37 S43. **Temporal pattern:** exception.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A15.01** | Remote app says disconnected | Check general internet → inspect exact endpoint and credentials → test safe read-only call | F08.08, F08.12, F10.16 | R |
| **A15.02** | Torrent stalled despite available peers | Compare tracker counts to live connections → inspect port policy and error | F08.11, F08.07, F08.05 | R |
| **A15.03** | Inbound peer port appears closed | Run port test → inspect host NAT/firewall and listener → retest | F08.07, F11.07, F04.08 | R |
| **A15.04** | Need to distinguish auth from transport problem | Read HTTP/RPC status and server version → isolate path/protocol mismatch | F08.08, F08.12, F10.02 | R |
| **A15.05** | Torrent has a tracker error | Read last announce/scrape status and next interval → adjust only relevant tracker | F08.05, F04.03, F02.13 | R |
| **A15.06** | Speed has collapsed after tweak | Inspect live graphs and prior setting → revert/change one variable → compare | F08.10, F05.09, F08.09 | R |
| **A15.07** | Content seems corrupted | Inspect hash-failure and per-file progress → force recheck → redownload invalid pieces | F03.15, F08.04, F08.13 | R |
| **A15.08** | Device battery or metered network matters | Check policy/battery/network profile → schedule/resume when conditions fit | F05.07, F15.09, F14.07 | O |
| **A15.09** | Bandwidth chart looks wrong | Compare session totals and per-peer rates → distinguish accounting period and reset | F08.09, F08.10, F08.06 | O |
| **A15.10** | Different remote app behaves differently | Check API version and supported method → compare capability, avoid unsupported mutation | F10.06, F08.12, F10.02 | R |
| **A15.11** | Need exact torrent technical identity | Inspect infohash, piece size and private flag → compare original descriptor | F08.01, F01.14, F08.13 | R |
| **A15.12** | Need evidence after failed command | Capture error and before/after state → distinguish request from result → retry only safely | F13.13, F08.08, F08.13 | R |

### A16 · Recovery, integrity, migration and reuse

**Activity character:** restorative, technical, custodial. **Typical actor/tools and evidence:** client, filesystem, backup, old/new server; S27 S36 S38 S39 S40. **Temporal pattern:** rare but high consequence.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A16.01** | Externally renamed one file | Find actual file → update engine's mapped filename/path → recheck hash → resume | F03.06, F03.19, F03.15 | R |
| **A16.02** | Folder moved to another directory | Locate data root → set client location or reconcile paths → verify files then seed | F03.07, F03.19, F03.15 | R |
| **A16.03** | App lost registration but data exists | Recover original metainfo → point new job to existing bytes → verify then resume | F01.15, F02.17, F03.15 | R |
| **A16.04** | Need to migrate without moving bytes | Export job descriptor/settings → register in other engine → acquire separately there | F02.16, F10.08, F02.17 | R |
| **A16.05** | Need to move both payload and job | Inventory paths and references → securely copy bytes → register/recheck new engine → retire old | F03.21, F11.14, F02.17 | R |
| **A16.06** | Need to preserve hardlinked library | Inspect links, mounts and dependent jobs → migrate filesystem references safely | F03.20, F03.24, F15.15 | R |
| **A16.07** | Want cross-seed despite different filenames | Choose matching method → create safe hardlinks/reflinks → inject and verify jobs | F06.11, F03.20, F15.15 | O |
| **A16.08** | Have partially downloaded bytes from other client | Choose existing directory → verify piece hashes → request only missing data | F01.15, F03.15, F08.04 | R |
| **A16.09** | Backup must be restorable | Export config, torrents, fast-resume metadata → restore test → compare state | F02.17, F11.12, F10.13 | R |
| **A16.10** | Client or host crash corrupts state | Read logs → inspect payload integrity → rebuild registrations without deleting bytes | F02.10, F08.08, F03.15 | R |
| **A16.11** | Large tree has Unicode or case mismatch | Compare actual names and path semantics → repair safe mapping → verify exact files | F03.16, F03.19, F03.06 | R |
| **A16.12** | Need to check changes following cleanup | Compare before/after job state and affected files → restore from recycle/backup if possible | F03.22, F13.14, F02.18 | R |

### A17 · Space, traffic, performance and cost economics

**Activity character:** resource-economic, operational, administrative. **Typical actor/tools and evidence:** client, host quota panel, file manager; S09 S17 S25 S38. **Temporal pattern:** periodic/threshold.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A17.01** | Disk approaches 100% | Compare file tree and quota → identify reclaimable data → retire safely | F03.18, F11.11, F03.22 | O |
| **A17.02** | Multiple paths use same bytes | Inspect hardlinks/reflinks and duplicates → avoid double-counting or false savings | F03.20, F03.19, F11.10 | O |
| **A17.03** | Want to honor ISP monthly cap | Set quota/rate policy → watch session usage → pause/adjust on threshold | F05.05, F11.11, F08.09 | O |
| **A17.04** | Speed limit needed only during video call | Toggle alternate schedule → verify latency → restore normal rates | F05.02, F05.01, F05.09 | F |
| **A17.05** | Some torrents must have separate budgets | Assign torrent/tag/group policy → verify overrides and global bounds | F05.03, F05.13, F07.01 | O |
| **A17.06** | Queue too aggressive for device | Limit active/seeding slots → monitor disk and peer load | F05.04, F02.06, F04.21 | O |
| **A17.07** | Battery should be conserved | Restrict cellular/battery schedule → background wait → resume on preferred power/network | F05.07, F15.09, F14.07 | O |
| **A17.08** | Host traffic cap is near reset | Inspect provider graph and renewal date → defer retrieval or change data policy | F11.11, F08.10, F05.05 | O |
| **A17.09** | Upload credit and retention conflict | Compare rare content, ratio targets and storage cap → select retention deliberately | F05.06, F11.11, F08.11 | O |
| **A17.10** | Need bandwidth isolation by task | Choose separate network/session policy → verify intended limits on each | F05.10, F05.13, F10.04 | R |
| **A17.11** | Want disk and network throughput balance | Review CPU/IO/network figures → tune cache/connection → test against baseline | F05.09, F04.21, F11.10 | R |
| **A17.12** | Ready to increase hosted capacity | Review current usage and service entitlements → change plan/slot → verify resources | F11.17, F11.11, F11.01 | R |

### A18 · Interaction surfaces, notifications and accessibility

**Activity character:** observational, assistive, operational. **Typical actor/tools and evidence:** Android, desktop, browser, TV, CLI; S07 S08 S37 S43. **Temporal pattern:** frequent, preference setup.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A18.01** | Phone screen is narrow | Switch between list and detail/compact layout → complete selected action without losing target | F14.01, F14.05, F14.09 | F |
| **A18.02** | Need to act on many rows efficiently | Use selection range/keyboard commands → review set → bulk apply action | F14.02, F02.04 | O |
| **A18.03** | Need clear overview of all jobs | Choose status widgets, rate graph and free-space tile → monitor from dashboard | F14.03, F14.04, F08.10 | F |
| **A18.04** | Client is used on TV/tablet | Use responsive target/layout → navigate larger list with available input method | F14.05, F14.09, F10.18 | O |
| **A18.05** | Notification announces completion | Open notification → jump to exact job → retrieve/use or continue seeding | F14.07, F12.06, F02.12 | F |
| **A18.06** | Want high contrast or local language | Change theme, typography or language → inspect readability and status meaning | F14.08, F14.09 | O |
| **A18.07** | Need screen-reader or keyboard access | Navigate focusable actions → read file/status → confirm intended operation | F14.09, F14.02 | F |
| **A18.08** | Remote mobile polling consumes data | Adjust background refresh → distinguish freshness from cached states | F14.10, F10.09, F10.10 | O |
| **A18.09** | Moving to a new laptop | Import preferences/presets → verify preferred views and connectivity | F14.11, F10.13 | R |
| **A18.10** | Confused by changed interface after update | Read release notes/help → compare new setting → continue safely | F14.12, F10.06 | R |
| **A18.11** | Provider has unusual RPC URL | Use profile quick setup hints → test path/credential combination → retain verified address | F14.13, F10.03, F10.16 | O |
| **A18.12** | Need to distinguish local vs remote notifications | Inspect notification's job/server source → open correct session | F14.07, F10.08, F13.13 | O |

### A19 · Unusual, historical and ecosystem-specific goals

**Activity character:** technical, creative, experimental. **Typical actor/tools and evidence:** specialized engine, seedbox plugin, transport tools; S03 S04 S11 S34 S37 S45. **Temporal pattern:** occasional/rare.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A19.01** | Want embedded tracker for friends | Enable appropriate local tracker → create descriptor → verify route and recipient download | F15.01, F01.16, F01.13 | R |
| **A19.02** | Need very long-tail reseeding mechanism | Evaluate long-term/super-seeding approach → watch availability and uploads | F15.02, F04.16 | R |
| **A19.03** | Have HTTP/FTP mirrors plus torrent | Provide or use compatible multi-protocol sources → compare integrity and transfer success | F15.03, F01.06, F04.17 | R |
| **A19.04** | Want distributed recommendations | Explore decentralized chat/reviews/tags → evaluate content independently | F15.04, F06.12 | R |
| **A19.05** | Participate in reward or token system | Inspect available system rules → seed according to chosen reputation objective | F15.05, F04.16 | R |
| **A19.06** | Want syndication or continuous publish | Set RSS/channel broadcast → subscribers auto-discover → maintain data availability | F15.06, F06.04, F12.02 | R |
| **A19.07** | Preparing torrent with file-aligned pieces | Choose metadata layout → generate pieces → test partial-file selection | F15.07, F03.14, F01.12 | R |
| **A19.08** | Machine should shut down after queue ends | Set completion-triggered OS shutdown → verify files persisted → resume next time | F15.08, F02.12 | O |
| **A19.09** | Remote seedbox offers media processing plugin | Request screenshots/spectrogram → inspect artifact → optionally download file | F15.11, F09.08, F09.06 | O |
| **A19.10** | Need archive creation remotely | Open seedbox file manager → compress or checksum chosen folder → download/share result | F15.12, F03.11, F03.13 | O |
| **A19.11** | Want one-click server stack | Choose provisioning dashboard package → install service → validate permission/port | F15.13, F11.16, F11.13 | R |
| **A19.12** | Need resumable server-to-server migration | Plan copy with checksums → resume interrupted transfer → confirm integrity | F15.14, F11.14, F03.21 | R |

### A20 · Ending, suspending, retiring and resuming later

**Activity character:** administrative, custodial, operational. **Typical actor/tools and evidence:** client, storage, host dashboard, backup; S05 S09 S17 S35 S36. **Temporal pattern:** per-item, periodic, exceptional.

| ID | Situation / goal | Action sequence and desired exit | Feature-family cross-links | Cadence |
|---|---|---|---|---|
| **A20.01** | Done with interface but not job | Close window/remote UI → daemon remains active → reenter for check later | F10.01, F02.10, F14.07 | F |
| **A20.02** | Need temporary pause of all activity | Stop selected/all jobs → leave bytes on disk → restart when condition changes | F02.02, F02.04, F02.18 | O |
| **A20.03** | Torrent reached share obligation | Verify ratio/time and tracker state → stop job while keeping payload | F05.06, F02.14, F13.05 | O |
| **A20.04** | Want to hide historical item | Choose filter/archive/remove registration → verify whether data still exists | F07.03, F02.03, F02.18 | O |
| **A20.05** | Want to reclaim disk | Inspect dependence and backups → delete chosen actual files with explicit consent → check free space | F03.22, F13.11, F03.18 | O |
| **A20.06** | Need to retire auto-downloading rule | Disable feed action → keep past jobs → confirm no new unintended submissions | F06.05, F12.02, F12.14 | O |
| **A20.07** | Stop hosting a seedbox but keep collection | Back up config and transfer data → verify local copies → deprovision service | F11.12, F11.14, F11.01 | R |
| **A20.08** | Replace old device with another | Export profile and session metadata → restore/test → revoke obsolete access | F10.13, F02.17, F13.07 | R |
| **A20.09** | Need to delete only metadata | Remove job while preserving shared bytes → confirm remaining hardlinks/references | F02.03, F03.20, F02.18 | O |
| **A20.10** | Delete original .torrent file after import | Check client saved job metadata → remove descriptor copy only if no needed provenance | F01.11, F02.17, F13.11 | O |
| **A20.11** | Leave long-term seed unattended | Check disk and network sustainability → retain active daemon → set alerts for exceptions | F04.16, F12.06, F11.11 | C |
| **A20.12** | A task is intentionally abandoned | Choose cancel versus pause or delete → verify intended final state and dependent automation | F02.18, F13.13, F12.13 | O |

## 20. Relationship vocabulary for the eventual conceptual map

The intent is a **typed, many-to-many activity graph**, not a tree of screens or a flat checklist of feature names. Useful node kinds include:

| Node kind | What it represents | Example |
|---|---|---|
| G: human goal | A desired outcome independent of UI | Play a chosen video without sacrificing a seed |
| T: trigger | What causes a session or intervention | Friend sends a magnet; quota warning appears |
| J: macro journey | A composition of several activities | Seedbox-to-local media retrieval (J16) |
| A: micro-workflow | Reusable action with a meaningful exit state | Retrieve one remote file (A10.08) |
| P: actor/persona-context | Who or what initiates the action | Casual phone user, publisher, scheduled rule |
| E: execution environment | Where an operation *runs* | Phone engine, hosted rTorrent, NAS, remote file daemon |
| O: object/artifact | What changes or remains | Metainfo, job registration, content bytes, hardlink, credential |
| S: observed state | What can actually be established | Job queued, file hash checked, library playback succeeded |
| C: constraint | Conditions and boundaries | Disk quota, private tracker rule, Android SAF, filesystem mount |
| X: failure or uncertainty | Why the desired transition may not occur | RPC timeout, missing path, no peers, import failed |
| F: feature/capability | Product affordance in the independent 240-feature catalogue | File-priority editing (F03.03) |
| V: verification/evidence | How an outcome is checked | Readback RPC, on-disk checksum, remote SFTP listing |
| R: resource | What is budgeted or preserved | Network data, storage blocks, money, attention, seeding time |
| L: lifecycle phase | Before/during/after with looping and overlap | Discovery, acquisition, delivery, long-term stewardship |

**Core edge labels and example semantics:**

- `goal →motivates→ journey/activity`; `trigger →initiates→ activity`.
- `journey →contains→ activity`; `activity →decomposes_into→ smaller actions`; `activity →precedes→ activity` (**only if ordering is necessary**).
- `activity →requires→ state/permission/resource`; `activity →optional_after→ activity`; `activity →alternates_with→ activity`.
- `activity →uses_capability→ F`; `F →exposes_state_of→ O`; `activity →operates_on→ O`.
- `actor →delegates_to→ daemon/remote/automation`; `activity →executes_at→ E`; `activity →observed_from→ E`.
- `activity →produces→ O/S`; `activity →verified_by→ V`; `activity →may_fail_as→ X`; `X →recovered_by→ A`.
- `activity →recurs_after→ time/event`; `activity →continues_while→ background state`; `activity →interrupts→ activity`.
- `job →references→ payload`; `file path →resolves_to→ stored bytes`; `library item →uses→ same bytes through a link/copy`. **Reference, copy and shared bytes are different edge types.**
- `setting →scoped_to→ engine/session/profile/job/file`; `command →acknowledged_by→ daemon`; `requested state →not_yet_verified_as→ observed state` when appropriate.

**One example with typed relationships:** `G: watch a movie at home` → motivates → `J16 remote-to-local retrieval` → contains → `A08.04 submit remote` → executes at → `seedbox` → produces → `remote torrent job` → references → `remote payload`; `A10.08 retrieve file` → executes via → `SFTP` → produces → `local copy` → verified by → `file read/playback`; `A05.01 keep sharing` → continues while → `local copy is consumed`. This graph avoids the common error of attaching a remote torrent action directly to phone-local file storage.

**Causality and evidence:** A documented API capability supports an `A →uses_capability→ F` hypothesis. It does not establish that users commonly take that path, that every frontend exposes the operation, or that any specific product is safe to perform it in all circumstances.

## 21. Composition examples and crosswalk from the 36 original journeys

The original 36 macro journeys are retained intact in Section 6 because they show credible narratives. Here are **example memberships**, not exhaustive or mandatory action sequences. Within each list, order is approximate: many tasks may occur concurrently, be omitted or repeat. The main point is that the same A-series unit is reused across many J-series purposes.

| Macro journey | Reusable activity units |
|---|---|
| **J01** | A01.01, A02.02, A02.07, A04.01, A20.01 |
| **J02** | A01.04, A03.01, A03.02, A03.06, A20.04 |
| **J03** | A01.05, A03.03, A03.04, A12.01, A04.09 |
| **J04** | A08.04, A02.01, A08.06, A04.01, A20.01 |
| **J05** | A08.04, A08.01, A02.07, A10.01, A20.01 |
| **J06** | A04.01, A04.02, A18.03, A20.01 |
| **J07** | A04.12, A20.01, A05.01 |
| **J08** | A17.04, A04.04, A17.03 |
| **J09** | A04.07, A15.02, A06.01, A15.07, A04.03 |
| **J10** | A05.01, A05.03, A05.12, A20.03 |
| **J11** | A05.04, A05.09, A16.09, A20.11 |
| **J12** | A05.02, A14.05, A17.09, A05.03 |
| **J13** | A05.10, A16.07, A03.12, A05.07 |
| **J14** | A01.02, A02.02, A10.03, A12.02, A12.06 |
| **J15** | A11.04, A11.02, A11.07, A12.02, A12.12 |
| **J16** | A10.01, A10.08, A10.09, A20.11 |
| **J17** | A10.09, A12.02, A11.07 |
| **J18** | A12.12, A16.06, A17.02, A05.01 |
| **J19** | A10.02, A10.03, A16.02, A15.07 |
| **J20** | A16.01, A16.02, A16.11, A15.07 |
| **J21** | A17.01, A17.02, A10.10, A20.05 |
| **J22** | A09.11, A17.08, A17.12, A20.07 |
| **J23** | A09.01, A08.01, A09.04, A02.02 |
| **J24** | A15.01, A09.02, A09.03, A14.04 |
| **J25** | A13.01, A13.02, A13.07, A13.10 |
| **J26** | A13.02, A13.07, A19.10, A05.11 |
| **J27** | A13.08, A13.06, A01.01, A05.11 |
| **J28** | A01.01, A13.08, A16.09, A05.09 |
| **J29** | A01.04, A03.02, A03.10, A12.05 |
| **J30** | A11.01, A11.02, A11.03, A11.09 |
| **J31** | A04.02, A04.08, A07.07, A18.02 |
| **J32** | A16.04, A16.05, A16.09, A20.08 |
| **J33** | A08.02, A08.03, A08.06, A17.10 |
| **J34** | A16.10, A04.11, A15.12, A16.03 |
| **J35** | A11.11, A11.09, A15.04, A09.10 |
| **J36** | A20.03, A20.05, A20.07, A20.09 |

### 21.1 Cross-cutting activity loops that a linear feature map would miss

- **Observe → decide → change → observe again:** A04.01 → A04.05 → A04.01. Can run many times within one torrent, and no mutation may be needed.
- **Find → choose → defer → later add:** A01.02 → A01.08 → A02.06 → A04.03. Discovery and acquisition are not obligatorily contiguous.
- **Acquire → consume while seeding → maintain:** A02.01 → A03.04 → A12.01 while A05.01 runs concurrently → A20.11.
- **Automated intake with exception recovery:** A11.01 → A11.02 → A02.05 → A11.07; error branches to A11.09 or A15.12.
- **Move files without removing job:** A10.02 → A16.02 → A15.07. A backing path change affects session integrity.
- **Remove job but retain payload:** A20.09 → A17.02. Separate from A20.05, which intends to reclaim disk.
- **Migrate a service while preserving data:** A16.09 → A16.05 → A09.01 → A08.01 → A20.07.
- **Publish and remain available:** A13.02 → A13.07 → A13.10 ↔ A05.09; distribution outlives metadata creation.
- **Change which machine is in control:** A08.06 → A08.02 → A04.01; switching view is not equivalent to moving a job.
- **Resource alarm interrupts other goals:** A17.01 interrupts A05.04 and A11.01; decision may lead to A20.05, A17.12 or deferral.
- **Cross-seed without downloading duplicate content:** A16.07 → A05.10 → A05.07 while A17.02 observes storage references.
- **Stop now, resume someday:** A20.02 → unattended interval → A04.03. “Leave app” is distinct from stop/pause.

## 22. Connecting the activity atlas back to the 240 external features

The feature reference contains 15 families and 240 numbered entries. Activity families deliberately **cross** feature families; the following is a navigation index, not a one-to-one allocation or a claim of exhaustive pairwise edges.

| Feature family in inventory | Primary activity clusters | Important cross-cutting associations |
|---|---|---|
| F01 Acquisition/intake/publication | A01 A02 A13 | A08 remote add; A16 imported existing data |
| F02 Lifecycle/queue | A02 A04 A05 A20 | A11 automation; A16 migration |
| F03 File and storage | A03 A10 A16 A17 | A12 media; A20 retirement |
| F04 Swarm/protocol | A05 A06 A13 A15 | A14 privacy; A19 experimental modes |
| F05 Bandwidth/policies | A04 A05 A17 | A06 peer settings; A20 seeding end |
| F06 Search/discovery/RSS | A01 A11 A13 | A05 cross-seeding; A12 library |
| F07 Organization/metadata | A07 A04 | A10 file naming; A02 initial classification |
| F08 Diagnostics | A04 A05 A15 | A06 swarm details; A16 recovery |
| F09 Media/intelligence | A03 A12 | A19 media plugins; A10 extraction |
| F10 Remote/multi-instance | A08 A09 | A02 add; A18 UI; A14 trust |
| F11 Seedbox admin | A09 A17 | A08 connectivity; A20 deprovision |
| F12 Automation/event | A11 A04 | A12 library; A05 stewardship |
| F13 Privacy/security | A14 A20 | A06 peer policy; A09 roles; A16 audit |
| F14 UX/accessibility | A18 A04 | A08 mobile remotes; A07 displays |
| F15 Specialized | A19 A13 A05 | A10 archives; A16 cross-server migration |

### 22.1 A map compiler must distinguish these layers

**User action ≠ product feature:** “Make episode 1 watchable sooner” (A03.04) may be enabled by file priority, sequential pieces and a player; none of those features alone is the user's goal.

**Generic feature ≠ exact capability in every environment:** F03.06 (rename backing file) can be native on one engine, provided by a plugin on a seedbox, or absent from a particular remote. An activity node can be relevant even when its implementation differs.

**Visible confirmation ≠ completed effect:** A08.04 might only establish that the daemon accepted an Add command; A10.08 requires independently observing local bytes. Keeping those as different states prevents incorrect edges.

**Jobs ≠ bytes ≠ references:** A16.04 intentionally moves registrations without moving payloads, while A16.05 moves both. A12.12 and A16.07 can increase visible entries without proportionally increasing storage blocks.

**Frequency ≠ importance:** A04.01 (glance) can happen constantly with almost zero harm; A20.05 (delete payload) may happen rarely with high consequences.

**Architecture ≠ user task:** TCP, DHT, RPC method version and piece allocation are often enabling mechanisms or diagnostic knobs, not consciously named objectives. Their feature nodes attach to human outcomes and to technical constraints, rather than being treated as independent goals.

### 22.2 Mapping checklist (for later conceptual-map authoring, not a product plan)

To translate the current two research references into a credible conceptual graph, each activity should retain: a **stable A ID**, a **goal and trigger**, one or more **actor/tool roles**, the **object being acted upon**, a **causal sequence** only where meaningful, **start and verified exit states**, **failure paths**, **work character**, **relative cadence**, relevant **F references**, and available **S evidence**. Additionally, every feature should either link to an explicit A activity, to a low-level enabling mechanism, or be marked *not yet explained by the observed activities*. Do not invent user behavior merely to make every feature have an incoming edge.

The A-series entries intentionally function as **candidate graph nodes** rather than hidden requirements, screen wireframes, UX recommendations, or implementation tickets.

## 23. Research limitations and next possible evidence

- The original 36 J-series journeys and 240 A-series micro-workflows are **constructed scenarios**, synthesized from sources; no claim is made that each exact ordered sequence was witnessed end-to-end.
- No user interviews, in-product analytics, diary studies or controlled task observation were conducted. Frequency terms must remain qualitative.
- Manuals show supported operations, not user intent or relative popularity. Forum discussions overrepresent troubleshooting and advanced hobbyists.
- A source can be dated or specific to a provider, plugin, client version, tracker policy or integration configuration; do not universalize it.
- The universe of BitTorrent use is wider than media downloading. Academic, archival, creative, software-distribution and local/community sharing are included deliberately.
- No behavioral analysis establishes legal permission to access or republish particular material. The protocol is neutral; content provenance and rules remain context-dependent.
- The document intentionally makes no current UI implementation, requirements, priority, or competitive-parity assertions about any application in this repository.

## 24. Source register

Sources are grouped by evidence type via the label in each entry. S37–S45 were added to ground the deeper activity atlas. URL links point to underlying product documentation or individual discussion threads; they are not a substitute for direct field observation. Synthesis entries above reference codes.

- **S01 · BitTorrent support: importing torrents and magnet links** — [Source](https://help.bittorrent.com/en/support/solutions/articles/29000022483-how-do-i-add-a-torrent-) · *Vendor help*. Evidence: Entry methods, metadata retrieval, destination and starting a job.
- **S02 · Ubuntu: BitTorrent alternative downloads** — [Source](https://ubuntu.com/download/alternative-downloads) · *Publisher workflow*. Evidence: Legitimate large-file distribution, end-user download motivation.
- **S03 · Internet Archive: Archive BitTorrents** — [Source](https://archivesupport.zendesk.com/hc/en-us/articles/360004715251-Archive-BitTorrents) · *Archive operating guide*. Evidence: Finding collections, partial selection, web seeds, contribution and publication.
- **S04 · Academic Torrents: getting started** — [Source](https://academictorrents.com/docs/getting-started.html) · *Research platform guide*. Evidence: Dataset discovery, distribution, persistence and preservation goals.
- **S05 · Transmission: headless usage** — [Source](https://github.com/transmission/transmission/blob/main/docs/Headless-Usage.md) · *Primary developer documentation*. Evidence: Background daemon, adding/listing/checking/managing jobs through RPC.
- **S06 · Transmission: web interface walkthrough** — [Source](https://github.com/transmission/transmission/blob/main/docs/Web-Interface.md) · *Primary developer documentation*. Evidence: Remote add and control sequence, save paths and per-transfer details.
- **S07 · Transdroid: features and supported clients** — [Source](https://transdroid.org/about/) · *Developer product documentation*. Evidence: Mobile remote workflows: add, search, RSS, move, label and notify.
- **S08 · nzb360: Android media server manager** — [Source](https://nzb360.com/) · *Developer product documentation*. Evidence: Combined search, downloaders, media library, requests and server oversight.
- **S09 · Ultra.cc: User Control Panel tour** — [Source](https://docs.ultra.cc/control-panel/user-control-panel-tour) · *Provider operations manual*. Evidence: Seedbox lifecycle, credentials, services, installs, controls and traffic.
- **S10 · Ultra.cc: download files from service** — [Source](https://docs.ultra.cc/misc-guides/download-from-your-ultra-service) · *Provider operations manual*. Evidence: FTP/SFTP, LFTP, HTTP, WebDAV and rclone choices, transport costs.
- **S11 · Seedhost.eu: ruTorrent File Manager** — [Source](https://www.seedhost.eu/client-area/knowledgebase/82/File-Manager-plugin-into-ruTorrent.html) · *Provider operating guide*. Evidence: Remote browsing, moving, renaming, archiving, checksums and screenshots.
- **S12 · Seedhost.eu: creating a torrent in ruTorrent** — [Source](https://www.seedhost.eu/client-area/knowledgebase/77/Creating-a-torrent-through-ruTorrent.html) · *Provider workflow guide*. Evidence: Publishing order, tracker options, metafile generation and upload.
- **S13 · Sonarr: FAQ, torrent process** — [Source](https://wiki.servarr.com/sonarr/faq) · *Maintainer operations documentation*. Evidence: Category-based monitoring, completed import, seeding-preserving copy/hardlink.
- **S14 · Sonarr: quick start guide** — [Source](https://wiki.servarr.com/sonarr/quick-start-guide) · *Maintainer setup guide*. Evidence: Indexer-client-storage-library configuration and naming.
- **S15 · cross-seed: getting started** — [Source](https://www.cross-seed.org/docs/basics/getting-started) · *Maintainer operations documentation*. Evidence: Multi-tracker retention and shared existing payloads.
- **S16 · cross-seed: tracker impact** — [Source](https://www.cross-seed.org/docs/reference/tracker-impact) · *Maintainer policy/operations guide*. Evidence: Preservation benefits and indexer/tracker load externalities.
- **S17 · Ultra.cc: FAQ** — [Source](https://docs.ultra.cc/getting-started/faq) · *Provider FAQ*. Evidence: Quota pressure, repairs, fair use and third-party storage integration.
- **S18 · Ultra.cc: configuring mobile apps** — [Source](https://docs.ultra.cc/misc-guides/mobile-apps) · *Provider configuration guide*. Evidence: App-specific remote paths, auth, lockouts and profile setup.
- **S19 · Seedhost.eu: autobrr configuration** — [Source](https://www.seedhost.eu/client-area/knowledgebase/291/autobrr-basic-configuration.html) · *Provider setup guide*. Evidence: Indexer intake and credentialed client dispatch.
- **S20 · Community: simplifying a tracker → seedbox → Plex workflow** — [Source](https://www.reddit.com/r/qBittorrent/comments/1qu8kvb/suggestions_to_simplify_my_torrent_workflow/) · *Qualitative user account*. Evidence: Manual multi-site handoffs and desired one-tap end-to-end experience.
- **S21 · Community: adding remote torrents from a phone** — [Source](https://www.reddit.com/r/torrents/comments/1fc0wvp) · *Qualitative user discussion*. Evidence: Out-of-home intent, VPN/remote desktop/watch-folder and app options.
- **S22 · Community: seedbox to local NAS and media-library workflow** — [Source](https://www.reddit.com/r/seedboxes/comments/1pip5xl/is_there_a_default_workflow_when_using_a_home/) · *Qualitative user discussion*. Evidence: Remote seeding versus local consumption; manual and automated arrangements.
- **S23 · Community: seedbox files copied with FileZilla to home server** — [Source](https://www.reddit.com/r/seedboxes/comments/1igylae) · *Qualitative user account*. Evidence: Sequence from manual .torrent addition to remote completion to local copy.
- **S24 · Community: filtering long lists of seeding torrents** — [Source](https://www.reddit.com/r/qBittorrent/comments/1j6qjmn) · *Qualitative user discussion*. Evidence: Attention management, status filters, categories/tags and high-count sessions.
- **S25 · Community: seedbox retention versus ratio racing** — [Source](https://www.reddit.com/r/seedboxes/comments/lekeb6) · *Qualitative debate*. Evidence: Distinct motives: convenience, sharing, credit and long-term availability.
- **S26 · Community: difficulty maintaining private tracker ratios** — [Source](https://www.reddit.com/r/torrents/comments/1tlhhoe/how_do_i_possibly_retain_seed_ratios/) · *Qualitative user discussion*. Evidence: Seed credits, automated intake, low demand, competing objectives.
- **S27 · Community: recovering a seeding job after file relocation** — [Source](https://www.reddit.com/r/qBittorrent/comments/1ihc41c) · *Qualitative troubleshooting*. Evidence: Path changes, recheck and resuming sharing.
- **S28 · Community: troubleshooting stalled downloads** — [Source](https://www.reddit.com/r/qBittorrent/comments/1sivrsi/stalled_on_torrents_that_are_heavily_seeded_but/) · *Qualitative troubleshooting*. Evidence: Separating swarm availability from local client/network faults.
- **S29 · Community: tracker reuse and hardlinked cross-seeding** — [Source](https://www.reddit.com/r/trackers/comments/1tijwq2/psa_if_you_are_in_any_private_trackers_start/) · *Qualitative technical discussion*. Evidence: One payload serving several tracker jobs and differing seed goals.
- **S30 · Community: remote self-hosted torrent interfaces** — [Source](https://www.reddit.com/r/selfhosted/comments/1q14uf7/selfhosted_remote_torrent_client/) · *Qualitative discussion*. Evidence: Browser, Android and native remote approaches.
- **S31 · Ultra.cc: connect via FTP/SFTP** — [Source](https://docs.ultra.cc/connection-details/ftp/recommended-ftp-clients) · *Provider guide*. Evidence: Authenticated local retrieval and interrupted-transfer resumption.
- **S32 · Ultra.cc: Syncthing setup** — [Source](https://docs.ultra.cc/applications/syncthing) · *Provider setup guide*. Evidence: Continuous server-to-device file synchronization.
- **S33 · Seedhost.eu: applications catalog** — [Source](https://www.seedhost.eu/client-area/knowledgebase/303/List-of-available-apps.html) · *Provider operations catalogue*. Evidence: Coexisting torrent, library, search, automation and host-management apps.
- **S34 · Internet Archive / Academic Torrents purpose and dissemination** — [Source](https://academictorrents.com/docs/about.html) · *Publisher mission statement*. Evidence: Preservation, open research and community dissemination.
- **S35 · BitTorrent support: seeding** — [Source](https://www.bittorrent.com/en/support/solutions/articles/29000023347-what-is-seeding-/) · *Vendor help*. Evidence: Post-download sharing and ratio concept.
- **S36 · Community: hardlink-aware migration between clients** — [Source](https://www.reddit.com/r/trackers/comments/1sp7qr2/migrating_bt_clients_best_practices/) · *Qualitative migration discussion*. Evidence: Large-session moves and tracker identity/overlap concerns.

---

- **S37 · Transmission remote operations/RPC state** — [Source](https://github.com/transmission/transmission/blob/main/docs/rpc-spec.md) · *Corroborating technical, operational or user-account reference*. Evidence: Primary RPC reference; torrent actions, file selection, queues, bandwidth and diagnostics.
- **S38 · TRaSH Guides: hardlinks and instant moves** — [Source](https://trash-guides.info/File-and-Folder-Structure/Hardlinks-and-Instant-Moves/) · *Corroborating technical, operational or user-account reference*. Evidence: Operational guide; storage ownership, same-volume linking, media import and cleanup semantics.
- **S39 · cross-seed: linking tutorial** — [Source](https://www.cross-seed.org/docs/tutorials/linking) · *Corroborating technical, operational or user-account reference*. Evidence: Maintainer guide; hardlink/symlink/reflink differences and path requirements.
- **S40 · cross-seed: data-based matching tutorial** — [Source](https://www.cross-seed.org/docs/tutorials/data-based-matching) · *Corroborating technical, operational or user-account reference*. Evidence: Maintainer guide; renamed data, partial matching and indexing existing files.
- **S41 · Community: sharing large original video with friend** — [Source](https://www.reddit.com/r/torrents/comments/1i69v08) · *Corroborating technical, operational or user-account reference*. Evidence: User-described motivation; public/private sharing, embedded tracker and alternative transport decisions.
- **S42 · Ultra.cc User Control Panel** — [Source](https://docs.ultra.cc/control-panel/user-control-panel-tour) · *Corroborating technical, operational or user-account reference*. Evidence: Provider manual; service install, control, recovery, ports, credentials and monitoring.
- **S43 · qBittorrent 5 WebUI API** — [Source](https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-%28qBittorrent-5.0%29) · *Corroborating technical, operational or user-account reference*. Evidence: Primary API reference; torrent management, tags, rename, RSS, search, remote state.
- **S44 · qbit_manage configuration and housekeeping rules** — [Source](https://github.com/StuffAnThings/qbit_manage/wiki/Config-Setup) · *Corroborating technical, operational or user-account reference*. Evidence: Maintainer guide; orphan-file investigation, automation and cleanup guardrails.
- **S45 · LibreTorrent Android README** — [Source](https://github.com/proninyaroslav/libretorrent) · *Corroborating technical, operational or user-account reference*. Evidence: Primary project reference; phone-specific power, storage, RSS and scheduling.

**Document boundary:** Standalone external ecosystem/user-activity reference only. It neither amends nor ranks product requirements, compares an application to competitors, creates tasks/issues, nor claims a software capability has been implemented.
