# Chunks Reference Panel: accented character spacing diagnostics

The 15-face visual atlas uncovered large width differences in accented samples. This diagnostic measures whether those differences originate in **individual glyph advances** or in **contextual kerning**. It does not alter or ship a font.

For each of five separately compiled experimental accent-fusion OTFs, `tools/audit_accent_context.py` reads the exact generated accent manifest and uses Pillow's libraqm/HarfBuzz shaping to compare each composed character with its Latin base at **48px**. Eight contexts include isolated glyphs, left and right neighbors, and short interior words. A per-character delta above 1px in any context is reported for review, with source OTF SHA-256.

Isolated advance should remain essentially unchanged, since the accent constructor assigns the composed glyph the same horizontal advance as its original reference base. Differences in surrounding text indicate a distinct GPOS context or placement issue; they should not be eliminated by compressing outlines without investigation.

This is a **diagnostic**, not a definitive optical quality score. Contextual pairs can legitimately differ because language-specific kerning may be intentional. The test covers 243–244 composed glyphs per weight rather than arbitrary Unicode grapheme clusters. Rendered Android/Compose typography with real layout bounds and 200% accessibility text remains an acceptance gate in [issue #347](https://github.com/gamesbyian/Chunks/issues/347).

Reproduce:

```bash
python -m pip install -r tools/requirements-chunks-font.txt
python tools/audit_accent_context.py --out accent-context-audit.json
python -m unittest discover -s tests -p test_accent_context_audit.py -v
```

The workflow `.github/workflows/chunks-accent-context-audit.yml` uploads the JSON; all original Chunks OTFs, SVGs and Android assets remain byte-for-byte unchanged.
