#!/usr/bin/env python3
"""Disposable host/Android-shell synthetic fixture probe, not a user-data extraction path."""
import argparse
import hashlib
from pathlib import Path
import resource
import re
import shlex
import subprocess
import tempfile

EXPECTED = "05abc6df769ec4637bcb82ddc1b5514d910d62502b40c1336aa15436405e2696"
INPUT_HASHES = (
    "4cc78dae7289d222afae0c28a1e186c59dba7298cf49e7cfa25ad57230a054f7",
    "a5cd28ebb8b985e7c43f99d510e7cdf244b813f2b67e4702761e016eee8c216c",
)


def limits():
    resource.setrlimit(resource.RLIMIT_AS, (1024 * 1024 * 1024,) * 2)
    resource.setrlimit(resource.RLIMIT_CPU, (15, 16))
    resource.setrlimit(resource.RLIMIT_FSIZE, (0, 0))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def adb(*args, timeout=20, check=True):
    return subprocess.run(["adb", *args], capture_output=True, timeout=timeout, check=check)


def shell(*args, check=True, timeout=20):
    # No PTY: the callback writes binary bytes, and shell v2 preserves exit status.
    return adb("shell", "-T", shlex.join(args), check=check, timeout=timeout)


def run_android(program, directory, budget, expected_files):
    remote = shell("mktemp", "-d", "/data/local/tmp/chunks-unrar-XXXXXXXX").stdout.decode("ascii").strip()
    if not re.fullmatch(r"/data/local/tmp/chunks-unrar-[A-Za-z0-9]{8}", remote):
        raise RuntimeError("unexpected disposable remote root")
    try:
        executable = remote + "/witness"
        adb("push", str(program.resolve()), executable)
        shell("chmod", "700", executable)
        for name in expected_files:
            adb("push", str(directory / name), remote + "/" + name)
        expected = sorted(["witness", *expected_files])
        if sorted(shell("ls", "-1A", remote).stdout.decode("ascii").splitlines()) != expected:
            raise RuntimeError("unexpected staged Android fixture inventory")
        binary = shell("cat", executable).stdout
        if hashlib.sha256(binary).digest() != hashlib.sha256(program.read_bytes()).digest():
            raise RuntimeError("staged Android executable differs")
        result = shell(executable, remote, str(budget), check=False, timeout=22)
        if sorted(shell("ls", "-1A", remote).stdout.decode("ascii").splitlines()) != expected:
            raise RuntimeError("decoder produced unexpected Android files")
        if shell("cat", executable).stdout != binary or any(
                shell("cat", remote + "/" + name).stdout != data for name, data in expected_files.items()):
            raise RuntimeError("decoder modified staged Android inputs")
        return result
    finally:
        # Only the verified mktemp root, never a caller-supplied path.
        shell("rm", "-rf", remote)


def main(root, program, android=False):
    volumes = [(root / f"Verified.part{i}.rar").read_bytes() for i in (1, 2)]
    if tuple(hashlib.sha256(v).hexdigest() for v in volumes) != INPUT_HASHES:
        raise RuntimeError("incorrect fixture bytes")
    changed = bytearray(volumes[1])
    changed[len(changed) // 2] ^= 0x40
    cases = (
        ("positive", volumes, 1048),
        ("missing_last", volumes[:1], 1048),
        ("reversed", volumes[::-1], 1048),
        ("duplicate", [volumes[0], volumes[0]], 1048),
        ("corrupt", [volumes[0], bytes(changed)], 1048),
        ("truncated", [volumes[0], volumes[1][:-64]], 1048),
        ("budget", volumes, 1047),
    )
    for label, inputs, budget in cases:
        with tempfile.TemporaryDirectory(prefix="chunks-unrar-probe-") as tmp:
            directory = Path(tmp)
            expected_files = {f"Verified.part{i}.rar": v for i, v in enumerate(inputs, 1)}
            for name, data in expected_files.items():
                (directory / name).write_bytes(data)
            result = (run_android(program, directory, budget, expected_files) if android else
                      subprocess.run([str(program.resolve()), str(directory), str(budget)],
                                     cwd=directory, capture_output=True, timeout=20,
                                     preexec_fn=limits))
            if {p.name: p.read_bytes() for p in directory.iterdir()} != expected_files:
                raise RuntimeError("decoder modified staged inputs or produced unexpected files")
            if label == "positive":
                if result.returncode != 0 or len(result.stdout) != 1048 or \
                        hashlib.sha256(result.stdout).hexdigest() != EXPECTED:
                    raise RuntimeError(f"positive decode failed: {result.returncode} {result.stderr[:500]!r}")
                print("CHUNKS_UNRAR: actual_bytes=1048 sha256=" + EXPECTED, flush=True)
            else:
                if result.returncode != 3 or result.stdout:
                    raise RuntimeError(f"{label} did not refuse cleanly: {result.returncode} {result.stderr[:500]!r}")
                print(f"CHUNKS_UNRAR: {label}=refused", flush=True)
    print("CHUNKS_UNRAR: PASS transport=" + ("android-shell" if android else "host"), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture_root", type=Path)
    parser.add_argument("program", type=Path)
    parser.add_argument("--android", action="store_true")
    args = parser.parse_args()
    main(args.fixture_root, args.program, args.android)
