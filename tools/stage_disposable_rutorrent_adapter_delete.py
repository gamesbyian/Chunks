#!/usr/bin/env python3
"""Stage and independently verify a real ruTorrent deletion by the production Kotlin adapter."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import time
from xmlrpc.client import ServerProxy

from verify_disposable_rutorrent_php_delete import bencode

NAME = "chunks-erasedata-adapter-positive.bin"
CANARY = "chunks-erasedata-adapter-canary.txt"
META = "chunks-erasedata-adapter-positive.torrent"
DATA = b"Chunks production-adapter PHP erasedata witness\n" * 83

def main(action):
    if action not in ("stage", "verify"):
        raise RuntimeError("Expected stage or verify")
    rpc = os.environ["CHUNKS_DISPOSABLE_RT_URL"]
    root = Path(os.environ["CHUNKS_DISPOSABLE_TARGET_DIR"]).resolve(strict=True)
    php = Path(os.environ["CHUNKS_RUTORRENT_PHP_ROOT"]).resolve(strict=True)
    if rpc != "http://127.0.0.1:18080/RPC2" or root.name != "chunks-data" or root.parent != Path(os.environ["RUNNER_TEMP"]).resolve() or "rutorrent1" not in php.parts:
        raise RuntimeError("Refusing non-disposable target")
    payload = root / NAME
    canary = root / CANARY
    torrent = root.parent / META
    info = {b"length":len(DATA), b"name":NAME.encode("ascii"),
            b"piece length":16384, b"pieces":hashlib.sha1(DATA).digest()}
    hash_value = hashlib.sha1(bencode(info)).hexdigest()
    peer = ServerProxy(rpc, allow_none=True)
    def present():
        return hash_value.upper() in {str(x).upper() for x in peer.download_list("")}
    if action == "stage":
        if any(x.exists() for x in (payload, canary, torrent)) or present():
            raise RuntimeError("Fixture is not clean")
        payload.write_bytes(DATA)
        canary.write_text("adapter canary must survive\n", encoding="utf-8")
        torrent.write_bytes(bencode({b"info":info}))
        getattr(peer,"load.normal")("", str(torrent))
        for _ in range(30):
            if present(): break
            time.sleep(0.25)
        if not present():
            raise RuntimeError("Stage failed to register exact torrent in real rTorrent")
        if getattr(peer, "d.name")(hash_value) != NAME or getattr(peer, "d.directory")(hash_value).rstrip("/") != str(root):
            raise RuntimeError("Wrong staging name or folder")
        print("PASS production adapter fixture staged on real rTorrent; hash", hash_value)
        return
    if present():
        raise RuntimeError("Production adapter did not erase the exact rTorrent session")
    if not payload.exists() or payload.read_bytes() != DATA:
        raise RuntimeError("Payload disappeared before actual PHP collector ran")
    if canary.read_text(encoding="utf-8") != "adapter canary must survive\n":
        raise RuntimeError("Foreign canary modified before collector")
    result = subprocess.run(["php", str(php / "plugins/erasedata/update.php")],
                            cwd=php, capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Upstream ruTorrent collector exited nonzero")
    if payload.exists() or not canary.exists() or canary.read_text(encoding="utf-8") != "adapter canary must survive\n":
        raise RuntimeError("Exact file not deleted or foreign canary damaged")
    if present():
        raise RuntimeError("Exact session returned after deletion")
    print("PASS production Kotlin adapter -> actual PHP erasedata -> real collector: exact file absent, canary intact")

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) == 2 else "")
