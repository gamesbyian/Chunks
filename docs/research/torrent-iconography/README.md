# Torrent-client iconography reference for Chunks

**Research snapshot:** 2026-11-12. **Status:** non-normative visual research, decision aid, not a product or asset-production mandate.

## Open the gallery

Open [the interactive HTML gallery](gallery.html) after checking out the repo, or download `gallery.html` and open it locally in a browser with internet access. It has **68 user-facing feature cards, six clients, and 175 source-backed artwork observations**, with filtering, the existing Chunks SVG drafts, and per-feature choices. The gallery links upstream icons by commit SHA rather than copying third-party art into the repository.

**Use the controls:** Filter to **No clear asset** to prioritize empty design-library slots. For each feature choose **Agent decides**, **Reuse / adapt draft**, **Generate new original**, **Review later**, or **No icon**; optionally select a preferred competitor representation and type your direction. Download **Export choices** to retain all decisions and **Export agent queue** for a candidate generation list. Browser-local persistence is best-effort; exported JSON is the durable handoff. No production asset is automatically changed.

[Source observations](observations.json) is the machine-readable research dataset. [Initial candidate gaps](asset-gap-candidates.json) is a smaller, unapproved work-candidate list generated from the current inventory, not a new independent Chunks backlog.

## Clients and evidence

| Client / surface | Visual scope | Source basis |
| --- | --- | --- |
| qBittorrent WebUI | Named SVG operations, state icons, queue arrows, filters, status | [WebUI index with actual icon mappings](https://github.com/qbittorrent/qBittorrent/blob/d56ce79c99dfffcf43954b81142bc8e7cb81a429/src/webui/www/private/index.html) |
| Transmission macOS | Control states, priority glyphs, turtle / ratio, badges | [macOS image assets](https://github.com/transmission/transmission/tree/e024d47a5004152739e0e714cde4d9d3eba907bf/macosx/Images/Images.xcassets) |
| Deluge GTK | Downloading, checking, queued, seeding, DHT and alert icons | [GTK pixmaps](https://github.com/deluge-torrent/deluge/tree/e58075416dedd53636e89b1cd240f86f2e7c2ee0/deluge/ui/data/pixmaps) |
| BiglyBT desktop | Rich command and status asset library, rate controls and networking | [Desktop icons](https://github.com/BiglySoftware/BiglyBT/tree/342900963767ea9a6786bf9f718284ef97ba0825/uis/src/com/biglybt/ui/icons) |
| LibreTorrent Android | Play, pause, stop, queue, labels, settings, RSS and system symbols | [Android vector drawables](https://github.com/proninyaroslav/libretorrent/tree/ae96f20b9852d2140fd704a3f2451ed7679e35a0/app/src/main/res/drawable) |
| ruTorrent web | Toolbar sprite, with the actual command offsets | [CSS mapping](https://github.com/Novik/ruTorrent/blob/5609c62e1b6d0f060b212a81cc4f310fe4d0b4c8/css/style.css#L401-L419) and [sprite](https://github.com/Novik/ruTorrent/blob/5609c62e1b6d0f060b212a81cc4f310fe4d0b4c8/images/toolbar.png) |

The representative six optimize for diverse desktop, web/remote, and Android visual conventions and practical source availability. They are **not** asserted to be a mathematically optimal six-client selection. The source baseline is the repo's [240-feature ecosystem inventory](../../BITTORRENT-ECOSYSTEM-FEATURE-INVENTORY-2026-10-29.md), [interaction testimony](../TORRENT-CLIENT-CONTROL-UX-VOICE-2026-11-01.md), and [source SVG inventory](../../design/svg/master-inventory.json).

## What the first pass establishes

- **36 features** have a directly relevant Chunks SVG **draft** in the icon inventory.
- **17 features** have only a related Chunks draft, worth inspecting or adapting.
- **15 features** have no clear matching first-pass Chunks SVG. This does **not** mean those 15 features are supported or even intended in Chunks, and does not prove the runtime lacks icons.
- **All 175 referenced upstream icon paths and all recorded Chunks candidate paths were checked for existence** in their named repository trees on 2026-11-12. For qBittorrent and ruTorrent, selected control/icon associations are directly mapped in the UI source. For most others the evidence is named **source assets**, which does not independently prove current on-screen placement.

### Useful initial visual patterns

- Start/resume is commonly the **play triangle**, while pause is **two bars**. Stop is a **square**, sometimes inside a circle. The clients do not always give pause, stop, and inactive the same semantics: preserve labels.
- Add and remove use plus/minus or deletion metaphors; use clear distinction between **Remove transfer** and **Delete payload files**.
- Download/upload are **down/up arrows** with text, speed, or status colour. Completion often uses a **checkmark**; error uses an **alert**; queue and stalled states vary more.
- **Turtle** is a meaningful alternate-speed convention, appearing in both qBittorrent (`slow.svg`) and Transmission's alternate-rate assets. Chunks has a speed-lightning draft but no turtle master in this scanned set.
- Recheck, reannounce, and refresh have overlapping circular-arrow/radio-wave metaphors: avoid using one ambiguous symbol for all three.
- **Tags, RSS, ratio, scheduling, file priority and notification bell** are straightforward candidates for original Chunks symbols if corresponding features are already part of an authoritative Chunks requirement.

These summaries are visual observations from the sampled assets, not user-preference measurements or universal design standards.

## Lightweight completion rule

This is a practical decision aid. Stop browsing for an individual feature once its common metaphor and a meaningful alternative are visible, or once the absence of a strong visual convention is clear. Do not block icon design waiting for proof of every historical application version, plugin, tooltip or state.

For production use: revalidate what Chunks actually needs; choose the metaphor; generate **original** SVGs in the approved Chunks style (see [SVG asset production plan](../../design/reference-images/SVG-ASSET-PRODUCTION-PLAN.md)), register assets, visually inspect small sizes and relevant states, and submit changes for owner review. No feature in this research automatically enters the main [work queue](../../WORK-QUEUE.md).

## Known limits

- The static gallery needs network connectivity for upstream artwork and Chunks asset images. Most LibreTorrent Android drawable sources are XML vectors, not browser-native SVG: the gallery attempts a lightweight browser conversion and otherwise exposes the original XML link.
- BiglyBT, Transmission, and Deluge source asset names are valid pointers; their placement and appearance in a current installed application have **not** all been verified.
- ruTorrent's icon sprite crops are mapped from source CSS offsets, but alternative themes may differ.
- Some feature cards provide a *related* graphic rather than a matching control (clearly named in their captions). Do not interpret an example as a claim that a client exposes that feature.
- This research is intentionally representative, not exhaustive. Re-check source links when a design choice becomes consequential.

**Repository impact:** Research documents and gallery only; no Android, SVG master, build, acceptance, or UI changes.
