# Chunks SVG optical and Android acceptance gates

**First-pass coverage is complete:** 815 individual SVG drafts, 20 names reusing exact existing SVG masters, one approved launcher and no undrawn entries in the original named asset inventory. These are **coverage counts, not visual approvals**.

The highest-value next work is optical cleanup of the low-resolution source-traced sticker lettering and spacing. PNG-to-vector reference diagnostics currently report purple Download/Organise ink IoU **0.6923**, green Fast/Private/Flexible **0.6341**, and pink Movies/Shows/Music **0.7089**. Scores are at original-image pixel scale and are only repeatable contour diagnostics. The six font-outline sticker variants preserve their text but remain interpretations rather than source-lettering reproductions. New versions should have separate IDs rather than silently replacing previous alternative treatments.

The source-traced Chunks wordmark, primary tagline and Big Files lettering still require spacing, shadow/underline and colour optical approval. Both reference colour systems remain separately selectable. A final chosen treatment needs a Pixel 6 Pro dark/light screenshot atlas, accessibility validation (TalkBack, focus, 200% text and 48dp touch sizes), and actual native Compose integration. The approved Android launcher must not change without explicit approval.

**Automated evidence:** `tools/validate_svg_library.py` checks manifests, XML and safe self-contained geometry; `tools/complete_svg_reference_assets.py` checks the 12 source-derived generator outputs; `tools/render_svg_atlas.py` renders all 815 at 32/160 pixels; `tools/svg_sticker_comparison.py` emits reference/source/overlay comparisons; `tools/svg_small_size_atlas.py` renders selected navigation, notification and launcher concept families at 16/24/32/48/64 pixels, with warnings for invisible and full-bleed candidates. A warning is a review lead, not a reason to delete an alternative.

No SVG wraps a reference bitmap. No static SVG specimen should replace live status text, progress controls or other functional native Android UI.
