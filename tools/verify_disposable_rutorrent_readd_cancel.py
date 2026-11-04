#!/usr/bin/env python3
"""Real ruTorrent pending-delete / same-hash re-add safety witness, localhost only."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from urllib import parse, request
from xmlrpc.client import Binary, ServerProxy
from verify_disposable_rutorrent_php_delete import bencode

def main():
    rpc = os.environ["CHUNKS_DISPOSABLE_RT_URL"]
    root = Path(os.environ["CHUNKS_DISPOSABLE_TARGET_DIR"]).resolve(strict=True)
    php = Path(os.environ["CHUNKS_RUTORRENT_PHP_ROOT"]).resolve(strict=True)
    url = os.environ["CHUNKS_RUTORRENT_PHP_URL"]
    temp = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    if (rpc != "http://127.0.0.1:18080/RPC2"
        or url != "http://127.0.0.1:18082/rutorrent1/plugins/erasedata/action.php"
        or root != temp / "chunks-data"
        or php != temp / "chunks-rutorrent-web" / "rutorrent1"):
        raise RuntimeError("Refusing non-disposable endpoints or paths")
    collector = php / "plugins/erasedata/update.php"
    if not collector.is_file():
        raise RuntimeError("Missing pinned upstream collector")

    name = "chunks-erasedata-readd-cancel.bin"
    data = b"Chunks readd safely cancels queued deletion\n" * 57
    payload = root / name
    canary = root / "chunks-erasedata-readd-foreign.txt"
    torrent_file = temp / "chunks-erasedata-readd-cancel.torrent"
    if any(p.exists() for p in (payload, canary, torrent_file)):
        raise RuntimeError("Expected fresh disposable fixture")
    info = {b"length": len(data), b"name": name.encode("ascii"),
            b"piece length": 16384, b"pieces": hashlib.sha1(data).digest()}
    infohash = hashlib.sha1(bencode(info)).hexdigest()
    payload.write_bytes(data)
    canary.write_bytes(b"untouched foreign canary\n")
    torrent_file.write_bytes(bencode({b"info": info}))
    before_payload = hashlib.sha256(payload.read_bytes()).digest()
    before_canary = hashlib.sha256(canary.read_bytes()).digest()
    engine = ServerProxy(rpc, allow_none=True)

    def present():
        return infohash.upper() in {str(h).upper() for h in engine.download_list("")}
    def check_exact():
        return (getattr(engine, "d.name")(infohash) == name and
                getattr(engine, "d.directory")(infohash).rstrip("/") == str(root))
    def wait(expected):
        for _ in range(40):
            if present() == expected:
                return
            time.sleep(.25)
        raise RuntimeError("Real engine never reached expected exact session state")
    def check_bytes():
        if (not payload.is_file() or
            hashlib.sha256(payload.read_bytes()).digest() != before_payload or
            not canary.is_file() or
            hashlib.sha256(canary.read_bytes()).digest() != before_canary):
            raise RuntimeError("Exact payload or unrelated canary modified")
    def collect():
        result = subprocess.run(["php", str(collector)], cwd=php,
                                capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise RuntimeError("Actual upstream PHP collector failed")

    if present():
        raise RuntimeError("Expected new exact hash")
    getattr(engine, "load.normal")("", str(torrent_file))
    wait(True)
    if not check_exact():
        raise RuntimeError("First Add identity mismatch")
    request_body = parse.urlencode({"mode":"removewithdata",
                                    "hash": infohash, "v": "1"}).encode("ascii")
    req = request.Request(url, data=request_body, method="POST",
                          headers={"Content-Type":"application/x-www-form-urlencoded"})
    with request.urlopen(req, timeout=15) as response:
        ack = json.load(response)
    if not isinstance(ack, list) or not ack:
        raise RuntimeError("PHP erase was not accepted")
    wait(False)
    check_bytes()  # queued response has not deleted bytes

    # Same-hash new session arrives BEFORE the old async cleanup collector.
    # Use the SAME raw-metainfo Add RPC as the production Chunks adapter.
    # This is a single reviewed Add, never an automatic retry after timeout.
    getattr(engine, "load.raw_verbose")("", Binary(torrent_file.read_bytes()))
    wait(True)
    if not check_exact():
        raise RuntimeError("Re-added session identity changed")
    collect()
    if not present() or not check_exact():
        raise RuntimeError("Pending old erase removed the new session")
    check_bytes()

    # Subsequent session-only Remove must not revive the withdrawn old
    # deletion request, even after a SECOND PHP collector pass.
    getattr(engine, "d.erase")(infohash)
    wait(False)
    collect()
    check_bytes()
    print("CHUNKS_READD_CANCEL: PASS actual ruTorrent PHP pending deletion " +
          "withdrawn after same-hash readd; two collector passes, intact payload/canary")

if __name__ == "__main__":
    main()
