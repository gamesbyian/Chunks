#!/usr/bin/env python3
"""Collect REAL, licensed public Archive torrents, never fabricate magnets.

Network run is explicit; no RPC, payload download, protected-host mutation or
unverified peer/seeder assertions. Outputs catalog.json and magnets.txt.
"""
import argparse
import json
from pathlib import Path
import sys
import time
import urllib.parse

from public_fixture_collector import fetch, archive_fixture, MAX_META

API = "https://archive.org/advancedsearch.php"
KINDS = ("audio", "movies", "texts", "software", "image", "data")

def discover(kind, page, rows):
    query = 'mediatype:' + kind + ' AND licenseurl:creativecommons.org*'
    params = urllib.parse.urlencode({
        "q": query, "fl[]": "identifier", "rows": str(rows),
        "page": str(page), "output": "json", "sort[]": "downloads desc"
    })
    data = json.loads(fetch(API + "?" + params, MAX_META))
    return [d.get("identifier") for d in data.get("response", {}).get("docs", [])
            if isinstance(d, dict) and isinstance(d.get("identifier"), str)]

def collect(limit, max_bytes, pages, delay, rows, output):
    found, hashes, failures = [], set(), {}
    # Interleave media types instead of taking the first 100 from one category.
    for page in range(1, pages + 1):
        pools = {}
        for kind in KINDS:
            try:
                pools[kind] = discover(kind, page, rows)
            except Exception as exc:
                failures[type(exc).__name__] = failures.get(type(exc).__name__, 0) + 1
                pools[kind] = []
            time.sleep(delay)
        for index in range(rows):
            for kind in KINDS:
                ids = pools[kind]
                if index >= len(ids):
                    continue
                try:
                    manifest, _ = archive_fixture(ids[index], max_bytes)
                    info = manifest["torrent"]
                    h = info.get("infohash_v1") or ("v2:" + str(info.get("infohash_v2")))
                    if h in hashes:
                        continue
                    hashes.add(h)
                    found.append({
                        "magnet": info["magnet"],
                        "infohash_v1": info.get("infohash_v1"),
                        "infohash_v2": info.get("infohash_v2"),
                        "name": info["name"],
                        "total_bytes": info["total_bytes"],
                        "file_count": info["file_count"],
                        "media_category": kind,
                        "source_url": manifest["source_url"],
                        "torrent_url": manifest["torrent_url"],
                        "license": manifest["license"],
                        "tracker_retained": info["tracker_retained"],
                        "ordinary_seeders_verified": False,
                        "peer_bytes_verified": False
                    })
                    print(f'{len(found)}/{limit} {kind} {info["total_bytes"]} {info["name"][:50]}', flush=True)
                except Exception as exc:
                    failures[type(exc).__name__] = failures.get(type(exc).__name__, 0) + 1
                time.sleep(delay)
                if len(found) >= limit:
                    break
            if len(found) >= limit:
                break
        if len(found) >= limit:
            break
    output.mkdir(parents=True, exist_ok=True)
    (output / "catalog.json").write_text(json.dumps({
        "schema": "chunks.public-magnet-catalog.v1",
        "requested": limit, "verified_metadata_count": len(found),
        "max_total_bytes": max_bytes,
        "warning": "Genuine source metainfo hashes; seed availability unverified. Archive webseed-only torrents may be unusable on rTorrent. Do not use as peer-byte acceptance until verified.",
        "rejection_counts": failures, "fixtures": found
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (output / "magnets.txt").write_text("".join(x["magnet"] + "\n" for x in found), encoding="utf-8")
    return len(found)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--count", type=int, default=100)
    p.add_argument("--max-bytes", type=int, default=555000000)
    p.add_argument("--pages", type=int, default=12)
    p.add_argument("--rows", type=int, default=50)
    p.add_argument("--delay", type=float, default=0.25)
    p.add_argument("--output", default="test-resources/public-magnets")
    a = p.parse_args()
    if not (1 <= a.count <= 1000 and 1 <= a.pages <= 100 and 1 <= a.rows <= 100
            and 0.2 <= a.delay <= 10 and 1 <= a.max_bytes <= 555000000):
        p.error("Invalid bounds")
    n = collect(a.count, a.max_bytes, a.pages, a.delay, a.rows, Path(a.output))
    print(f"Collected {n}/{a.count} from validated source metainfo.")
    if n < a.count:
        sys.exit(2)

if __name__ == "__main__":
    main()
