# Chunks last-mile SVG reference candidates

This directory contains the **12 newly generated, individually addressable SVG drafts** that complete the named *first-pass* inventory. It does **not** mean every asset has final visual/optical approval.

The files are deterministic outputs from `tools/complete_svg_reference_assets.py`, which reads reference sheet 06, the checked-in Chunks ExtraBold OTF and approved vector artwork without changing any of those inputs. Every output uses actual self-contained SVG paths; there are no raster inclusions or live `<text>` elements. `provenance.json` records every candidate and its construction method.

**Three PNG-contour drafts:** purple Download/Organise, green Fast/Private/Flexible, and pink Movies/Shows/Music. These preserve the approximate source lettering shapes, but small pixels, isolated dots, text-holes and edge extraction need hand review. Do not label them pixel-perfect.

**Six font-outline concepts:** Same Torrents (white and black-outline concepts), Same Files, Simple Icons, Same Vibes, and Your Torrents. These carry fixed editable vector outlines based on the checked-in Chunks OTF. They preserve the *phrases and intended shapes* of the references, but **do not claim exact original hand lettering**. The two Same Torrents concepts are retained separately on purpose.

**Three splash/brand composites:** cream launch frame, splash decorative treatment and onboarding hero. They inline the canonical approved icon and reference-traced logo in self-contained SVG documents. Their layout is an interpretation that still requires spacing and Android crop/mask QA.

Rebuild/check on a suitable Python environment:

```bash
python3 -m pip install 'numpy>=2,<3' 'opencv-python-headless>=4.11,<5' 'Pillow>=11,<13' 'fonttools>=4.55,<5'
python3 tools/complete_svg_reference_assets.py
python3 tools/validate_svg_library.py
```

Generation can be reproduced with `python3 tools/complete_svg_reference_assets.py --write`, but **do not** use that to overwrite visually approved future refinements. If refining any draft by hand, preserve the source-derived candidate under its original stable ID and add a new non-destructive variant with independent provenance. Update the generator/test to understand any deliberately frozen manual result.

**Shipping gate:** No replacement of `docs/design/approved/chunks-app-icon-master.svg`; no integration into native Compose screens, no claim of physical handset approval, font licensing sign-off or complete colour-contrast audit.
