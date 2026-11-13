#!/usr/bin/env bash
set -euo pipefail

# Auto-discovers the phone's wireless-ADB endpoint over mDNS.
# Pairing is one-time per phone; if it ever forgets us, run:
#   adb pair PAIR_IP:PAIR_PORT  PAIR_CODE
# using the values from "Pair device with pairing code".

ANDROID_HOME=/usr/local/share/android-commandlinetools
ADB="$ANDROID_HOME/platform-tools/adb"

"$ADB" start-server >/dev/null 2>&1 || true

# Service type _adb-tls-connect._tcp is the post-pair connect endpoint.
# Re-query for up to ~5s — first call sometimes returns empty while the
# resolver is warming up.
TARGET=""
for _ in 1 2 3 4 5; do
    TARGET="$("$ADB" mdns services 2>/dev/null \
        | awk '$2=="_adb-tls-connect._tcp"{print $3; exit}')"
    [ -n "$TARGET" ] && break
    sleep 1
done

if [ -z "$TARGET" ]; then
    echo "No _adb-tls-connect._tcp service found via mDNS." >&2
    echo "Make sure Wireless debugging is on and the phone is on the same network." >&2
    exit 1
fi

echo "discovered $TARGET"
"$ADB" connect "$TARGET"
"$ADB" devices
