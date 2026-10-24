# Public torrent and media fixture process

## Sources and provenance

Use approved, license-reviewed fixtures before any test Add, Start, Stop, Remove or Relocate. Internet Archive publishes real .torrent metainfo for many public items, with an official metadata API (https://archive.org/developers/metadata.html) and Archive BitTorrents FAQ (https://archivesupport.zendesk.com/hc/en-us/articles/360004715251-Archive-BitTorrents). Archive hosting does not by itself guarantee licensing or swarm availability; mutable Archive items can make old torrents obsolete.

Wikimedia Commons provides individually licensed media files through its MediaWiki API. Official Wikimedia data dumps distribute XML, compressed .bz2/.7z, JSON and checksums; they do not promise a universal torrent/magnet feed. Modern MediaWiki Content File Exports supersede several legacy dumps and replace them by January 2027 (https://wikitech.wikimedia.org/wiki/MediaWiki_Content_File_Exports). Wikipedia articles about torrents are documentation, not test torrents. Do not invent a torrent or magnet for a Wikimedia file that has not been hashed and made available to peers.

## Commands

Python standard library only. Offline tests:

    python3 -m unittest discover -s tools -p 'test_public_fixture_collector.py' -v

Manual local single-item collection (replace these placeholders with verified public sources):

    python3 tools/public_fixture_collector.py --archive-id PUBLIC_ITEM_ID --output-dir /tmp/chunks-archive-fixture
    python3 tools/public_fixture_collector.py --commons-file 'File:PUBLIC_IMAGE.png' --output-dir /tmp/chunks-commons-fixture

Outputs for Internet Archive: manifest.json, source-sanitized.torrent, source.magnet. The collector reads item metadata and up to 2 MiB of published torrent metainfo, verifies an explicit CC/public-domain license, bencode structure, path safety, torrent file sizes and budget, retains the **original exact raw info dictionary** and derives a magnet from the verified infohash. It strips untrusted announce addresses, passkeys, extraneous comments and original webseed metadata from the exported torrent; only the official Archive tracker may be retained. It never downloads actual media or contacts rTorrent.

Commons outputs manifest.json only, including the official source media URL, MIME, size and licensing. No torrent/magnet is asserted. Media formats such as .ogg, .webm, .png, .pdf can later exercise file picker, Android share/open and MIME handling. Wikimedia dumps (.xml, .bz2, .7z, .json, SHA256SUMS) are content/picker references, not implicitly BitTorrent sources.

## CI / use

Manual-only harvesting: https://github.com/gamesbyian/Chunks/actions/workflows/public-source-fixtures.yml . Choose archive or commons, enter exact public item ID or File: title, optionally configure maximum advertised torrent payload (default 512 MiB). Only the offline security/parser tests run on a PR; collection is manual, has no secrets or BitTorrent mutations, runs against allowlisted provider hosts, and uploads a 14-day artifact. Fail closed if license missing, response oversize, path unsafe or torrent unavailable. Avoid high-volume crawling.

## Test matrix and next gates

1. Internet Archive real .torrent and exact-infohash magnet: distinguish upload, magnet, duplicate hash, metadata pending, cancel, destination selection and read-back.
2. Commons OGG/WebM/PNG/PDF: content reference, MIME filter, Android share/open and future *locally built and seeded* torrent creation.
3. Official Wikimedia .xml/.bz2/.7z/.json: asset/filename, large-size, compression and validation UX, without falsely treating them as torrent metainfo.
4. Synthetic malformed bencode, duplicate keys, unsafe path, malicious tracker/redirect, oversized source, unavailable license: refuse and log meaningful errors.
5. Actual authorized dev-Seedhost tests: explicitly choose target infohash and destination, snapshot existing transfers, add one approved fixture, verify hash/state/path, then test lifecycle, removal and recovery. All server Add/Remove operations need their own manual acceptance gate; this collector itself NEVER performs them.

The owner expressly authorized destructive torrent testing on the designated Seedhost development instance and authorized real test torrent details in visual QA artifacts. This is not permission to expose passwords, session cookies or tracker passkeys, or to mutate unrelated FTP paths. Physical file movement requires its own journal and byte-level verification.

## Opt-in real Commons bytes and newly built .torrent

To test actual file import, enable `download_commons` in the manual workflow, or run `python3 tools/public_fixture_collector.py --commons-file 'File:PUBLIC_SMALL_IMAGE.png' --download-commons --max-commons-mib 8 --output-dir /tmp/commons-test`. The script refuses media over 8 MiB by default (hard maximum 16 MiB), checks the MediaWiki-reported byte length, computes SHA256 and creates a deterministic v1 single-file torrent with 64 KiB pieces from the **actual downloaded bytes**. It includes the exact official HTTPS original as a BEP19 URL seed without an external tracker. This is **new local metainfo**, not an existing public swarm; rTorrent webseed support and actual downloading remain unverified. The file, .torrent, .magnet and provenance manifest become one short-lived artifact. For reproducibility, preserve the content SHA256 and infohash together. File formats follow the actual Commons asset type; do not bypass the explicit license gate.

## Integration with Chunks remote Add core

PR #31 introduces a deliberate stopped-load API accepting validated v1 magnet links and official Archive `.torrent` URLs when supplied with the fixture's **verified** 40-character infohash and an explicit absolute server destination. It preflights the main view, requests `load.verbose` with a directory post-load command, and reads back hash, directory base and stopped state. It is **not** a physical move, full URL/binary file import, verified Seedhost command set or connected Compose UI. Treat source-hash mismatch and uncertain replies as failures needing reconciliation. Later, on-demand local `.torrent` binary upload needs separate XML-RPC base64 encoding/validation and full acceptance.
