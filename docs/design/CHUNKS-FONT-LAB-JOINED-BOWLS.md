# Nonshipping native joined-stem and counter masters: D/P/b/d/p/q

**Design status: SOURCE CANDIDATES ONLY.** Six individually authored, connected outer silhouettes at four actual weights (24 standalone native quadratic/cubic SVG masters); each encloses one optically specified counter. Unlike polygon-thinning derived weight studies, this introduces true vertical stem, bowl join, ascender or descender geometry with independent counter growth and visibly different curved terminals across categories.

- Uppercase D is a one-sided vertical stem and tall bowl, P is a tall stem with separately curved upper bowl; neither may be interpreted as an ellipse alone.
- Lowercase b/d keep an ascender and an asymmetric lower bowl, using mirrored left/right stems. Lowercase p/q use the same two-sided grammar with a descending stem and **upper** bowl, not an upside-down copy of b/d.
- Four weights each have separate counter dimensions; geometry/source bounds were preflighted against the original immutable reference. All source areas grow from Regular through Bold, and remain within 0.30–1.20× the original reference ink envelope.
- For D/P/b/p/q, original-ink area ratios by weight are approximately D 0.82/0.88/0.93/0.97; P 0.87/0.91/0.95/0.99; b 0.85/0.90/0.94/0.98; p 0.84/0.89/0.93/0.96; q 0.84/0.89/0.93/0.96. d at Bold is just under its original after the counter adjustment.
- **Do not claim approved art.** Regular P/d may be too heavy against native O/C at small sizes. This needs actual 14/24/48/82px screen proofs, source counters at 200% app scale, optical spacing and owner editorial assessment.

This advances the *editable source* basic-glyph coverage from 13 to **19/62 per lighter weight** (76 masters across 400/500/600/700). OTF binaries/metrics on the branch refer to the previous 52-master checkpoint until the next explicit, isolated local full-family rebuild. No PR or automatic CI required by this checkpoint.

Run the source-only acceptance with `python -m unittest discover -s tests -p test_font_lab_joined_bowls.py -v`, then when the lab batch is frozen run `bash tools/font_lab_gate.sh full`.

See [font lab contract](CHUNKS-FONT-LAB-OPERATIONS.md) and [#347](https://github.com/gamesbyian/Chunks/issues/347).
