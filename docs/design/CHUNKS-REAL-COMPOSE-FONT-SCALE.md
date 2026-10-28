# Chunks installed Compose acceptance at actual Android system font scale 200%

**Status:** native API35 test-only, gated by exact-head CI; not font-art or TalkBack approval.

The earlier five-weight CFF proof confirms 30 real TextView/Canvas pixels at **simulated** 100% and 200% text sizes. That alone cannot establish how Android's font preference affects the **actual Compose screens** in Chunks.

This companion gate launches the genuine MainActivity in a fresh API 35 x86_64 Pixel 6 emulator after setting the Android system setting font_scale=2.0 before Activity startup. The display is 1080×2400 at 420 dpi. The selected instrumentation test fails if MainActivity.resources.configuration.fontScale is below 1.9. It checks the real Chunks brand and captures the unauthenticated Connection screen, then connects to a disposable **read-only localhost XML-RPC fixture** (the same deterministic fixture as the existing installed MainActivity test), navigating Transfers → Places → Organize → Server → Transfers with real Compose semantics. The fixture returns synthetic public-demo transfer records and rejects all mutation methods; it never uses a seedbox or external network.

The initial CI screenshot audit identified a transient first-Transfers frame with clipped controls while the connection IME was still shrinking the viewport, although the later return-to-Transfers frame was complete. The test now treats this as a **hard synchronization gate**: after connection, it waits until the lower `Save inventory-only snapshot` Compose semantics node exists and is actually displayed before saving the first Transfers screenshot. If the viewport never settles, the test fails rather than publishing that incomplete screenshot as accepted visual evidence.

The test saves six actual captureToImage() screenshots in Pictures/ChunksCompose200Pct through Android MediaStore, so they persist after the instrumentation runner uninstalls the app. The workflow extracts real images after testing, rejects missing names or bad PNG magic/IHDR, verifies exactly six images, and retains a SHA-256 manifest. Other workflows can run at default font size without claiming this system 200% acceptance; this workflow explicitly selects the new test.

Command: bash tools/run_compose_system_font_200pct.sh

This test requires **no Seedhost credentials, no external network or remote mutations, no mock Compose UI, and no experimental font substitutions**. It uses a local-only synthetic XML-RPC fixture solely to unlock the app's real authenticated main navigation, plus a real unauthenticated connection-screen capture. It measures current app typography and layout under the genuine Android setting, complementing the separately gated experimental CFF sample renderer.

**Not claimed:** TalkBack speech/focus traversal, absence of visible clipping without human screenshot review, all dialogs or screen sizes (including 320px), nonlinear scaling across every typography slot, keyboard input at large text, or approval of candidate source outlines. Issue #347 remains open. A failing CI test must be treated as diagnostic evidence of an actual large-type defect, not acceptance.
