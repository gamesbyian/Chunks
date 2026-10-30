#!/usr/bin/env python3
"""Safely stage audited custom-disk source into an explicit libtorrent4j fork.

This is a source-only helper. It cannot build JNI, update the Android app, or
certify public Downloads. Do not point at production without a reviewed fork.
"""
from pathlib import Path
import argparse
import shutil

FILES = ("owned_payload_file.h", "owned_payload_file.cc",
         "owned_torrent_storage.h", "owned_torrent_storage.cc",
         "bridge/checked_piece_range.h", "bridge/checked_piece_range.cc",
         "bridge/receipt_admission.h", "bridge/receipt_admission.cc",
         "bridge/serial_native_io_queue.h", "bridge/serial_native_io_queue.cc",
         "bridge/owned_libtorrent_disk.h", "bridge/owned_libtorrent_disk.cc",
         "bridge/owned_disk_extension.i", "bridge/owned_native_session_smoke.cc")
CMAKE_SOURCES = """
target_sources(torrent4j PRIVATE
  chunks/owned_payload_file.cc
  chunks/owned_torrent_storage.cc
  chunks/bridge/checked_piece_range.cc
  chunks/bridge/receipt_admission.cc
  chunks/bridge/serial_native_io_queue.cc
  chunks/bridge/owned_libtorrent_disk.cc
)
target_include_directories(torrent4j PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/chunks" "${CMAKE_CURRENT_SOURCE_DIR}")

# Host-only actual libtorrent session safety smoke (not included in Android .so)
add_executable(chunks_owned_session_smoke
  chunks/bridge/owned_native_session_smoke.cc
  chunks/owned_payload_file.cc
  chunks/owned_torrent_storage.cc
  chunks/bridge/checked_piece_range.cc
  chunks/bridge/receipt_admission.cc
  chunks/bridge/serial_native_io_queue.cc
  chunks/bridge/owned_libtorrent_disk.cc
)
target_link_libraries(chunks_owned_session_smoke PRIVATE torrent-rasterbar)
target_include_directories(chunks_owned_session_smoke PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/chunks")
"""
JAVA_METHOD = """
    /** Requires regenerated SWIG and rebuilt native library; never call on stock JNI. */
    public void setOwnedDiskIO(String privateReceiptRoot) {
        if (privateReceiptRoot == null || privateReceiptRoot.isEmpty())
            throw new IllegalArgumentException("Missing private receipt directory");
        h.set_owned_disk_io_constructor(privateReceiptRoot);
    }

"""


def patch_once(s: str, anchor: str, insertion: str) -> str:
    if insertion in s:
        return s
    if s.count(anchor) != 1:
        raise ValueError("Missing/duplicated upstream patch seam, abort")
    return s.replace(anchor, anchor + insertion, 1)


def patch_before_once(s: str, anchor: str, insertion: str) -> str:
    if insertion in s:
        return s
    if s.count(anchor) != 1:
        raise ValueError("Missing/duplicated upstream patch seam, abort")
    return s.replace(anchor, insertion + anchor, 1)


def stage(fork: Path, native: Path) -> None:
    fork, native = fork.resolve(strict=True), native.resolve(strict=True)
    if 'version = "2.1.0-39"' not in (fork / "build.gradle.kts").read_text():
        raise ValueError("Unsupported libtorrent4j version")
    swigdir = fork / "swig"
    if "%ignore libtorrent::session_params::disk_io_constructor;" not in (
        swigdir / "libtorrent/session_params.i").read_text():
        raise ValueError("Different upstream SWIG disk API: manual review required")
    swig = swigdir / "libtorrent.i"
    cmake = swigdir / "CMakeLists.txt"
    java = fork / "src/main/java/org/libtorrent4j/SessionParams.java"
    updates = {
        swig: patch_once(
            patch_once(swig.read_text(),
                '#include "libtorrent/session_params.hpp"\n',
                '#include "chunks/bridge/owned_libtorrent_disk.h"\n'),
            '%include "libtorrent/session_params.i"\n',
            '%include "chunks/bridge/owned_disk_extension.i"\n'),
        cmake: patch_once(cmake.read_text(),
            'target_link_libraries(torrent4j PRIVATE torrent-rasterbar)\n',
            CMAKE_SOURCES),
        java: patch_before_once(java.read_text(),
            '    private static session_params bdecode0(File file) {\n',
            JAVA_METHOD),
    }
    # Validate all source and customization conflicts before ANY writes.
    for rel in FILES:
        source = native / rel
        target = swigdir / "chunks" / rel
        if not source.is_file():
            raise ValueError(f"Missing native source {rel}")
        if target.exists() and target.read_bytes() != source.read_bytes():
            raise ValueError(f"Refusing to overwrite customized file {target}")
    for rel in FILES:
        target = swigdir / "chunks" / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            shutil.copyfile(native / rel, target)
    for path, content in updates.items():
        if path.read_text() != content:
            path.write_text(content)
    print("Source staged. Now regenerate SWIG and rebuild/test JNI for each ABI.")
    print("Native public Downloads/SD write acceptance remains UNPROVEN.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fork", type=Path)
    args = parser.parse_args()
    stage(args.fork, Path(__file__).resolve().parent.parent)
