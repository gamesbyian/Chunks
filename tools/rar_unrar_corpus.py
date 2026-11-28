#!/usr/bin/env python3
"""Host-only actual RAR4 legacy/modern and RAR5 modern corpus witness.
Expected bytes are independently generated, not extracted by a second decoder.
No arbitrary archive paths, user data, filesystem extraction or shipping code.
"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile
import zlib
from rar_unrar_witness import limits

# Independent rarfile regression corpus, commit pinned in workflow.
# ISC license remains with the upstream checkout. Git blob identity and
# input size independently checked before copying into disposable storage.
SETS = {
    "legacy4": (
        ("rar3-old.rar", 102400, "962d8b6f4b9d7e60396c1b5366f8962868c71fe1"),
        ("rar3-old.r00", 102400, "0ba628367eaae36c92a648217e9baf96125c2acb"),
        ("rar3-old.r01", 2572, "9bc19dde8cb2e46d3e064b83074c7fc4e7e5c3da"),
    ),
    "modern4": (
        ("rar3-vols.part1.rar", 102400, "483812fcb1c3630bdaa006dac709a4327862b8f6"),
        ("rar3-vols.part2.rar", 102400, "0fa8f87fae09ce30ac1e25b2b1f5610fdc706ea0"),
        ("rar3-vols.part3.rar", 2572, "5d32e160fded21ab5cd2a34a91d13305419c30dd"),
    ),
    "modern5": (
        ("rar5-vols.part1.rar", 102400, "0926f2f8f6d74bd1a7e8c4c1ebe74432c0b8d4e2"),
        ("rar5-vols.part2.rar", 102400, "d4f55a2f859d0c7b422b99e111db4fc5e8b7e34f"),
        ("rar5-vols.part3.rar", 11384, "ba6e9924a6db984d321364b8788301cda8c3bfba"),
    ),
}
EXPECTED = tuple(b"".join(f"{i:40d}\n".encode("ascii") for i in range(1, n + 1))
                 for n in (5000, 50))
SHA = ("57613b4a0d18b31472c9abe90780dcaf834f4edf48e79008f027a99710cf3632",
       "6805973b24128edda3148235c77a7f7b5a7a9be6272bc882a6e030871b2b18d1")


def main(root, program):
    if tuple(map(len, EXPECTED)) != (205000, 2050) or \
            tuple(zlib.crc32(b) for b in EXPECTED) != (0x509ad74c, 0xd08a1f86) or \
            tuple(hashlib.sha256(b).hexdigest() for b in EXPECTED) != SHA:
        raise RuntimeError("independent expected bytes mismatch")
    for kind, files in SETS.items():
        volumes = []
        for name, size, blob in files:
            data = (root / name).read_bytes()
            if len(data) != size or hashlib.sha1(
                    f"blob {size}\0".encode("ascii") + data).hexdigest() != blob:
                raise RuntimeError(f"unexpected fixture identity: {kind}/{name}")
            volumes.append(data)
        changed = bytearray(volumes[1])
        changed[len(changed) // 2] ^= 0x40
        cases = (
            ("positive", dict(zip((f[0] for f in files), volumes)), 207050),
            ("missing_middle", {files[0][0]: volumes[0], files[2][0]: volumes[2]}, 207050),
            ("missing_last", dict(zip((f[0] for f in files), volumes[:2])), 207050),
            ("corrupt_middle", dict(zip((f[0] for f in files),
                                        (volumes[0], bytes(changed), volumes[2]))), 207050),
            ("reversed_middle_last", dict(zip((f[0] for f in files),
                                             (volumes[0], volumes[2], volumes[1]))), 207050),
            ("aggregate_budget", dict(zip((f[0] for f in files), volumes)), 207049),
        )
        for label, inputs, budget in cases:
            with tempfile.TemporaryDirectory(prefix="chunks-unrar-corpus-") as tmp:
                directory = Path(tmp)
                for name, data in inputs.items():
                    (directory / name).write_bytes(data)
                result = subprocess.run([str(program.resolve()), str(directory), str(budget), kind],
                                        cwd=directory, capture_output=True, timeout=20, preexec_fn=limits)
                if {p.name: p.read_bytes() for p in directory.iterdir()} != inputs:
                    raise RuntimeError("source modified or unexpected file")
                if label == "positive":
                    if result.returncode != 0 or result.stdout != b"".join(EXPECTED):
                        raise RuntimeError(f"{kind} decode failed: {result.returncode} {result.stderr[:500]!r}")
                    print(f"CHUNKS_UNRAR_CORPUS: {kind} bytes=207050 member_sha256={','.join(SHA)}", flush=True)
                else:
                    if result.returncode != 3 or result.stdout:
                        raise RuntimeError(f"{kind}/{label} did not refuse: {result.returncode} {result.stderr[:500]!r}")
                    print(f"CHUNKS_UNRAR_CORPUS: {kind}/{label}=refused", flush=True)
    print("CHUNKS_UNRAR_CORPUS: PASS", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture_root", type=Path)
    parser.add_argument("program", type=Path)
    args = parser.parse_args()
    main(args.fixture_root, args.program)
