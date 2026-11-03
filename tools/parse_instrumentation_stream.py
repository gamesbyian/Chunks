#!/usr/bin/env python3
"""Fail-closed AndroidJUnitRunner raw-status to JUnit adapter for a CI experiment.

Input must come from actual 'adb shell am instrument -w -r'. Only conventional
test identities and pass/fail/skip outcomes enter XML, never raw stack traces,
stdout, endpoints or environment values.
"""
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path
import xml.etree.ElementTree as ET

STATUS = "INSTRUMENTATION_STATUS: "
STATUS_CODE = "INSTRUMENTATION_STATUS_CODE: "
RESULT_CODE = "INSTRUMENTATION_CODE: "
OUTCOMES = {0: "passed", -1: "error", -2: "failed", -3: "skipped", -4: "skipped"}


def failure_locations(text: str) -> list[str]:
    """Keep only bounded project source frames from failed status records.

    Exception messages, stdout, external frames and request values never enter
    this diagnostic. Original outcomes and structural JUnit remain unchanged.
    """
    frame = re.compile(
        r"\s*at (io\.github\.gamesbyian\.chunks\.[A-Za-z0-9_.$]{1,200}"
        r"\([A-Za-z0-9_]{1,100}\.kt:[1-9][0-9]{0,6}\))\s*")
    pending: list[str] = []
    result: list[str] = []
    in_stack = False
    for line in text.splitlines():
        if line.startswith(STATUS_CODE):
            if line[len(STATUS_CODE):].strip() in ("-1", "-2"):
                for location in pending:
                    if location not in result and len(result) < 16:
                        result.append(location)
            pending = []
            in_stack = False
        elif line.startswith(STATUS):
            in_stack = line.startswith(STATUS + "stack=")
        elif in_stack and len(pending) < 16:
            match = frame.fullmatch(line)
            if match and match[1] not in pending:
                pending.append(match[1])
    return result


@dataclass(frozen=True)
class TestResult:
    classname: str
    method: str
    outcome: str


def parse_stream(text: str) -> list[TestResult]:
    fields: dict[str, str] = {}
    started: set[tuple[str, str]] = set()
    finished: dict[tuple[str, str], TestResult] = {}
    announced: set[int] = set()
    finals: list[int] = []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith(STATUS):
            value = line[len(STATUS):]
            if "=" in value:
                key, content = value.split("=", 1)
                if key in ("class", "test", "numtests"):
                    fields[key] = content.strip()
            continue
        if line.startswith(RESULT_CODE):
            try:
                finals.append(int(line[len(RESULT_CODE):].strip()))
            except ValueError as exc:
                raise ValueError("Invalid instrumentation terminator") from exc
            continue
        if not line.startswith(STATUS_CODE):
            continue
        try:
            code = int(line[len(STATUS_CODE):].strip())
        except ValueError as exc:
            raise ValueError("Invalid instrumentation status code") from exc
        if "numtests" in fields:
            try:
                announced.add(int(fields["numtests"]))
            except ValueError as exc:
                raise ValueError("Invalid declared test count") from exc
        ident = (fields.get("class"), fields.get("test"))
        if not all(ident) or code not in (1, *OUTCOMES):
            raise ValueError("Unrecognized or unidentifiable Android test status")
        if code == 1:
            if ident in started or ident in finished:
                raise ValueError("Repeated test start")
            started.add(ident)
        else:
            if ident in finished:
                raise ValueError("Duplicated completed testcase")
            if ident not in started and code != -3:
                raise ValueError("Test completion without start")
            finished[ident] = TestResult(ident[0], ident[1], OUTCOMES[code])
        fields = {}
    if finals != [-1]:
        raise ValueError("Missing or non-successful instrumentation terminator")
    if not finished or started - finished.keys():
        raise ValueError("Missing completed test events")
    if not announced or announced != {len(finished)}:
        raise ValueError("Declared Android test count differs from completed identities")
    return list(finished.values())


def write_junit(cases: list[TestResult], path: Path) -> None:
    failures = sum(x.outcome == "failed" for x in cases)
    errors = sum(x.outcome == "error" for x in cases)
    skipped = sum(x.outcome == "skipped" for x in cases)
    suite = ET.Element("testsuite", {
        "name": "ChunksPrebuiltAndroidJUnitRunner",
        "tests": str(len(cases)), "failures": str(failures),
        "errors": str(errors), "skipped": str(skipped),
    })
    for result in cases:
        case = ET.SubElement(suite, "testcase", {
            "classname": result.classname, "name": result.method,
        })
        if result.outcome == "failed":
            ET.SubElement(case, "failure", message="instrumentation reported failure")
        elif result.outcome == "error":
            ET.SubElement(case, "error", message="instrumentation reported error")
        elif result.outcome == "skipped":
            ET.SubElement(case, "skipped")
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--raw", type=Path, required=True)
    p.add_argument("--junit", type=Path, required=True)
    args = p.parse_args()
    raw = args.raw.read_text(encoding="utf-8", errors="replace")
    cases = parse_stream(raw)
    write_junit(cases, args.junit)
    values = {kind: sum(x.outcome == kind for x in cases)
              for kind in ("passed", "failed", "error", "skipped")}
    print("CHUNKS_PREBUILT_INSTRUMENT: " + " ".join(
        f"{kind}={values[kind]}" for kind in ("passed", "failed", "error", "skipped")))
    if values["failed"] or values["error"] or not values["passed"]:
        for location in failure_locations(raw):
            print("CHUNKS_PREBUILT_FAILURE_LOCATION: " + location)
        raise SystemExit("Android instrumentation has failed or no executed tests")


if __name__ == "__main__":
    main()
