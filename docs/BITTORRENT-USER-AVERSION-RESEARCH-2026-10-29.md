# BitTorrent aversion and user-friction atlas
**Research snapshot:** 2026-10-29 · **Status:** non-normative, qualitative primary-voice research · **Scope:** BitTorrent use, non-use, abandonment, remote clients, seedboxes, tracker communities, and adjacent workflows.

**Purpose.** Capture why people dislike, avoid, stop, or begrudgingly tolerate BitTorrent, with enough specificity to inform the Chunks feature inventory and user-activity conceptual map. The complaints are *evidence of user experience*, not diagnoses of a protocol flaw, population frequencies, or automatically authorized product requirements. See [RESEARCH](RESEARCH.md) and [documentation authority](DOCUMENTATION-STANDARD.md).

## How the evidence was collected and interpreted

Searches deliberately included first-person opposition (`hate torrents`, `don't torrent because`, `stopped torrenting`), comparative alternatives (DDL, streaming, Usenet), and situated complaints (`stuck at 99.9%`, `no upload`, `cannot rename and seed`, tracker ratios, seedbox configuration). Searched community discussions, product reviews, GitHub bug/feature reports, native-language forums, and accounts of non-use, not just product marketing. Some searches only yielded secondhand descriptions or guides; those were not counted as first-person evidence.

Native/colloquial queries covered **English, Spanish, Portuguese, French, German, Russian, Simplified Chinese, Turkish, Indonesian**, and exploratory **Japanese, Korean, Hindi, Arabic, Polish, and Italian**. Examples: `odio los torrents porque`, `odeio torrent porque`, `je n'utilise pas les torrents`, `torrent j'ai la flemme`, `ich hasse Torrents`, `торренты не люблю потому что`, `为什么很多人用 BT 屏蔽上传`, `torrent sevmiyorum`, `torrent gak seed lemot`, `토렌트 싫은 이유`, `トレント 使わない 理由`, `التورنت لا أستخدم بسبب`. **Clear native-language first-person evidence was located in ES, PT, FR, DE, RU, ZH, TR and ID**; a broad search in the other exploratory languages did not yield comparable reliable examples in this pass. English/Hinglish discussion from India is separately represented. Language coverage is a search method, not a representative sample.

**Coding:** **R = recurrent**: independently voiced in multiple sources/contexts in this sample; **D = distinctive**: unusually revealing first-person example, possibly only one; **M = mixed/misconception**: voiced concern that may be inaccurate or vary by jurisdiction, network, program, or tracker. Evidence links identify **S** entries below. Related complaints are intentionally separated when they imply different user goals or remedies. One thread can support several factors but does not count as several independent respondents. This is purposive qualitative sampling, with large English/Reddit and enthusiast-community selection biases. Vote scores, recency, and search rank are **not** population prevalence estimates. Users who never try torrents are underrepresented.

## Most consequential synthesis

Four abandonment mechanisms appear repeatedly: (1) *risk and uncertainty*, including legal notices, malware and unknowable VPN protection; (2) *unreliable attainment*, where finding a torrent is not assurance that the desired file can ever finish; (3) *ongoing obligations*, such as seeding, ratio, storage, device uptime, bandwidth and tracker surveillance; (4) *fragile ownership/workflows*, where normal renaming, moving, playback or media-library management destroys seeding continuity. [S01][S03][S06][S07][S13][S14][S17][S21][S22][S23][S26][S32][S34][S37]

The strongest counterintuitive lesson: **“I cannot seed” often means “I am prevented from maintaining a normal life or file library while seeding,” not unwillingness to contribute.** Another: **“slow” is a catchall user-visible failure state**, spanning absent data, no peer connectivity, blocked metadata exchange, queue settings, congested uplink, poor hosting and failing disks; an undifferentiated spinner produces helpless experimentation. [S06][S10][S15][S19][S24][S28][S34][S35]

## Coded factor inventory

### A. Initial refusal, perceived risk, and trust

