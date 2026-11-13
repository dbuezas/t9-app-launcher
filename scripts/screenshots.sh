#!/usr/bin/env bash
# Retakes the README screenshots (docs/*.png) on the connected phone.
# The panel opens over the home screen; each shot is cropped to the panel plus a 40 px margin.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB=/usr/local/share/android-commandlinetools/platform-tools/adb
export ANDROID_SERIAL="${ANDROID_SERIAL:-$("$ADB" devices | awk '$2=="device"{print $1; exit}')}"
[ -n "$ANDROID_SERIAL" ] || { echo "No device. Run ./connect.sh first." >&2; exit 1; }
RAW=$(mktemp -d)
trap 'rm -r "$RAW"' EXIT

"$ADB" shell input keyevent HOME; sleep 1
"$ADB" shell am start -W -a android.intent.action.ASSIST -n com.dbuezas.t9launcher/.MainActivity >/dev/null; sleep 1

# Panel window bounds, e.g. frame=[120,1355][960,2200]
F=$("$ADB" shell dumpsys window windows | grep -A30 'com.dbuezas.t9launcher/' | grep -m1 -o 'frame=\[[0-9,]*\]\[[0-9,]*\]')
read -r L T R B <<<"$(echo "$F" | tr -c '0-9' ' ')"
DENSITY=$("$ADB" shell wm density | awk '/Physical/{print $3}')
RESULTS_DP=$(grep -o 'RESULTS_DP = [0-9]*' app/src/main/java/com/dbuezas/t9launcher/DialerView.java | grep -o '[0-9]*$')
KT=$((T + RESULTS_DP * DENSITY / 160)); KH=$(( (B - KT) / 3 ))
r1=$((KT + KH / 2)); r2=$((r1 + KH)); r3=$((r2 + KH))
c1=$((L + (R - L) / 6)); c2=$(((L + R) / 2)); c3=$((R - (R - L) / 6))

key() {
    case $1 in
        C) xy="$c1 $r1";; 2) xy="$c2 $r1";; 3) xy="$c3 $r1";;
        4) xy="$c1 $r2";; 5) xy="$c2 $r2";; 6) xy="$c3 $r2";;
        7) xy="$c1 $r3";; 8) xy="$c2 $r3";; 9) xy="$c3 $r3";;
    esac
    "$ADB" shell input tap $xy; sleep 0.15
}
shot() { sleep 0.6; "$ADB" exec-out screencap -p > "$RAW/$1.png"; }

key C; shot empty                              # recent apps
for k in 6 2 7 7; do key $k; done; shot maps   # maps
key C; for k in 4 6; do key $k; done; shot go  # go
key C; for k in 2 5 8 3; do key $k; done; shot blue  # blue -> Bluetooth settings
"$ADB" shell input keyevent BACK

for n in empty maps go blue; do
    sips -c $((B - T + 80)) $((R - L + 80)) --cropOffset $((T - 40)) $((L - 40)) "$RAW/$n.png" --out "docs/$n.png" >/dev/null
    sips -Z 480 "docs/$n.png" >/dev/null
done
echo "Updated: docs/empty.png docs/maps.png docs/go.png docs/blue.png"
