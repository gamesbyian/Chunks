#!/usr/bin/env python3
"""Invariant-breaking controls (A2611-08): break one safety invariant at a time and see whether
the JVM tests notice. KILLED = a listed test failed (the invariant has a witness). SURVIVED =
no listed test failed (a witness gap, to be closed with a test, never hidden).

  python3 tools/invariant_controls.py --list
  python3 tools/invariant_controls.py --run [id ...]   # needs the local toolchain (docs/BUILD.md)

The mutated file is restored in a finally block and verified byte-identical afterwards.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTROLS = ROOT / "tools" / "invariant_controls.json"


def load_controls(path: Path = CONTROLS) -> list[dict]:
    data = json.loads(path.read_text())
    controls = data["controls"]
    seen = set()
    for control in controls:
        for key in ("id", "invariant", "file", "old", "new", "tests"):
            if key not in control:
                raise ValueError(f"control missing {key}: {control}")
        if control["id"] in seen:
            raise ValueError(f"duplicate control id {control['id']}")
        seen.add(control["id"])
        if control["old"] == control["new"]:
            raise ValueError(f"control {control['id']} changes nothing")
    return controls


def mutate(text: str, control: dict) -> str:
    if text.count(control["old"]) != 1:
        raise ValueError(f"{control['id']}: the target must occur exactly once (found {text.count(control['old'])})")
    return text.replace(control["old"], control["new"], 1)


def run_control(control: dict, gradle: list[str], root: Path = ROOT, log: Path | None = None) -> str:
    path = root / control["file"]
    original = path.read_bytes()
    try:
        path.write_text(mutate(original.decode(), control))
        args = [a for t in control["tests"] for a in ("--tests", t)]
        result = subprocess.run(gradle + [":app:testDebugUnitTest"] + args, cwd=root,
                                capture_output=True, text=True)
        out = result.stdout + result.stderr
        if log is not None and log.exists():
            out += log.read_text(errors="replace")
        if "COMPILATION ERROR" in out or "\ne: " in out:
            return "ERROR-compile"
        return "KILLED" if result.returncode != 0 else "SURVIVED"
    finally:
        path.write_bytes(original)
        if path.read_bytes() != original:
            raise RuntimeError("restore failed for " + control["file"])


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--run", nargs="*", metavar="ID")
    parser.add_argument("--gradle", default="gradle --no-daemon --console=plain",
                        help="command prefix that runs gradle, e.g. '/opt/dl/gb.sh /tmp/x.log' in the agent container")
    parser.add_argument("--log", type=Path, help="log file the gradle command writes, scanned for compile errors")
    args = parser.parse_args(argv)
    controls = load_controls()
    if args.list or args.run is None:
        for c in controls:
            print(f"{c['id']}: {c['invariant']}")
        return 0
    chosen = [c for c in controls if not args.run or c["id"] in args.run]
    survivors = 0
    for control in chosen:
        verdict = run_control(control, args.gradle.split(), log=args.log)
        print(f"CHUNKS_INVARIANT_CONTROL {control['id']} {verdict}")
        survivors += verdict != "KILLED"
    return 1 if survivors else 0


if __name__ == "__main__":
    sys.exit(main())
