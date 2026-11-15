#!/usr/bin/env python3
"""Stage ONE stopped public-title fixture on a DISPOSABLE local rTorrent.

The job's name is the same public TV-episode release name the local installed
artwork proof uses, so automatic identification can be checked on a *remote*
inventory. Payload bytes are run-specific and never downloaded; the announce
URL is an unreachable loopback port. Loopback servers only.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from preload_combined_two_servers import load, torrent  # noqa: E402

NAME = "Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.x264-GRP.mkv"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--dir", required=True)
    parser.add_argument("--run", required=True)
    parser.add_argument("--receipt", required=True)
    args = parser.parse_args()
    meta, infohash = torrent(NAME, b"remote-media:" + args.run.encode() * 128)
    load(args.url, meta, infohash, args.dir)
    with open(args.receipt, "w") as out:
        json.dump({"name": NAME, "hash": infohash}, out)
    print("CHUNKS_REMOTE_MEDIA_FIXTURE: staged stopped hash=%s" % infohash)


if __name__ == "__main__":
    main()
