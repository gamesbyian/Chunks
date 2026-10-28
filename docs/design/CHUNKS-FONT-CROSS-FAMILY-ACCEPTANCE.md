# Chunks: independent OpenType family regression gate

**Purpose:** preserve the accepted Android display face and its license while font studies evolve independently. The cross-family audit does not promote study typography into the shipped application.

The audit reads actual CFF/OTF binaries after they are checked in, rather than trusting source-script success. It covers **15 binaries**: 5 approved-base weight candidates (400–800), 4 lighter Reference Panel contour studies (400–700), the Reference Panel ExtraBold optical comparator (800), and 5 accent-fusion studies (400–800). The experimental 800 unoptimized comparator is intentionally excluded from the primary set because the optically adjusted 800 is the reference for design studies.

For every font it verifies real OpenType CFF outlines, GPOS/GSUB/GDEF features, correct weight metadata, identical 856-codepoint cmap mapping, stable ascender/descender/line-gap metrics relative to its native-weight base, and retained non-Latin shaping through measured Hindi samples. The app's installed `chunks_extrabold.otf` must be **byte-for-byte identical** to the base master, and the bundled SIL OFL license to its source. Experimental font hashes must be different from the base. Weight-study and accent-study manifests must reference the actual binary SHA-256 and expected source-count invariants.

The resulting JSON evidence is uploaded in CI and is safe to compare across PRs. To reproduce:

```sh
python -m pip install -r tools/requirements-chunks-font.txt
python tools/audit_font_families.py --output chunks-font-cross-family-audit.json
```

**Limits:** equal shaping advance widths for the tested Hindi samples is a useful regression check, not a proof that the full script shaping is identical. It does not certify all possible Unicode combinations, drawn outlines, aesthetics, 200%-text Android accessibility, or exhaustive optical collisions. The approved wordmark SVG is separate. The 4 lighter Reference Panel contours and 243–244 precomposed accent fusions are experimental; human contour/design acceptance under [#347](https://github.com/gamesbyian/Chunks/issues/347) is still required.

The audit is read-only. It cannot write, promote, delete or replace OTFs and is run on changed source font assets, experimental variants, Android font/license changes, and its own test/workflow changes.
## Twenty-face coverage after #384

The acceptance catalog now verifies **20 compiled CFF binaries**: the five original Chunks weights, five reference-panel weight variants (four lighter weights plus the reference-optical ExtraBold), five original accent-fusion studies, and the new **five reference-panel accent-context kerned variants**. The last five each have a separate installable PostScript name, immutable source accent-study checksums, 243–244 reconstructed glyphs, and over 75,000 bounded pair adjustments. The cross-family audit checks each source/output checksum, `hmtx` metrics, bold/regular style flags and non-Latin shaping, while preserving the existing Android-source byte identity check.

Note that 20 binary font candidates are *not* 20 independently designed typefaces, nor a declaration of visual approval. Production still uses only the original installed ExtraBold header font. The kerning family is a complete **engineering prototype**, with optical contour refinement and extended typography acceptance tracked in [#347](https://github.com/gamesbyian/Chunks/issues/347).
