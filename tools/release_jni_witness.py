#!/usr/bin/env python3
"""Static JNI/R8 compatibility witness for a release-shrunk Chunks APK (A2611-09).

The SWIG-generated libtorrent4j native library looks Java classes up by exact name. This
checks, without an emulator, that every `org/libtorrent4j/...` class name that the packaged
native libraries mention survives R8 *unrenamed and unremoved*, using the R8 outputs of the
same build (mapping.txt and usage.txt), and that the expected ABIs are packaged. It is a
necessary offline witness, not a substitute for the installed smoke on the same APK.

Usage: release_jni_witness.py APK MAPPING USAGE [--abis arm64-v8a,x86_64]
Exit 0 only when every referenced class is retained and every expected ABI is present.
"""
from __future__ import annotations

import argparse
import re
import sys
import zipfile

CLASS_RE = re.compile(rb"org/libtorrent4j/[A-Za-z0-9_/$]+")


def native_class_references(apk: zipfile.ZipFile, abis: list[str]) -> tuple[dict[str, set[str]], list[str]]:
    refs: dict[str, set[str]] = {}
    missing: list[str] = []
    for abi in abis:
        libs = [n for n in apk.namelist() if n.startswith(f"lib/{abi}/") and n.endswith(".so")]
        if not libs:
            missing.append(abi)
            continue
        for lib in libs:
            for raw in CLASS_RE.findall(apk.read(lib)):
                refs.setdefault(raw.decode("ascii").replace("/", "."), set()).add(lib)
    return refs, missing


def mapping_classes(text: str) -> dict[str, str]:
    """Original class name -> obfuscated class name, from R8's mapping.txt."""
    result: dict[str, str] = {}
    for line in text.splitlines():
        if line.startswith((" ", "#")) or " -> " not in line or not line.endswith(":"):
            continue
        original, renamed = line[:-1].split(" -> ", 1)
        result[original.strip()] = renamed.strip()
    return result


def removed_classes(text: str) -> set[str]:
    """Classes R8 removed entirely (usage.txt lists them as unindented names)."""
    return {line.strip() for line in text.splitlines() if line and not line.startswith((" ", "\t", "#"))}


def check(apk_path: str, mapping_text: str, usage_text: str, abis: list[str]) -> tuple[int, list[str]]:
    """Return (referenced native class count, problems); problems empty means compatible."""
    with zipfile.ZipFile(apk_path) as apk:
        refs, missing = native_class_references(apk, abis)
    mapped = mapping_classes(mapping_text)
    removed = removed_classes(usage_text)
    problems: list[str] = []
    if missing:
        problems.append(f"missing ABI libraries: {missing}")
    if not refs:
        problems.append("no org/libtorrent4j class references found in the native libraries")
    for name in sorted(refs):
        # Nested class references appear with `$`; the mapping uses the same binary name.
        if name in removed:
            problems.append(f"REMOVED by R8: {name}")
        elif name in mapped and mapped[name] != name:
            problems.append(f"RENAMED by R8: {name} -> {mapped[name]}")
        elif name not in mapped:
            problems.append(f"not present in mapping (absent from the dex): {name}")
    return len(refs), problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("mapping")
    parser.add_argument("usage")
    parser.add_argument("--abis", default="arm64-v8a,x86_64")
    args = parser.parse_args()
    abis = [a for a in args.abis.split(",") if a]
    count, problems = check(args.apk, open(args.mapping, encoding="utf-8").read(),
                            open(args.usage, encoding="utf-8").read(), abis)
    print(f"CHUNKS_JNI_WITNESS: native_classes={count} abis={abis} problems={len(problems)}")
    for problem in problems:
        print("  " + problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
