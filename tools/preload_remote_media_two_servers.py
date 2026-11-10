#!/usr/bin/env python3
"""Stage public synthetic media on two DISPOSABLE local rTorrent daemons.

Server A gets {public TV episode, only-a}; server B gets {same episode, only-b}. ``shared`` is
byte-identical metainfo, so both servers hold the same v1 infohash. Each
transfer is loaded stopped through XML-RPC ``load.raw_verbose`` and then read
back by exact hash. Never used against a protected host: URLs must be
http://127.0.0.1. Writes a nonsecret JSON receipt for the installed test.
"""
import argparse
import hashlib
import json
import sys
import time
import xmlrpc.client


def bencode(value):
    if isinstance(value, int):
        return b"i%de" % value
    if isinstance(value, bytes):
        return b"%d:%s" % (len(value), value)
    if isinstance(value, str):
        return bencode(value.encode())
    if isinstance(value, list):
        return b"l" + b"".join(bencode(v) for v in value) + b"e"
    if isinstance(value, dict):
        items = sorted((k.encode() if isinstance(k, str) else k, v) for k, v in value.items())
        return b"d" + b"".join(bencode(k) + bencode(v) for k, v in items) + b"e"
    raise TypeError(type(value))


def torrent(name, payload):
    info = {"length": len(payload), "name": name, "piece length": 16384,
            "pieces": hashlib.sha1(payload).digest()}
    meta = bencode({"announce": "http://127.0.0.1:1/announce", "info": info})
    return meta, hashlib.sha1(bencode(info)).hexdigest()


def load(url, meta, infohash, directory, user="", password=""):
    if not url.startswith("http://127.0.0.1:"):
        raise SystemExit("disposable loopback servers only")
    if user:
        url = url.replace("http://", "http://%s:%s@" % (user, password), 1)
    server = xmlrpc.client.ServerProxy(url)
    if infohash.upper() in [h.upper() for h in server.download_list("", "main")]:
        raise SystemExit("fixture hash unexpectedly present; refusing")
    server.load.raw_verbose("", xmlrpc.client.Binary(meta), "d.directory.set=" + directory)
    for _ in range(50):
        try:
            if server.d.name(infohash.upper()) and server.d.state(infohash.upper()) == 0:
                return
        except xmlrpc.client.Fault:
            pass
        time.sleep(0.2)
    raise SystemExit("stopped fixture not verified on %s" % url)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--a", required=True)
    parser.add_argument("--a-user", default="")
    parser.add_argument("--a-pass", default="")
    parser.add_argument("--b", required=True)
    parser.add_argument("--dir-a", required=True)
    parser.add_argument("--dir-b", required=True)
    parser.add_argument("--run", required=True)
    parser.add_argument("--receipt", required=True)
    args = parser.parse_args()
    shared_name = "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.x264-CHUNKS.mkv"
    only_a_name = "chunks-combined-only-a-%s.bin" % args.run
    only_b_name = "chunks-combined-only-b-%s.bin" % args.run
    shared, shared_hash = torrent(shared_name, b"shared:" + args.run.encode() * 64)
    only_a, hash_a = torrent(only_a_name, b"only-a:" + args.run.encode() * 64)
    only_b, hash_b = torrent(only_b_name, b"only-b:" + args.run.encode() * 64)
    load(args.a, shared, shared_hash, args.dir_a, args.a_user, args.a_pass)
    load(args.a, only_a, hash_a, args.dir_a, args.a_user, args.a_pass)
    load(args.b, shared, shared_hash, args.dir_b)
    load(args.b, only_b, hash_b, args.dir_b)
    receipt = {"shared": shared_name, "shared_hash": shared_hash,
               "only_a": only_a_name, "hash_a": hash_a,
               "only_b": only_b_name, "hash_b": hash_b}
    with open(args.receipt, "w") as handle:
        json.dump(receipt, handle, indent=2)
    print("CHUNKS_COMBINED_PRELOAD: shared=%s a=%s b=%s stopped=verified" % (shared_hash, hash_a, hash_b))


if __name__ == "__main__":
    sys.exit(main())
