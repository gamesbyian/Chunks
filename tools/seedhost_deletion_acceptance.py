#!/usr/bin/env python3
"""Trusted-main, read-only deletion diagnosis. Never print server-controlled strings."""
import json
import re
import ftplib
import ssl
import os
import sys
import urllib.error
import urllib.request
import xmlrpc.client

BASE = 'https://ianseeds.wood.seedhost.eu/rutorrent/'
READS = (
    ('system.client_version', ('',)),
    ('system.listMethods', ()),
    ('system.cwd', ('',)),
    ('method.get', ('', 'event.download.erased')),
    ('method.list_keys', ('', 'event.download.erased')),
    ('directory.default', ('',)),
    ('view.size_not_visible', ('', 'default')),
    ('view.size', ('', 'default')),
    ('download_list', ('',)),
    ('download_list', ('', 'default')),
    ('download_list', ('', 'main')),
    ('d.multicall2', ('', 'default', 'd.hash=', 'd.name=', 'd.directory=')),
)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

def opener():
    manager = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    manager.add_password(None, BASE, os.environ['CHUNKS_LIVE_RTORRENT_USER'],
                         os.environ['CHUNKS_LIVE_RTORRENT_PASS'])
    return urllib.request.build_opener(NoRedirect(),
        urllib.request.HTTPDigestAuthHandler(manager), urllib.request.HTTPBasicAuthHandler(manager))

def main():
    if os.environ.get('GITHUB_REF') != 'refs/heads/main' or os.environ.get('GITHUB_RUN_ATTEMPT') != '1':
        raise ValueError('trusted main only')
    client = opener()
    inventory = None
    for method, args in READS:
        result = {'phase':'rpc', 'method':method, 'variant':len(args)}
        try:
            body = xmlrpc.client.dumps(args, methodname=method).encode()
            req = urllib.request.Request(BASE + 'plugins/httprpc/action.php', data=body,
                headers={'Content-Type':'text/xml'}, method='POST')
            with client.open(req, timeout=20) as response:
                raw = response.read(2_000_001)
            if len(raw) > 2_000_000:
                raise ValueError('oversized')
            value = xmlrpc.client.loads(raw)[0][0]
            result['status'] = 'ok'
            result['type'] = type(value).__name__
            if isinstance(value, list):
                result['count'] = len(value)
            if method == 'method.get' and isinstance(value, dict):
                principal = os.environ['CHUNKS_LIVE_RTORRENT_USER']
                result['hooks'] = {prefix: isinstance(value.get(prefix + principal), str) for prefix in ('erasedata0', 'erasedata1')}
                result['hook_semantics'] = {token: any(token in str(v) for k,v in value.items() if k in ('erasedata0' + principal, 'erasedata1' + principal)) for token in ('custom5', 'frozen_path', '/fin.sh', 'd.open')}
            if method == 'view.size_not_visible' and isinstance(value, int):
                result['unfiltered'] = value == 0
            if method == 'system.listMethods' and isinstance(value, list):
                result['deletion_getters'] = {m: m in value for m in
                    ('view.size_not_visible', 'download_list', 'd.directory', 'f.multicall')}
            if method == 'd.multicall2':
                inventory = value
        except urllib.error.HTTPError as error:
            result.update(status='http', code=error.code)
        except xmlrpc.client.Fault as error:
            result.update(status='rpc-fault', code=error.faultCode)
            # Classify known policy refusals without echoing arbitrary fault text.
            result['policy_denied'] = any(s in error.faultString.lower() for s in ('not permitted', 'not allowed', 'not supported', 'untrusted', 'denied'))
        except Exception:
            result['status'] = 'unavailable'
        print('DELETION_PROBE ' + json.dumps(result, sort_keys=True), flush=True)
    for path in ('plugins/erasedata/action.php', 'plugins/erasedata/init.js', 'plugins/httprpc/init.js'):
        result = {'phase':'http', 'route':path.split('/')[1], 'resource':path.rsplit('/',1)[1]}
        try:
            req = urllib.request.Request(BASE + path, method='GET' if path.endswith('.js') else 'HEAD')
            with client.open(req, timeout=20) as response:
                raw = response.read(150_001) if path.endswith('.js') else b''
                result.update(status='ok', code=response.status)
                if raw:
                    result['form_adapter'] = b'getCommon("removewithdata")' in raw or b'getCommon( "removewithdata" )' in raw
                    result['legacy_custom5'] = b'custom5' in raw
                    result['httprpc_delete_override'] = bool(re.search(rb'rTorrentStub\.prototype\.removewithdata\s*=', raw))
                    result['legacy_delete_commands'] = all(token in raw for token in (b'd.set_custom5', b'd.delete_tied', b'd.erase'))
        except urllib.error.HTTPError as error:
            result.update(status='http', code=error.code)
        except Exception:
            result['status'] = 'unavailable'
        print('DELETION_PROBE ' + json.dumps(result, sort_keys=True), flush=True)
    try:
        with ftplib.FTP_TLS(context=ssl.create_default_context(), timeout=15) as ftp:
            ftp.connect('ianseeds.wood.seedhost.eu', 21)
            ftp.login(os.environ['CHUNKS_LIVE_RTORRENT_USER'], os.environ['CHUNKS_LIVE_RTORRENT_PASS'])
            ftp.prot_p()
            result = {'phase':'independent-files', 'ftps':'authenticated', 'pwd_absolute':ftp.pwd().startswith('/')}
    except Exception:
        result = {'phase':'independent-files', 'ftps':'unavailable'}
    print('DELETION_PROBE ' + json.dumps(result, sort_keys=True), flush=True)
    print('DELETION_PROBE completed zero_mutations=true')

if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('DELETION_PROBE fatal=preflight-or-transport')
        sys.exit(1)
