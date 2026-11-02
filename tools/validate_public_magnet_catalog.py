#!/usr/bin/env python3
"""Offline consistency gate for checked-in *actual* public magnet catalogs.

Authentic source metainfo and live peer-byte evidence remain separate requirements.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import urllib.parse

HEX40 = re.compile(r"[a-f0-9]{40}")
HEX64 = re.compile(r"[a-f0-9]{64}")
MAX_BYTES = 555_000_000

def validate(path, magnets_path, allow_partial=False):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    fixtures = data["fixtures"]
    requested = data["requested"]
    if not isinstance(requested, int) or not 1 <= requested <= 1000:
        raise ValueError("Bad requested count")
    if len(fixtures) != data["verified_metadata_count"] or len(fixtures) > requested:
        raise ValueError("Count inconsistent")
    lines = Path(magnets_path).read_text(encoding="utf-8").splitlines()
    if lines != [item["magnet"] for item in fixtures]:
        raise ValueError("Magnets file mismatch")
    if not allow_partial and len(fixtures) != requested:
        raise ValueError("Catalog incomplete")
    hashes = set()
    sources = Counter()
    trackers = set()
    for i, item in enumerate(fixtures, 1):
        n = item["total_bytes"]
        if type(n) is not int or not 0 < n <= MAX_BYTES:
            raise ValueError(f"Invalid total size at {i}")
        h1, h2 = item.get("infohash_v1"), item.get("infohash_v2")
        if (h1 and not HEX40.fullmatch(h1)) or (h2 and not HEX64.fullmatch(h2)) or not (h1 or h2):
            raise ValueError(f"Invalid infohash at {i}")
        fingerprint = (h1, h2)
        if fingerprint in hashes:
            raise ValueError(f"Duplicate infohash at {i}")
        hashes.add(fingerprint)
        uri = item["magnet"]
        # urllib normalizes stray control characters before parsing. Reject
        # them from the raw catalog entry, and allow only the curator's actual
        # identity/display/tracker fields, not peer injection or web seeds.
        if (not isinstance(uri, str) or len(uri) > 8192
                or any(ord(c) < 32 or ord(c) == 127 for c in uri)):
            raise ValueError(f"Unsafe magnet text at {i}")
        magnet = urllib.parse.urlsplit(uri)
        if (magnet.scheme != "magnet" or magnet.netloc or magnet.path
                or magnet.fragment):
            raise ValueError(f"Bad magnet URI at {i}")
        params = urllib.parse.parse_qs(magnet.query, keep_blank_values=True)
        if set(params) - {"xt", "dn", "tr"}:
            raise ValueError(f"Unexpected magnet parameter at {i}")
        expected_topics = (
            (["urn:btih:" + h1] if h1 else [])
            + (["urn:btmh:1220" + h2] if h2 else [])
        )
        # A magnet that contains the correct xt plus a second unrelated xt
        # is ambiguous to actual clients and is not a source-verified fixture.
        if sorted(params.get("xt", [])) != sorted(expected_topics):
            raise ValueError(f"Magnet exact topic mismatch at {i}")
        if (not isinstance(item.get("name"), str) or not item["name"]
                or params.get("dn") != [item["name"]]
                or not item.get("source_url")):
            raise ValueError(f"Missing or conflicting identity/provenance at {i}")
        source = urllib.parse.urlsplit(item["source_url"])
        if source.scheme != "https" or source.hostname not in ("archive.org", "linuxtracker.org"):
            raise ValueError(f"Unexpected source at {i}")
        sources[source.hostname] += 1
        for tr in params.get("tr", []):
            p = urllib.parse.urlsplit(tr)
            if p.scheme not in ("http", "https", "udp") or not p.hostname or p.username or p.password or p.query or p.fragment or p.path != "/announce":
                raise ValueError(f"Unsafe tracker at {i}")
            trackers.add(p.hostname)
    print(json.dumps({"count": len(fixtures), "sources": sources,
                      "distinct_tracker_hosts": len(trackers), "tracker_hosts": sorted(trackers)},
                     indent=2))
    return len(fixtures)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--catalog", default="test-resources/public-magnets/catalog.json")
    p.add_argument("--magnets", default="test-resources/public-magnets/magnets.txt")
    p.add_argument("--allow-partial", action="store_true")
    a = p.parse_args()
    validate(a.catalog, a.magnets, a.allow_partial)

if __name__ == "__main__":
    main()
