# Chunks Preview handset acceptance (2026-11-01)

Build workflow: [Phone preview APK](../../.github/workflows/phone-preview-apk.yml).
Source baseline: `ecfeceba86868932c35f5be7ae5db2debfd0d38c` plus isolated build workflow.
This is **pre-alpha**. A successful APK build does not certify working local transfers, remote server mutations, Android background seeding, SAF overwrite, or physical accessibility.

## Install
- Download the ZIP artifact from Actions, extract `Chunks-Preview-2026-11-01.apk`, and install the APK using Android's package installer.
- The preview package ID is `io.github.gamesbyian.chunks.preview`, so it installs independently of the ordinary Chunks app.
- Preserve any existing app and downloaded torrents. Do not uninstall/reset the existing app.
- Use disposable/public torrents, non-sensitive destinations, and test-only remote jobs. Do not grant a production remote profile for destructive operations.

## Report checklist
1. Android version/build and device model; was this a fresh Preview install?
2. First-launch setup: local-only and remote-only choices, navigation, permission prompts; screenshots of confusing state.
3. Local Add: share a public .torrent and magnet separately; record expected/actual download folder, visible state/progress, and whether bytes arrive.
4. Local Stop → force-stop process → reopen → Start: whether the same transfer appears, continues, and seeds once completed. Treat background/Doze and reboot as separate experiments.
5. Remote: use a disposable, authorized rTorrent test job; verify profile identity, list, Start/Stop, file priority, and session-only Remove; never remove test data by assumption. Report any unexpected mutation immediately.
6. Combined: one phone and two server profiles if available; verify environment colors, scope indicators, grouping/sorting after restart, server switching with same-name or same-hash entries, and per-profile operations.
7. Storage: Downloads/Chunks Downloads default, Change destination, permission revocation and regrant; **avoid overwrite/Replace tests involving valuable files**. Create-new and Keep-both should be tested on disposable targets only.
8. Accessibility: portrait 412×892 and if possible smaller 320×700 equivalent; Android text/display scale up to 200%, TalkBack, keyboard, reduced motion, clipped/overlapping controls.
9. Upload screenshots with credentials, server URLs, passkeys and torrent-specific private information removed. Send reproduction steps, expected vs actual, operation time, and whether retry had any unexpected effect.

**Stop testing and report immediately** on unintended file modification/deletion, mutation of the wrong remote server/torrent, duplicate remote actions, credential leaks or apparently successful actions that never happened.
