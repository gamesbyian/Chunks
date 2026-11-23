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


# A deliberately narrow exception to the default stack-message redaction:
# one project-owned local fixture test reports only fixed Boolean/integer native
# state. Never forward arbitrary stack/error text or values from sources.
_SAFE_RECHECK_TEST = (
    "io.github.gamesbyian.chunks.LocalStoppedRecheckInstrumentedTest",
    "stoppedMagnetOverPartialDataVerifiesWhenMetadataArrives",
)
_RECHECK_FINGERPRINT = re.compile(
    r"^java\.lang\.AssertionError: stopped-magnet timeout: "
    r"handle=(true|false) metadata=(true|false|null) "
    r"stopped=(true|false|null) stopsAfterCheck=(true|false|null) "
    r"checking=(true|false) valid64KiBpieces=(-?[0-9]{1,12}) "
    r"peers=(-?[0-9]{1,12}) errorPresent=(true|false) "
    r"seedStarted=(true|false) seedPeers=(-?[0-9]{1,12})$"
)


def safe_stopped_magnet_failure(text: str) -> str | None:
    """Only emit verified, bounded condition flags for one failed fixture test.

    The raw instrumentation stream may contain passkeys and private paths. Do
    not copy arbitrary exception text, stdout, source names or Android frames.
    """
    fields: dict[str, str] = {}
    stack: str | None = None
    for line in text.splitlines():
        if line.startswith(STATUS):
            value = line[len(STATUS):]
            if value.startswith("class="):
                fields["class"] = value[len("class="):]
            elif value.startswith("test="):
                fields["test"] = value[len("test="):]
            elif value.startswith("stack="):
                stack = value[len("stack="):]
        elif line.startswith(STATUS_CODE):
            if (line[len(STATUS_CODE):].strip() == "-2" and
                    (fields.get("class"), fields.get("test")) == _SAFE_RECHECK_TEST and
                    stack is not None):
                match = _RECHECK_FINGERPRINT.fullmatch(stack)
                if match:
                    keys = ("handle", "metadata", "stopped", "stopsAfterCheck",
                            "checking", "valid64KiBpieces", "peers", "errorPresent",
                            "seedStarted", "seedPeers")
                    return " ".join(f"{key}={value}" for key, value in zip(keys, match.groups()))
            fields = {}
            stack = None
    return None


# Only app-owned installed Open With fixtures may emit this fixed Boolean
# fingerprint. No arbitrary exception, URI, filename or tracker value passes.
_SAFE_OPENWITH_TESTS = {
    ("io.github.gamesbyian.chunks.InstalledOpenWithHandoffInstrumentedTest",
     "installedSecondOpenWithReentersSameTaskAndQueuesSourceWithoutSubmitting"),
    ("io.github.gamesbyian.chunks.InstalledOpenWithHandoffInstrumentedTest",
     "installedSecondOpenWithReturnsToExistingChunksAndOffersAnExplicitSourceChoice"),
    ("io.github.gamesbyian.chunks.InstalledOpenWithHandoffInstrumentedTest",
     "installedGenericBinaryOpenWithKeepsUnsubmittedReviewAcrossBackgroundAndRecreation"),
}
_OPENWITH_FINGERPRINT = re.compile(
    r"^java\.lang\.AssertionError: openwith second-source timeout: "
    r"intent=(true|false) queued=(true|false) reviewed=(true|false) "
    r"choice=(true|false) finishing=(true|false)$"
)


_OPENWITH_STAGE_FINGERPRINT = re.compile(
    r"^java\.lang\.AssertionError: openwith staged failure: "
    r"stage=(fixture|launch|initial-review|owner|second-source|second-intent|"
    r"delivery|choice|final|explicit-finish|cleanup) "
    r"kind=(timeout|assertion|security|state|other)$"
)


def safe_openwith_failure(text: str) -> str | None:
    fields: dict[str, str] = {}
    stack: str | None = None
    for line in text.splitlines():
        if line.startswith(STATUS):
            value = line[len(STATUS):]
            if value.startswith("class="):
                fields["class"] = value[len("class="):]
            elif value.startswith("test="):
                fields["test"] = value[len("test="):]
            elif value.startswith("stack="):
                stack = value[len("stack="):]
        elif line.startswith(STATUS_CODE):
            if (line[len(STATUS_CODE):].strip() == "-2" and
                    (fields.get("class"), fields.get("test")) in _SAFE_OPENWITH_TESTS and
                    stack is not None):
                match = _OPENWITH_FINGERPRINT.fullmatch(stack)
                if match:
                    keys = ("intent", "queued", "reviewed", "choice", "finishing")
                    return " ".join(f"{key}={value}" for key, value in zip(keys, match.groups()))
                stage = _OPENWITH_STAGE_FINGERPRINT.fullmatch(stack)
                if stage:
                    return "stage=" + stage[1] + " kind=" + stage[2]
            fields = {}
            stack = None
    return None