| ID | Code | Distinct pain or resistance | Evidence | User goal / design implication (hypothesis, not requirement) |
|---|---|---|---|---|
| A01 | R | Copyright/ISP notices, threatened fines or takedown anxiety deter participation or cause abandonment | S01 S02 S03 S04 S05 S29 | Explain visibility and environment-specific risk without legal guarantees |
| A02 | R | Fear that ordinary torrent downloading exposes the household IP and identity to a swarm | S01 S03 S05 S16 | Make privacy state clear; never imply anonymity by default |
| A03 | R | VPN necessity, ongoing price, interface binding and kill-switch failures feel like a second specialized system | S02 S03 S06 S16 S35 | Diagnose interface changes and disclose traffic route without magical safety claims |
| A04 | R | Malware, spoofed uploads, suspicious executables and dubious index quality defeat trust | S07 S08 S09 S43 | File type, provenance and trust cues; warnings without asserting safety |
| A05 | D | User distrusts torrent software itself after security concerns or regression stories | S09 S40 | Keep the client transparent, minimal and predictable |
| A06 | D | Ethical objections to unauthorized distribution are a genuine reason to refuse torrenting altogether | S30 S46 | Support lawful user-shared content without treating all nonusers as prospective pirates |
| A07 | R | Perceived technical initiation cost: VPNs, ports, Docker, NAS, trackers, folders, codecs, ratios | S10 S11 S16 S36 | A straightforward ordinary path and layered guidance |
| A08 | R | Hostile, opaque or exclusionary communities make beginners reluctant to ask | S11 S12 S31 | Teach concepts in product language and avoid shaming |
| A09 | M | Users conflate BitTorrent protocol, torrent indexes, a specific client, and piracy in their assessment of risk | S08 S12 S30 S40 | Clearly distinguish transport, content source, app and legal context |

### B. Finding a usable and desirable release

| ID | Code | Distinct pain or resistance | Evidence | User goal / design implication |
|---|---|---|---|---|
| B01 | R | Cannot find a trusted source/index without dead domains, mirrors or misleading results | S09 S12 S31 S42 | Source provenance, user-controlled provider handoffs; no invented search guarantees |
| B02 | R | Scarcity of seeds for older, niche, regional or obscure works | S13 S14 S15 S31 S42 | Explain observed swarm availability, alternate source searches and honest waiting |
| B03 | R | Advertised seed counts do not match available complete peers; apparent abundance is misleading | S14 S15 S37 | Separate website claims from observed connected peers and piece availability |
| B04 | R | Wrong episode, unwanted release, variant, codec or language discovered *after* a lengthy download | S41 S42 S51 | Preview contents, full filenames and media attributes *before* commitment |
| B05 | D | Regional-language subtitles, dubbing or desired audio editions are unavailable even where seeds exist | S31 S42 | Expose track/release metadata where knowable; avoid claiming a file exists |
| B06 | D | Private trackers become chokepoints: site shutdown, changed limits and access obstacles | S12 S31 S32 | Keep transfer definitions/export independent of an index site's longevity |
| B07 | R | Users cannot distinguish a magnet URI, a .torrent file, the actual payload, and a search/result page | S19 S50 S56 | Conceptual onboarding through the intended action |
| B08 | D | Better competing methods exist for the user's immediate objective: one-click direct files, streaming, Usenet, personal media services | S19 S20 S31 | Judge Chunks by time-to-usable-content, not only transfer throughput |

### C. Progress, speed and recoverability

