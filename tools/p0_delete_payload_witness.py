#!/usr/bin/env python3
"""Read-only, independently observed disposable payload deletion witness.

This checks exact disk bytes before/after a separately authorized engine
mutation. It never performs deletion and CANNOT establish the provenance or
exclusive ownership of a file. Use only after an independent ownership gate,
on explicitly disposable phone/remote fixtures, never as authority to erase.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import unicodedata

HASH = re.compile(r"(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})\Z")
MAX_FILES = 10000


class Refused(ValueError):
    pass


def _paths(manifest: dict) -> tuple[list[str], list[str]]:
    targets, canaries = manifest.get("targets"), manifest.get("canaries")
    if not isinstance(targets, list) or not targets or not isinstance(canaries, list) or not canaries:
        raise Refused("Targets and independent canaries are both required")
    if len(targets) + len(canaries) > MAX_FILES:
        raise Refused("File inventory is too large")
    seen = set()
    for path in targets + canaries:
        if (not isinstance(path, str) or not path or len(path) > 4096 or
                path.startswith("/") or "\\" in path or
                any(ord(ch) < 32 or ord(ch) == 127 for ch in path)):
            raise Refused("Unsafe relative file name")
        parts = path.split("/")
        if any(not part or part in (".", "..") for part in parts):
            raise Refused("File name escapes or aliases a parent")
        key = unicodedata.normalize("NFKC", path).casefold()
        if key in seen:
            raise Refused("Aliased file identity")
        seen.add(key)
    for key in seen:
        parts = key.split("/")
        if any("/".join(parts[:n]) in seen for n in range(1, len(parts))):
            raise Refused("File path overlaps a parent file")
    return targets, canaries


def _root(root: Path) -> Path:
    root = root.absolute()
    # This is an evidence-only tool. Reject symlink roots and ancestors rather
    # than following an indirect root into a different tree.
    current = Path(root.anchor)
    for part in root.parts[1:]:
        current /= part
        try:
            mode = current.lstat().st_mode
        except OSError as exc:
            raise Refused("Root is unavailable") from exc
        if not stat.S_ISDIR(mode):
            raise Refused("Root traverses a non-directory or symbolic link")
    return root


def _file(root: Path, name: str, allow_absent: bool = False):
    current = root
    parts = name.split("/")
    for n, part in enumerate(parts):
        current /= part
        try:
            st = current.lstat()
        except FileNotFoundError:
            if allow_absent:
                return None
            raise Refused("Expected payload or canary is missing")
        except OSError as exc:
            raise Refused("File identity cannot be inspected") from exc
        if n < len(parts) - 1:
            if not stat.S_ISDIR(st.st_mode):
                raise Refused("File path traverses non-directory or symlink")
        elif not stat.S_ISREG(st.st_mode):
            raise Refused("File target is not a regular file")
    if st.st_nlink != 1:
        raise Refused("Hardlinked file has other owners")
    return current, st


def _digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def capture(manifest: dict, root: Path) -> dict:
    targets, canaries = _paths(manifest)
    if not HASH.fullmatch(str(manifest.get("info_hash", ""))):
        raise Refused("Expected exact torrent infohash")
    environment = manifest.get("environment")
    if not isinstance(environment, str) or not environment or len(environment) > 128:
        raise Refused("Expected disposable environment identity")
    base = _root(root)
    records = {}
    for name in targets + canaries:
        path, st = _file(base, name)
        records[name] = {"sha256": _digest(path), "size": st.st_size,
                         "device": st.st_dev, "inode": st.st_ino}
    return {
        "schema": "CHUNKS-P0-DELETE-WITNESS-1",
        "info_hash": manifest["info_hash"].lower(),
        "environment": environment,
        "root_sha256": hashlib.sha256(os.fsencode(base)).hexdigest(),
        "targets": targets,
        "canaries": canaries,
        "files": records,
        "evidence_tier": "independent disposable filesystem snapshot, NOT ownership authorization",
    }


def verify(before: dict, root: Path) -> dict:
    if before.get("schema") != "CHUNKS-P0-DELETE-WITNESS-1":
        raise Refused("Unexpected witness schema")
    targets, canaries = _paths(before)
    base = _root(root)
    if hashlib.sha256(os.fsencode(base)).hexdigest() != before.get("root_sha256"):
        raise Refused("Different filesystem root")
    if not HASH.fullmatch(str(before.get("info_hash", ""))):
        raise Refused("Missing exact torrent identity")
    records = before.get("files")
    if not isinstance(records, dict) or set(records) != set(targets + canaries):
        raise Refused("Incomplete baseline file inventory")
    for name in targets:
        if _file(base, name, allow_absent=True) is not None:
            raise Refused("Declared payload still exists")
    for name in canaries:
        found = _file(base, name)
        if found is None:
            raise Refused("Canary disappeared")
        path, st = found
        expected = records[name]
        if (st.st_dev != expected.get("device") or st.st_ino != expected.get("inode")
                or st.st_size != expected.get("size")
                or _digest(path) != expected.get("sha256")):
            raise Refused("Foreign canary identity or bytes changed")
    return {
        "schema": before["schema"],
        "info_hash": before["info_hash"],
        "environment": before["environment"],
        "targets_absent": len(targets),
        "canaries_unchanged": len(canaries),
        "evidence_tier": "filesystem result only; no source ownership or mutation authority proved",
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["capture", "verify"])
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        manifest = json.loads(args.input.read_text(encoding="utf-8"))
        result = capture(manifest, args.root) if args.operation == "capture" else verify(manifest, args.root)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print("CHUNKS_DELETE_WITNESS: PASS (independent file observation; no ownership authority)")
        return 0
    except (Refused, OSError, ValueError, KeyError, TypeError) as exc:
        # Do not print private host locations, announce URLs, or payload names.
        print("CHUNKS_DELETE_WITNESS: REFUSED (" + type(exc).__name__ + ")", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
