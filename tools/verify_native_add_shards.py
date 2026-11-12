#!/usr/bin/env python3
"""Fail-closed coordinator for AndroidJUnitRunner's complete sharded Add suite.

Real JUnit execution is mandatory on every shard; required screenshots may be
produced by either shard. Nothing here treats absent stages or files as green.
Never reads Android test logs or prints environment secrets.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import sys

from check_native_atlas_contract import required_images

REPO = Path(__file__).resolve().parent.parent
# Authoritative accepted JUnit run 41367486529: 334 cases, 306 executed,
# 28 deliberately skipped. Android console's '362 tests' is NOT 362 executed.
BASELINE_JUNIT_TESTS = 334
BASELINE_EXECUTED_TESTS = 306
MAX_BASELINE_SKIPPED = 34  # 28 accepted skips + 1 opt-in native delete research
#   + 1 CombinedTwoRealServersInstrumentedTest (runs only in its two-server workflow)
#   + 1 CombinedThreeSeedhostReadOnlyInstrumentedTest (protected manual dispatch only)
#   + 1 LocalIntakeJourney phaseDelete (runs only from tools/local_intake_journey.sh)
#   + 1 CombinedTwoRealServers process-death phase 2 (two-server workflow only)
#   + 1 RemoteDeleteDataInstalledInstrumentedTest (disposable ruTorrent workflow only)
# The new HTTPS local case executes with its public-piece host in the installed
# source campaign. Permit only that exact additional opt-in identity here;
# preserve the existing ceiling for every other skipped case.
HTTPS_LOCAL_ID = hashlib.sha256((
    "io.github.gamesbyian.chunks.HttpsLocalAddJourneyInstrumentedTest" + "\\0" +
    "fetchedHttpsTorrentUsesOneTimePlaceThenNextAddUsesUnchangedDefault").encode()).hexdigest()

# Only these two exact public-TVmaze installed-media cases are eligible to skip
# outside their explicitly authorized one-shot emulator workflow. The normal
# native Add suite must still execute every unrelated case and retain the
# original baseline. Never relax MAX_BASELINE_SKIPPED globally.
INSTALLED_PUBLIC_MEDIA_OPT_IN_IDS = frozenset(
    hashlib.sha256((
        "io.github.gamesbyian.chunks.InstalledAutomaticPublicMediaInstrumentedTest" +
        "\\0" + name).encode()).hexdigest()
    for name in (
        "phaseA_liveAutomaticArtworkWithoutIdentify",
        "phaseB_offlineColdProcessRestoresRealArtwork",
    )
)


def verify(root, count, sha, screenshot_manifest=None):
    if not 1 < count <= 4:
        raise ValueError("invalid shard count")
    manifest = screenshot_manifest
    if manifest is None:
        manifest = required_images((REPO / "tools/native_atlas_manifest.json").read_text())
    totals = {"tests": 0, "executed": 0, "failed": 0, "skipped": 0, "reports": 0}
    summaries = []
    known_test_ids = set()
    https_local_skipped = 0
    installed_media_opt_in_skipped = 0
    for index in range(count):
        path = root / f"chunks-native-add-receipt-shard-{index}" / "digest.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("schema_version") != 1 or data.get("source_sha") != sha:
            raise ValueError(f"shard {index} incorrect source or schema")
        if data.get("suite") != f"api35-native-add-shard-{index}":
            raise ValueError(f"shard {index} incorrect receipt identity")
        j = data.get("junit", {})
        if (data.get("reported_job_status") != "success" or
                not data.get("evidence_executed") or
                data.get("invalid_reports") or
                int(j.get("reports", 0)) <= 0 or
                int(j.get("executed", 0)) <= 0 or
                int(j.get("failed", 0)) != 0):
            raise ValueError(f"shard {index} lacks clean executed JUnit evidence")
        cases = data.get("test_cases", [])
        if not data.get("test_identity_coverage_complete") or len(cases) != int(j.get("tests", -1)):
            raise ValueError("incomplete per-test receipt")
        for case in cases:
            ident = case.get("id_sha256", "")
            if len(ident) != 64 or any(ch not in "0123456789abcdef" for ch in ident) or ident in known_test_ids:
                raise ValueError("invalid or overlapping shard identity")
            known_test_ids.add(ident)
            if case.get("outcome") not in ("passed", "skipped"):
                raise ValueError("failed test in accepted shard")
            if ident == HTTPS_LOCAL_ID and case["outcome"] == "skipped":
                https_local_skipped += 1
            if ident in INSTALLED_PUBLIC_MEDIA_OPT_IN_IDS and case["outcome"] == "skipped":
                installed_media_opt_in_skipped += 1
        if int(j.get("tests", -1)) != int(j["executed"]) + int(j["skipped"]):
            raise ValueError("shard inconsistent JUnit skip/execute counts")
        if sum(case["outcome"] == "skipped" for case in cases) != int(j["skipped"]):
            raise ValueError("shard skipped count contradicts per-case outcomes")
        for key in totals:
            totals[key] += int(j[key])
        print("CHUNKS_NATIVE_SHARDS_PHASE: shard=" + str(index) +
              " reports=" + str(j["reports"]) +
              " executed=" + str(j["executed"]) +
              " skipped=" + str(j["skipped"]) +
              " failures=" + str(j["failed"]))
        summaries.append({
            "shard": index, "executed": j["executed"],
            "skipped": j["skipped"], "failed": j["failed"],
            "top_slow_classes": data.get("timing", {}).get("slowest_classes", [])[:3],
        })
    if len(known_test_ids) != totals['executed'] + totals['skipped']:
        raise ValueError('missing test-case identities')
    if (totals['tests'] < BASELINE_JUNIT_TESTS or
            totals['executed'] < BASELINE_EXECUTED_TESTS or
            totals['skipped'] - https_local_skipped -
            installed_media_opt_in_skipped > MAX_BASELINE_SKIPPED):
        raise ValueError('below accepted complete JUnit coverage or excessive skips')
    screenshots = {}
    for name in manifest:
        candidates = []
        for index in range(count):
            path = root / f"chunks-native-add-atlas-shard-{index}" / name
            if path.is_file():
                raw = path.read_bytes()
                if (raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR"
                        or len(raw) < 24):
                    raise ValueError(f"invalid PNG: {name}")
                w, h = struct.unpack(">II", raw[16:24])
                if w < 300 or h < 500:
                    raise ValueError(f"invalid screenshot dimensions: {name}")
                candidates.append(index)
        if not candidates:
            raise ValueError(f"required native screenshot missing across all shards: {name}")
        screenshots[name] = candidates
    return {"result": "passed", "source_sha": sha, "shards": summaries,
            "junit": totals, "mandatory_real_png_count": len(screenshots),
            "screenshot_shards": screenshots}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", required=True)
    parser.add_argument("--shards", type=int, required=True)
    parser.add_argument("--sha", required=True)
    args = parser.parse_args()
    try:
        result = verify(Path(args.artifacts), args.shards, args.sha)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        # Manifest filenames are public; never print JUnit or screenshot contents.
        print(f"CHUNKS_NATIVE_SHARDS: result=failed class={type(exc).__name__}")
        raise SystemExit(1)
    print("CHUNKS_NATIVE_SHARDS: result=passed tests=" +
          str(result["junit"]["tests"]) + " executed=" +
          str(result["junit"]["executed"]) +
          " failed=0 skipped=" + str(result["junit"]["skipped"]) +
          " real_pngs=" + str(result["mandatory_real_png_count"]) +
          " shard_executed=" + ",".join(str(v["executed"]) for v in result["shards"]))
    receipt = Path(args.artifacts) / "native-add-acceptance.json"
    receipt.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    step_summary = os.getenv("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as out:
            out.write(f"### Native Compose Add acceptance\n\n")
            out.write(f"- **PASS** {args.shards} real API35 emulator shards, "
                      f"{result['junit']['executed']} executed test cases, "
                      f"{result['mandatory_real_png_count']} real screenshot checks\n")
            for item in result["shards"]:
                out.write(f"- Shard {item['shard']}: {item['executed']} executed; "
                          f"{item['skipped']} skipped; 0 failed\n")
            out.write(f"- Exact source SHA: {args.sha}\n")


if __name__ == "__main__":
    main()
