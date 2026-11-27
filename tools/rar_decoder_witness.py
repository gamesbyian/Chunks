#!/usr/bin/env python3
"""Disposable Linux-only libarchive RAR read-callback witness; no output writer.

Use only the repository's tiny, independently generated synthetic fixture.
This experiment is neither an Android dependency nor an extraction executor.
"""
import argparse
import ctypes as C
import ctypes.util
import hashlib
import resource
import stat
from pathlib import Path

P, I, L, SIZE = C.c_void_p, C.c_int, C.c_int64, C.c_size_t
READ = C.CFUNCTYPE(C.c_ssize_t, P, P, C.POINTER(P))
SWITCH = C.CFUNCTYPE(I, P, P, P)
OK, EOF = 0, 1
INPUT_CAP = 1024 * 1024
OUTPUT_CAP = 8 * 1024 * 1024
FIXTURE_HASHES = (
    "4cc78dae7289d222afae0c28a1e186c59dba7298cf49e7cfa25ad57230a054f7",
    "a5cd28ebb8b985e7c43f99d510e7cdf244b813f2b67e4702761e016eee8c216c",
)
EXPECTED = [("known.bin", 1048, "05abc6df769ec4637bcb82ddc1b5514d910d62502b40c1336aa15436405e2696")]


class Refused(Exception):
    pass


def require(condition, reason):
    if not condition:
        raise Refused(reason)


def library(path):
    lib = C.CDLL(path or ctypes.util.find_library("archive") or "libarchive.so.13")
    signatures = {
        "archive_version_string": (C.c_char_p, []),
        "archive_error_string": (C.c_char_p, [P]),
        "archive_read_new": (P, []),
        "archive_read_support_filter_none": (I, [P]),
        "archive_read_support_format_rar": (I, [P]),
        "archive_read_support_format_rar5": (I, [P]),
        "archive_read_set_read_callback": (I, [P, READ]),
        "archive_read_set_switch_callback": (I, [P, SWITCH]),
        "archive_read_append_callback_data": (I, [P, P]),
        "archive_read_open1": (I, [P]),
        "archive_read_next_header": (I, [P, C.POINTER(P)]),
        "archive_read_data_block": (I, [P, C.POINTER(P), C.POINTER(SIZE), C.POINTER(L)]),
        "archive_read_has_encrypted_entries": (I, [P]),
        "archive_entry_pathname_utf8": (C.c_char_p, [P]),
        "archive_entry_filetype": (C.c_uint, [P]),
        "archive_entry_size_is_set": (I, [P]),
        "archive_entry_size": (L, [P]),
        "archive_entry_is_encrypted": (I, [P]),
        "archive_entry_hardlink": (C.c_char_p, [P]),
        "archive_entry_symlink": (C.c_char_p, [P]),
        "archive_entry_sparse_count": (I, [P]),
        "archive_read_close": (I, [P]),
        "archive_read_free": (I, [P]),
    }
    for name, (result, args) in signatures.items():
        fn = getattr(lib, name)
        fn.restype, fn.argtypes = result, args
    return lib


