#!/usr/bin/env bash
# The Android emulator runner executes one command; keep optional orchestration in Bash.
set -euo pipefail
bash tools/installed_rtorrent_magnets.sh
if [[ "${CHUNKS_REAL_MEDIA:-0}" == "1" ]]; then
  bash tools/installed_real_media.sh "io.github.gamesbyian.chunks.${CHUNKS_PREVIEW_SUFFIX:?}"
fi
