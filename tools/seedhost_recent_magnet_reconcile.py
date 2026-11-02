#!/usr/bin/env python3
"""Read-only exact public synthetic transfer name reconciliation.

Only the unique failed one-shot BEP9 test run 41370450181 is queried. This
script never mutates rTorrent, leaks private inventory or prints RPC faults.
"""
import os
import sys

from seedhost_magnet_readonly_forensics import call, rpc_opener

RUN_ID = "41370450181"
EXPECTED_NAME = "chunks-magnet-peer-" + RUN_ID + ".bin"
EXPECTED_SIZE = 512 * 1024


def main():
    if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_RUN_ATTEMPT") != "1":
        raise ValueError("trusted-main read-only run only")
    if not os.environ.get("CHUNKS_LIVE_RTORRENT_USER") or not os.environ.get("CHUNKS_LIVE_RTORRENT_PASS"):
        raise ValueError("no protected credentials")
    opener = rpc_opener()
    hashes = call(opener, "download_list")
    if not isinstance(hashes, (tuple, list)) or len(hashes) > 20000:
        raise ValueError("unusable server inventory")
    matches = []
    faults = 0
    for value in hashes:
        hash_value = str(value)
        if len(hash_value) != 40 or any(c not in "0123456789abcdefABCDEF" for c in hash_value):
            raise ValueError("invalid inventory hash")
        try:
            if call(opener, "d.name", hash_value) == EXPECTED_NAME:
                matches.append(hash_value.lower())
        except Exception:
            faults += 1
    print("MAGNET_RECONCILE run=" + RUN_ID +
          " found_by_original_name=" + str(len(matches)) +
          " unknown_name_reads=" + str(faults) +
          " total_entries=" + str(len(hashes)))
    for target in matches:
        # This is a newly generated synthetic-only infohash, not any user job.
        state = {}
        for method in ("d.hash", "d.state", "d.is_active", "d.is_open", "d.size_bytes", "d.down.total", "d.completed_bytes", "d.peers_connected"):
            try:
                state[method] = call(opener, method, target)
            except Exception:
                state[method] = None
        if str(state["d.hash"]).lower() != target:
            print("MAGNET_RECONCILE identity=unverified")
            continue
        yes = lambda key: "true" if isinstance(state[key], int) and state[key] > 0 else "false" if isinstance(state[key], int) else "unknown"
        print("MAGNET_RECONCILE synthetic_hash=" + target +
              " size_is_512k=" + str(state["d.size_bytes"] == EXPECTED_SIZE).lower() +
              " started=" + yes("d.state") +
              " active=" + yes("d.is_active") +
              " received=" + yes("d.down.total") +
              " verified=" + yes("d.completed_bytes") +
              " peers=" + yes("d.peers_connected"))
    print("MAGNET_RECONCILE read_only=true mutations=0")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("MAGNET_RECONCILE result=unavailable no-mutations")
        sys.exit(1)
