#!/usr/bin/env python3
"""True PHP erasedata + real loopback rTorrent + independent filesystem witness.

Only for the throwaway GitHub Actions runner. No protected server can satisfy
the exact loopback and temporary-directory assertions.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib import parse, request
from xmlrpc.client import ServerProxy

def bencode(value):
    if isinstance(value, int):
        return b"i" + str(value).encode("ascii") + b"e"
    if isinstance(value, bytes):
        return str(len(value)).encode("ascii") + b":" + value
    if isinstance(value, list):
        return b"l" + b"".join(map(bencode, value)) + b"e"
    if isinstance(value, dict):
        return b"d" + b"".join(bencode(k) + bencode(value[k]) for k in sorted(value)) + b"e"
    raise TypeError(type(value))

def main():
    rpc = os.environ["CHUNKS_DISPOSABLE_RT_URL"]
    root = Path(os.environ["CHUNKS_DISPOSABLE_TARGET_DIR"]).resolve(strict=True)
    php_root = Path(os.environ["CHUNKS_RUTORRENT_PHP_ROOT"]).resolve(strict=True)
    php_url = os.environ["CHUNKS_RUTORRENT_PHP_URL"]
    if rpc != "http://127.0.0.1:18080/RPC2" or php_url != "http://127.0.0.1:18082/rutorrent1/plugins/erasedata/action.php":
        raise RuntimeError("Refusing a non-disposable, non-loopback target")
    if root.name != "chunks-data" or root.parent != Path(os.environ["RUNNER_TEMP"]).resolve():
        raise RuntimeError("Not a throwaway data directory")
    if "rutorrent1" not in php_root.parts:
        raise RuntimeError("Not a disposable ruTorrent installation")

    data = b"Chunks disposable real PHP erasedata file witness\n" * 97
    name = "chunks-erasedata-php-positive.bin"
    payload = root / name
    canary = root / "foreign-canary-not-in-torrent.txt"
    metainfo = root.parent / "chunks-erasedata-php-positive.torrent"
    for path in (payload, canary, metainfo):
        if path.exists():
            raise RuntimeError("Unexpected pre-existing disposable test fixture")
    info = {b"length":len(data), b"name":name.encode("ascii"),
            b"piece length":16384, b"pieces":hashlib.sha1(data).digest()}
    torrent = bencode({b"info":info})
    infohash = hashlib.sha1(bencode(info)).hexdigest()
    payload.write_bytes(data)
    canary.write_text("foreign data survives\n", encoding="utf-8")
    metainfo.write_bytes(torrent)
    canary_before = hashlib.sha256(canary.read_bytes()).digest()
    peer = ServerProxy(rpc, allow_none=True)
    if infohash.upper() in [str(x).upper() for x in peer.download_list("")]:
        raise RuntimeError("Fixture hash was already present in disposable rTorrent")

    # rTorrent 0.9.x uses the standard stopped load.normal("", filename).
    getattr(peer, "load.normal")("", str(metainfo))
    for _ in range(30):
        if infohash.upper() in [str(x).upper() for x in peer.download_list("")]:
            break
        time.sleep(0.25)
    else:
        raise RuntimeError("Real engine never registered the exact fixture hash")
    actual_name = getattr(peer, "d.name")(infohash)
    actual_dir = getattr(peer, "d.directory")(infohash)
    if actual_name != name or actual_dir.rstrip("/") != str(root):
        raise RuntimeError("Real rTorrent reported wrong exact name/destination")

    body = parse.urlencode({"mode":"removewithdata", "hash":infohash, "v":"1"}).encode("ascii")
    req = request.Request(php_url, data=body,
                          headers={"Content-Type":"application/x-www-form-urlencoded"},
                          method="POST")
    with request.urlopen(req, timeout=15) as resp:
        value = json.load(resp)
    if not isinstance(value, list) or len(value) == 0:
        raise RuntimeError("PHP plugin did not positively acknowledge a real erase")
    # Disposable no-secret scalar response shape: regression diagnosis for the
    # production adapter parser, not a remote credential or full RPC payload.
    print("DISPOSABLE_RUTORRENT_PHP_RESPONSE", repr(value)[:240])
    for _ in range(30):
        if infohash.upper() not in [str(x).upper() for x in peer.download_list("")]:
            break
        time.sleep(0.25)
    else:
        raise RuntimeError("Exact rTorrent session was not removed after plugin request")

    # The original HTTP acknowledgement is never file-deletion proof. Run the
    # actual upstream PHP collector and independently read filesystem bytes.
    collector = php_root / "plugins/erasedata/update.php"
    result = subprocess.run(["php", str(collector)], cwd=php_root, timeout=30,
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("ruTorrent PHP collector failed (exit {})".format(result.returncode))
    if payload.exists():
        raise RuntimeError("Accepted PHP cleanup did not remove exact payload")
    if not canary.exists() or hashlib.sha256(canary.read_bytes()).digest() != canary_before:
        raise RuntimeError("Unrelated foreign file was modified")
    if infohash.upper() in [str(x).upper() for x in peer.download_list("")]:
        raise RuntimeError("Exact hash unexpectedly returned after PHP cleanup")
    print("PASS disposable real rTorrent + real ruTorrent PHP plugin + collector: "
          "session absent, exact payload absent, foreign canary unchanged")

if __name__ == "__main__":
    main()
