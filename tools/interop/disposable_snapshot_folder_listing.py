#!/usr/bin/env python3
"""Disposable read-only ruTorrent _getdir fixture for one real rTorrent account.

Only the runner's precreated root and one child are acknowledged. nginx owns
the matching Basic-auth tenant mount; this loopback service never writes.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit

root = sys.argv[1]
assert root.startswith("/") and root != "/" and not root.endswith("/")
target = root + "/reviewed"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlsplit(self.path)
        args = parse_qs(url.query, keep_blank_values=True)
        if url.path != "/rutorrent/plugins/_getdir/listdir.php" or set(args) != {"dir"} or len(args["dir"]) != 1:
            self.send_error(404)
            return
        requested = args["dir"][0]
        if requested == "":
            body = {"path": root, "directories": ["reviewed"]}
        elif requested == target:
            body = {"path": target, "directories": []}
        else:
            self.send_error(404)
            return
        data = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        # Path arguments can be supplied by a user; do not log them.
        return


HTTPServer(("127.0.0.1", 18084), Handler).serve_forever()
