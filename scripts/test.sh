#!/usr/bin/env bash
# Matching tests on a plain JVM (no Android, no libraries): fixed cases from the README rules,
# then 300,000 random names and key sequences checked against a slow, simple reference matcher.
# usage: scripts/test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/matchtest
mkdir -p "$out"
javac -encoding UTF-8 -d "$out" $(find test/stubs test/src -name '*.java') app/src/main/java/com/dbuezas/t9launcher/App.java
java -cp "$out" com.dbuezas.t9launcher.MatchTest
