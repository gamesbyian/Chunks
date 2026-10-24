# Current decisions (single canonical ledger)
2026-10-23. Labels: Approved = direct owner requirement; Proposed = reasonable architecture choice; Open = needs evidence/owner sign-off.

| ID | Status | Decision | Rationale / reconsider when |
|---|---|---|---|
| D01 | Approved | Name Chunks; Android-native BitTorrent client plus bulletproof rTorrent remote | Product brief |
| D02 | Approved | Per-transfer Place choice, live relocation and safe rename/copy | Signature experience |
| D03 | Approved | Suggest/smartly sort across media, franchises, series, books/comics/music | User concept |
| D04 | Approved | Modern, colourful, irreverent, generous interface with deep controls | User concept |
| D05 | Proposed | Kotlin + Compose, typed adapter interface, Gradle Android shell | Reuse Glimpse native proof |
| D06 | Proposed | XML-RPC baseline over protected HTTPS; JSON-RPC if probed | Broad legacy rTorrent compatibility |
| D07 | Proposed | Restricted optional server companion for actual remote file operations | RPC metadata alone insufficient |
| D08 | Proposed | Separate Transfers, Places, Collections and seed-preserving copies | Avoid coupling location to classification |
| D09 | Proposed | Rules and deterministic parsing before ML/metadata lookups | Explanations, privacy, offline utility |
| D10 | Proposed | Single live queue + issues + acceptance ledger + one lean CI workflow | Reuse best of ROYGBIV/UR/Glimpse without archaeology |
| D11 | Open | Full release license, branding assets, Play-vs-sideload flavors | Needs policy/legal and owner decisions |
| D12 | Open | Companion language, packaged seedbox deployment constraints | Requires real-server matrix |
| D13 | Open | Best native libtorrent binding and background strategy | Requires Android build/device proof |

When a decision changes, update this table and affected implementation/acceptance rather than appending a competing dated plan.