#!/usr/bin/env python3
"""Protected single ruTorrent native Add-form control for a known public magnet.

Compare the EXACT magnet/seedbox already tested via native 2-arg load.start.
The previous control verified its own session-only cleanup before this test.
Basic HTTP auth was positively verified on this endpoint before form POST.
Do not retry any uncertain non-idempotent request.
"""
import os
import sys
import time
import uuid
import urllib.parse
import urllib.request

from seedhost_magnet_twoarg_control_once import ExactRpc, fixture, cleanup
from seedhost_magnet_readonly_forensics import RPC

FORM = "https://ianseeds.wood.seedhost.eu/rutorrent/php/addtorrent.php"


def post_form_once(client):
    if urllib.parse.urlsplit(FORM).netloc != urllib.parse.urlsplit(RPC).netloc:
        raise ValueError("ruTorrent form and RPC not same server")
    if urllib.parse.urlsplit(FORM).scheme != "https":
        raise ValueError("ruTorrent Add form must use HTTPS")
    boundary = "----chunks" + uuid.uuid4().hex
    body = (
        "--" + boundary + "\r\n" +
        'Content-Disposition: form-data; name="url"\r\n\r\n' +
        client.magnet + "\r\n" +
        "--" + boundary + "--\r\n"
    ).encode("utf-8")
    request = urllib.request.Request(
        FORM, data=body, method="POST",
        headers={
            "Content-Type": "multipart/form-data; boundary=" + boundary,
            "Accept": "application/json, text/plain, */*",
            "Authorization": client.auth,
        },
    )
    # The same no-auth-handler, no-redirect opener issues this POST ONCE.
    # An HTTP failure or timeout could have applied the Add; never replay.
    with client.opener.open(request, timeout=20) as response:
        if response.status != 200 or response.geturl() != FORM:
            raise ValueError("untrusted form ACK")
        response.read(32_769)  # Never print response / tracker / login data.


def main():
    if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_RUN_ATTEMPT") != "1":
        raise ValueError("not an authorized trusted-main first attempt")
    if not os.environ.get("CHUNKS_LIVE_RTORRENT_USER") or not os.environ.get("CHUNKS_LIVE_RTORRENT_PASS"):
        raise ValueError("missing protected credentials")
    hash_value, expected_name, expected_size, magnet = fixture()
    client = ExactRpc(hash_value, magnet)

    # Read-only preauthorization using explicit Basic. The form POST itself
    # NEVER automatically resends following 401, failed ACK or proxy timeout.
    version = client.call("system.client_version")
    if not isinstance(version, str) or not version:
        raise ValueError("auth read preflight not proven")
    if hash_value in client.inventory():
        raise ValueError("known source already registered; no duplicate Add")
    print("MAGNET_RUTORRENT preflight=authenticated exact_hash=absent source=previously-peer-verified")

    attempted = False
    passed = False
    cleaned = False
    try:
        attempted = True
        post_form_once(client)
        print("MAGNET_RUTORRENT form_post=acknowledged")
        deadline = time.monotonic() + 180
        registered = False
        metadata = False
        peers = False
        received = False
        verified = False
        while time.monotonic() < deadline:
            try:
                if hash_value not in client.inventory():
                    time.sleep(4)
                    continue
                snapshot = client.sample()
            except Exception:
                time.sleep(4)
                continue
            if not registered:
                registered = True
                print("MAGNET_RUTORRENT registration=exact-hash-confirmed")
            metadata = metadata or (
                snapshot["d.name"] == expected_name and
                snapshot["d.size_bytes"] == expected_size
            )
            peers = peers or isinstance(snapshot["d.peers_connected"], int) and snapshot["d.peers_connected"] > 0
            received = received or isinstance(snapshot["d.down.total"], int) and snapshot["d.down.total"] > 0
            verified = verified or isinstance(snapshot["d.completed_bytes"], int) and snapshot["d.completed_bytes"] > 0
            if metadata and peers and received and verified:
                passed = True
                break
            time.sleep(4)
        print("MAGNET_RUTORRENT evidence registered=" + str(registered).lower() +
              " metadata=" + str(metadata).lower() +
              " connected_peer=" + str(peers).lower() +
              " received=" + str(received).lower() +
              " verified=" + str(verified).lower())
    finally:
        if attempted:
            try:
                cleaned = cleanup(client)
            except Exception:
                print("MAGNET_RUTORRENT cleanup=uncertain no-retry")
    if not (passed and cleaned):
        raise ValueError("reference magnet or exact cleanup did not pass")
    print("MAGNET_RUTORRENT PASS real metainfo and peer/piece-verified bytes")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Do not print HTTP error body, URLs, credentials, private torrent data.
        print("MAGNET_RUTORRENT result=failed-or-inconclusive; no automatic retry")
        sys.exit(1)
