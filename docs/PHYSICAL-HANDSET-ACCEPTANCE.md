# Physical handset acceptance runbook

**Type:** reference/operations (how to produce evidence). It adds no requirements; the gates come from [WORK-QUEUE](WORK-QUEUE.md) 0B/0C/1B, [#568](https://github.com/gamesbyian/Chunks/issues/568), [#569](https://github.com/gamesbyian/Chunks/issues/569), [#8](https://github.com/gamesbyian/Chunks/issues/8) and [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md). Emulator runs never satisfy a handset gate. Record results in [QUALITY-LEDGER](QUALITY-LEDGER.md).

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

## Automated checks that run unchanged on a handset

These are the same opt-in scripts CI runs on emulators; each fails unless its test actually ran (not skipped).

| Check (gate) | Command | Pass means |
|---|---|---|
| dataSync timeout honesty (#569) | `bash tools/fgs_timeout_repro.sh` | Shortened limit → readback-confirmed pause, notice, bytes intact. Uses Android's documented test controls; restores them on exit |
| Sudden death after download (#568/#569) | `bash tools/process_death_repro.sh` (after `fgs_timeout_repro.sh` installed the APKs) | Restored stopped, no leftover notification, SHA unchanged, 100% recheck |
| Real-peer download / interrupted resume / Doze / network loss (#568/#569) | Collect the sanitized Debian torrent as in `android-public-swarm.yml`, export `CHUNKS_PUBLIC_SWARM_TORRENT`, `CHUNKS_PUBLIC_SWARM_BUDGET_BYTES`, `CHUNKS_PUBLIC_SWARM_SECONDS`, then run `tools/android_public_swarm.sh`, `…_interrupt.sh`, `…_doze.sh` or `…_netflap.sh` | Verified bytes from ordinary peers; restore + continuation; behaviour under forced Doze; recovery after radios return. INCONCLUSIVE = peer availability, not engine acceptance |
| Real 200% font Compose (#8) | `bash tools/run_compose_system_font_200pct.sh` (needs `RUNNER_TEMP` set to a writable folder) | Seven real-pixel captures including All environments and Open in its list |

Note `netflap` turns the handset's Wi-Fi and mobile data off for 30 s (restored on exit); do not run it over a network you depend on for adb.

**Restore the handset afterwards.** These scripts change device settings:
- **`run_compose_system_font_200pct.sh`** sets the display size, density and font scale and does **not** reset them. Run `adb shell wm size reset; adb shell wm density reset; adb shell settings put system font_scale 1.0`.
- **`fgs_timeout_repro.sh`** tries to restore the `dataSync` limit on exit. On an emulator the `device_config delete` step reported failure, so confirm with `adb shell device_config get activity_manager data_sync_fgs_timeout_duration` that it prints `null`. If it doesn't, delete it again or reboot.
- **`doze.sh`** unforces idle and resets the battery state on exit. If it was interrupted, run `adb shell dumpsys deviceidle unforce; adb shell dumpsys battery reset`.

## Manual checks (no script yet)

1. **Natural background and Doze:** start a real public download, lock the screen, leave unplugged ≥ 2 h; record whether it is still running, the notification state and verified bytes. Repeat with the OEM battery optimizer on and off.
2. **Real handover:** during a download, walk out of Wi-Fi range onto cellular and back; record pause/continue and whether any error is shown.
3. **Reboot:** with transfers present, reboot; confirm they return **stopped** and nothing auto-starts (Android forbids starting `dataSync` from boot).
4. **Reinstall/ownership and access loss (#568):** with files in `Downloads/Chunks Downloads`, uninstall and reinstall; then revoke file access in Settings; record what Chunks can still read, what it reports and that no file is deleted.
5. **TalkBack and keyboard (#8):** with TalkBack on, operate phone list, server list, All environments (scope, sort direction, Select…, Start/Stop review, Open in its list, Environment colours) and confirm every control is announced with its environment and state; repeat with a hardware keyboard.

Each manual check records the receipt above plus the exact observed outcome; a step that could not be performed is recorded as such, never as a pass.
