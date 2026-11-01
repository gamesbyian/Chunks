# Public magnet catalog for Chunks real-transfer testing

The objective is **100 authentic BitTorrent magnets**, each with exact metainfo size ≤ **555,000,000 bytes**, plus useful tracker/source/content diversity. The checked-in candidates and any automatically collected catalog must distinguish a real source hash from live seeder proof.

## Files and evidence

- `seeded-candidates.json`: **11 source-indexed candidates**, taken from LinuxTracker's published information hashes, displayed sizes and reported seeders. These are valid constructed magnet URIs using *published* hashes, but exact source .torrent metadata has **not yet been fetched and recomputed** by us. Reported seed counts are **site observations**, not live Chunks/seedbox measurements, and vary over time.
- `catalog.json` and `magnets.txt` (once generated): automatically harvested from **actual source .torrent bytes**. The exact info dictionary is hashed, size totals checked and links deduplicated; the offsite data is left on this PR branch for review. These are still not necessarily verified active peer swarms.

## Collection / verification

From repository root with external Internet access:

```bash
python3 tools/collect_public_magnet_catalog.py --count 100 --max-bytes 555000000
python3 tools/validate_public_magnet_catalog.py
```

The collector checks published torrent metadata from the Internet Archive (audio, video, texts, software and other media, requiring an explicit recognized CC/public-domain license) and LinuxTracker Linux distributions (exact hash match against the tracker index, publisher-reported seeder hints; per-release licence review still needed). Archive magnets retain only a sanitized official source tracker; LinuxTracker magnet tracker URLs are restricted to a curated public allowlist. The script never downloads payloads, modifies remote rTorrent sessions or accesses credentials. The manual / temporary-branch [GitHub Actions workflow](../../.github/workflows/collect-public-magnets.yml) is an alternative, with review artifact and branch-only auto-commit. An incomplete harvest exits nonzero, but keeps its partial output with an explicit count.

## Real acceptance

Treat the source's displayed seeder count as an **unconfirmed hint**. To promote a fixture to an actual working test, have an authorized rTorrent/libtorrent session resolve a magnet through real peers, observe per-infohash metadata and **hash-verified piece-byte growth**, then record the exact environment, observation date, tracker/DHT topology and results. Archive webseeds do not prove ordinary rTorrent peer availability, and a single torrent with 20 tracker URLs does not create 20 independent swarms. Keep separate columns for index validity, source metadata verification, peer metadata resolution, conventional seeders, received pieces and verified completion. Do not claim 100 active torrents without 100 actual observations.

See [public torrent fixture process](../../docs/PUBLIC-TORRENT-FIXTURES.md) and [real-transfer acceptance](../../docs/REAL-TRANSFER-ACCEPTANCE.md).
