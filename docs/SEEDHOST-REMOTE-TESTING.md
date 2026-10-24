# Seedhost remote setup and read-only acceptance

Seedhost documents HTTPS port 443, ruTorrent authentication and the XML-RPC plugin endpoint at /rutorrent/plugins/httprpc/action.php. On some installations the endpoint includes a leading /username/ path; an older /username/RPC1 variant is also documented. The ruTorrent HTML URL is not itself the RPC endpoint.

- https://www.seedhost.eu/client-area/knowledgebase/241/Sonarr-basic-configuration.html
- https://www.seedhost.eu/client-area/knowledgebase/300/Couchpotato-basic-configuration.html

Chunks now derives the plugin RPC candidate from a user-entered ruTorrent web URL. An individual Seedhost account's exact path and authentication must be verified; guessing is not proof.

Never commit or echo the owner's personal seedbox address, password, private torrent inventory or tracker tokens. GitHub secret values cannot be read back. The general-purpose repo uses FTP_PASSWORD, which must NOT automatically be interpreted as a ruTorrent credential.

Acceptance: a trusted default-branch, manual-only workflow with explicit relevant credentials stored as Chunks repository secrets; read-only system.client_version over HTTPS with bounded response and redacted logs; then version/method and list completeness verification and physical Android testing. No file movement or write calls before authorization and separate disposable tests.

## Available manual smoke workflow

`.github/workflows/seedhost-readonly-probe.yml` can be launched from Actions **after merging**, but only if credentials and URL are present as repository secrets. Accepted URL secret names: `RTORRENT_URL`, `RUTORRENT_URL` or `SEEDHOST_RUTORRENT_URL`; accepted user/password pairs: `RTORRENT_USERNAME`/`RTORRENT_PASSWORD`, `RUTORRENT_USERNAME`/`RUTORRENT_PASSWORD` or `SEEDHOST_USERNAME`/`SEEDHOST_PASSWORD`. If secret names differ, adapt the workflow carefully on a trusted branch rather than exposing values. The workflow does not print the target URL, version, XML response or credentials. It has no push or PR trigger and makes only the read-only `system.client_version` request.