# This installed controlled-HTTPS case uses only fixed booleans and the
# recognizer's bounded enum, never names, requests, tracker URLs or hashes.
# Retain the original test failure. This is diagnostic, not acceptance.
_SAFE_MEDIA_FIRST_TEST = (
    "io.github.gamesbyian.chunks.InstalledSecondAddMediaConsentInstrumentedTest",
    "phaseA_submitTwoSourcesAndRefreshInventory",
)
_MEDIA_FIRST_FINGERPRINT = re.compile(
    r"^java\.lang\.AssertionError: Controlled first metadata request absent: "
    r"sourceFetched=(true|false) online=(true|false) "
    r"metered=(true|false) powerSaver=(true|false) "
    r"onlineConsent=(true|false) automatic=(true|false) "
    r"showArtwork=(true|false) downloadArtwork=(true|false) "
    r"tvmazeEnabled=(true|false) localTransferPresent=(true|false) "
    r"kind=([A-Z][A-Z0-9_]{0,39})$"
)


def safe_media_first_positive_failure(text: str) -> str | None:
    """Only expose a bounded diagnostic if the entire stack is syntax-safe.

    Android instrumentation prints the throwable's first line under stack=,
    then prints its frames as raw continuation lines. Reject unexpected lines
    rather than silently treating the first line as the entire assertion.
    """
    fields: dict[str, str] = {}
    stack: str | None = None
    stack_continuation = False
    unsafe_suffix = False
    java_frame = re.compile(
        r"^[ \\t]+at [A-Za-z_$][A-Za-z0-9_.$]{0,239}"
        r"\\([A-Za-z0-9_.$<>-]{1,140}(?::[0-9]{1,7})?\\)$"
    )
    omitted_frames = re.compile(r"^[ \\t]*\\.\\.\\. [1-9][0-9]{0,3} more$")
    for line in text.splitlines():
        if line.startswith(STATUS):
            value = line[len(STATUS):]
            stack_continuation = False
            if value.startswith("class="):
                fields["class"] = value[len("class="):]
            elif value.startswith("test="):
                fields["test"] = value[len("test="):]
            elif value.startswith("stack="):
                stack = value[len("stack="):]
                stack_continuation = True
        elif line.startswith(STATUS_CODE):
            if (line[len(STATUS_CODE):].strip() == "-2" and
                    (fields.get("class"), fields.get("test")) == _SAFE_MEDIA_FIRST_TEST and
                    stack is not None and not unsafe_suffix):
                match = _MEDIA_FIRST_FINGERPRINT.fullmatch(stack)
                if match:
                    keys = ("sourceFetched", "online", "metered", "powerSaver",
                            "onlineConsent", "automatic", "showArtwork",
                            "downloadArtwork", "tvmazeEnabled", "localTransferPresent", "kind")
                    return " ".join(f"{key}={value}" for key, value in zip(keys, match.groups()))
            fields = {}
            stack = None
            stack_continuation = False
            unsafe_suffix = False
        elif stack_continuation and not (
                java_frame.fullmatch(line) or omitted_frames.fullmatch(line)):
            unsafe_suffix = True
    return None


# The controlled HTTPS second-Add fixture may fail *before* metadata is
# reviewed. Only local fixture counts and exact Boolean UI-state witnesses are
# allowed into CI. Untrusted stack messages remain redacted in full.
_MEDIA_REVIEW_FAILURE_TESTS = {
    ("io.github.gamesbyian.chunks.InstalledSecondAddMediaConsentInstrumentedTest",
     "phaseA_submitTwoSourcesAndRefreshInventory"),
    ("io.github.gamesbyian.chunks.InstalledSecondAddMediaConsentInstrumentedTest",
     "phaseB_restoredExclusionAndFreshPositiveControl"),
}
_MEDIA_REVIEW_FINGERPRINT = re.compile(
    r"^java\.lang\.AssertionError: Controlled HTTPS review was not retained: "
    r"fixtureSourceRequests=(unavailable|0|[1-9][0-9]{0,3}) "
    r"ownerHasAnyReviewedTorrent=(true|false|unavailable) "
    r"addFooterVisible=(true|false|unavailable) "
    r"sourceFieldVisible=(true|false|unavailable) "
    r"intakeProblem=(NONE|UNAVAILABLE|NO_HOST|LOCAL_NETWORK|OFFLINE|TIMEOUT|"
    r"REFUSED|NOT_FOUND|NOT_TORRENT|UNEXPECTED|BUSY|DOWNGRADE|WEB_PAGE|TOO_LARGE)$"
)


