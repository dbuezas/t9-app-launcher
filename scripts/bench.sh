#!/usr/bin/env bash
# Benchmarks an app on the connected phone: median of 8 cold and 8 warm opens.
# usage: scripts/bench.sh [label] [package] [activity] [apk to install first]
# defaults: this app. AppDialer: scripts/bench.sh AppDialer name.pilgr.appdialer.pro name.pilgr.appdialer.AppDialer
set -u
ADB=/usr/local/share/android-commandlinetools/platform-tools/adb
export ANDROID_SERIAL="${ANDROID_SERIAL:-$("$ADB" devices | awk '$2=="device"{print $1; exit}')}"
L=${1:-T9 App Launcher}; P=${2:-com.dbuezas.t9launcher}; A=${3:-.MainActivity}
[ $# -ge 4 ] && { "$ADB" install -r "$4" >/dev/null || exit 1; }
t() { "$ADB" shell am start -W -n "$P/$A" | awk '/TotalTime/{print $2}'; }
close() { "$ADB" shell input keyevent BACK; sleep 0.8; }
med() { tr ' ' '\n' | grep . | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'; }
# CPU ms of the main process: utime + stime, 100 Hz ticks
cpu() { "$ADB" shell "pid=\$(pidof $P); [ -n \"\$pid\" ] && awk '{print (\$14+\$15)*10}' /proc/\$pid/stat || echo 0"; }
coldt=""; coldc=""
for i in $(seq 8); do
    "$ADB" shell am force-stop "$P"; sleep 0.5
    coldt="$coldt $(t)"; sleep 2; coldc="$coldc $(cpu)"; close
done
warmt=""; warmc=""
for i in $(seq 8); do
    c0=$(cpu); warmt="$warmt $(t)"; sleep 1; c1=$(cpu); warmc="$warmc $((c1 - c0))"; close
done
t >/dev/null; sleep 2
open=$("$ADB" shell dumpsys meminfo "$P" | awk '/TOTAL PSS/{print $3}')
close; sleep 3
closed=$("$ADB" shell dumpsys meminfo "$P" | awk '/TOTAL PSS/{print $3}')
echo "$L | cold $(echo $coldt | med) ms, cpu $(echo $coldc | med) ms | warm $(echo $warmt | med) ms, cpu $(echo $warmc | med) ms | RAM open $((open / 1024)) MB, closed $((closed / 1024)) MB"
