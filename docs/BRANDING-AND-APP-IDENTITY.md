# Chunks branding and app identity contract

**Implement and preserve these three distinct naming surfaces:**

| Surface | Required name | Authority |
| --- | --- | --- |
| Public/full product title and planned Google Play listing | **Chunks Torrent Client & Remote** | Play Console store metadata, plus the source-controlled `chunks_product_name` resource |
| Installed ordinary Android app identity: launcher, app drawer, Settings > Apps and Android-generated notification attribution | **Chunks** | `app/build.gradle.kts` `chunks_app_name` resource and `AndroidManifest.xml` application `android:label` |
| In-app visual/README wordmark | **chunks.** | Existing branded typography and approved artwork; do not replace with the long descriptor |

**Actions for any future release or branded UI:**
1. Keep Android `<application android:label="@string/chunks_app_name">` on the short `Chunks` resource. Do not put the full product/store title in the manifest application label. Do not change package `io.github.gamesbyian.chunks` or existing launch icon/assets as a side effect of display-name changes.
2. Keep existing **Chunks Preview** and **Chunks Magnet Preview** labels for side-by-side `CHUNKS_SIDE_BY_SIDE=1` debug/QA builds. These are important for provenance and avoiding accidental test on the production application; never disguise a preview as the normal app.
3. Use **Chunks Torrent Client & Remote** in the official Play Console app title when a Play listing is eventually created. It is exactly **30 characters** (current maximum at publication planning). Source control cannot publish or modify Play Console listings; the store title is a release step, not proof that a listing exists.
4. Keep the lower-case `chunks.` wordmark in the app where branded presentation is appropriate. Accessible headings, TalkBack labels and Android-owned notifications continue to say **Chunks**, not an awkward repeated full title.
5. For notifications, do not attempt to set a misleading Android application identity via notification text or channels. Notifications can have descriptive **event-specific content titles** such as transfer names or meaningful progress, but app attribution remains the short system app label.
6. Do not give individual launcher activities longer labels merely to squeeze the product descriptor into system surfaces. If alternate launcher aliases ever become necessary, test app drawer, sharesheet, notification attribution and installed-app Settings on actual Android versions.
7. Confirm the short ordinary label and distinct preview labels by inspecting **merged manifest and build resources** (not only the source XML), then test a normal APK and one side-by-side debug preview. Update this contract if any naming surface is intentionally changed.

The feature description in README and store copy can explain the local BitTorrent client and the rTorrent remote; the long descriptive name should never monopolize limited launcher or notification space. Update [PRODUCT](PRODUCT.md) and [DECISIONS](DECISIONS.md) only for owner-approved changes. This is a binding naming specification, not a release-signing or Google Play publication claim.
