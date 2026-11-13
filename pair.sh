#!/usr/bin/env bash
# Pairs this computer with the phone. Only the 6-digit code is needed:
# the pairing IP and port are found over mDNS.
# On the phone: Wireless debugging -> Pair device with pairing code. Then: ./pair.sh 123456
set -euo pipefail
ADB=/usr/local/share/android-commandlinetools/platform-tools/adb
CODE="${1:-}"
[ -n "$CODE" ] || read -rp "Pairing code: " CODE
"$ADB" start-server >/dev/null 2>&1 || true
TARGET=""
for _ in $(seq 10); do
    TARGET="$("$ADB" mdns services 2>/dev/null | awk '$2=="_adb-tls-pairing._tcp"{print $3; exit}')"
    [ -n "$TARGET" ] && break
    sleep 1
done
[ -n "$TARGET" ] || { echo "No pairing service found. Is the pairing dialog open on the phone?" >&2; exit 1; }
"$ADB" pair "$TARGET" "$CODE"
exec "$(dirname "$0")/connect.sh"
