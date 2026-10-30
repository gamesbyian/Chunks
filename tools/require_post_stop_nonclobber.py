#!/usr/bin/env python3
"""Strict post-Stop native foreign-file non-clobber acceptance receipt.

Consumes a *real executed* LocalNativePostAddCollisionProbeTest JUnit XML.
An intentionally green characterization probe can witness data destruction;
this tool must refuse to promote such a run as non-clobber acceptance.
It only validates the printed synthetic witness and test report, not real
physical-device, symlink race, ownership or release-signing evidence.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

FAMILY = "CHUNKS_POST_STOP_COLLISION"
TEST_CLASS = "LocalNativePostAddCollisionProbeTest"
TEST_METHOD = "stoppedPartialJobThenForeignSkippedFileAndRestartCharacterizesNativeWrites"
HASHES = re.compile(
    rf"{FAMILY} before_sha256=([a-f0-9]{{64}}) expected_sha256=([a-f0-9]{{64}}) "
    rf"after_priority_sha256=([a-f0-9]{{64}}) after_sha256=([a-f0-9]{{64}})")
LIFECYCLE = re.compile(
    rf"{FAMILY} first_start_outcome=VERIFIED stop_outcome=VERIFIED "
    rf"selection_outcome=(VERIFIED|UNCERTAIN) restart_outcome=(VERIFIED|UNCERTAIN|REFUSED)")


def verify(root: ET.Element) -> tuple[bool, list[str]]:
    errors: list[str] = []
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    if not suites:
        errors.append("JUnit report has no test suite")
        return False, errors

    tests = failures = skipped = 0
    matches = []
    recorded_cases = 0
    for suite in suites:
        try:
            tests += int(suite.attrib["tests"])
            failures += int(suite.attrib.get("failures", "0")) + int(suite.attrib.get("errors", "0"))
            skipped += int(suite.attrib.get("skipped", "0"))
        except (ValueError, KeyError):
            errors.append("JUnit suite has invalid/incomplete execution counters")
            continue
        recorded_cases += len(suite.findall("testcase"))
        matches.extend(tc for tc in suite.findall("testcase")
                       if TEST_CLASS in tc.get("classname", "")
                       and tc.get("name") == TEST_METHOD)
    if recorded_cases != tests:
        errors.append(f"JUnit case count mismatch: declared={tests}, actual={recorded_cases}")
    if tests < 1 or failures != 0 or skipped != 0:
        errors.append(f"JUnit execution not clean: tests={tests}, failures/errors={failures}, skipped={skipped}")
    if len(matches) != 1:
        errors.append(f"Expected exactly one executed {TEST_CLASS}.{TEST_METHOD}, found {len(matches)}")
    if matches and (matches[0].find("skipped") is not None
                    or matches[0].find("failure") is not None
                    or matches[0].find("error") is not None):
        errors.append("Target case is skipped or failed")
    texts = [el.text or "" for suite in suites for el in suite.iter("system-out")]
    lines = [line.strip() for text in texts for line in text.splitlines()]
    groups: dict[str, str] = {}
    for line in lines:
        if line.startswith(FAMILY + " "):
            key = line.split(" ", 2)[1].partition("=")[0]
            if key in groups:
                errors.append(f"Ambiguous duplicate native witness: {key}")
            groups[key] = line
    if len(groups) != 4:
        errors.append(f"Expected four named native witness lines; got {len(groups)}")
    hashes = HASHES.fullmatch(groups.get("before_sha256", ""))
    if not hashes:
        errors.append("Missing/invalid exact SHA-256 timeline")
    else:
        before, expected, after_priority, final = hashes.groups()
        if before == expected:
            errors.append("Foreign-file fixture and expected torrent bytes are not distinct")
        if before != after_priority:
            errors.append("FOREIGN BYTES CHANGED DURING FILE PRIORITY MUTATION")
        if before != final:
            errors.append("FOREIGN BYTES CHANGED BY END OF STOP/RESTART TEST")
    if groups.get("foreign_file_replaced") != f"{FAMILY} foreign_file_replaced=false":
        errors.append("Missing or unsafe foreign_file_replaced=false")
    if groups.get("expected_torrent_data_present") != f"{FAMILY} expected_torrent_data_present=false":
        errors.append("Missing or unsafe expected_torrent_data_present=false")
    if not LIFECYCLE.fullmatch(groups.get("first_start_outcome", "")):
        errors.append("Missing or invalid verified Start/Stop, priority and restart witness")
    return not errors, errors


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("junit_xml", type=Path)
    args = ap.parse_args(argv)
    try:
        root = ET.parse(args.junit_xml).getroot()
        ok, problems = verify(root)
    except (OSError, ET.ParseError) as exc:
        print(f"BLOCKED: invalid or absent JUnit XML ({type(exc).__name__})", file=sys.stderr)
        return 2
    for problem in problems:
        print("BLOCKED: " + problem, file=sys.stderr)
    if not ok:
        return 1
    print("PASS: synthetic post-Stop foreign file unchanged before/after priority and restart")
    print("LIMIT: no native atomic ownership, external race, persistent recovery or physical-device proof")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