def decode(lib, volumes, max_output=OUTPUT_CAP):
    """Only immutable fixture bytes reach the decoder, not source or destination paths."""
    total_input = sum(map(len, volumes))
    require(1 <= len(volumes) <= 32 and all(volumes) and total_input <= INPUT_CAP, "input bound")
    limit = min(max_output, OUTPUT_CAP, total_input * 200)
    buffers = [C.create_string_buffer(volume) for volume in volumes]
    offsets = [0] * len(volumes)
    visited = {0}
    callback_errors = []

    @READ
    def read(_archive, client, out):
        try:
            index = int(client) - 1
            require(0 <= index < len(volumes) and index in visited, "unexpected volume read")
            pos = offsets[index]
            count = min(4096, len(volumes[index]) - pos)
            out[0] = C.addressof(buffers[index]) + pos if count else None
            offsets[index] += count
            return count
        except Exception as err:
            callback_errors.append(type(err).__name__)
            return -1

    @SWITCH
    def switch(_archive, old, new):
        try:
            require(int(new) == int(old) + 1 and int(new) <= len(volumes), "unexpected volume switch")
            visited.add(int(new) - 1)
            return OK
        except Exception as err:
            callback_errors.append(type(err).__name__)
            return -30

    archive = lib.archive_read_new()
    require(archive, "decoder allocation")

    def checked(status, stage):
        if status != OK:
            # Disposable synthetic inputs only. Not a production logging policy.
            detail = lib.archive_error_string(archive)
            raise Refused(f"{stage}: {status} {detail!r}")

    try:
        for setup in (lib.archive_read_support_filter_none, lib.archive_read_support_format_rar,
                      lib.archive_read_support_format_rar5):
            checked(setup(archive), "decoder configuration")
        checked(lib.archive_read_set_read_callback(archive, read), "read callback")
        checked(lib.archive_read_set_switch_callback(archive, switch), "switch callback")
        for index in range(len(volumes)):
            checked(lib.archive_read_append_callback_data(archive, P(index + 1)), "volume registration")
        checked(lib.archive_read_open1(archive), "decoder open")
        result, seen, total = [], set(), 0
        while True:
            entry = P()
            status = lib.archive_read_next_header(archive, C.byref(entry))
            if status == EOF:
                break
            checked(status, "entry header")
            require(entry and len(result) < 128, "entry bound")
            name = lib.archive_entry_pathname_utf8(entry)
            # Narrow initial capability: flat ASCII regular files only.
            require(name and 1 <= len(name) <= 255 and all(32 <= b < 127 for b in name)
                    and b"/" not in name and b"\\" not in name and b":" not in name
                    and name not in (b".", b"..") and name.rstrip(b" .") == name
                    and name.lower() not in seen, "unsafe or duplicate fixture member")
            seen.add(name.lower())
            require(lib.archive_entry_filetype(entry) == stat.S_IFREG
                    and not lib.archive_entry_hardlink(entry) and not lib.archive_entry_symlink(entry)
                    and lib.archive_entry_sparse_count(entry) == 0
                    and lib.archive_entry_is_encrypted(entry) == 0, "unsupported member type or encryption")
            require(lib.archive_entry_size_is_set(entry), "unknown declared size")
            declared = lib.archive_entry_size(entry)
            require(0 <= declared <= limit - total, "declared output bound")
            digest, done = hashlib.sha256(), 0
            while True:
                data, count, offset = P(), SIZE(), L()
                status = lib.archive_read_data_block(archive, C.byref(data), C.byref(count), C.byref(offset))
                if status == EOF:
                    break
                checked(status, "decoded block")
                require(offset.value == done and 0 < count.value <= declared - done
                        and count.value <= limit - total and data, "decoded block length or offset")
                digest.update(C.string_at(data, count.value))
                done += count.value
                total += count.value
            require(done == declared, "decoded length mismatch")
            result.append((name.decode("ascii"), done, digest.hexdigest()))
        require(not callback_errors and len(visited) == len(volumes) and result,
                "callback fault, unused selected volume or empty archive")
        require(lib.archive_read_has_encrypted_entries(archive) == 0, "encryption not ruled out")
        checked(lib.archive_read_close(archive), "decoder close")
        return result
    finally:
        lib.archive_read_free(archive)


def main(root, library_path, expected_version):
    # Independent native CPU/virtual-memory ceilings, not just output limits.
    resource.setrlimit(resource.RLIMIT_AS, (1024 * 1024 * 1024,) * 2)
    resource.setrlimit(resource.RLIMIT_CPU, (15, 16))
    lib = library(library_path)
    version = lib.archive_version_string().decode()
    print("CHUNKS_RAR_DECODER: version=" + version, flush=True)
    require(expected_version is None or version == expected_version, "wrong loaded library")
    volumes = []
    for i in (1, 2):
        with (root / f"Verified.part{i}.rar").open("rb") as file:
            volumes.append(file.read(INPUT_CAP + 1))
    require(tuple(hashlib.sha256(v).hexdigest() for v in volumes) == FIXTURE_HASHES, "fixture identity")
    require(decode(lib, volumes) == EXPECTED, "independent decoded manifest mismatch")
    print("CHUNKS_RAR_DECODER: rar5_multipart_bytes_verified=true", flush=True)
    changed = bytearray(volumes[1])
    changed[len(changed) // 2] ^= 0x40
    for label, supplied, budget in (
        ("missing_last", volumes[:1], OUTPUT_CAP),
        ("reversed", volumes[::-1], OUTPUT_CAP),
        ("duplicate", [volumes[0], volumes[0]], OUTPUT_CAP),
        ("corrupt_packed", [volumes[0], bytes(changed)], OUTPUT_CAP),
        ("truncated", [volumes[0], volumes[1][:-20]], OUTPUT_CAP),
        ("over_budget", volumes, 1047),
    ):
        try:
            observed = decode(lib, supplied, budget)
        except Refused:
            print(f"CHUNKS_RAR_DECODER: {label}=refused", flush=True)
        else:
            raise RuntimeError(f"negative {label} returned a manifest (matches expected={observed == EXPECTED})")
    require(tuple(hashlib.sha256(v).hexdigest() for v in volumes) == FIXTURE_HASHES, "input bytes changed")
    print("CHUNKS_RAR_DECODER: PASS", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture_root", type=Path)
    parser.add_argument("--library")
    parser.add_argument("--expected-version")
    args = parser.parse_args()
    main(args.fixture_root, args.library, args.expected_version)
