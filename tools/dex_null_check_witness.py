#!/usr/bin/env python3
"""Offline witness for a D8 register-allocation miscompile in app methods (found 2026-11-27).

In `CombinedTransfersScreen`, a method with more than 256 registers, D8 emitted
`if-eqz v0, L` immediately followed by `invoke-virtual/range {v256}`: the null check read a low
register that last held an unrelated OBJECT (a MutableState), while the call went through the real,
null, `server` parameter. Kotlin's `server?.aliasScope` became an unconditional call and every
installed run crashed with an NPE; no JVM/Robolectric test can see it (#2163).

This flags that signature: a null branch on vA immediately followed by a range invoke whose receiver
vB >= v256 differs from vA, where vA was last written by an object-producing instruction. Branches
on an int/boolean flag (`if (flag && x.call())`) are the common benign shape and are not flagged.
The scan is linear (it ignores control flow), so it is a witness, not a verifier.

  dex_null_check_witness.py APK [--package io.github.gamesbyian]
Exit 1 when any suspect is found.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile

METHOD = re.compile(r"\|\[[0-9a-f]+\] (\S+):\(")
INSTRUCTION = re.compile(r"\|([0-9a-f]{4}): (\S+) (.*)")
NO_DESTINATION = ("invoke", "if-", "return", "throw", "goto", "packed", "sparse", "fill", "filled",
                  "aput", "iput", "sput", "monitor", "nop")
OBJECT_WRITES = ("move-object", "move-result-object", "check-cast", "new-instance", "new-array",
                 "const-string", "const-class", "iget-object", "sget-object", "aget-object")


def scan(dump: str, package: str) -> list[tuple[str, str, int, int, str]]:
    suspects: list[tuple[str, str, int, int, str]] = []
    method, last, pending = None, {}, None
    for line in dump.splitlines():
        m = METHOD.search(line)
        if m:
            method, last, pending = m.group(1), {}, None
            continue
        ins = INSTRUCTION.search(line)
        if not ins:
            continue
        at, op, args = ins.groups()
        if pending is not None and op.startswith("invoke") and "/range" in op:
            receiver = int(re.search(r"\{v(\d+)", args).group(1))
            checked_at, checked = pending
            writer = last.get(checked, "")
            if (receiver >= 256 and receiver != checked and method and package in method
                    and writer.startswith(OBJECT_WRITES)):
                suspects.append((method, checked_at, checked, receiver, writer))
        pending = None
        if op in ("if-eqz", "if-nez"):
            pending = (at, int(re.match(r"v(\d+)", args).group(1)))
        destination = re.match(r"v(\d+)", args)
        if destination and not op.startswith(NO_DESTINATION):
            last[int(destination.group(1))] = op
    return suspects


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
    parser.add_argument("--package", default="io.github.gamesbyian")
    args = parser.parse_args()
    tool = dexdump_tool()
    suspects = []
    observed_app_code = False
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(args.apk) as apk:
        entries = [name for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        if "classes.dex" not in entries or len(entries) != len(set(entries)):
            print("CHUNKS_DEX_NULL_CHECK_WITNESS: unavailable (missing or duplicate DEX entries)", file=sys.stderr)
            return 1
        for name in entries:
            path = apk.extract(name, tmp)
            result = subprocess.run([tool, "-d", path], capture_output=True)
            dump = result.stdout.decode("utf-8", "replace")
            if result.returncode != 0 or not re.search(r"Class descriptor\s*:", dump):
                print("CHUNKS_DEX_NULL_CHECK_WITNESS: unavailable (DEX dump failed or unrecognized)", file=sys.stderr)
                return 1
            # An individual library DEX need not contain app code, but the
            # complete APK must have recognized app disassembly.
            in_app_method = False
            for line in dump.splitlines():
                header = METHOD.search(line)
                if header:
                    in_app_method = args.package in header.group(1)
                elif in_app_method and INSTRUCTION.search(line):
                    observed_app_code = True
            suspects += scan(dump, args.package)
    if not observed_app_code:
        print("CHUNKS_DEX_NULL_CHECK_WITNESS: unavailable (no app instructions observed)", file=sys.stderr)
        return 1
    print(f"CHUNKS_DEX_NULL_CHECK_WITNESS: suspects={len(suspects)}")
    for method, at, checked, receiver, writer in suspects:
        print(f"  {method} @{at}: null check of v{checked} (last {writer}), call through v{receiver}")
    return 1 if suspects else 0


if __name__ == "__main__":
    sys.exit(main())