| ID | Code | Distinct pain or resistance | Evidence | User goal / design implication |
|---|---|---|---|---|
| C01 | R | Download indefinitely stranded at 99–99.9%, sometimes after tens of gigabytes | S13 S14 S15 S17 | Piece-availability diagnosis; distinguish useful completed files from torrent-wide completion |
| C02 | R | A missing tiny auxiliary piece prevents “100%” despite main media being playable | S14 S15 S17 | File-by-file completeness and safe usable-file indicators; explain v1 piece overlap |
| C03 | R | Magnet stays at “downloading metadata”; user cannot even inspect the file list | S19 S20 S35 | Show metadata-acquisition progress, reachability and alternate metainfo options |
| C04 | R | “Stalled,” “connecting,” “slow,” and “error” give insufficient causal information | S10 S17 S19 S35 S44 | Context-specific diagnosis rather than generic retry advice |
| C05 | R | One download works while another does not, so users cannot tell whether device, tracker, torrent, VPN or ISP is at fault | S19 S20 S35 | Layered network/source/peer/disk diagnostic model |
| C06 | R | Throughput swings wildly or stays far below advertised bandwidth, including uploading faster than downloading | S21 S24 S39 S42 | Report bottleneck and units honestly; do not imply guaranteed speed |
| C07 | D | Restarting the app or pausing for hours sometimes “fixes” transfers but gives no explanation | S20 S35 | Surface reannounce/reconnect behavior and eventual outcome |
| C08 | D | Client update, firewall rule or VPN adapter-name change silently breaks previously working magnets | S19 S35 | Network-binding validity check, actionable reauthorization, safe fail-closed behavior |
| C09 | D | Defective/blocked download or write looks like a torrent bug but is a permission/antivirus/disk error | S22 S44 | Evidence-specific diagnostics and integrity-preserving repair |
| C10 | D | A swarm shows an apparently complete peer that repeatedly appears but cannot actually connect | S37 | Mark connectivity vs full-copy sightings as separate signals |

### D. Sharing obligations and resource economics

| ID | Code | Distinct pain or resistance | Evidence | User goal / design implication |
|---|---|---|---|---|
| D01 | R | Seeding saturates limited upstream, breaking browsing or shared household Internet | S24 S25 S26 S27 S28 | Safe adaptive uplink limits and per-network scheduling |
| D02 | R | Data/FUP caps and metered connections turn ongoing seeding into unexpected bills or slowdowns | S25 S27 S28 | Accounting, limits, network-specific rules, proactive alerts |
| D03 | R | The computer/phone must stay powered, online and awake long after the useful download ends | S24 S26 S27 S33 | Explicit policies, maintenance windows, battery-friendly transparent states |
| D04 | R | Keeping payloads to seed consumes disk space, duplicates collections or blocks cleanup | S14 S26 S33 | Storage provenance, non-destructive cleanup and seeding continuity |
| D05 | D | Seeding feels competitive and futile when fast seedboxes monopolize upload opportunities | S34 S38 | Explain “no demand” versus blocked connectivity and time-based obligations |
| D06 | R | Private tracker ratio, hit-and-run, freeleech and minimum retention rules are confusing and threatening | S32 S33 S34 S38 S45 | User-entered tracker policy reminders, not unsupported scraper promises |
| D07 | D | Uploader must maintain unwanted or huge incomplete collections to avoid tracker sanctions | S45 | Show policy implications before starting/stopping/removing |
| D08 | R | Users want to contribute but have weak uplink or scarce bandwidth, creating guilt and moral pressure | S26 S28 S38 | Respect voluntary limits; do not moralize or shame |
| D09 | D | Some purposely suppress upload while downloading because earlier congestion and disk-wear experiences became habits | S24 | Explain impact and separate download-time upload from post-completion seeding |
| D10 | D | Private tracker completion status can disagree with “I can play the file” because omitted non-media files still count | S17 S38 | Show torrent-wide versus selected-file completion and tracker consequences |

### E. File management, data integrity and downstream use

| ID | Code | Distinct pain or resistance | Evidence | User goal / design implication |
|---|---|---|---|---|
| E01 | R | Renaming/moving a file after download breaks seeding or resume mapping | S18 S23 S36 S47 | Separate display alias, torrent mapping and physical filesystem path |
| E02 | R | Want to reorganize TV seasons/music genres without manually renaming hundreds of entries | S23 S47 | Previewed bulk restructuring while preserving content identity |
| E03 | D | Impossible/awkward to represent alternate internal folders with duplicate names | S23 | Avoid flattening real structure or assuming arbitrary renamed paths are reversible |
| E04 | D | Some filename/case collisions work initially and fail after restart, causing mysterious “missing files” | S48 | Platform-aware collision detection and persisted mapping tests |
| E05 | R | Users stop seeding so media managers can organize, rename and import files into their libraries | S23 S47 | Separate serving copy from managed torrent backing, or verified rebinding |
| E06 | D | Existing file migration to another client/seedbox requires manual .torrent/resume extraction and hash rechecks | S33 | Portable transfer-definition backup and guided restoration |
| E07 | D | “Delete torrent” versus “delete content” and trash versus permanent deletion are easily misunderstood | S49 | Deliberate scope, exact object preview, reversible paths when possible |
| E08 | D | Paid mobile client hides full filenames and shows unexplained remote warnings for users without a computer | S51 | Full metadata visibility; local-only and remote-only are complete, unobtrusive modes |
| E09 | D | Cannot rename at initial add after an upgrade, or client crashes while renaming | S52 | Pre-add naming/preflight and regression tests |
| E10 | D | Torrent packages that are fine as individual media files are unusable as incomplete solid archives | S13 | Distinguish playable individual files from extraction-required archives |

