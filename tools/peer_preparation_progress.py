#!/usr/bin/env python3
"""Bounded, secret-free status of the disposable real-peer fixture preparation.

Only fixed phase and fixture names are persisted; public source URLs, torrent
metainfo, tracker data, credentials, paths and native alerts are never logged.
This is diagnostic only and cannot qualify a source or authorize an Add.
"""
import argparse
import json
from pathlib import Path

PHASES = frozenset(("publisher-metainfo", "publisher-prefix", "native-piece-check", "source-verified"))
FIXTURES = frozenset(("magnet", "magnet2", "magnet3", "magnet4", "magnet5", "magnet0", "all"))
NAME = "PREPARATION-PROGRESS.json"


def write_progress(root: Path, phase: str, fixture: str) -> bool:
    if phase not in PHASES or fixture not in FIXTURES:
        raise ValueError("Unrecognized fixture preparation milestone")
    try:
        temp = root / ".PREPARATION-PROGRESS.tmp"
        temp.write_text(json.dumps({"phase": phase, "fixture": fixture}) + "\n",
                        encoding="ascii")
        temp.replace(root / NAME)
        return True
    except OSError:
        # Best-effort diagnostics must never turn a valid source into a false red.
        return False


def read_progress(root: Path):
    try:
        path = root / NAME
        if path.stat().st_size > 200:
            return None
        data = json.loads(path.read_text(encoding="ascii"))
        if not isinstance(data, dict) or set(data) != {"phase", "fixture"}:
            return None
        if data["phase"] in PHASES and data["fixture"] in FIXTURES:
            return data["phase"], data["fixture"]
    except (OSError, ValueError, UnicodeError, TypeError, KeyError):
        pass
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    state = read_progress(args.root)
    if state is None:
        print("CHUNKS_PEER_PREPARATION: milestone=unavailable")
    else:
        print(f"CHUNKS_PEER_PREPARATION: phase={state[0]} fixture={state[1]}")


if __name__ == "__main__":
    main()
