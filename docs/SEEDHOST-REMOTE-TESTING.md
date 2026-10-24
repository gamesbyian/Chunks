# Seedhost remote setup and read-only acceptance

Seedhost documents HTTPS port 443, ruTorrent authentication and the XML-RPC plugin endpoint at /rutorrent/plugins/httprpc/action.php. On some installations the endpoint includes a leading /username/ path; an older /username/RPC1 variant is also documented. The ruTorrent HTML URL is not itself the RPC endpoint.

- https://www.seedhost.eu/client-area/knowledgebase/241/Sonarr-basic-configuration.html
- https://www.seedhost.eu/client-area/knowledgebase/300/Couchpotato-basic-configuration.html

Chunks now derives the plugin RPC candidate from a user-entered ruTorrent web URL. An individual Seedhost account's exact path and authentication must be verified; guessing is not proof.

Never commit or echo the owner's personal seedbox address, password, private torrent inventory or tracker tokens. GitHub secret values cannot be read back. The general-purpose repo uses FTP_PASSWORD, which must NOT automatically be interpreted as a ruTorrent credential.

Acceptance: a trusted default-branch, manual-only workflow with explicit relevant credentials stored as Chunks repository secrets; read-only system.client_version over HTTPS with bounded response and redacted logs; then version/method and list completeness verification and physical Android testing. No file movement or write calls before authorization and separate disposable tests.
