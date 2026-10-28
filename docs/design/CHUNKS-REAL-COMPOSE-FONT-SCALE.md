# Chunks installed Compose acceptance at actual Android system font scale 200%

**Status:** native API35 test-only, gated by exact-head CI; not font-art or TalkBack approval.

The earlier five-weight CFF proof confirms 30 real TextView/Canvas pixels at **simulated** 100% and 200% text sizes. That alone cannot establish how Android's font preference affects the **actual Compose screens** in Chunks.

This companion gate launches the genuine MainActivity in a fresh API 35 x86_64 Pixel 6 emulator after setting the Android system setting font_scale=2.0 before Activity startup. The display is 1080×2400 at 420 dpi. The selected instrumentation test fails if MainActivity.resources.configuration.fontScale is below 1.9. It checks that the real Chunks brand is in the accessibility tree, then navigates Transfers → Places → Organize → Server → Transfers with real Compose semantics.

The test saves five actual captureToImage() screenshots in Pictures/ChunksCompose200Pct through Android MediaStore, so they persist after the instrumentation runner uninstalls the app. The workflow extracts real images after testing, rejects missing names or bad PNG magic/IHDR, and retains a SHA-256 manifest. Other workflows can run at default font size without claiming this system 200% acceptance; this workflow explicitly selects the new test.

Command: bash tools/run_compose_system_font_200pct.sh

This test requires **no Seedhost credentials, no network fixtures, no mock UI, no torrent mutations, and no experimental font substitutions**. It measures current app typography and layout under the genuine Android setting, complementing the separately gated experimental CFF sample renderer.

**Not claimed:** TalkBack speech/focus traversal, absence of visible clipping without human screenshot review, all dialogs or screen sizes (including 320px), nonlinear scaling across every typography slot, keyboard input at large text, or approval of candidate source outlines. Issue #347 remains open. A failing CI test must be treated as diagnostic evidence of an actual large-type defect, not acceptance.
