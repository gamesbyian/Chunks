# Chunks palette contrast audit

Reference colours remain separate and editable. This is automated WCAG 2.x pairwise contrast evidence, not optical approval or a chosen app palette. Dark text means Brand Essence near-black and light text means Brand Essence off-white.

AA normal text: 4.5:1. Large text and relevant non-text icon boundaries: 3:1. A higher-contrast text suggestion is evidence only; no reference colour is overwritten.

## brand-essence

Colour and illustrations sheet, values explicitly printed (printed-reference)

| Token | Value | Dark text | Light text | Suggested text | Normal AA | Icon on dark | Icon on light |
| --- | --- | ---: | ---: | --- | --- | --- | --- |
| pink | #FF4EC4 | 6.52:1 | 2.81:1 | near-black (6.52:1) | Pass | Pass | Fail |
| purple | #A56BFF | 5.61:1 | 3.27:1 | near-black (5.61:1) | Pass | Pass | Pass |
| blue | #3B82FF | 5.32:1 | 3.45:1 | near-black (5.32:1) | Pass | Pass | Pass |
| yellow | #FFD93D | 13.92:1 | 1.32:1 | near-black (13.92:1) | Pass | Pass | Fail |
| green | #22D97A | 10.30:1 | 1.78:1 | near-black (10.30:1) | Pass | Pass | Fail |
| cyan | #00D4FF | 10.83:1 | 1.69:1 | near-black (10.83:1) | Pass | Pass | Fail |
| near_black | #0B0F17 | 1.00:1 | 18.33:1 | off-white (18.33:1) | Pass | Fail | Pass |
| surface | #1A1F2B | 1.16:1 | 15.74:1 | off-white (15.74:1) | Pass | Fail | Pass |
| muted | #2E3647 | 1.58:1 | 11.57:1 | off-white (11.57:1) | Pass | Fail | Pass |
| secondary | #6B7280 | 3.97:1 | 4.62:1 | off-white (4.62:1) | Pass | Pass | Pass |
| divider | #E5E7EB | 15.49:1 | 1.18:1 | near-black (15.49:1) | Pass | Pass | Fail |
| light | #F8FAFC | 18.33:1 | 1.00:1 | near-black (18.33:1) | Pass | Pass | Fail |

## type-hierarchy

Typography and colour hierarchy sheet, values interpreted from small raster labels; candidate values pending manual verification (visually-interpreted-needs-verification)

| Token | Value | Dark text | Light text | Suggested text | Normal AA | Icon on dark | Icon on light |
| --- | --- | ---: | ---: | --- | --- | --- | --- |
| brand | #A855F7 | 4.85:1 | 3.78:1 | near-black (4.85:1) | Pass | Pass | Pass |
| pink | #FF4EB8 | 6.42:1 | 2.85:1 | near-black (6.42:1) | Pass | Pass | Fail |
| blue | #22C1FF | 9.26:1 | 1.98:1 | near-black (9.26:1) | Pass | Pass | Fail |
| yellow | #FFD400 | 13.40:1 | 1.37:1 | near-black (13.40:1) | Pass | Pass | Fail |
| success | #33D17A | 9.63:1 | 1.90:1 | near-black (9.63:1) | Pass | Pass | Fail |
| warning | #FF8A3D | 8.18:1 | 2.24:1 | near-black (8.18:1) | Pass | Pass | Fail |
| error | #FF4E5C | 5.93:1 | 3.09:1 | near-black (5.93:1) | Pass | Pass | Pass |
| neutral | #E6EAF0 | 15.88:1 | 1.15:1 | near-black (15.88:1) | Pass | Pass | Fail |

## dimensional-materials

Artwork colours used by the independent draft 3D object library (not a replacement for the approved SVG gradients) (design-exploration)

| Token | Value | Dark text | Light text | Suggested text | Normal AA | Icon on dark | Icon on light |
| --- | --- | ---: | ---: | --- | --- | --- | --- |
| folder_front | #05BCFF | 8.81:1 | 2.08:1 | near-black (8.81:1) | Pass | Pass | Fail |
| folder_back | #0A75E8 | 4.31:1 | 4.25:1 | near-black (4.31:1) | Fail | Pass | Pass |
| folder_side | #004EC8 | 2.67:1 | 6.86:1 | off-white (6.86:1) | Pass | Fail | Pass |
| storage_front | #A46AFF | 5.56:1 | 3.30:1 | near-black (5.56:1) | Pass | Pass | Pass |
| storage_side | #6632D7 | 2.74:1 | 6.68:1 | off-white (6.68:1) | Pass | Fail | Pass |
| download_front | #30E655 | 11.49:1 | 1.60:1 | near-black (11.49:1) | Pass | Pass | Fail |
| download_side | #00B83D | 7.24:1 | 2.53:1 | near-black (7.24:1) | Pass | Pass | Fail |
| media_front | #FF5869 | 6.26:1 | 2.93:1 | near-black (6.26:1) | Pass | Pass | Fail |
| media_side | #E83C68 | 4.82:1 | 3.80:1 | near-black (4.82:1) | Pass | Pass | Pass |

## Interpretation

Contrast applies to a particular foreground/background pair. A bright icon may pass on near-black but fail on off-white. 3:1 is not sufficient for ordinary text, and colour alone cannot communicate transfer state. Preserve descriptive icons, status text and TalkBack semantics. Both supplied palette systems and dimensional material shades remain available for mixing and matching.

This report does not certify gradients, focus rings, disabled states, 200 percent font scaling, final Android Compose colours, or any physical device. Source colour labels from the Typography sheet remain explicitly provisional.
