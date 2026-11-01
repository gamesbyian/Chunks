# Public magnet catalog harvesting (development fixtures)

This directory is reserved for **real, source-verified** BitTorrent magnets. Do not enter guessed hashes or treat a successful metadata parse as a working download.

Run from repo root when external connectivity is available:

```bash
python3 tools/collect_public_magnet_catalog.py --count 100 --max-bytes 555000000
```

Outputs are `test-resources/public-magnets/catalog.json` and `magnets.txt`. Both are intended to be reviewed and committed as ordinary repository resources once generated. The script discovers diverse Internet Archive media categories, checks a clearly reusable licence, fetches the actual published .torrent metainfo using the existing security-hardened collector, derives the authentic v1/v2 infohash from the exact original info dictionary, rejects torrents exceeding **555,000,000 bytes total**, and deduplicates hashes. It does not invent sources, fake magnet links, download payloads, touch seedboxes or perform rTorrent writes. Insufficient verified candidates cause a nonzero exit; **never pad the count**.

**Important qualification:** Internet Archive is one source/tracker family and can rely entirely on HTTPS webseeds. This catalog, even when it reaches 100 genuine magnets, does **not** establish 100 active conventional swarms or satisfy the requested cross-tracker diversity. For ordinary peer acceptance, a second live verification stage must resolve metadata via actual peers, observe tracker/DHT peers and verify per-hash piece bytes with the target rTorrent environment. Record dates and results per item. Unknown seeder counts remain **unverified**, not zero and not positive. See [public source fixture process](../../docs/PUBLIC-TORRENT-FIXTURES.md) and [real transfer acceptance](../../docs/REAL-TRANSFER-ACCEPTANCE.md).

**Status as authored:** collector implementation added; the requested 100-row output **has not been collected or verified in this change**. Review output and confirm source media licensing before wide use. Prioritize creator-hosted open-source torrents with demonstrable ordinary peer seeders for the real Add → Start smoke suite.
