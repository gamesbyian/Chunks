#!/usr/bin/env python3
"""Fail closed when raw .torrent metadata is tracked in this Git checkout.

Real public .torrent test inputs are obtained via the source-reviewed fixture
catalog. Private tracker metainfo can contain passkeys in announce URLs; this
check does not read file contents or print even the matched filenames.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys


def tracked_torrent_count(root: Path) -> int:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "--cached", "-z"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False,
        timeout=20,
    )
    if result.returncode != 0:
        raise RuntimeError("Cannot inspect tracked Git index; refusing verification")
    return sum(
        bool(path) and path.lower().endswith(b".torrent")
        for path in result.stdout.split(b"\0")
    )


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    try:
        count = tracked_torrent_count(args.root)
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        print("ERROR: Git index hygiene verification unavailable", file=sys.stderr)
        return 2
    if count:
        print(
            f"ERROR: {count} raw .torrent metainfo file(s) tracked; "
            "remove them from Git and use reviewed public external fixtures. "
            "Filenames and credentials intentionally omitted.",
            file=sys.stderr,
        )
        return 1
    print("Tracked torrent metainfo hygiene: 0 raw .torrent files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
