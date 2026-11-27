#!/usr/bin/env python3
"""Register-budget ratchet for app methods in the debug APK (follow-up to #2163).

D8 miscompiled a null check in a method with more than 256 registers (an installed launch crash
that no JVM test can see). This keeps that hazard from spreading while A2611-02 decomposes the
known giants: no app method may exceed 255 registers unless it is listed in
tools/dex_register_budget.json, and a listed method may never exceed its recorded budget. Lower a
budget (or delete the entry) when a method shrinks; never raise one without a reviewed reason.

  dex_register_budget.py APK [--budgets tools/dex_register_budget.json]
Exit 1 when any method is over budget.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

DEFAULT_LIMIT = 255
CLASS = re.compile(r"Class descriptor\s*:\s*'L([^;]+);'")
NAME = re.compile(r"^\s+name\s*:\s*'([^']+)'")
REGISTERS = re.compile(r"^\s+registers\s*:\s*(\d+)")


def methods(dump: str, package: str) -> dict[str, int]:
    """Fully qualified method name -> highest register count seen (overloads share a name)."""
    found: dict[str, int] = {}
    cls, name = None, None
    for line in dump.splitlines():
        if (m := CLASS.search(line)):
            cls, name = m.group(1).replace("/", "."), None
        elif (m := NAME.match(line)):
            name = m.group(1)
        elif (m := REGISTERS.match(line)) and cls and name and cls.startswith(package):
            key = f"{cls}.{name}"
            found[key] = max(found.get(key, 0), int(m.group(1)))
    return found


def over_budget(found: dict[str, int], budgets: dict[str, int], limit: int = DEFAULT_LIMIT) -> list[str]:
    problems = []
    for method, registers in sorted(found.items()):
        allowed = budgets.get(method, limit)
        if registers > allowed:
            source = "recorded budget" if method in budgets else "default limit"
            problems.append(f"{method}: {registers} registers > {allowed} ({source})")
    return problems


def dexdump_tool() -> str:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    tools = os.path.join(home, "build-tools")
    versions = sorted(os.listdir(tools)) if os.path.isdir(tools) else []
    if not versions:
        raise SystemExit("dexdump not found: set ANDROID_HOME")
    return os.path.join(tools, versions[-1], "dexdump")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--budgets", default=str(Path(__file__).with_name("dex_register_budget.json")))
    parser.add_argument("--package", default="io.github.gamesbyian.chunks")
    args = parser.parse_args()
    budgets = json.loads(Path(args.budgets).read_text())["budgets"]
    tool = dexdump_tool()
    found: dict[str, int] = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(args.apk) as apk:
        entries = [name for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        if "classes.dex" not in entries or len(entries) != len(set(entries)):
            print("CHUNKS_DEX_REGISTER_BUDGET: unavailable (missing or duplicate DEX entries)", file=sys.stderr)
            return 1
        for entry in entries:
            path = apk.extract(entry, tmp)
            result = subprocess.run([tool, path], capture_output=True)
            dump = result.stdout.decode("utf-8", "replace")
            if result.returncode != 0 or not CLASS.search(dump):
                print("CHUNKS_DEX_REGISTER_BUDGET: unavailable (DEX dump failed or unrecognized)", file=sys.stderr)
                return 1
            for method, registers in methods(dump, args.package).items():
                found[method] = max(found.get(method, 0), registers)
    if not found:
        print("CHUNKS_DEX_REGISTER_BUDGET: unavailable (no app methods measured)", file=sys.stderr)
        return 1
    problems = over_budget(found, budgets)
    largest = sorted(found.items(), key=lambda kv: -kv[1])[:5]
    print("CHUNKS_DEX_REGISTER_BUDGET: methods=%d over=%d largest=%s" % (
        len(found), len(problems), ", ".join(f"{m.rsplit('.', 1)[-1]}={r}" for m, r in largest)))
    for problem in problems:
        print("  " + problem)
    for method, budget in budgets.items():
        if found.get(method, 0) <= budget - 20:
            print(f"  note: {method} now uses {found.get(method, 0)} registers; lower its budget from {budget}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
