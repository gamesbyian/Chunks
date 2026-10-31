#!/usr/bin/env python3
"""Strict raw-Add and deferred-magnet native foreign-byte acceptance receipts.

Consumes real executed LocalNativePostAddCollisionProbeTest JUnit XML.
A green characterization test can still describe destroyed foreign bytes.
This proves neither atomic writer ownership nor physical Android safety.
"""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CLASS = "io.github.gamesbyian.chunks.engine.LocalNativePostAddCollisionProbeTest"
CASES = {
    "CHUNKS_POST_ADD_COLLISION": "newForeignFileAfterStoppedAddIsAnUnprotectedStartTimeCollision",
    "CHUNKS_MAGNET_COLLISION": "deferredMagnetAutoStartCanCollideWithFileIntroducedBeforeMetadata",
}
HASH = re.compile(r"before_sha256=([a-f0-9]{64}) expected_sha256=([a-f0-9]{64}) after_sha256=([a-f0-9]{64})")


def verify(root: ET.Element) -> tuple[bool, list[str]]:
    errors: list[str] = []
    suites = ([root] if root.tag == "testsuite" else
              root.findall("testsuite") if root.tag == "testsuites" else [])
    if not suites:
        return False, ["No JUnit test suite"]
    target_suites: dict[str, list[ET.Element]] = {key: [] for key in CASES}
    target_cases: dict[str, list[ET.Element]] = {key: [] for key in CASES}
    observed = declared = failures = skipped = 0
    for suite in suites:
        cases = suite.findall("testcase")
        observed += len(cases)
        try:
            declared += int(suite.attrib["tests"])
            failures += int(suite.attrib.get("failures", "0")) + int(suite.attrib.get("errors", "0"))
            skipped += int(suite.attrib.get("skipped", "0"))
        except (KeyError, ValueError):
            errors.append("Invalid JUnit execution counters")
        output = "\n".join((e.text or "") for e in suite.iter("system-out"))
        for prefix, method in CASES.items():
            matches = [c for c in cases if c.get("classname") == CLASS and c.get("name") == method]
            target_cases[prefix].extend(matches)
            if matches:
                target_suites[prefix].append(suite)
            elif any(line.strip().startswith(prefix + " ") for line in output.splitlines()):
                errors.append(f"{prefix} witness belongs to unrelated JUnit suite")
    if observed != declared or declared < 1:
        errors.append(f"JUnit case count mismatch: declared={declared}, observed={observed}")
    if failures or skipped:
        errors.append(f"Unclean JUnit execution: failures/errors={failures}, skipped={skipped}")
    for prefix, method in CASES.items():
        matches = target_cases[prefix]
        if len(matches) != 1 or len(target_suites[prefix]) != 1:
            errors.append(f"Expected exactly one executed {CLASS}.{method}")
            continue
        if any(matches[0].find(tag) is not None for tag in ("failure", "error", "skipped")):
            errors.append(f"{prefix} target test was skipped or failed")
        output = "\n".join((e.text or "") for e in target_suites[prefix][0].iter("system-out"))
        lines = [line.strip() for line in output.splitlines()
                 if line.strip().startswith(prefix + " ")]
        if len(lines) != 4:
            errors.append(f"{prefix} must have exactly four native witness lines (found {len(lines)})")

        def one(field: str, pattern: str) -> str | None:
            rx = re.compile(rf"{re.escape(prefix)} {field}={pattern}")
            values = [m.group(1) if m.lastindex else line for line in lines
                      if (m := rx.fullmatch(line)) is not None]
            if len(values) != 1:
                errors.append(f"{prefix} missing/duplicate {field} witness")
                return None
            return values[0]

        timeline = [m for line in lines if (m := re.fullmatch(
            rf"{re.escape(prefix)} {HASH.pattern}", line)) is not None]
        if len(timeline) != 1:
            errors.append(f"{prefix} missing/duplicate complete SHA-256 timeline")
        else:
            before, expected, after = timeline[0].groups()
            if before == expected:
                errors.append(f"{prefix} fixture lacks distinct foreign bytes")
            if before != after:
                errors.append(f"{prefix} FOREIGN BYTES OVERWRITTEN")
        replaced = one("foreign_file_replaced", r"(true|false)")
        present = one("expected_torrent_data_present", r"(true|false)")
        if replaced != "false" or present != "false":
            errors.append(f"{prefix} native outcome flags are unsafe")
        if prefix == "CHUNKS_POST_ADD_COLLISION":
            if one("native_start_outcome", r"(VERIFIED|UNCERTAIN|REFUSED)") != "REFUSED":
                errors.append(f"{prefix} colliding Start not explicitly refused")
        else:
            required = f"{prefix} observed_metadata=true stopped=true upload_only=true"
            if lines.count(required) != 1:
                errors.append(f"{prefix} unsafe magnet metadata/auto-Start state")
    return not errors, errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("junit_xml", type=Path)
    args = parser.parse_args(argv)
    try:
        root = ET.parse(args.junit_xml).getroot()
    except (OSError, ET.ParseError) as exc:
        print(f"BLOCKED: native collision JUnit XML unavailable ({type(exc).__name__})", file=sys.stderr)
        return 2
    ok, errors = verify(root)
    for e in errors:
        print("BLOCKED: " + e, file=sys.stderr)
    if ok:
        print("PASS: raw Start refused, deferred magnet stopped, foreign bytes unchanged")
        print("LIMIT: synthetic native fixture only; writer ownership and physical Android unproven")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
