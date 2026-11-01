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
- Sources: **30 LinuxTracker torrents**, whose site displayed positive seed counts at collection, and **70 Internet Archive torrents**, CC/public-domain metadata checked by the existing collector.
- Media types: software **42**, audio **6**, movies **8**, images **18**, data **18**, texts **8**.
- **Eight distinct sanitized tracker hosts**; tracker paths are strictly `/announce` (no passkey/path tokens, URL queries or embedded credentials). Multiple tracker hosts do not imply multiple independently healthy swarms.
- **Seeder and transferred-byte verification is still open**. LinuxTracker's reported seeder counts are hints, not direct rTorrent observations. Internet Archive relies on webseeds for some torrents; rTorrent may need actual peers. Per-release Linux distribution licences need appropriate human review before any content redistribution.

## Collect / validate / refresh

From the repository root with external Internet access:

```bash
python3 tools/validate_public_magnet_catalog.py
python3 tools/collect_public_magnet_catalog.py --count 100 --max-bytes 555000000
python3 tools/validate_public_magnet_catalog.py
```

The collector reads original source metadata from LinuxTracker/Internet Archive, hashes the exact bencoded info dictionary, checks **whole-torrent sizes**, deduplicates, restricts tracker URLs and outputs JSON and TXT without downloading payloads or touching remote servers. Harvested results can change as upstream torrents change. The [manual GitHub Actions workflow](../../.github/workflows/collect-public-magnets.yml) uploads an artifact for review; no automatic write to `main`, and no protected rTorrent or seedbox mutations. Incomplete harvests exit nonzero while retaining a partial list with an explicit count. Validate differences before replacing the checked-in files.

## Promotion to real-client acceptance

To establish a working swarm, make a **real magnet Add through installed Chunks** to an authorized rTorrent/libtorrent engine, observe metadata resolution through actual peers and per-infohash piece-hash-verified byte growth, and capture server/profile, timestamp, path, Start/Stop and cleanup receipts. Do not claim peer availability from an index count or a green metadata parse. See [public torrent fixture process](../../docs/PUBLIC-TORRENT-FIXTURES.md) and [real-transfer acceptance](../../docs/REAL-TRANSFER-ACCEPTANCE.md).
