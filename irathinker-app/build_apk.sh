#!/bin/bash
# Manual APK build for IraThinker (no Gradle - the daemon JVM dies in this sandbox).
# Pipeline: aapt2 compile/link -> javac -> d8 -> zipalign -> apksigner.
set -e
BT=$HOME/android-sdk/build-tools/34.0.0
AJAR=$HOME/android-sdk/platforms/android-36/android.jar
PROJ=$HOME/workspace/irathinker/app/app/src/main
OUT=$HOME/workspace/irathinker/manual_build
VER=${1:-v1}
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"

# debug keystore (once)
KS=$HOME/workspace/irathinker/debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
fi

echo "[1/5] aapt2 compile + link"
$BT/aapt2 compile --dir "$PROJ/res" -o "$OUT/res.zip"
CODE=$(echo "$VER" | tr -dc '0-9'); CODE=${CODE:-1}
$BT/aapt2 link -o "$OUT/app-unsigned.apk" -I "$AJAR" \
  --manifest "$PROJ/AndroidManifest.xml" -R "$OUT/res.zip" \
  --java "$OUT/gen" --auto-add-overlay \
  --version-code "$CODE" --version-name "$VER"

echo "[2/5] javac"
find "$PROJ/java" -name '*.java' > "$OUT/sources.txt"
echo "$OUT/gen/com/irathinker/app/R.java" >> "$OUT/sources.txt"
javac -encoding UTF-8 --release 8 -cp "$AJAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/5] d8"
$BT/d8 --lib "$AJAR" --min-api 26 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[4/5] package + zipalign"
cp "$OUT/app-unsigned.apk" "$OUT/app-dex.apk"
cd "$OUT/dex" && zip -q -u "$OUT/app-dex.apk" classes.dex
$BT/zipalign -f 4 "$OUT/app-dex.apk" "$OUT/app-aligned.apk"

echo "[5/5] sign"
$BT/apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$HOME/workspace/irathinker/IraThinker-$VER.apk" "$OUT/app-aligned.apk"
$BT/apksigner verify --print-certs "$HOME/workspace/irathinker/IraThinker-$VER.apk" | head -2
ls -la "$HOME/workspace/irathinker/IraThinker-$VER.apk"
echo "MANUAL_BUILD_DONE $VER"
