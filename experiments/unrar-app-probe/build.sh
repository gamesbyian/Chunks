#!/usr/bin/env bash
set -euo pipefail
work="${1:?disposable runner output directory}"
test -d "$work/lib/x86_64"
test -d "$work/lib/arm64-v8a"
bt="$ANDROID_HOME/build-tools/35.0.0"
platform="$ANDROID_HOME/platforms/android-35/android.jar"
if [ ! -x "$bt/aapt2" ] || [ ! -f "$platform" ]; then
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "build-tools;35.0.0" "platforms;android-35"
fi
mkdir -p "$work/classes" "$work/dex" "$work/assets"
cp app/src/androidTest/assets/rar-volume-review/Verified.part{1,2}.rar "$work/assets/"
cp upstream-unrar/license.txt "$work/assets/UnRAR-LICENSE.txt"
test "$(git -C upstream-rarfile-fixtures rev-parse HEAD)" = d2f7df6fc843dae356fd6b0a85971dc36fd6e757
# Only the nine known files; never package the upstream tree or other fixtures.
for name in rar3-old.rar rar3-old.r00 rar3-old.r01 rar3-vols.part{1,2,3}.rar rar5-vols.part{1,2,3}.rar; do
  cp "upstream-rarfile-fixtures/test/files/$name" "$work/assets/$name"
done
cp upstream-rarfile-fixtures/LICENSE "$work/assets/rarfile-fixtures-LICENSE.txt"
javac --release 8 -classpath "$platform" -d "$work/classes" experiments/unrar-app-probe/WitnessInstrumentation.java
"$bt/d8" --min-api 29 --lib "$platform" --output "$work/dex" "$work/classes/io/github/gamesbyian/chunks/unrarprobe/WitnessInstrumentation.class"
"$bt/aapt2" link -o "$work/unsigned.apk" -I "$platform" --manifest experiments/unrar-app-probe/AndroidManifest.xml -A "$work/assets"
cp "$work/dex/classes.dex" "$work/classes.dex"
(cd "$work" && zip -q -0 unsigned.apk classes.dex lib/x86_64/libchunks_unrar_probe.so lib/arm64-v8a/libchunks_unrar_probe.so)
"$bt/zipalign" -P 16 -f 4 "$work/unsigned.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/probe.jks" -storepass throwaway -keypass throwaway -alias t -keyalg RSA -keysize 2048 -validity 2 -dname CN=UnRAR-Probe
"$bt/apksigner" sign --ks "$work/probe.jks" --ks-pass pass:throwaway --key-pass pass:throwaway --out "$work/probe.apk" "$work/aligned.apk"
"$bt/apksigner" verify "$work/probe.apk"
"$bt/zipalign" -c -P 16 4 "$work/probe.apk"
unzip -l "$work/probe.apk"