### F. Client, remote and seedbox ecosystems

| ID | Code | Distinct pain or resistance | Evidence | User goal / design implication |
|---|---|---|---|---|
| F01 | R | Client UI changes break themes, readability or familiar workflows without sensible fallback | S40 S51 | Stable accessibility, contrast and deliberate settings migration |
| F02 | D | Clashing software conventions cause uncertain statuses (“seeding” mistaken for not downloaded) | S50 | Explain downloading done / sharing ongoing in ordinary language |
| F03 | R | Seedboxes add financial cost, capped storage, FTP transfer and a second set of stateful tools | S33 S53 S54 | Make remote-to-phone actions clear, distinguish transfer job from payload |
| F04 | D | User leaves a seedbox because maintaining it takes more time than infrequent downloads justify | S33 | Zero-maintenance paths and graceful export/exit |
| F05 | D | Hosted seedbox speed/peering/disk conditions can be worse than expected | S54 | Expose host-side limits and evidence without guaranteed performance |
| F06 | D | Client connection setup is confusing even for technically capable users choosing a provider | S53 S55 | Connection discovery and guided fallback, no insecure guesses |
| F07 | R | Users struggle to create/share torrents because piece sizing, trackers, web seeds and source flags are cryptic | S56 S12 | Plain-language Create & Share and honest publication outcomes |
| F08 | D | People distrust torrent apps that overload users with advertising, opaque monetization or unsupported extras | S51 S57 | No forced paid/remote surface or deceptive status prompts |

## Short first-person evidence vignettes (paraphrased or translated)

These are *glosses*, not verbatim transcripts. Original language and first-person context matter:

1. **Spanish, 2013:** A downloader describes reaching about 80% only to lose the last seeder; moving a shared file also makes the torrent disappear from circulation. This frames swarm fragility as a betrayal of an apparent download promise. [S18]
2. **Portuguese, reporting a younger interviewee:** “I don't use torrent” because P2P participation seems traceable. That is a perception and jurisdiction-dependent legal belief, not legal guidance. [S01]
3. **German, 2011:** A user objects to uploading at roughly 500 KB/s while only receiving about 30 KB/s. The perceived reciprocity bargain feels unfair. [S21]
4. **French, 2025:** A private-tracker veteran describes abandoning P2P due to centralized gatekeeping, strict rules and rejected personal releases, despite valuing decentralized sharing. [S31]
5. **French, 2024:** A user has already abandoned a tracker after DNS/VPN/relogin obstacles, perceived arbitrary bans and extra payment. [S32]
6. **Chinese, 2018:** A user explains that earlier unrestricted BT upload rendered the web unusable and shaped a habit of stopping seeds even after bandwidth improved. [S24]
7. **Chinese, 2018:** A second participant observes that keeping files online costs electricity and drive life; they distinguish uploading while downloading from after completion. [S24]
8. **Turkish, 2025–2026:** One user says sites have vanished and usable seeds/subtitles are elusive; others stop torrenting because they fear malicious downloads damaging an expensive system or stealing credentials. [S42]
9. **Indonesian, 2026:** A seeder warns that forgetting background seeding under an ISP fair-use policy can exhaust the monthly allowance before month-end. [S25]
10. **English, 2025:** A highly experienced public seeder finds private tracker admission impenetrable because proof of membership in other private trackers is demanded before joining any. [S12]
11. **English, 2026:** A user moves/renames files, accidentally eliminates about a terabyte's worth of potential seeding, and only later learns that file organization and torrent mapping are coupled. [S36]
12. **English, 2026:** A user with a large, automated media setup feels unable to build ratio because popular seedboxes fulfill demand before their client can upload. [S34]

