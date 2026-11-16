# Transfer artwork: category-specific aspect ratios (owner-approved, 2026-11-15)

**Binding replacement for earlier square-movie/TV-row requirements.** Use the source's natural artwork format wherever available, with media-category preferences as search/ranking and layout defaults, not enforced stretching or cropping. Applies to Local and all remote transfer rows, expanded cards, Details and the **Choose artwork** picker. See [TRANSFER-ARTWORK](../TRANSFER-ARTWORK.md), [compact Transfers](TRANSFERS-COMPACT-LIST-FIRST.md), [unified Details](TRANSFER-DETAILS-UNIFIED-IDENTITY.md), and [media implementation](../MEDIA-ARTWORK-IMPLEMENTATION.md).

| Identified type | Preferred transfer-list format | Nominal aspect ratio (width:height) |
| --- | --- | --- |
| Movie, TV series, season or episode | Vertical theatrical/series poster | 2:3 portrait |
| Music album | Album cover | 1:1 square |
| Book / ebook | Book front cover | 2:3 portrait |
| Audiobook | Audiobook/retailer cover (often square) | 1:1 square |
| Comic / manga | Comic cover | 2:3 portrait |
| Magazine | Magazine front cover | 3:4 portrait |
| Software / application | App/product icon | 1:1 square |
| Unknown, archives, other types | Neutral type icon or supported user-selected artwork | Preserve actual ratio; fallback icon square |

The values above are **preferred conventions**, not proof that every provider returns them. Prefer actual provider-supplied image semantics and dimensions. A comic or book cover whose native ratio differs is shown at that ratio, within bounds. Preserve explicit user selection of any alternative format, including landscape/banner artwork. Do not coerce all movies/TV to squares, synthesize square derivatives when a portrait poster is available, stretch artwork, center-crop critical content or require a provider-specific square field. Prefer category-appropriate authentic artwork and retain existing cached/manual sources when valid.

## Compact list geometry

- Preserve existing collapsed-row information density and tap targets: use a consistent **bounded thumbnail slot** (approximately 60dp high on ordinary phone font scale) with adaptive image width, e.g. poster 40×60dp and album cover 48×48dp, both centered/contained. Exact dp may adapt to accessibility text and existing row layout. **Do not make every row taller merely because art is portrait.**
- Fit artwork **inside** its box using uniform scale and no distortion. Empty side space/neutral background is preferable to severe crop. Keep name/progress/rates legible and avoid layouts that jitter during async image loading or when metadata identification changes.
- The full Details top identity section displays substantially larger media-appropriate artwork, usually portrait poster for movies/TV and square for albums/software; Details is also natural-ratio-preserving for books, comics, magazines, audiobooks and manually chosen images.
- A media-category preference ranks image candidates in the picker, with alternates still available and the user's explicit choices retained. No automatic re-selection/re-fetch overwrites a manual override when this layout policy is deployed.
- Distinguish unknown media from misidentified media: show a neutral placeholder when no dependable identity/art is available. Do not conflate cached images with verified live remote transfer state.

## Acceptance and delivery

Validate in canonical All/Local/remote transfer lists and full Details for movie, series, album, audiobook, ebook, comic, magazine, software, unmatched release and user-custom image; verify 2:3, 1:1, 3:4 and unusual native aspect ratios, unavailable/offline art, fast list scrolling, lazy-list reuse, image cache/restart, 320/412dp, 200% font and TalkBack. Ensure square art never becomes a requirement for video lookup and fallback logic does not turn genuine available portraits into generic icons. This is documentation for a planned implementation, not a claim of source changes or physical APK acceptance.
