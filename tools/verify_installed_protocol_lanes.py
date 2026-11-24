"""Fail-closed acceptance of independent installed Universal Add lanes."""
import re
import sys
from pathlib import Path

LANES = ("transmission-rtorrent", "qbittorrent-deluge", "local-document")
PATTERN = re.compile(r"chunks-protocol-lane-(" + "|".join(LANES) + r")-attempt-([1-9][0-9]*)")
SHA = re.compile(r"[0-9a-f]{40}")


def verify(root, sha, tree, result):
    if result != "success" or not SHA.fullmatch(sha) or not SHA.fullmatch(tree):
        raise ValueError("matrix not successful or invalid source identity")
    selected = {}
    for directory in root.iterdir():
        match = PATTERN.fullmatch(directory.name)
        if not match or not directory.is_dir():
            raise ValueError("unexpected lane artifact")
        lane, attempt = match[1], int(match[2])
        if lane not in selected or attempt > selected[lane][0]:
            selected[lane] = (attempt, directory)
    if set(selected) != set(LANES):
        raise ValueError("missing required lane")
    identity = None
    for lane, (attempt, directory) in sorted(selected.items()):
        for name, expected in (("passed-lane", lane), ("source-sha", sha),
                               ("source-tree", tree)):
            if (directory / name).read_text() != expected + "\n":
                raise ValueError(f"{lane} attempt {attempt}: invalid {name}")
        lines = (directory / "installed-identity.txt").read_text().splitlines()
        expected_header = (f"CHUNKS_PROTOCOL_SOURCE: commit={sha} tree={tree} "
                           f"package=io.github.gamesbyian.chunks version=0.1.0-dev+{sha[:12]}")
        if len(lines) != 2 or lines[0] != expected_header or not re.fullmatch(
                r"CHUNKS_INSTALLED_APK: matched size_bytes=[1-9][0-9]* sha256=[0-9a-f]{64}", lines[1]):
            raise ValueError(f"{lane}: invalid installed APK evidence")
        if identity is not None and identity != lines[1]:
            raise ValueError("lanes installed different app APKs")
        identity = lines[1]
    return {lane: attempt for lane, (attempt, _) in selected.items()}


if __name__ == "__main__":
    try:
        root, sha, tree, result = sys.argv[1:]
        print("CHUNKS_PROTOCOL_LANES: accepted", verify(Path(root), sha, tree, result))
    except (ValueError, OSError) as exc:
        sys.exit("CHUNKS_PROTOCOL_LANES: rejected: " + str(exc))
