#!/usr/bin/env bash
# Pre-install one pinned API35 emulator image, with bounded retries for
# transient Google SDK download truncation ("Premature EOF"). No test is
# skipped or considered successful without a verified SDK package receipt.
set -euo pipefail

: "${ANDROID_HOME:?Missing Android SDK root}"
[[ "$ANDROID_HOME" = /* && "$ANDROID_HOME" != / ]] || {
    echo "CHUNKS_ANDROID_IMAGE: unsafe SDK root" >&2
    exit 2
}
sdkmanager="${CHUNKS_SDKMANAGER:-$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager}"
package="system-images;android-35;google_apis;x86_64"
image_dir="$ANDROID_HOME/system-images/android-35/google_apis/x86_64"
receipt="$image_dir/package.xml"

if [[ -f "$receipt" ]]; then
    echo "CHUNKS_ANDROID_IMAGE: pinned image already installed"
    exit 0
fi

for attempt in 1 2 3; do
    echo "CHUNKS_ANDROID_IMAGE: installing pinned API35 image (attempt $attempt/3)" >&2
    # A failed download must not be treated as installed merely because the
    # SDK manager returned success; the exact pinned package must exist.
    if printf 'y\n' | timeout "${CHUNKS_SDK_TIMEOUT:-240s}" "$sdkmanager" \
        --channel=0 --install "$package" && [[ -f "$receipt" ]]; then
        echo "CHUNKS_ANDROID_IMAGE: package.xml verified, attempt=$attempt"
        exit 0
    fi
    if (( attempt < 3 )); then
        echo "CHUNKS_ANDROID_IMAGE: incomplete SDK package; removing ONLY the failed image" >&2
        rm -rf -- "$image_dir"
        sleep "${CHUNKS_SDK_RETRY_DELAY:-3}"
    fi
done

echo "::error::Pinned Android API35 Google APIs x86_64 system image unavailable after 3 bounded attempts" >&2
exit 1