def _safe_timeout_signature(
    text: str, tests: set[tuple[str, str]], signature: re.Pattern[str], timeout_ms: int,
) -> re.Match[str] | None:
    """Match only a named test's exact assertion and allowlisted Java continuation."""
    fields: dict[str, str] = {}
    stack: str | None = None
    stack_continuation = False
    stack_untrusted_suffix = False
    # Android puts Java stack frames on separate raw lines after its initial
    # INSTRUMENTATION_STATUS: stack= assertion. Do not silently ignore an
    # arbitrary second line (including a URL, tracker token or injected text).
    # Recognize syntax only; never print the stack frame or captured contents.
    java_frame = re.compile(
        r"^[ \t]+at [A-Za-z_$][A-Za-z0-9_.$]{0,239}"
        r"\([A-Za-z0-9_.$<>-]{1,140}(?::[0-9]{1,7})?\)$"
    )
    # Java prints an explicit, stable cause for a Compose wait timeout.
    # Accept only that class and fixed duration sentence, never an arbitrary
    # cause message (which may contain user values or a source URL).
    timeout_cause = re.compile(
        r"^Caused by: androidx\.compose\.ui\.test\.ComposeTimeoutException"
        rf"(?:: Condition still not satisfied after {timeout_ms} ms)?$"
    )
    omitted_frames = re.compile(r"^[ \t]*\.\.\. [1-9][0-9]{0,3} more$")
    for line in text.splitlines():
        if line.startswith(STATUS):
            value = line[len(STATUS):]
            stack_continuation = False
            if value.startswith("class="):
                fields["class"] = value[len("class="):]
            elif value.startswith("test="):
                fields["test"] = value[len("test="):]
            elif value.startswith("stack="):
                stack = value[len("stack="):]
                stack_continuation = True
        elif line.startswith(STATUS_CODE):
            if (line[len(STATUS_CODE):].strip() == "-2" and
                    (fields.get("class"), fields.get("test")) in tests and
                    stack is not None and not stack_untrusted_suffix):
                match = signature.fullmatch(stack)
                if match:
                    return match
            fields = {}
            stack = None
            stack_continuation = False
            stack_untrusted_suffix = False
        elif stack_continuation and not (
                java_frame.fullmatch(line) or timeout_cause.fullmatch(line) or
                omitted_frames.fullmatch(line)):
            stack_untrusted_suffix = True
    return None


def safe_media_review_failure(text: str) -> str | None:
    """One bounded failure-only fingerprint; no source URL, hash or stack."""
    match = _safe_timeout_signature(text, _MEDIA_REVIEW_FAILURE_TESTS,
                                    _MEDIA_REVIEW_FINGERPRINT, 30_000)
    if match is None:
        return None
    names = ("fixtureSourceRequests", "ownerHasAnyReviewedTorrent",
             "addFooterVisible", "sourceFieldVisible", "intakeProblem")
    return " ".join(f"{key}={value}" for key, value in zip(names, match.groups()))


_IME_FOCUS_TESTS = {
    ("io.github.gamesbyian.chunks.FooterImeInstalledInstrumentedTest",
     "footerRemainsAvailableThroughSoftKeyboardAndAfterSearchKey"),
}
_IME_FOCUS_FINGERPRINT = re.compile(
    r"java\.lang\.AssertionError: Installed keyboard journey did not acquire Chunks window focus; "
    r"foregroundIsChunks=(true|false|unavailable); "
    r"externalLauncherAnrVisible=(true|false|unavailable); "
    r"externalLauncherWaitClicked=(true|false)\. No input or transfer write was attempted\."
)


def safe_ime_focus_failure(text: str) -> str | None:
    """Expose foreground and exact external ANR witnesses without dumping Android windows."""
    match = _safe_timeout_signature(text, _IME_FOCUS_TESTS, _IME_FOCUS_FINGERPRINT, 15_000)
    if match is None:
        return None
    names = ("foregroundIsChunks", "externalLauncherAnrVisible", "externalLauncherWaitClicked")
    return " ".join(f"{key}={value}" for key, value in zip(names, match.groups()))

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
        safe_recheck = safe_stopped_magnet_failure(raw)
        if safe_recheck is not None:
            print("CHUNKS_PREBUILT_SAFE_STOPPED_MAGNET: " + safe_recheck)
        safe_openwith = safe_openwith_failure(raw)
        if safe_openwith is not None:
            print("CHUNKS_PREBUILT_SAFE_OPENWITH: " + safe_openwith)
        safe_media = safe_media_first_positive_failure(raw)
        if safe_media is not None:
            print("CHUNKS_PREBUILT_SAFE_MEDIA_FIRST: " + safe_media)
        safe_source = safe_media_review_failure(raw)
        if safe_source is not None:
            print("CHUNKS_PREBUILT_SAFE_MEDIA_REVIEW: " + safe_source)
        safe_focus = safe_ime_focus_failure(raw)
        if safe_focus is not None:
            print("CHUNKS_PREBUILT_SAFE_IME_FOCUS: " + safe_focus)
        raise SystemExit("Android instrumentation has failed or no executed tests")


if __name__ == "__main__":
    main()
