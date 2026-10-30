#!/usr/bin/env python3
"""Fail-closed independent acceptance of the checked-torrent restoration witness.

The native diagnostic is deliberately GREEN even when a restored checked torrent
overwrites a disposable foreign file. A release safety receipt must reject it.
This program reads actual JUnit XML after a real native run. It neither runs a
torrent nor verifies physical handset/atomic inode ownership.
"""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

FAMILY = "CHUNKS_RESTORE_COLLISION"
CLASS = "LocalNativePostAddCollisionProbeTest"
CASE = "checkedStoppedAddLosesFirstStartCollisionFenceAcrossEngineRestart"
HEX = r"[a-f0-9]{64}"
HASHES = re.compile(
    rf"{FAMILY} before_sha256=({HEX}) expected_sha256=({HEX}) "
    rf"after_restore_sha256=({HEX}) after_sha256=({HEX})")
STATE = re.compile(
    rf"{FAMILY} observed_stopped=(true|false) "
    r"resumed_outcome=(VERIFIED|UNCERTAIN|REFUSED|NOT_FOUND)")
BOOL_FIELDS = ("restore_changed_foreign", "foreign_file_replaced",
               "expected_torrent_data_present")


def verify(root: ET.Element) -> tuple[bool, list[str]]:
    errors: list[str] = []
    if root.tag == "testsuite":
        suites = [root]
    elif root.tag == "testsuites":
        suites = root.findall("testsuite")
    else:
        suites = []
    if not suites:
        return False, ["JUnit XML contains no test suites"]
    declared = observed = failed = skipped = 0
    targets: list[ET.Element] = []
    lines: list[str] = []
    for suite in suites:
        try:
            declared += int(suite.attrib["tests"])
            failed += int(suite.attrib.get("failures", "0"))
            failed += int(suite.attrib.get("errors", "0"))
            skipped += int(suite.attrib.get("skipped", "0"))
        except (KeyError, ValueError):
            errors.append("JUnit test counter absent or invalid")
        cases = suite.findall("testcase")
        observed += len(cases)
        targets.extend(c for c in cases if CLASS in c.attrib.get("classname", "")
                       and c.attrib.get("name") == CASE)
        for output in suite.findall("system-out"):
            lines.extend((output.text or "").splitlines())
    if observed != declared or declared < 1:
        errors.append(f"JUnit testcase count disagrees with report: actual={observed}, declared={declared}")
    if failed or skipped:
        errors.append(f"JUnit execution not clean: failures/errors={failed}, skipped={skipped}")
    if len(targets) != 1:
        errors.append(f"Expected exactly one executed {CLASS}.{CASE}, got {len(targets)}")
    for target in targets:
        if any(target.find(tag) is not None for tag in ("failure", "error", "skipped")):
            errors.append("Target case failed or was skipped")
    evidence = [line.strip() for line in lines if line.strip().startswith(FAMILY + " ")]
    if len(evidence) != 5:
        errors.append(f"Expected exactly five restoration witness lines; got {len(evidence)}")
    hashes = [HASHES.fullmatch(line) for line in evidence]
    hashes = [m for m in hashes if m is not None]
    state = [STATE.fullmatch(line) for line in evidence]
    state = [m for m in state if m is not None]
    if len(hashes) != 1:
        errors.append("Missing or duplicate complete SHA-256 restoration timeline")
    if len(state) != 1:
        errors.append("Missing or duplicate exact restoration outcome")
    booleans: dict[str, bool] = {}
    for field in BOOL_FIELDS:
        pattern = re.compile(rf"{FAMILY} {field}=(true|false)")
        values = [m.group(1) == "true" for line in evidence
                  if (m := pattern.fullmatch(line)) is not None]
        if len(values) != 1:
            errors.append(f"Missing or duplicate {field} witness")
        else:
            booleans[field] = values[0]
    if hashes:
        before, expected, restored, after = hashes[0].groups()
        if before == expected:
            errors.append("Foreign fixture bytes equal expected torrent bytes")
        if before != restored:
            errors.append("FOREIGN BYTES CHANGED DURING ENGINE RESTORATION")
        if before != after:
            errors.append("FOREIGN BYTES CHANGED AFTER RESTORED START")
        expectations = {
            "restore_changed_foreign": before != restored,
            "foreign_file_replaced": before != after,
            "expected_torrent_data_present": after == expected,
        }
        for field, observed_value in booleans.items():
            if observed_value != expectations[field]:
                errors.append(f"{field} contradicts independently supplied SHA-256 timeline")
        for field in BOOL_FIELDS:
            if booleans.get(field) is not False:
                errors.append(f"{field} must be false for non-clobber acceptance")
    if state:
        stopped, outcome = state[0].groups()
        # This case has foreign unowned bytes present before restored Start.
        # Only a blocked Start or no registered handle can be considered safe.
        if outcome not in ("REFUSED", "NOT_FOUND"):
            errors.append(f"Restored native Start not safely blocked: {outcome}")
        if outcome == "REFUSED" and stopped != "true":
            errors.append("A refused restore/Start must leave the native handle stopped")
        if outcome == "NOT_FOUND" and stopped != "false":
            errors.append("An absent restored handle must be recorded as not stopped")
    return not errors, errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("junit_xml", type=Path)
    args = parser.parse_args(argv)
    try:
        root = ET.parse(args.junit_xml).getroot()
    except (OSError, ET.ParseError) as exc:
        print(f"BLOCKED: cannot read JUnit XML ({type(exc).__name__})", file=sys.stderr)
        return 2
    good, failures = verify(root)
    for failure in failures:
        print("BLOCKED: " + failure, file=sys.stderr)
    if good:
        print("PASS: restored checked job refused/absent; foreign bytes unchanged at restore and after Start")
        print("LIMIT: synthetic checked restore only; native atomic file ownership and physical handset unverified")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