## Higher-order conceptual map for later work

Rather than a flat list of broken features, relate these complaints to the user's continuing objectives:

| Intended life outcome | Stages where friction emerges | Representative factors | What failure *feels like* |
|---|---|---|---|
| Obtain the **specific usable thing** | Locate → assess → start → verify → open | B01–B08, C01–C03, E10 | “I spent time and bandwidth and got nothing.” |
| Act **without becoming a network engineer** | Setup → connect → repair → update | A03, A07, C04–C09, F06 | “I have no idea which system is broken.” |
| Keep a working **ordinary phone/household network** | Active transfer → background → overnight | D01–D03, D09 | “Other things stop working when this runs.” |
| Preserve agency over **files I now own/control** | Rename → organize → play → backup → move | E01–E09 | “Tidying my files destroyed my ability to share.” |
| Avoid **threat, exposure and surprise costs** | Evaluate risk → join swarm → seed → exit | A01–A06, D02, F03 | “This could cost me more than the thing is worth.” |
| Participate sustainably **without obligations swallowing the benefit** | Tracker signup → seed → meet ratio → retire | D04–D08, F04 | “The reward comes with a second job.” |
| Publish or help others **without learning a subculture** | Create → announce → find peers → maintain | A08, B06, F07 | “I don't know what I am expected to do.” |

**Before:** search engines, indexes and social advice; evaluate legitimacy/quality, access, risk, VPN/provider options, storage and room for seeding. **During:** watch speed/availability, manage caps, defend other device activity, choose needed files, troubleshoot changing peers and protect privacy. **After:** open/media-library import, rename, extract, back up, continue seeding, meet tracker obligations, rehome files, export/migrate or deliberately retire a job without accidentally losing data. **Absence/avoidance is itself a workflow:** decide the risks and administrative burden are excessive and choose streaming, DDL, Usenet, physical media or doing without. [S01][S11][S18][S19][S23][S26][S31][S33][S34]

## Product-discovery hypotheses for Chunks (not owner-approved changes)

1. **Explain what is actually wrong.** A problem model should distinguish `metadata missing`, `no complete copy seen`, `complete copy seen but unreachable`, `network binding invalid`, `tracker rejected`, `not authorized to write`, `disk exhausted`, `queued/paused`, and `remote backend unavailable`. Show evidence, freshness and remediation boundaries. Never claim a dead torrent can be repaired by a button. [C01–C10]
2. **Let users seed and still organize.** Recovery from renamed/relocated backing files, batch media renames, collision-safe paths and transfer/payload separation directly address dropout caused by normal file ownership. Existing governing contracts: [transfer recovery](TRANSFER-FILE-RECOVERY.md), [Plex filename repair](PLEX-FILENAME-REPAIR.md), [storage and extraction](STORAGE-AND-EXTRACTION.md). [E01–E06]
3. **Make resources consentful and bounded.** Per-Wi-Fi/mobile profiles; bandwidth, battery, time and storage budgets; policy-aware stop/retire views. The research motivates tests and proposals, **not** unconditional new background permissions or autonomous deletion. [D01–D09]
4. **Treat local, remote and both as equally valid first-class experiences.** The mobile review objecting to forced remote functionality is a direct warning about imposing a mode a user never enabled. Existing governing contract: [unified environments](UNIFIED-ENVIRONMENTS.md). [E08][F03]
5. **Onboarding by outcome, terminology on demand.** Help a novice get a legitimate file or share their own, while keeping exact tracker, metadata, port and privacy controls discoverable. Existing contracts: [smart remote connection](SMART-REMOTE-CONNECTION.md) and [product scope](PRODUCT.md). [A07][B07][F06][F07]
6. **Capture the *exit* journey.** For any removal, disconnection, backup, move or provider abandonment, make explicit: transfer definition, payload bytes, seeding responsibilities, library copies, completion state and whether resumption remains possible. Existing [transfer snapshots](TRANSFER-SNAPSHOTS.md) and [cross-engine jobs](CROSS-ENGINE-TRANSFER-JOBS.md). [E06][E07][F04]
7. **Invest in preflight and outcome rather than spinner polish.** Before spending tens of gigabytes, show credible availability, selected files, space estimate and feasibility signals; after completion, show which files can be used, extracted or safely opened. [B01–B07][C01–C03]
8. **Do not code moral judgments.** Seeding may be impossible due to law, disability, weak connections, caps, power costs, or lack of demand. Explain consequences without classifying a person as a “leecher.” [D01–D09]

