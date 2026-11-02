#!/usr/bin/env python3
"""Preload one real fixture on the local disposable rTorrent for a skip test.

This is a negative control, not peer-byte proof. It has no host or credential
arguments and refuses every environment except the dedicated disposable job.
"""
import hashlib
import json
import os
from pathlib import Path
import socket
import time
import xmlrpc.client

from public_fixture_collector import parse_bencode


def main():
    if os.environ.get("CHUNKS_DISPOSABLE") != "1":
        raise SystemExit("Disposable server required")
    root = Path(os.environ["RUNNER_TEMP"]) / "chunks-real-peer"
    data = (root / "magnet.torrent").read_bytes()
    if not 0 < len(data) <= 2_097_152:
        raise SystemExit("Invalid fixture metainfo size")
    meta, info = parse_bencode(data)
    exact_hash = hashlib.sha1(info).hexdigest()
    fixture = json.loads((root / "PEER-RECEIPT.json").read_text())["fixtures"]["magnet"]
    if fixture["hash"] != exact_hash or fixture["native_verified_bytes"] < fixture["prefix_bytes"]:
        raise SystemExit("Original fixture identity/pieces not verified")
    expected_name = meta[b"info"][b"name"].decode("utf-8", errors="strict")
    # This file exists only under the newly created runner-local disposable
    # data directory. Exclusive creation prevents replacing anything there.
    control = (root / "magnet0" / "chunks-precompleted-control.bin").read_bytes()
    if len(control) != 65536:
        raise SystemExit("Invalid precompleted negative-control bytes")
    target = Path(os.environ["RUNNER_TEMP"]) / "chunks-disposable" / "data" / "chunks-precompleted-control.bin"
    with target.open("xb") as out:
        out.write(control)
    socket.setdefaulttimeout(5)
    rpc = xmlrpc.client.ServerProxy("http://127.0.0.1:18080/RPC2", allow_none=False)
    if getattr(rpc, "load.raw")("", xmlrpc.client.Binary(data)) != 0:
        raise SystemExit("Disposable pre-existing fixture Add refused")
    for _ in range(40):
        try:
            if getattr(rpc, "d.name")(exact_hash) == expected_name:
                print("CHUNKS_DISPOSABLE_PREEXISTING: original session and separate precompleted control prepared")
                return
        except xmlrpc.client.Fault:
            pass
        time.sleep(.25)
    raise SystemExit("Disposable pre-existing fixture not observed")


if __name__ == "__main__":
    main()
