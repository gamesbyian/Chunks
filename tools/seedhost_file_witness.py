#!/usr/bin/env python3
"""Private subprocess protocol: independent read-only exact-parent PHP file listing.

Stdout contains private base64 paths for the calling host test ONLY, never CI output.
No torrent RPC, mutation, redirects, host fallback or filesystem traversal.
"""
import base64
import json
import os
import sys
from urllib import parse, request, error

BASE = 'https://ianseeds.wood.seedhost.eu/rutorrent/'

class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

def read_parent(parent):
    if (len(parent) > 4096 or not parent.startswith('/') or parent == '/' or
        any(c in parent for c in ('\\', '\r', '\n', '\x00')) or
        any(p in ('', '.', '..') for p in parent[1:].split('/'))):
        raise ValueError('invalid parent')
    url = BASE + 'plugins/_getdir/listdir.php?' + parse.urlencode({'dir': parent})
    manager = request.HTTPPasswordMgrWithDefaultRealm()
    manager.add_password(None, url, os.environ['CHUNKS_LIVE_RTORRENT_USER'], os.environ['CHUNKS_LIVE_RTORRENT_PASS'])
    client = request.build_opener(NoRedirect(), request.HTTPDigestAuthHandler(manager), request.HTTPBasicAuthHandler(manager))
    with client.open(request.Request(url, headers={'Accept':'application/json'}), timeout=20) as response:
        if response.status != 200 or response.url != url:
            raise ValueError('unexpected response')
        raw = response.read(2_000_001)
    if len(raw) > 2_000_000:
        raise ValueError('oversized response')
    page = json.loads(raw)
    if set(page) != {'path', 'directories', 'files'} or page['path'].rstrip('/') != parent:
        raise ValueError('parent fallback or invalid page')
    paths = {parent + '/'}
    for kind in ('directories', 'files'):
        entries = page[kind]
        if not isinstance(entries, list) or len(entries) > 12_000:
            raise ValueError('invalid entries')
        for name in entries:
            if kind == 'directories' and name in ('.', '..'):
                continue
            if (not isinstance(name, str) or not name or name in ('.', '..') or
                '/' in name or '\\' in name or any(ord(c) < 32 or ord(c) == 127 for c in name)):
                raise ValueError('invalid child')
            paths.add(parent + '/' + name + ('/' if kind == 'directories' else ''))
    return paths

def main():
    if os.environ.get('GITHUB_REF') != 'refs/heads/main' or os.environ.get('GITHUB_RUN_ATTEMPT') != '1':
        raise ValueError('not trusted first attempt main')
    parent = sys.stdin.readline(4098).removesuffix('\n')
    for path in sorted(read_parent(parent)):
        print(base64.b64encode(path.encode()).decode())

if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        # Caller captures stderr privately too. Never print exception text.
        code = (' code=' + str(failure.code)) if isinstance(failure, error.HTTPError) else ''
        print('FILESYSTEM_WITNESS ' + type(failure).__name__ + code, file=sys.stderr)
        sys.exit(1)