Suggested research follow-up (separate from engineering priority): targeted interviews or permissioned survey with *nonusers and abandoners*, particularly mobile-only users, low-bandwidth homes, non-English speakers, legitimate large-dataset publishers and remote-only seedbox users; ask for the exact *last action before quitting*. The current public-post sample cannot answer prevalence.

## Primary-source catalogue

Each link opens the original discussion/review/issue (first-person when marked **voice**; otherwise **context**). No source is a population representative survey.

| ID | Language / type | Specific evidence |
|---|---|---|
| S01 | PT · voice in interview | [Vice interview: avoids torrents due to being traceable](https://www.vice.com/pt/article/a-gen-z-esta-liderando-uma-nova-onda-de-pirataria-na-internet/) |
| S02 | EN · voice | [Stopped torrenting after repeated ISP notices despite VPN steps](https://www.reddit.com/r/Piracy/comments/1nf4p0e/what_am_i_doing_wrong_i_keep_getting_caught_by_my/) |
| S03 | EN · voices | [VPN binding is easily missed by beginners](https://www.reddit.com/r/Piracy/comments/1waqgiw/stupid_question_is_a_vpn_enough/) |
| S04 | FR · voices | [Fear of fines and notices when seeding](https://www.reddit.com/r/FrancePirate/comments/1ojbbus/a_quel_point_cest_risqu%C3%A9_le_torrenting_en_france/) |
| S05 | DE · voice | [Household account holder receives alleged BitTorrent copyright demand](https://www.reddit.com/r/de_EDV/comments/x0jc65/) |
| S06 | EN · voice | [ISP letters make BitTorrent not worth the hassle](https://www.reddit.com/r/HomeNetworking/comments/1g112nd/att_fiber_need_to_ditch_their_router/) |
| S07 | FR · voice | [User fears malicious downloaded files and prefers streaming/yt-dlp](https://www.reddit.com/r/FrancePirate/comments/1phh1aj/comment_faitesvous_pour_%C3%AAtre_serein_en/) |
| S08 | EN · voices | [Why people distrust a poorly moderated torrent index](https://www.reddit.com/r/Piracy/comments/16lvruh) |
| S09 | TR · voices | [Turkish torrent thread: client security and loss of trusted sources](https://eksisozluk.com/torrent--584130?p=59) |
| S10 | EN · voice | [Beginner overwhelmed by stack/network terminology](https://www.reddit.com/r/torrents/comments/1ag8orj/why_is_the_community_harsh_toward_beginners/) |
| S11 | EN · voices | [Comparing direct downloads vs torrents, setup friction](https://www.reddit.com/r/Piracy/comments/1wxg51h/life_of_a_pirate/) |
| S12 | EN · voice | [Veteran public seeder hates private-tracker admission barriers](https://www.reddit.com/r/trackers/comments/1lcka0u/private_trackers_i_think_i_hate_you/) |
| S13 | EN · voice | [Single missing seeder strands a solid 7z archive at 99.9%](https://www.reddit.com/r/torrents/comments/rpyy6x) |
| S14 | EN · voices | [99.9% near-complete; missing small file, disk pressure](https://www.reddit.com/r/torrents/comments/1hrihdi) |
| S15 | EN · voice | [Four months waiting on an elusive final 2 MiB](https://www.reddit.com/r/qBittorrent/comments/1twxmkx/this_is_really_frustrating/) |
| S16 | EN · voice | [First-time VPN user questions whether setup is enough](https://www.reddit.com/r/Piracy/comments/1waqgiw/stupid_question_is_a_vpn_enough/) |
| S17 | EN · voice | [Missing supplementary files and apparent incomplete seeding](https://www.reddit.com/r/qBittorrent/comments/1rnyw93/reseeding_file_changed_to_999_stalled/) |
| S18 | ES · voice | [First-person “odio y recontra odio”: moved files and stalled at ~80%](https://lesliemishigan.blogspot.com/2013/05/la-crisis-de-las-descargas_6.html) |
| S19 | EN · voices | [Magnets stick at metadata; VPN interface renaming and proxy issues](https://www.reddit.com/r/qBittorrent/comments/1iau4bu/new_magnet_links_stuck_on_downloading_metadata/) |
| S20 | EN · voices | [Metadata problems sometimes disappear only after restarting](https://www.reddit.com/r/qBittorrent/comments/qn5b6s) |
| S21 | DE · voice | [“Ich hasse Torrents”: far faster upload than download](https://www.hardwareluxx.de/community/threads/battlefield-3.734942/page-344) |
| S22 | EN · voice | [Antivirus/permission interference with writes](https://www.reddit.com/r/qBittorrent/comments/13d8qoi) |
| S23 | EN · voice | [Music organization requires breaking seeding to rearrange folder structure](https://www.reddit.com/r/qBittorrent/comments/1hndsel) |
| S24 | ZH · voices | [V2EX: upload chokes browsing, old habit, disk wear and electricity](https://v2ex.com/t/449809?p=1) |
| S25 | ID · voice | [Indonesian ISP fair-use ceiling: seeding forgotten overnight/monthly](https://www.reddit.com/r/indotech/comments/1tn1mbv/sedikit_was_was_jadi_seeder_pakai_isp_yang/) |
| S26 | EN · voices | [Low upload, laptop performance, shared household and data cap make seeding hard](https://www.reddit.com/r/Piracy/comments/1qi0ras/why_is_the_seedspeers_ratio_this_bad_on_one_of/) |
| S27 | EN · voice | [Seeding requires expensive upstream and scarce storage](https://www.reddit.com/r/Piracy/comments/1b3y9d6) |
| S28 | EN · voices | [Full upstream leaves other Internet actions unresponsive](https://www.reddit.com/r/Piracy/comments/1nglzyk/average_4chan_take/) |
| S29 | FR · voice | [Moved to DDL after repeated notices, unwilling to pay for VPN](https://www.reddit.com/r/FrancePirate/comments/1gud1jm) |
| S30 | RU · voice | [Russian user explicitly opposes torrent-associated unauthorized distribution](https://www.trv-science.ru/2010/03/torrenty-za-i-protiv/) |
| S31 | FR · voice | [Former P2P user prefers DDL; tracker centralization, rules and upload rejection](https://www.reddit.com/r/yggTorrents/comments/1pxx4kk/pourquoi_le_p2p_est_peu_%C3%A0_peu_mort_en_france/) |
| S32 | FR · voices | [Tracker barriers: DNS/VPN login, bans, payment and site distrust](https://www.reddit.com/r/yggTorrents/comments/1fvha4z/bon_en_vrai/) |
| S33 | EN · voice | [Leaves seedbox due to money/time; stranded partial jobs require migration](https://www.reddit.com/r/seedboxes/comments/jskfdp) |
| S34 | EN · voice | [Seedbox competition and low uploading make ratio feel impossible](https://www.reddit.com/r/torrents/comments/1laskpo) |
| S35 | EN · voice | [“HOURS of work” troubleshooting stuck metadata and warning icon](https://www.reddit.com/r/qBittorrent/comments/1revv0m/downloading_metadata/) |
| S36 | EN · voice | [Renamed/moved files, failed to seed 1 TB; learning the model later](https://www.reddit.com/r/Torrenting/comments/1w6dg93/why_not_seed/) |
| S37 | EN · voice | [100%-complete peer repeatedly glimpsed but unreachable](https://www.reddit.com/r/qBittorrent/comments/1qdp5sn/torrent_stuck_at_999_single_seed_wont_connect/) |
| S38 | EN · voices | [Private tracker ratio penalties and inability to upload](https://www.reddit.com/r/Piracy/comments/19begk9/im_about_to_get_banned_cuz_my_seeding_ratio_is/) |
| S39 | EN · voice | [Uploader says seedbox slow and poor peering for the price](https://www.reddit.com/r/seedboxes/comments/1jrx7g9/rapidseedbox_review/) |
| S40 | EN · voice | [Version 5 UI contrast/theme regression pushes user to leave client](https://www.reddit.com/r/unRAID/comments/1ftsi1y) |
| S41 | PT · voice | [Wrong TV episode discovered after download](https://pausaparaumcafe.com.br/series-5-situacoes-de-crise-de-todo-seriador/) |
| S42 | TR · voices | [Torrent index shutdowns, weak seeds, subtitles and malware fears](https://eksisozluk.com/torrent--584130?p=60) |
| S43 | EN · voice | [Unexpected suspicious .exe payload despite filtering](https://www.reddit.com/r/qBittorrent/comments/1wsh47n/almostcertainlymalicious_exe_files_still_getting/) |
| S44 | EN · voice | [File access denied and antivirus/debugging confusion](https://www.reddit.com/r/qBittorrent/comments/13d8qoi) |
| S45 | EN · voice | [Huge slow collection and hit-and-run penalty trap](https://www.reddit.com/r/trackers/comments/sg7nyi) |
| S46 | PL · voice/context | [Polish ethical opposition to piracy, not to the transport as such](https://onetech.pl/brzydze-sie-piractwem/) |
| S47 | EN · voice | [VueTorrent bulk folder reorganization needed without losing seeds](https://github.com/VueTorrent/VueTorrent/issues/2698) |
| S48 | EN · bug report | [qBittorrent filename case collisions cause missing files after restart](https://github.com/qbittorrent/qBittorrent/issues/24292) |
| S49 | EN · voices | [Trash-vs-permanent-delete surprise after qBittorrent upgrade](https://www.reddit.com/r/qBittorrent/comments/1fuc2wv) |
| S50 | EN · first-person question | [Seeding versus finished downloading concept confusion](https://www.reddit.com/r/IndianGaming/comments/14vmc9k) |
| S51 | ES · Google Play reviews | [Paid Android client hides filenames, forces remote warnings; magnet failure](https://play.google.com/store/apps/details?hl=es&id=com.utorrent.client.pro) |
| S52 | EN · bug report | [Transmission 4 cannot rename at initial add; crash/regression](https://github.com/transmission/transmission/issues/5353) |
| S53 | EN · voices | [Seedbox costs, caps and learning curve despite benefits](https://www.reddit.com/r/Piracy/comments/17jqm59/torrenters_do_you_use_a_seedbox_why_or_why_not/) |
| S54 | EN · voice | [Paid seedbox upload/peering disappointing](https://www.reddit.com/r/seedboxes/comments/1jrx7g9/rapidseedbox_review/) |
| S55 | EN · voice | [Too many seedbox providers/options, confusing evaluation](https://www.reddit.com/r/Torrenting/comments/1ux1z71/seedbox_recommendation/) |
| S56 | EN · first-person question | [Create-torrent flags, trackers/web seeds and piece size overwhelm](https://www.reddit.com/r/qBittorrent/comments/1hu310c) |
| S57 | ZH · developer framing, *not independent user evidence* | [Chinese open-source project describes ads/bloat, steep learning, unclear errors](https://github.com/koi-apps/koi-bt) |

**Evidence integrity notes.** S57 is developer positioning, not a user statement; it supports exploratory competitive framing only. S46 concerns piracy rather than BitTorrent specifically; S30 likewise mixes ethical assessment with transport. S50 is a novice question rather than dislike and is only used for comprehension friction. S39/S54 refer to the **same** complaint; never count them as separate users. S03/S16 and S22/S44 are the same sources under multiple analytical angles. Claims about real legal exposure, ISP manipulation, trackers' actual policies, client vulnerabilities or malware prevalence would need independent technical/legal verification; this document records *perceptions and incidents*.

**Research boundary.** Findings here may inform the [feature inventory PR](https://github.com/gamesbyian/Chunks/pull/537) and [workflow atlas PR](https://github.com/gamesbyian/Chunks/pull/538) as independent descriptive references; neither PR is merged or incorporated by this document. No Chunks implementation/acceptance is asserted. Any resulting requirement must be separately authorized and placed in its canonical product/feature contract, with testable acceptance criteria.
