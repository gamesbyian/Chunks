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

**Owner refinement (2026-11-16; supersedes the earlier bounded ~60dp thumbnail-slot geometry):** Each canonical transfer card has a two-column arrangement. Artwork is anchored at the left and renders at **almost the full inner card height**, with a small consistent inset. All other information, including the client short name/colour, release name, Details action, transfer status, download/upload rates, ratio and **the entire progress bar**, occupies the right column. Neither progress text nor progress bar may wrap or extend beneath the artwork.

- **Fix the artwork-to-information gap, not the information column's absolute x-position.** Artwork retains its actual/native aspect ratio, so a narrow 2:3 poster, wider 1:1 cover and landscape/manual image produce different text-column starting x-coordinates in different cards. Never impose a single fixed artwork width or align text starts across cards by inserting artificial empty space.
- Derive artwork width from rendered height × aspect ratio, bounded by a responsive maximum width / minimum remaining text width. Preserve intrinsic ratio (no stretch or compulsory cropping). If extreme landscapes or very narrow viewports would starve the text column, reduce artwork **proportionally** within the card rather than clipping content, placing text below the artwork, or horizontally scrolling the card. Use a stable placeholder with compatible measured dimensions while loading to prevent row jitter.
- Within the right column, place source/client label, title, Details, status/progress text, per-job rates, ratio and progress bar in a legible vertical hierarchy. The bar spans **only the right column**. Permit the card to grow to accommodate long release names, 200% font size and accessibility, and recalculate artwork size/placement accordingly; never overlap the fixed footer or hide the card's controls.
- Keep consecutive cards reasonably compact for a list-first screen, but **do not reintroduce the old tiny-thumbnail treatment** simply to force every card to a uniform height. No rule requires identical card heights or text-column left edges. Keep Details and supported expansion/touch/semantics behavior intact.
- Apply consistently to All, Local and named remote scopes, with image categories (movie/TV posters, albums, books, magazines, software and manually selected landscape art) sharing the same height-led geometry rule.
- The full Details top identity section displays substantially larger media-appropriate artwork, usually portrait poster for movies/TV and square for albums/software; Details is also natural-ratio-preserving for books, comics, magazines, audiobooks and manually chosen images.
- A media-category preference ranks image candidates in the picker, with alternates still available and the user's explicit choices retained. No automatic re-selection/re-fetch overwrites a manual override when this layout policy is deployed.
- Distinguish unknown media from misidentified media: show a neutral placeholder when no dependable identity/art is available. Do not conflate cached images with verified live remote transfer state.

## Acceptance and delivery

Validate the left-artwork/right-information geometry in collapsed and expanded canonical All/Local/remote transfer cards: all status and progress content, including the complete progress bar, must remain right of artwork, with a consistent artwork-to-text gap but variable text starting x-position for 2:3, 1:1, 3:4 and landscape sources. Check text wrapping, extreme panoramas, async loading, 320/412dp and 200% font. Also validate in full Details for movie, series, album, audiobook, ebook, comic, magazine, software, unmatched release and user-custom image; verify 2:3, 1:1, 3:4 and unusual native aspect ratios, unavailable/offline art, fast list scrolling, lazy-list reuse, image cache/restart, 320/412dp, 200% font and TalkBack. Ensure square art never becomes a requirement for video lookup and fallback logic does not turn genuine available portraits into generic icons. This is documentation for a planned implementation, not a claim of source changes or physical APK acceptance.
