#!/usr/bin/env python3
"""Host-only synthetic fixture probe, never a user-data extraction path."""
import argparse
import hashlib
from pathlib import Path
import resource
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


def main(root, program):
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
            result = subprocess.run([str(program.resolve()), str(directory), str(budget)],
                                    cwd=directory, capture_output=True, timeout=20,
                                    preexec_fn=limits)
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
    print("CHUNKS_UNRAR: PASS", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture_root", type=Path)
    parser.add_argument("program", type=Path)
    args = parser.parse_args()
    main(args.fixture_root, args.program)
