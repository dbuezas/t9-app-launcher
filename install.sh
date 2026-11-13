#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME=/Users/davidbuezas/code/night-android/.tools/jdk-17.0.13+11/Contents/Home
export ANDROID_HOME=/usr/local/share/android-commandlinetools
ADB="$ANDROID_HOME/platform-tools/adb"

VARIANT="${1:-release}"   # pass "debug" for faster iteration without R8
case "$VARIANT" in
    debug)   TASK=assembleDebug;   APK=app/build/outputs/apk/debug/app-debug.apk ;;
    release) TASK=assembleRelease; APK=app/build/outputs/apk/release/app-release.apk ;;
    *) echo "usage: $0 [debug|release]" >&2; exit 1 ;;
esac

./gradlew "$TASK"

SERIAL=$("$ADB" devices | awk '$2=="device"{print $1; exit}')
if [ -z "$SERIAL" ]; then
    echo "No device connected. Try: $ADB connect <IP:PORT>" >&2
    exit 1
fi

"$ADB" -s "$SERIAL" install -r "$APK"
echo "Installed: $APK"
