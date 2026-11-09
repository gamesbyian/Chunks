#!/usr/bin/env python3
"""Fail-closed installed Android receipt plus actual runner-derived JUnit."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import xml.etree.ElementTree as ET
from parse_installed_magnet_add_receipt import parse


_INSTALLED_APK_LINE = re.compile(
    r"CHUNKS_INSTALLED_APK: matched size_bytes=([1-9][0-9]{0,11}) "
    r"sha256=([0-9a-f]{64})\n?\Z"
)


def parse_installed_apk_identity(raw, source_sha, source_tree):
    """Accept one bounded, path-free digest produced after pulling actual Android bytes."""
    if not isinstance(raw, str) or len(raw) > 160:
        return None
    if not isinstance(source_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        return None
    if not isinstance(source_tree, str) or not re.fullmatch(r"[0-9a-f]{40}", source_tree):
        return None
    match = _INSTALLED_APK_LINE.fullmatch(raw)
    if match is None:
        return None
    size = int(match.group(1))
    if size > 500_000_000:
        return None
    return {
        "source_sha": source_sha,
        "git_tree_sha": source_tree,
        "installed_apk_sha256": match.group(2),
        "installed_apk_size_bytes": size,
        "installed_equals_built_bytes": True,
    }


def exact_checkout_tree(source_sha):
    """Bind the report to checkout HEAD, not an unrelated source SHA assertion."""
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "HEAD", "HEAD^{tree}"],
            cwd=Path(__file__).resolve().parents[1],
            capture_output=True, text=True, timeout=5, check=True,
        )
        lines = proc.stdout.splitlines()
        if len(lines) == 2 and lines[0] == source_sha and re.fullmatch(r"[0-9a-f]{40}", lines[1]):
            return lines[1]
    except (OSError, subprocess.SubprocessError):
        pass
    return None


def precompleted_control_preserved(runner_temp, env):
    """Verify both copies against the deterministic disposable input bytes."""
    if not (env.get("CHUNKS_CONTROLLED_PEER") == "1" and
            env.get("CHUNKS_DISPOSABLE") == "1" and
            env.get("CHUNKS_PUBLIC_DISCOVERY") != "1"):
        return None
    run_id = env.get("GITHUB_RUN_ID", "")
    if not re.fullmatch(r"[0-9]{5,20}", run_id):
        return False
    expected = hashlib.sha256(run_id.encode()).digest() * 2048
    paths = (runner_temp / "chunks-real-peer/magnet0/chunks-precompleted-control.bin",
             runner_temp / "chunks-disposable/data/chunks-precompleted-control.bin")
    try:
        states = [path.lstat() for path in paths]
        if any(not stat.S_ISREG(state.st_mode) for state in states) or (
                states[0].st_dev, states[0].st_ino) == (states[1].st_dev, states[1].st_ino):
            return False
        for path in paths:
            with path.open("rb") as stream:
                if stream.read(len(expected) + 1) != expected:
                    return False
        return True
    except OSError:
        return False



# Only fixed protocol stages and source-file basenames may enter CI annotations.
# Never interpolate raw instrumentation, stack traces, credentials, endpoints or
# potentially hostile test labels into an Actions command.
_SAFE_UI_STAGES = frozenset((
    "runner-ready", "connect-click-attempt", "connect-submitted", "queue",
    "add-open-requested", "add-open", "magnet-entered", "place-ready",
    "add-review", "add-submitted", "add-registered", "metadata-already-resolved",
    "read-only-exhausted",
))
_SAFE_FRAME_FILES = frozenset((
    "MainActivityRealMagnetJourney.kt", "SeedhostAndroidAddUiInstrumentedTest.kt",
    "VerifiedAddScreen.kt", "RTorrentRemote.kt", "TransferLifecycleControl.kt",
))


def safe_failure_diagnostic(receipt, peer_valid, control_preserved, journeys_verified=True, apk_valid=True):
    """Bounded causal hints; never change the underlying acceptance decision."""
    runner = receipt.get("runner") if isinstance(receipt.get("runner"), dict) else {}
    failed = runner.get("failed_tests", 0)
    if type(failed) is int and failed > 0:
        reason = "android-test-failed"
    elif not runner.get("runner_finished"):
        reason = "instrumentation-incomplete"
    elif not peer_valid:
        reason = "independent-peer-evidence-not-proven"
    elif control_preserved is False:
        reason = "precompleted-control-bytes-mismatch"
    elif not journeys_verified:
        reason = "required-installed-journey-not-proven"
    elif not apk_valid:
        reason = "installed-apk-provenance-not-proven"
    else:
        reason = "required-case-or-phase-evidence-incomplete"
    stages = receipt.get("app_stage_evidence")
    last = "none"
    if isinstance(stages, list):
        last = next((stage for stage in reversed(stages)
                     if isinstance(stage, str) and stage in _SAFE_UI_STAGES), "none")
    frames = receipt.get("safe_failure_source_frames")
    frame = "none"
    if isinstance(frames, list):
        for entry in frames:
            if not isinstance(entry, dict):
                continue
            file, line = entry.get("file"), entry.get("line")
            if isinstance(file, str) and file in _SAFE_FRAME_FILES and type(line) is int and 1 <= line <= 10000:
                frame = f"{file}:{line}"
                break
    return {"reason": reason, "last_ui_stage": last, "first_safe_frame": frame}


def required_journey_specs(env):
    required = []
    if env.get("CHUNKS_HTTPS_ADD") == "1":
        required.append(("chunks-https-phone-digest.json", "https-phone",
                         "io.github.gamesbyian.chunks.HttpsPhoneAddJourneyInstrumentedTest",
                         "fetchedHttpsTorrentUsesOneTimePlaceThenNextAddUsesUnchangedDefault"))
    if env.get("CHUNKS_REAL_MEDIA") == "1":
        for method in ("phaseA_tvmazeConsentSelectionAndArtwork", "phaseA_musicbrainzSelectionAndArtwork",
                       "phaseA_openlibrarySelectionAndArtwork", "phaseB_tvmazeOfflineProcess",
                       "phaseB_musicbrainzOfflineProcess", "phaseB_openlibraryOfflineProcess"):
            required.append((f"chunks-real-media/{method}-digest.json", f"installed-real-media-{method}",
                             "io.github.gamesbyian.chunks.InstalledRealMediaInstrumentedTest", method))
    return required


def required_journeys_verified(root, env):
    """A remote success cannot publish an APK after another requested journey failed."""
    try:
        for path, suite, classname, method in required_journey_specs(env):
            data = json.loads((root / path).read_text())
            expected_id = hashlib.sha256((classname + "\\0" + method).encode()).hexdigest()
            if (data.get("schema_version") != 1 or data.get("source_sha") != env["GITHUB_SHA"] or
                    data.get("run_id") != env["GITHUB_RUN_ID"] or data.get("suite") != suite or
                    data.get("reported_job_status") != "success" or data.get("evidence_executed") is not True or
                    data.get("test_identity_coverage_complete") is not True or data.get("invalid_reports") != [] or
                    data.get("test_identity_count") != 1 or
                    data.get("test_cases") != [{"id_sha256": expected_id, "outcome": "passed"}] or
                    any(data.get("junit", {}).get(key) != value for key, value in
                        dict(tests=1, executed=1, failed=0, skipped=0, reports=1).items())):
                return False
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return False
    return True


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--runner-temp", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=True)
    def read(name):
        path = a.runner_temp / name
        return path.read_text(errors="replace") if path.is_file() else ""
    try:
        code = int(read("chunks-installed-magnets.exit").strip())
    except ValueError:
        code = -1
    env = dict(os.environ)
    runtime = read("chunks-installed-magnets.runtime").strip()
    env["CHUNKS_ACCEPTANCE_RUNTIME_SECONDS"] = runtime if runtime.isdecimal() else None
    raw = read("chunks-installed-magnets.raw")
    receipt = parse(raw, code, env)
    # This file was written before any Android source or remote lifecycle test
    # could fail. It records actual adb-pulled bytes; no APK is uploaded on red.
    apk_identity = parse_installed_apk_identity(
        read("chunks-installed-apk-identity.txt"),
        env.get("GITHUB_SHA", ""), exact_checkout_tree(env.get("GITHUB_SHA", "")),
    )
    receipt["installed_apk_provenance"] = apk_identity or {"status": "not-proven"}
    if apk_identity is not None:
        (a.output / "APK-IDENTITY.json").write_text(
            json.dumps(apk_identity, indent=2, sort_keys=True) + "\n"
        )
    peer_path = a.runner_temp / "chunks-real-peer/PEER-RECEIPT.json"
    peer_valid = False
    if peer_path.is_file():
        try:
            peer = json.loads(peer_path.read_text())
            peer_valid = peer["source_sha"] == env["GITHUB_SHA"] and peer["run_id"] == env["GITHUB_RUN_ID"]
            for label, source in receipt["sources"].items():
                independent = peer["fixtures"][label]
                peer_valid = peer_valid and (source["exact_infohash"] == independent["hash"] and
                    independent["native_verified_bytes"] >= independent["prefix_bytes"] > 0)
                if receipt["public_peer_discovery"]:
                    # Public-mode acceptance must not secretly use our seed.
                    peer_valid = peer_valid and (independent["stages"] == ["preflight"] and
                        independent["payload_uploaded_bytes"] == independent["peer_connections"] == 0)
                elif label == "magnet0":
                    peer_valid = peer_valid and (independent["stages"] == ["preflight", "metadata"] and
                        independent["payload_uploaded_bytes"] == 0)
                elif source["passed"]:
                    peer_valid = peer_valid and (independent["payload_uploaded_bytes"] > 0 and independent["peer_connections"] > 0)
            shutil.copyfile(peer_path, a.output / "PEER-RECEIPT.json")
        except (ValueError, KeyError, TypeError):
            peer_valid = False
    receipt["application_id"] = "io.github.gamesbyian.chunks." + env.get("CHUNKS_PREVIEW_SUFFIX", "preview")
    receipt["independent_peer_evidence"] = "verified" if peer_valid else "missing-or-inconsistent"
    receipt["independent_evidence_kind"] = "original source pieces checked; supplied peer unused" if receipt["public_peer_discovery"] else "real supplied peer upload and connections"
    control_preserved = precompleted_control_preserved(a.runner_temp, env)
    receipt["precompleted_control_bytes_preserved"] = control_preserved
    journeys_verified = required_journeys_verified(a.runner_temp, env)
    receipt["requested_installed_journeys"] = [spec[1] for spec in required_journey_specs(env)]
    receipt["requested_installed_journeys_verified"] = journeys_verified
    if journeys_verified:
        for path, _, _, _ in required_journey_specs(env):
            source = a.runner_temp / path
            shutil.copyfile(source, a.output / source.name)
    if not peer_valid or control_preserved is False or not journeys_verified or apk_identity is None:
        receipt["result"] = "failed-or-inconclusive"
    if receipt["result"] != "passed":
        receipt["safe_failure_diagnostic"] = safe_failure_diagnostic(
            receipt, peer_valid, control_preserved, journeys_verified, apk_valid=apk_identity is not None)
    (a.output / "RECEIPT.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    # The two test identities and outcomes come from AndroidJUnitRunner, not
    # from successful RPC acknowledgements or invented "passed" XML cases.
    passed = receipt["result"] == "passed"
    expected_cases = ("installedAppAddsOneSyntheticMagnetAtAVerifiedServerPlace",)
    suite = ET.Element("testsuite", name="installed-real-rtorrent-magnets", tests=str(len(expected_cases)),
                       failures="0", errors="0", skipped="0", time=runtime if runtime.isdecimal() else "0")
    failures, errors = 0, 0
    for name in expected_cases:
        case = ET.SubElement(suite, "testcase", classname="io.github.gamesbyian.chunks.SeedhostAndroidAddUiInstrumentedTest", name=name)
        actual = [c for c in receipt["actual_case_results"] if c["name"] == name and c["class"] == "io.github.gamesbyian.chunks.SeedhostAndroidAddUiInstrumentedTest"]
        if len(actual) != 1:
            errors += 1
            ET.SubElement(case, "error", type="RunnerReportMissing", message="Actual Android test completion absent or conflicting")
        elif actual[0]["outcome"] != "passed":
            failures += 1
            ET.SubElement(case, "failure", type="AndroidAssertionFailed", message="Actual Android test failed; see phase receipt")
    if not passed and failures == errors == 0:
        errors += 1
        ET.SubElement(ET.SubElement(suite, "testcase", name="independentAcceptanceEvidence"),
            "error", type="AcceptanceIncomplete", message="Required independent peer or negative evidence missing")
        suite.set("tests", str(len(expected_cases) + 1))
    suite.set("failures", str(failures)); suite.set("errors", str(errors))
    ET.ElementTree(suite).write(a.output / "TEST-installed-magnets.xml", encoding="utf-8", xml_declaration=True)
    raw_path = a.runner_temp / "chunks-installed-magnets.raw"
    raw_path.unlink(missing_ok=True)
    if not passed:
        detail = receipt["safe_failure_diagnostic"]
        # All three fields were selected from fixed allowlists / bounded numbers.
        print("::error title=Chunks real-magnet evidence::"
              f"reason={detail['reason']} "
              f"last_ui_stage={detail['last_ui_stage']} "
              f"first_safe_frame={detail['first_safe_frame']}", flush=True)
        raise SystemExit("Installed real-magnet acceptance incomplete; no qualified APK")
    source = env["GITHUB_SHA"]
    if not re.fullmatch("[a-f0-9]{40}", source):
        raise SystemExit("Invalid source provenance")
    apk = Path("app/build/outputs/apk/debug/app-debug.apk")
    intake = "https" if env.get("CHUNKS_HTTPS_ADD") == "1" else "magnet"
    apk_name = "chunks-" + intake + "-verified-preview-" + source[:12] + ".apk"
    shutil.copyfile(apk, a.output / apk_name)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (a.output / "CHUNKS-BUILD-SHA256.txt").write_text(
        f"# source_sha={source}\n# run_id={env['GITHUB_RUN_ID']}\n# debug-signed preview; API35 emulator; physical/OEM not tested\n{digest}  {apk_name}\n")
    print("CHUNKS_INSTALLED_SOURCE: two public " + intake +
          " sources, UI controls, resumed verified pieces, peerless truth and cleanup passed")


if __name__ == "__main__":
    main()
