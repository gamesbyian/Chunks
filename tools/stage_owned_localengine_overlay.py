#!/usr/bin/env python3
"""Emit a test-only Chunks LocalTorrentEngine owned-JNI seam for a disposable Android checkout.

This file does not edit app sources. It expects pinned exact text anchors, refuses
re-staging or source tree outputs, and emits a separate copy plus hashes.
Production keeps the stock dependency and must NEVER receive this lab patch.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

PACKAGE = "package io.github.gamesbyian.chunks.engine"
MODE_OLD = "enum class LocalDiskIoMode { DEFAULT, POSIX }"
MODE_NEW = "enum class LocalDiskIoMode { DEFAULT, POSIX, OWNED_TEST_ONLY }"
SEAM_OLD = "        if (config.diskIoMode == LocalDiskIoMode.POSIX) params.setPosixDiskIO()"
SEAM_NEW = """        if (config.diskIoMode == LocalDiskIoMode.POSIX) params.setPosixDiskIO()
        if (config.diskIoMode == LocalDiskIoMode.OWNED_TEST_ONLY) {
            // NON-SHIPPING lab only: the forked pinned SWIG JNI must supply this.
            // A new app-private state root is required, never arbitrary Downloads.
            val state = checkNotNull(config.stateDirectory) {
                "Owned lab requires explicit private engine state"
            }
            val receipts = File(state, "owned-native-receipts")
            check(receipts.mkdir()) { "Owned lab refuses existing or uncertain receipt root" }
            android.system.Os.chmod(receipts.absolutePath, 448) // 0700
            params.setOwnedDiskIO(receipts.absolutePath)
        }"""
MARKER = "// NON-SHIPPING owned native writer LAB only; do not merge into production."


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def render_engine(source: str) -> str:
    if not source.startswith(PACKAGE + "\n"):
        raise ValueError("Unexpected source package")
    if source.count(MODE_OLD) != 1 or source.count(SEAM_OLD) != 1:
        raise ValueError("Owned JNI source seam changed; refusing blind rewrite")
    if "OWNED_TEST_ONLY" in source or "setOwnedDiskIO" in source:
        raise ValueError("Already owned-modified source is not a safe input")
    if not all(token in source for token in (
        "class LocalTorrentEngine(", "data class LocalEngineConfig(",
        "val stateDirectory: File? = null", "LocalResumeDirectoryInventory::prepare"
    )):
        raise ValueError("Required real engine ownership/lifecycle guards missing")
    result = source.replace(MODE_OLD, MODE_NEW).replace(SEAM_OLD, SEAM_NEW)
    return result.replace(PACKAGE + "\n", PACKAGE + "\n\n" + MARKER + "\n", 1)


def stage(source: Path, output: Path) -> Path:
    original_path = source.expanduser().resolve(strict=True)
    intended = Path("app/src/main/java/io/github/gamesbyian/chunks/engine/LocalTorrentEngine.kt")
    if not str(original_path).replace("\\", "/").endswith(str(intended)):
        raise ValueError("Input must be Chunks actual LocalTorrentEngine.kt")
    target = output.expanduser().resolve()
    project_root = original_path.parents[9]  # repository root, not app/
    if target == original_path or project_root in target.parents or target == project_root:
        raise ValueError("Refuse writing patched engine inside repository")
    if target.exists() or target.with_suffix(".json").exists():
        raise ValueError("Output must be absent; no replacement of prior lab evidence")
    source_bytes = original_path.read_bytes()
    patched = render_engine(source_bytes.decode("utf-8")).encode("utf-8")
    target.parent.mkdir(parents=True, exist_ok=True)
    # CREATE_NEW, never overwrite an existing staged or production file.
    with target.open("xb") as f:
        f.write(patched)
        f.flush()
        import os
        os.fsync(f.fileno())
    manifest = {
        "purpose": "test-only synthetic LocalTorrentEngine, not production",
        "source_sha256": sha256(source_bytes),
        "patched_sha256": sha256(patched),
        "engine_patch": target.name,
        "allowed_build": "disposable debug AndroidTest only",
    }
    with target.with_suffix(".json").open("x", encoding="utf-8") as f:
        json.dump(manifest, f, sort_keys=True)
        f.write("\n")
        f.flush()
        import os
        os.fsync(f.fileno())
    if target.read_bytes() != patched:
        raise IOError("Staged engine bytes changed")
    return target


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--engine-source", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    try:
        result = stage(args.engine_source, args.output)
    except (OSError, ValueError, UnicodeError) as exc:
        print("CHUNKS_OWNED_ENGINE_OVERLAY=REFUSED " + str(exc), file=sys.stderr)
        return 2
    print("CHUNKS_OWNED_ENGINE_OVERLAY=STAGED " + str(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
