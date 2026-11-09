# Physical handset acceptance runbook

> **NEXT HANDSET UX/MEDIA TEST ADDITION · 2026-11-09:** When a qualified new APK exists, record the one-row four-root footer, All/device/server identical transfer cards/full Details, two/three-across action controls, 320/412dp and 200%-text/IME focus, conditional default-checked Add-only media checkbox and absence from active Details, and real authorized background poster persistence (with consent/offline/provider failure differentiated). Use [binding test matrix](NEXT-PUSH-UX-RECONCILIATION-2026-11-09.md). A pre-alpha emulator APK or documentation PR does not count as physical handset acceptance.


**Type:** reference/operations (how to produce evidence). It adds no requirements; the gates come from [WORK-QUEUE](WORK-QUEUE.md) 0B/0C/0D, [#5](https://github.com/gamesbyian/Chunks/issues/5) and [#865](https://github.com/gamesbyian/Chunks/issues/865), [#569](https://github.com/gamesbyian/Chunks/issues/569), [#8](https://github.com/gamesbyian/Chunks/issues/8) and [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md). Emulator runs never satisfy a handset gate. Record results in [QUALITY-LEDGER](QUALITY-LEDGER.md).

## Receipt (fill for every run)

| Field | Value |
|---|---|
| Device model / OEM build / Android version + security patch | |
| Chunks commit (full SHA) and APK SHA-256 (from CI artifact or `sha256sum`) | |
| Network (Wi-Fi/cellular/VPN) and battery state | |
| Check, command, start/end time (UTC) | |
| Result: PASS / FAIL / INCONCLUSIVE, with the `CHUNKS_*` log lines | |

Never include passwords, cookies, tracker passkeys or private torrent names in a receipt or screenshot.

## Setup

Enable USB debugging, connect one handset (`adb devices` lists exactly it), and use a **disposable** install: the scripts install the debug APKs and create/remove their own test transfers in app storage. From the repository root with the Android SDK and Gradle 9.3.1 (see [BUILD](BUILD.md)):

```sh
gradle :app:assembleDebug :app:assembleDebugAndroidTest
```

**Emulator-only exception:** `tools/phone_intake_journey.sh` is the installed Share/magnet → real bytes → process death → restore/seeding acceptance harness. It starts a cold process for each flow, preserves package data/MediaStore ownership, refuses a nonempty pre-consent job inventory, and refuses physical devices before stopping the app. Data-clear/reinstall ownership is a separate scenario. Its CI success is never a handset receipt.

## Automated checks that run unchanged on a handset

These are the same opt-in scripts CI runs on emulators; each fails unless its test actually ran (not skipped).

| Check (gate) | Command | Pass means |
|---|---|---|
| dataSync timeout honesty (#569) | `bash tools/fgs_timeout_repro.sh` | Shortened limit → readback-confirmed pause, notice, bytes intact. Uses Android's documented test controls; restores them on exit |
| Sudden death after download (#5/#569/#865) | `bash tools/process_death_repro.sh` (after `fgs_timeout_repro.sh` installed the APKs) | Restored stopped, no leftover notification, SHA unchanged, 100% recheck |
| Real-peer download / interrupted resume / Doze / network loss (#568/#569) | Collect the sanitized Debian torrent as in `android-public-swarm.yml`, export `CHUNKS_PUBLIC_SWARM_TORRENT`, `CHUNKS_PUBLIC_SWARM_BUDGET_BYTES`, `CHUNKS_PUBLIC_SWARM_SECONDS`, then run `tools/android_public_swarm.sh`, `…_interrupt.sh`, `…_doze.sh` or `…_netflap.sh` | Verified bytes from ordinary peers; restore + continuation; behaviour under forced Doze; recovery after radios return. INCONCLUSIVE = peer availability, not engine acceptance |
| Real 200% font Compose (#8) | `bash tools/run_compose_system_font_200pct.sh` (needs `RUNNER_TEMP` set to a writable folder) | Seven real-pixel captures including All environments and Open in its list |

**Network-flap safety:** `netflap` requires USB adb (no wireless `IP:port`), one uniquely selected device or explicit `ADB_SERIAL`, and a readable `0`/`1` baseline for both Android's `wifi_on` and `mobile_data` global settings. It refuses to toggle radios if either value is unknown, as can occur on multi-SIM/OEM builds. It disables both radios during the configured outage, then requests the **original individual states** and checks their readback; a failed restoration fails the test. This is a device-settings check, not proof of every carrier/OEM connectivity state. After any interruption, manually check Wi-Fi/mobile-data toggles, especially if the test was forcibly killed before its EXIT trap ran.

**Restore the handset afterwards.** These scripts change device settings:
- **`run_compose_system_font_200pct.sh`** sets the display size, density and font scale and does **not** reset them. Run `adb shell wm size reset; adb shell wm density reset; adb shell settings put system font_scale 1.0`.
- **`fgs_timeout_repro.sh`** tries to restore the `dataSync` limit on exit. On an emulator the `device_config delete` step reported failure, so confirm with `adb shell device_config get activity_manager data_sync_fgs_timeout_duration` that it prints `null`. If it doesn't, delete it again or reboot.
- **`doze.sh`** unforces idle and resets the battery state on exit. If it was interrupted, run `adb shell dumpsys deviceidle unforce; adb shell dumpsys battery reset`.

## Remote rTorrent actual-transfer acceptance on the physical handset (#4)

This is a **human-operated positive test**, not a mock, a synthetic-hash RPC fixture or the emulator's local engine. Apply [real-transfer-first acceptance](REAL-TRANSFER-ACCEPTANCE.md). Use the tested APK with its actual SHA, the **selected named development rTorrent environment**, and licensed/owner-provided **real** .torrent and magnet sources with available conventional peers. Confirm known-good reference-client behaviour with ruTorrent or Transdroid on the same instance. Avoid adding an already-present infohash and keep the resulting session/contents identifiable for cleanup. Do not paste credentials or tracker-passkey URLs into issues.

- **File Add:** From the phone's installed Chunks UI, select the exact server and real `.torrent` plus intended download parent. Record source infohash and source/client entry path. Observe the exact hash and chosen parent on rTorrent, **started and active** state, per-hash received-byte and hash-verified piece growth, and actual file progress. Then Stop, Start and prove continued functioning, change a selected file priority and confirm it takes effect. Session-only Remove must leave payload files intact.
- **Magnet Add, independently:** Use a fresh magnet with live metadata-serving peers. Observe *metadata pending → actual filename/files/size/pieces → exact-job received and verified-byte growth*. A fake hash or a magnet that never resolves metadata is **not** a pass. Inspect the same transfer through ruTorrent/Transdroid before and after Chunks Stop/Start to catch a Chunks-created paused state which other clients cannot recover.
- **Reference differential:** On that exact server, perform equivalent fresh Adds through ruTorrent or Transdroid (use the same fixture only after exact session retirement, or a comparable separate known-good fixture). Compare native Add start-on-load semantics, initial state, destination, metadata resolution and peer transfers. A reference success alongside a Chunks failure is a **Chunks-specific regression** until independently disproven.
- **Honest evidence:** Record the tested source SHA/APK, server profile alias, `.torrent` or magnet and nonsecret infohash, actual visible result, per-job received/verified bytes before/after, metadata/name/files result, Stop/Start and Remove observations, reference control and any exact network/permission error. Use **PASS / FAIL / INCONCLUSIVE / NOT RUN** per action. If the public swarm lacks reachable peers, report **INCONCLUSIVE peer availability** and retry with an actual controlled seeder or a healthy public swarm; a started flag alone never earns PASS.
- **Scope and cleanup:** Only mutate the explicitly selected test server/profile and exact testing infohashes. Protected host tests remain authorized as documented in [SEEDHOST-REMOTE-TESTING](SEEDHOST-REMOTE-TESTING.md); do not blindly repeat a write after timeout or delete unrelated data. Owner-designated test torrents are suitable for destructive tests; default Remove retains payload.

An Android emulator can establish a separate installed-app gate with an actual server, but it cannot substitute for this physical-phone outcome. This runbook asks for evidence; it **does not claim** the current APK, magnet or host have passed it.

## Manual checks (no script yet)

1. **Natural background and Doze:** start a real public download, lock the screen, leave unplugged ≥ 2 h; record whether it is still running, the notification state and verified bytes. Repeat with the OEM battery optimizer on and off.
2. **Real handover:** during a download, walk out of Wi-Fi range onto cellular and back; record pause/continue and whether any error is shown.
3. **Reboot:** with transfers present, reboot; confirm they return **stopped** and nothing auto-starts (Android forbids starting `dataSync` from boot).
4. **Reinstall/ownership and access loss (#568):** with files in `Downloads/Chunks Downloads`, uninstall and reinstall; then revoke file access in Settings; record what Chunks can still read, what it reports and that no file is deleted.
5. **TalkBack and keyboard (#8):** with TalkBack on, operate phone list, server list, All environments (scope, sort direction, Select…, Start/Stop review, Open in its list, Environment colours) and confirm every control is announced with its environment and state; repeat with a hardware keyboard.

Each manual check records the receipt above plus the exact observed outcome; a step that could not be performed is recorded as such, never as a pass.
