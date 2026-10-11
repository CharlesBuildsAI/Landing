#!/usr/bin/env bash
# Build Landing without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Needs JDK 17+ and the Android SDK (platform 35 + build-tools 35).
#   ANDROID_HOME   your SDK folder (default: ~/Android/Sdk)
#   ANDROID_JAR    default: $ANDROID_HOME/platforms/android-35/android.jar
#   BUILD_TOOLS    default: $ANDROID_HOME/build-tools/35.0.0
#   AAPT2 D8 ZIPALIGN APKSIGNER   override single tools (e.g. "java -jar apksigner.jar")
#
# Signing:
#   Release:  KEYSTORE=/path/release.jks KEY_ALIAS=landing KS_PASS=... ./build.sh
#   Otherwise a throwaway debug key is created at ~/.landing/debug.jks (never commit keys).
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-35/android.jar}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/35.0.0}"
AAPT2="${AAPT2:-$BUILD_TOOLS/aapt2}"
D8="${D8:-$BUILD_TOOLS/d8}"
ZIPALIGN="${ZIPALIGN:-$BUILD_TOOLS/zipalign}"
APKSIGNER="${APKSIGNER:-$BUILD_TOOLS/apksigner}"

VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/AndroidManifest.xml)
OUT="dist/Landing-$VERSION.apk"
B=build
rm -rf "$B"; mkdir -p "$B/classes" "$B/dex" dist

echo "Building Landing $VERSION"
$AAPT2 compile --dir app/res -o "$B/res.zip"
$AAPT2 link -o "$B/unsigned.apk" -I "$ANDROID_JAR" --manifest app/AndroidManifest.xml "$B/res.zip" --java "$B/gen"
javac -source 11 -target 11 -Xlint:-options -nowarn -cp "$ANDROID_JAR" -d "$B/classes" \
    $(find app/src "$B/gen" -name '*.java')
$D8 --release --lib "$ANDROID_JAR" --min-api 26 --output "$B/dex" $(find "$B/classes" -name '*.class')
(cd "$B/dex" && zip -q ../unsigned.apk classes.dex)
$ZIPALIGN -f 4 "$B/unsigned.apk" "$B/aligned.apk"

if [ -n "${KEYSTORE:-}" ]; then
    : "${KS_PASS:?set KS_PASS for the release keystore}"
    $APKSIGNER sign --ks "$KEYSTORE" --ks-key-alias "${KEY_ALIAS:-landing}" \
        --ks-pass env:KS_PASS --key-pass env:KS_PASS --out "$OUT" "$B/aligned.apk"
else
    DBG="$HOME/.landing/debug.jks"
    if [ ! -f "$DBG" ]; then
        mkdir -p "$(dirname "$DBG")"
        keytool -genkeypair -keystore "$DBG" -storepass android -keypass android -alias debug \
            -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Landing Debug" >/dev/null 2>&1
    fi
    OUT="dist/Landing-$VERSION-debug.apk"
    $APKSIGNER sign --ks "$DBG" --ks-pass pass:android --key-pass pass:android --out "$OUT" "$B/aligned.apk"
fi
rm -f "$OUT.idsig"
$APKSIGNER verify --print-certs "$OUT" | head -3
echo "Built $OUT"
