# Public magnet catalog for Chunks real-transfer testing

**Status (2026-11-01): 100 authentic, independently size-checked magnet identities committed for review.** The exact SHA-1/SHA-256 info dictionary hashes were derived from published source `.torrent` metainfo, not guessed hashes. This establishes provenance, hash validity and the full advertised payload size, **not** that all 100 currently have accessible conventional BitTorrent seeders.

## Use the catalog

- [`magnets.txt`](magnets.txt): **100 real magnet URI lines**, ready for Chunks Add tests.
- [`catalog.json`](catalog.json): the same 100 links with actual infohashes, precise total byte sizes, names, file counts, categories, source URLs, retained tracker information and explicit unverified peer fields.
- [`first-15-source-verified.magnets.txt`](first-15-source-verified.magnets.txt) and [manifest](first-15-source-verified.json): independently harvested small smoke batch, sanitized tracker URLs; [GitHub Actions run 41360943530](https://github.com/gamesbyian/Chunks/actions/runs/41360943530).
- [`live-metainfo-smoke.json`](live-metainfo-smoke.json): two live-source checked hashes and exact sizes; [run 41360738223](https://github.com/gamesbyian/Chunks/actions/runs/41360738223).
- [`seeded-candidates.json`](seeded-candidates.json): 11 additional source-indexed entries for separate follow-up; **these are not a source-metainfo-verified substitute** for the curated catalog.

### Audited properties of the 100

- **100 unique infohashes and 100 matching magnet lines**, no duplicate hashes, sizes strictly above zero and **at most 555,000,000 bytes** each; actual range **11,234 to 460,947,956 bytes**.
- Sources: **30 LinuxTracker torrents**, whose site displayed positive seed counts at collection, and **70 Internet Archive torrents**, with source-asserted CC/public-domain labels recorded but rights not independently established.
- Media types: software **42**, audio **6**, movies **8**, images **18**, data **18**, texts **8**.
- **Eight distinct sanitized tracker hosts**; tracker paths are strictly `/announce` (no passkey/path tokens, URL queries or embedded credentials). Multiple tracker hosts do not imply multiple independently healthy swarms.
- **Seeder and transferred-byte verification is still open**. LinuxTracker's reported seeder counts are hints, not direct rTorrent observations. Internet Archive relies on webseeds for some torrents; rTorrent may need actual peers. Per-release Linux distribution licences need appropriate human review before any content redistribution.

## License-claim evidence audit (2026-11-02)

The catalog's **100 source-derived infohashes** are genuine provenance evidence, but a successful torrent hash does not establish a reusable content license or a currently healthy swarm. The *existing checked-in* 100-entry snapshot has these source-supplied labels:

| Source-side license claim | Records | What it establishes |
| --- | ---: | --- |
| Named Creative Commons license URI | 15 | An uploader/record asserted a specific CC license; rights-holder authority and conditions remain unverified |
| CC0 public-domain dedication URI | 6 | A purported dedication, not proof that the submitter held the rights |
| Creative Commons Public Domain Mark | 43 | An **informational public-domain status mark**, not a grant of rights or a verified worldwide determination |
| Legacy `/licenses/publicdomain/` | 6 | Redirects to a **retired US-specific certification tool**; requires particular legal/provenance review before content redistribution |
| Linux distribution releases | 30 | Public-source distribution identities; relevant license terms need release-by-release review |
| **Total** | **100** | **No entry has independent rights-holder or peer-byte proof from this metadata alone** |

The six legacy records are flagged rather than silently converted to CC0 or modern PDM. Creative Commons [documents its retired tool and jurisdiction limits](https://creativecommons.org/licenses/publicdomain/) and [explains why PDM and CC0 have different legal meanings](https://creativecommons.org/public-domain/). The source corpus was harvested **before** the BEP 27 private-flag admission check and strict CC URI origin verification were introduced: its existing JSON contains no per-item independent BEP 27 flag attestation. New-source collection applies those stricter checks; do **not** infer they retroactively revalidated this old catalog. Re-fetch and verify exact original source metainfo before promoting a legacy candidate to a manually authorized public-peer test, and review rights independently before sharing actual media.

`python3 tools/validate_public_magnet_catalog.py` now emits a `license_claim_evidence_tiers` count based on the actual manifest labels, alongside the existing source/tracker identity checks. It does **not** reject historic legitimate source hashes solely because their license claims need review, and does **not** mutate any source, server, or download.

## Collect / validate / refresh

From the repository root with external Internet access:

```bash
python3 tools/validate_public_magnet_catalog.py
python3 tools/collect_public_magnet_catalog.py --count 100 --max-bytes 555000000
python3 tools/validate_public_magnet_catalog.py
```

The collector reads original source metadata from LinuxTracker/Internet Archive, hashes the exact bencoded info dictionary, checks **whole-torrent sizes**, deduplicates, restricts tracker URLs and outputs JSON and TXT without downloading payloads or touching remote servers. Harvested results can change as upstream torrents change. The [manual GitHub Actions workflow](../../.github/workflows/collect-public-magnets.yml) uploads an artifact for review; no automatic write to `main`, and no protected rTorrent or seedbox mutations. Incomplete harvests exit nonzero while retaining a partial list with an explicit count. Validate differences before replacing the checked-in files.

The offline catalog validator additionally requires the magnet to contain **exactly the `xt` topic(s) recorded in the verified manifest**, including both hashes only for a verified hybrid entry. Duplicate, unrelated or conflicting topics are refused, as are a display name inconsistent with the manifest and unreviewed external seed/peer source parameters. These restrictions apply to the *curated test catalog*; they are not new limits on general-purpose user-entered magnet links. They prevent a correct source hash from masking a second identity that could be chosen by a different BitTorrent client.

## Promotion to real-client acceptance

To establish a working swarm, make a **real magnet Add through installed Chunks** to an authorized rTorrent/libtorrent engine, observe metadata resolution through actual peers and per-infohash piece-hash-verified byte growth, and capture server/profile, timestamp, path, Start/Stop and cleanup receipts. Do not claim peer availability from an index count or a green metadata parse. See [public torrent fixture process](../../docs/PUBLIC-TORRENT-FIXTURES.md) and [real-transfer acceptance](../../docs/REAL-TRANSFER-ACCEPTANCE.md).
