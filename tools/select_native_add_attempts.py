#!/usr/bin/env python3
"""Select each shard's latest source-bound artifact after GitHub Actions reruns.

A failed-job rerun leaves artifacts from earlier attempts attached to the same
workflow run. In particular, download-artifact's name/pattern collision
resolution is NOT a reliable latest-attempt policy. Preserve original
attempt-scoped directories and copy only the selected receipt and images into
the paths expected by verify_native_add_shards.py.

Never fall back to an earlier passing shard when a later attempted shard failed.
This code reads only our bounded, secret-free JUnit digest and PNG paths.
"""
import argparse
import json
from pathlib import Path
import re
import shutil


def select(root: Path, count: int, sha: str, shards_result: str) -> list[tuple[int, int]]:
    if shards_result != "success":
        raise ValueError("the required Android shard matrix did not succeed")
    if not 1 < count <= 4 or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("invalid source identity or shard count")
    if not root.is_dir() or root.is_symlink():
        raise ValueError("missing or indirect artifact directory")

    chosen = []
    for index in range(count):
        pattern = re.compile(
            rf"chunks-native-add-receipt-shard-{index}-attempt-([1-9][0-9]{{0,5}})"
        )
        attempts = {}
        for entry in root.iterdir():
            match = pattern.fullmatch(entry.name)
            if not match:
                continue
            if not entry.is_dir() or entry.is_symlink():
                raise ValueError("indirect shard receipt")
            attempt = int(match.group(1))
            if attempt in attempts:
                raise ValueError("duplicate shard attempt")
            attempts[attempt] = entry
        if not attempts:
            raise ValueError(f"missing shard {index} attempt-scoped receipt")
        attempt = max(attempts)
        source = attempts[attempt] / "digest.json"
        if not source.is_file() or source.is_symlink():
            raise ValueError(f"missing latest shard {index} receipt")
        try:
            receipt = json.loads(source.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, ValueError) as exc:
            raise ValueError("invalid newest shard receipt") from exc
        if (
            receipt.get("run_attempt") != str(attempt)
            or receipt.get("source_sha") != sha
            or receipt.get("suite") != f"api35-native-add-shard-{index}"
            or receipt.get("schema_version") != 1
        ):
            raise ValueError(f"shard {index} latest attempt identity mismatch")
        if (
            receipt.get("reported_job_status") != "success"
            or receipt.get("junit", {}).get("failed") != 0
            or not receipt.get("evidence_executed")
        ):
            raise ValueError(f"shard {index} latest attempt did not pass")

        atlas = root / f"chunks-native-add-atlas-shard-{index}-attempt-{attempt}"
        if not atlas.is_dir() or atlas.is_symlink():
            raise ValueError(f"missing shard {index} latest atlas")
        target = root / f"chunks-native-add-receipt-shard-{index}"
        images = root / f"chunks-native-add-atlas-shard-{index}"
        if target.exists() or images.exists():
            raise ValueError("refusing to overwrite staged canonical evidence")
        target.mkdir()
        images.mkdir()
        shutil.copyfile(source, target / "digest.json")
        for file in atlas.iterdir():
            if not file.is_file() or file.is_symlink() or file.suffix != ".png":
                raise ValueError("invalid or indirect native screenshot")
            shutil.copyfile(file, images / file.name)
        chosen.append((index, attempt))
        print(f"CHUNKS_NATIVE_ATTEMPT: shard={index} selected_attempt={attempt}")
    return chosen


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--artifacts", required=True, type=Path)
    p.add_argument("--shards", required=True, type=int)
    p.add_argument("--sha", required=True)
    p.add_argument("--shards-result", required=True)
    args = p.parse_args()
    try:
        select(args.artifacts, args.shards, args.sha, args.shards_result)
    except (OSError, ValueError, TypeError) as exc:
        # Errors describe only fixed, secret-free artifact names and states.
        print(f"CHUNKS_NATIVE_ATTEMPT: result=failed class={type(exc).__name__}")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
