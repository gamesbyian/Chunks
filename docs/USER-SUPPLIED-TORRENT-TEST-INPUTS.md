# User-supplied real-world torrent inputs (2026-10-27)

The owner authorized eight additional transfer inputs for development and testing. **Availability does not imply media redistribution rights or safe publication of tracker passkeys.** These are separate from the licensed, public-source fixtures in [PUBLIC-TORRENT-FIXTURES.md](PUBLIC-TORRENT-FIXTURES.md).

## Six currently committed metainfo files

The following six files were placed in the repository root by the owner:

- `898 Hebrew Fonts  784 TTF & 114 OTF [IPT].torrent`
- `A_Thousand_Details-A_Baixa_em_2010s-(ATD038)-16BIT-WEB-FLAC-2026-PTC [IPT].torrent`
- `Beeny_Update_v1.4_NSW-SUXXORS [IPT].torrent`
- `Flycatcher.Design.Nomad.CrossPlatform.Commercial.Font-SERiF [IPT].torrent`
- `Kenny_Summit-Perfect_Stranger-(SLACKER027)-16BIT-WEB-FLAC-2026-TR [IPT].torrent`
- `Poison_Zcora-How_It_Feels_To_Be_Plundered-(BGTF003)-16BIT-WEB-FLAC-2026-TR [IPT].torrent`

**Security hold:** `[IPT]` may indicate private-tracker metainfo containing unique announce passkeys. Before relocating, committing regenerated copies, uploading artifacts, invoking network tests, or feeding into public CI, inspect bencoded announce/announce-list, url-list and related properties locally with **redacted output**. Do not print or commit credentials. Repository is public, so a discovered passkey may already be exposed in Git history even if the file is subsequently moved or removed; report to the owner so tracker credentials can be rotated if needed. Prefer sanitized synthetic metainfo for routine CI; never silently strip trackers from source files and claim the resulting infohash or metadata behavior is equivalent.

## Two provided magnet-link identities

| Label (untrusted display name only) | Exact BTIH v1 identity |
|---|---|
| Mercy 2026 576p WEBRip x265 AAC-SSN | `76988BF9339EBBD60EE9D9C61C48F4382A393C91` |
| Theo.Von.Im.Doing.Fine.2026.WEB.H264-RBB | `9C78BE118C210A236E3C0B682EACC8DC6C2892ED` |

Each supplied magnet has an `xt=urn:btih:` hash, an encoded `dn=` display name and 13 UDP `tr=` tracker parameters. The original full links are recorded in the owner conversation and are not copied into repository documentation. No peer availability, actual title, content rights, metadata success, or received/hash-verified bytes have been established.

Use for parser-level regression of repeated `tr` fields, encoded `dn`, tracker preservation, Android ACTION_VIEW handling and local/remote Add review. Network download or Add should be a separately reviewed and opt-in test, not an automatic PR CI action. For deterministic real-byte acceptance use independently licensed public fixtures and controlled peers.

## Acceptance targets

1. `.torrent` import from Android file managers and Downloads: open/share intents resolve to Chunks, parse metadata within limits, display review before Add, and retain failure reasons.
2. Magnet capture: validate 40-hex BTIH case-insensitively, percent decoding, repeated trackers, Unicode-safe title and malformed-input rejection; avoid accidental network access during pure parser tests.
3. Confirm stopped/running restart controls and distinction between Add accepted, metadata retrieved, peers connected, received bytes and hash-verified payload.
4. Snapshot export/import classification: full valid metainfo versus magnet-only versus hash-only; preserve tracker privacy, detect duplicates by exact infohash, compare complete target inventory, default Add stopped, journal uncertainty, and verify post-Add identity.
5. Keep source torrent payloads untouched; do not use these files as evidence of successful or lawful content transfer without independent verification.

Related: [TRANSFER-SNAPSHOTS.md](TRANSFER-SNAPSHOTS.md), [SEEDHOST-REMOTE-TESTING.md](SEEDHOST-REMOTE-TESTING.md), [SECURITY.md](SECURITY.md).
