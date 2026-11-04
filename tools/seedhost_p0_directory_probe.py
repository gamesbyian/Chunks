#!/usr/bin/env python3
"""Verify one existing owner-authorized ruTorrent download folder without mutation.

Only callable for the two numbered development instances on trusted-main GHA.
Any redirect, authentication/permission failure, unexpected directory fallback,
unknown response, or path change refuses staging before a torrent Add.
"""
import json
import os
from pathlib import Path
from urllib import error, parse, request

# Primary instance's 2026-11-04 read-only inventory (run 41769760329)
# confirmed 11 existing complete single-file transfers at this parent.
# It is a CANDIDATE on numbered instances until their own read-only picker
# returns the exact same accessible path.
CANDIDATE_PARENT = "/home20/ianseeds/downloads"

def acceptable(response, candidate=CANDIDATE_PARENT):
    if not isinstance(response, dict) or set(response.keys()) != {
        "path", "directories", "files"
    }:
        return False
    path = response["path"]
    if not isinstance(path, str) or not path.startswith("/") or path == "/":
        return False
    if any(part in ("", ".", "..") for part in path.strip("/").split("/")):
        return False
    if not isinstance(response["directories"], list) or not isinstance(response["files"], list):
        return False
    if len(response["directories"]) > 10000 or len(response["files"]) > 10000:
        return False
    return path.rstrip("/") == candidate.rstrip("/")

class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def main():
    instance = os.environ["CHUNKS_P0_COHORT_INSTANCE"]
    run_id = os.environ["CHUNKS_P0_COHORT_RUN_ID"]
    user = os.environ["CHUNKS_P0_COHORT_USER"]
    password = os.environ["CHUNKS_P0_COHORT_PASS"]
    root = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    output = Path(os.environ["CHUNKS_P0_COHORT_DEST_FILE"])
    if (instance not in ("2", "3") or
        not run_id.isdecimal() or len(run_id) > 20 or
        user != "ianseeds" or not password or
        output != root / ("cohort-confirmed-dir-" + instance + ".txt")):
        raise RuntimeError("Unauthorized numbered-profile destination probe")
    origin = "https://ianseeds.wood.seedhost.eu"
    url = (origin + "/rutorrent" + instance + "/plugins/_getdir/listdir.php?" +
        parse.urlencode({"dir": CANDIDATE_PARENT}))
    credentials = request.HTTPPasswordMgrWithDefaultRealm()
    credentials.add_password(None, url, user, password)
    opener = request.build_opener(
        NoRedirect(),
        request.HTTPDigestAuthHandler(credentials),
        request.HTTPBasicAuthHandler(credentials),
    )
    req = request.Request(url, method="GET", headers={"Accept": "application/json"})
    try:
        with opener.open(req, timeout=12) as response:
            if response.url != url or response.status != 200:
                raise RuntimeError("Remote directory-picker response did not match exact request")
            body = response.read(131073)
            if len(body) > 131072:
                raise RuntimeError("Directory response too large")
        result = json.loads(body)
    except (error.HTTPError, error.URLError, ValueError) as problem:
        # No host paths, response body, Basic/Digest headers or URL are logged.
        raise RuntimeError("Numbered-instance directory read unavailable: " +
                           type(problem).__name__) from None
    if not acceptable(result):
        raise RuntimeError(
            "Numbered-instance picker did not verify primary download parent; no Add authorized"
        )
    output.write_text(CANDIDATE_PARENT + "\n", encoding="utf-8")
    print("CHUNKS_P0_DIR: instance=" + instance +
          " exact parent confirmed read-only, no mutation")

if __name__ == "__main__":
    main()
