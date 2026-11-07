#!/usr/bin/env python3
"""Export only bounded numeric disposable-peer protocol evidence on a failed installed run.

The upstream peer controller records richer in-run state; never upload that
raw internal file, process logs, addresses, torrent URLs or payload bytes.
This diagnostic cannot determine or alter the acceptance result.
"""
import argparse
import json
import os
from pathlib import Path
import re

SOURCES = ("magnet0", "magnet", "magnet3", "magnet2", "magnet4")
PHASES = frozenset(("preflight", "metadata", "payload", "resume"))
METRICS = ("epoch_ms", "verified_bytes", "available_pieces",
           "uploaded_bytes", "upload_rate", "peer_count")
PEER_METRICS = ("flags", "pieces", "upload_queue", "download_queue", "uploaded_bytes")
REQUEST_METRICS = ("epoch_ms", "piece", "offset", "length")
EVENTS = frozenset(
    prefix + action for prefix in ("in_", "out_") for action in
    ("request", "cancel", "choke", "unchoke", "interested",
     "not_interested", "have", "bitfield")
)
MAX_INPUT_BYTES = 6_000_000
MAX_SAMPLES = 180
MAX_OUTPUT_BYTES = 2_000_000


def nonnegative_int(value):
    if type(value) is not int or not 0 <= value < 2**63:
        raise ValueError("Invalid numeric protocol sample")
    return value


def numbers(source, keys):
    if not isinstance(source, dict):
        raise ValueError("Malformed numeric protocol sample")
    return {key: nonnegative_int(source[key]) for key in keys if key in source}


def sanitize(raw, source_sha):
    if not re.fullmatch(r"[a-f0-9]{40}", source_sha) or not isinstance(raw, dict):
        raise ValueError("Unbound numeric protocol evidence")
    result = {"schema_version": 1, "source_sha": source_sha, "fixtures": {}}
    for label in SOURCES:
        entries = raw.get(label, [])
        if not isinstance(entries, list):
            raise ValueError("Malformed peer timeline")
        samples = []
        for entry in entries[-MAX_SAMPLES:]:
            if not isinstance(entry, dict) or entry.get("phase") not in PHASES:
                raise ValueError("Unknown protocol phase")
            sample = numbers(entry, METRICS)
            if not set(METRICS).issubset(sample):
                raise ValueError("Incomplete numeric peer sample")
            sample["phase"] = entry["phase"]
            sample["events"] = numbers(entry.get("events"), EVENTS)
            for key, metric_keys in (("recent_requests", REQUEST_METRICS),
                                      ("peers", PEER_METRICS)):
                items = entry.get(key)
                if not isinstance(items, list):
                    raise ValueError("Missing numeric protocol records")
                sample[key] = [numbers(item, metric_keys) for item in items[-8:]]
            samples.append(sample)
        result["fixtures"][label] = samples
    return result


def export(source, destination, source_sha):
    if not source.is_file() or source.stat().st_size > MAX_INPUT_BYTES:
        raise ValueError("Unavailable bounded peer timeline")
    receipt = sanitize(json.loads(source.read_text(encoding="utf-8")), source_sha)
    encoded = json.dumps(receipt, separators=(",", ":"), sort_keys=True) + "\n"
    if len(encoded.encode("utf-8")) > MAX_OUTPUT_BYTES:
        raise ValueError("Oversize numeric peer timeline")
    destination.write_text(encoded, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        export(args.source, args.output, os.environ.get("GITHUB_SHA", ""))
    except (OSError, ValueError, TypeError, UnicodeError, json.JSONDecodeError):
        # Never echo source bytes, paths, tracker names or exception text.
        raise SystemExit("CHUNKS_PEER_NUMERIC_EXPORT: unavailable")
    print("CHUNKS_PEER_NUMERIC_EXPORT: bounded protocol evidence only")


if __name__ == "__main__":
    main()
