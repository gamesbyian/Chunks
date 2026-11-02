# Cross-media provider record evidence (2026-11-02)

**Research only, no provider adapter or live API JSON acceptance.** The [provider registry](../MEDIA-PROVIDER-REGISTRY.md) owns legal and implementation candidates; [shared relationship contract](../SHARED-MEDIA-IDENTITY-AND-SIMILAR-TRANSFERS.md) owns the graph. Public human-readable record facts are not proof a particular API returns those fields.

## Real cross-media test evidence

| Relationship | Verified source facts | Required app evidence | Common false positive |
| --- | --- | --- | --- |
| Babylon 5 S02E07 ↔ Hulk comics | [TVmaze Soul Mates, episode 43454](https://www.tvmaze.com/episodes/43454/babylon-5-2x07-soul-mates) credits Peter David as writer; [GCD Incredible Hulk #331, issue 42860](https://www.comics.org/issue/42860/) specifically credits Peter David with the script of the story Inconstant Moon. | Actual episode-specific writer and story/issue writer IDs, exact verified comic members, and [Wikidata Q1351554](https://www.wikidata.org/wiki/Q1351554) crosswalk TMDB person 156223 to GCD creator 10447. | Same display name without ID mapping; assigning one credit to all 1987–1998 issues, cover, letters and reprints. |
| Walk the Line ↔ Johnny Cash FLAC albums | [20th Century Studios](https://www.20thcenturystudios.com/movies/walk-the-line) identifies the film's true biographical subject Johnny Cash; MusicBrainz separates artists, recordings, releases and release groups. | Verified film subject identity (not Joaquin Phoenix as subject) linked to correct MB artist/album credit. | Blind text-token or actor name matches. |
| Walk the Line ↔ Ray | Two film biographies about musicians. | Specific independently verified music-biopic theme, subject and profession. | Generic drama/movie genre. |
| Ray ↔ Stealth | Shared actor Jamie Foxx. | Same resolved cast person ID on both movies. | People with identical or similar names. |
| Stealth ↔ Her | Both centrally feature AI across dissimilar genres. | Independently confirmed high-specificity artificial-intelligence narrative subject. | Generic science fiction classification. |

## Concrete API contracts requiring sample JSON

- **TMDB:** [episode credits](https://developer.themoviedb.org/reference/tv-episode-credits) and [season details](https://developer.themoviedb.org/reference/tv-season-details) expose episode identity plus crew member ID, department and job when present. Do not substitute show-level crew for writer of a particular episode. Requires registered API key and appropriate license/attribution.
- **TVmaze:** [API](https://www.tvmaze.com/api) documents episodes by ID, episode guest crew, and show-level crew separately. The *website* for episode 43454 lists Peter David; this review did **not** capture its raw guestcrew API JSON. Verify role coverage before trusting the endpoint for writers.
- **Grand Comics Database:** its [own site](https://www.comics.org/) explicitly warns that API URLs are stable but fields/format are currently not. [Hulk #331](https://www.comics.org/issue/42860/) contains multiple story and non-story sections with distinct script, lettering, cover, editing roles. A [2026 issue report](https://github.com/GrandComicsDatabase/gcd-django/issues/710) alleges reprint/localization credit contamination: treat this as a source-quality risk report, **not** proven universal corruption.
- **Metron:** [documented API](https://github.com/Metron-Project/metron/blob/master/api/README.md) requires basic or bearer-token auth, exposes issue/creator/role and GCD ID filters, and supports If-Modified-Since detail fetches. Never assume anonymous public API. Confirm exact live nested credit shape with an authorized token; no token in APK.
- **Wikidata:** [Q1351554](https://www.wikidata.org/wiki/Q1351554) exposes TMDB person ID 156223 (property P4985) and GCD creator ID 10447 (property P9707). Fetch one item's EntityData JSON or MediaWiki API, not an unbounded SPARQL graph query. Verify conflicts and provenance; do not join creator records solely by names.
- **MusicBrainz:** [WS/2](https://musicbrainz.org/doc/MusicBrainz_API) distinguishes artist, recording, release-group, release and work, supports relations and pagination; [release-group semantics](https://musicbrainz.org/doc/Release_Group) distinguish conceptual album from precise edition. Use meaningful User-Agent and <=1 call/second; no all-tracks artist attribution from a compilation's release credit.
- **Open Library:** [low-volume API policy](https://openlibrary.org/developers/api) requests caching and human-directed lookups, 1 req/s default, 3 req/s when properly identified. A novel work, audiobook edition and author must retain separate IDs; do not treat the API as a bulk crawler.

## Acceptance stages

1. Acquire legal, small **actual JSON** fixture responses from approved TV-episode crew, comic-issue credit and Wikidata entity endpoints. Record source dates, schema variants, nullable fields, attribution and caching restrictions. Current public webpage evidence is not this fixture.
2. Parse and assert exact source-specific writer role, credited comic story and person ID. Differentiate script from cover/letter columns, base issue from variant/reprint, episode writer from show crew and collection name from actual member inventory.
3. Verify Q1351554 provider ID bridge and negative conflicting QID; pass typed resolved IDs to the pure [offline media relation index](../../app/src/main/java/io/github/gamesbyian/chunks/model/MediaRelationIndex.kt), which is proposed in [PR #1031](https://github.com/gamesbyian/Chunks/pull/1031), not yet a working feature.
4. Integrate only into the existing consent-aware artwork/media enrichment pipeline, with cache/TTL, source-specific quotas, negative cache, provider errors, locked/offline states and no automatic third-party lookups solely on opening transfer details.
5. Test installed Android devices/API tiers and accessible UX; report false-positive rates and costs, warm index benchmark and real performance. No name inference authorizes physical rename or torrent mutation.

**Status boundary:** All provider endpoints listed remain candidates until actual response, legal and rate/consent gates pass. No real API response JSON or licensed artwork has been integrated as a result of this research.
