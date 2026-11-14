# Remote download folder picker: interaction and visual design

**Status: Universal Add picker and guarded New folder merged in [PR #1710](https://github.com/gamesbyian/Chunks/pull/1710).** Connections default-folder reuse is the next implementation; physical Pixel visual/accessible acceptance and real Seedhost plugin permissions remain pending. [Canonical behavior and security](../REMOTE-DIRECTORY-BROWSER.md) are binding; this file owns placement, composition, view states and visual acceptance. Reuse the existing Chunks theme, iconography and shared Compose controls; use the approved [folder icon](svg/icons/files/folder.svg) and [new-folder icon](svg/icons/files/new-folder.svg) when visually suitable, avoiding generic placeholder vectors. Keep the one-row Transfers / + Add / Connections / Options footer under the parent Add screen; picker overlay must respect system insets and not duplicate navigation.

## Entry and hierarchy

**Universal Add:** Source input and picker stay first; selected exact target below; then **Save to** summary with actual absolute path and visible **Change**. One-row Add remains possible without opening the picker if effective Place is valid. On remote Change, use an adaptive full-height sheet/full-screen view on the owner's Pixel 6 Pro (dialog only on sufficiently large layouts). Do not launch Android's local document picker for a remote server or create a separate Add destination screen.

**Proposed portrait composition (labels illustrative, never invented real folder listings):**

```text
┌────────────────────────────────────┐
│ ‹  Save to · [Remote connection]    │
│    On server: [short account label] │
│                                    │
│ Current location                   │
│ /home/account/downloads/TV         │
│ [downloads] › [TV]                 │
│                                    │
│  ↑ Parent folder                   │
│  📁 Comedy                      ›  │
│  📁 Documentaries                ›  │
│  📁 Drama                        ›  │
│  📁 Science Fiction              ›  │
│                                    │
│ [ New folder ]   [ Enter path ]   │
│                                    │
│ [ Cancel ]   [ Use this folder ]   │
└────────────────────────────────────┘
```

The names and path above are **not** real server observations. The actual UI **must** populate from the exact authenticated server; never render this mock data as a default. Folder list scrolls independently of the fixed actions. Show profile name in text and optional established environment colour, never solely colour.

- Header: **Save to**, current server, clear Close/Back with announcement. Path block: selectable/copyable displayed full canonical directory, horizontally scrollable accessible breadcrumbs, explicit **Up** constrained at the allowed root. Long path wraps or supports horizontal scroll without clipping the selected destination or controls.
- Main list: one folder per row with native Chunks folder glyph, legible name, chevron/enter affordance, any known **Read-only** status with text. Rows ≥48dp high; tap row **enters**, not selects. Never show fabricated folders, hidden neighboring tenants, files or unrelated download data.
- Bottom actions: **Cancel** (no draft change), **Use this folder** (affirmative draft-only selection), secondary **New folder** when separately permitted, and discoverable **Enter path manually**. Keyboard/IME must not cover these controls; preserve accessibility focus. Do not require Back to leave, since Chunks normally avoids Back-dependent navigation.
- **New folder dialog:** title **New folder in [current folder]**, a single name field, live validation and the resulting full path preview; **Cancel** / **Create folder**. Only the latter sends an authorized server mutation; progress locks duplicate submission. After verified creation, refresh list and allow using the created folder; do not submit a torrent or alter saved defaults.
- **Manual fallback:** for unsupported browsing, an accessible **Remote folder path** field with explicit exact-profile label, selected saved/default/recent parent suggestions where known, and **Use this path** returning to Add. Clearly say path existence/writeability has not been verified if server cannot preflight. Do not make a typed custom value mandatory when Add's effective verified default is already available.

## UI state matrix

| State | Rendered evidence | Actions |
| --- | --- | --- |
| Initial load | Spinner/progress text **Loading server folders**; current profile/path visible | Cancel, stale-safe read-only retry after timeout |
| Ready (writable current parent) | Real subfolders + path; **Use this folder** enabled | Enter folder, Up, New folder only if separately permitted |
| Empty (valid list) | **No subfolders here**; current folder still selectable if writable | Up, Use, New folder if supported |
| Read-only or forbidden folder | Specific **Cannot save here** / permission detail; no false enabled Use | Up/Retry/Manual |
| Browse supported, create absent | List works; New folder omitted or disabled with **Server does not allow creating folders** | Use existing folder |
| Offline, expired auth, missing plugin | Specific failure and account name; no stale listing from another session | Reconnect, read-only Retry, manual path |
| Create pending, refused, exists, uncertain | Target path and distinct status; no optimistic success | Cancel/read-only refresh; explicit fresh user action only |
| Server or Add target switched | Prior folder data cleared, new profile shown; Add draft retained | Re-probe current profile |
| Picker cancelled | Original Add path/source/Start unchanged; no remote operation | Return to same Add screen |

## Reuse, responsiveness and proof

The per-server Options/Connections **Default download folder** editor opens the same folder picker in *saved-default mode* with an explicitly labelled **Set as this server's default** commit; it does not inherit the Add draft's one-off semantics by accident. Existing recents remain a compact shortcut near Save to; optional folder browsing doesn't introduce a new root tab. Remote Details destination changes must not piggyback on this UI without separate payload-operation proof.

Perform actual-render visual review alongside technical verification: Pixel 6 Pro first, both themes, 320dp/412dp/landscape as proportionately necessary, 200% text, 48dp targets, stable selected footer, TalkBack announces server/path/folder writable and Create uncertainty, rotary/keyboard where supported, IME, focus after Cancel/Use, auth lock, large lists and very long Unicode paths. Report functional versus experience status separately. Reference [feature delivery contract](../FEATURE-EXPERIENCE-CONTRACT.md), [UX](../UX.md), [Spec Coverage](../SPEC-COVERAGE.md) and [issue #1680](https://github.com/gamesbyian/Chunks/issues/1680).
