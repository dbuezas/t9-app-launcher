#!/usr/bin/env bash
# Release notes for a tag: the commits since the previous tag, without version bumps and
# README/CI/script-only commits. Body lines that start with "- " become sub-items.
# usage: scripts/release-notes.sh v1.7.0
set -euo pipefail
tag=${1:?usage: $0 <tag>}
prev=$(git describe --tags --abbrev=0 "$tag^" 2>/dev/null || true)
range=${prev:+$prev..}$tag

echo "## Changes"
echo
git log --no-merges --format='%x1e%s%n%b' "$range" | awk '
    BEGIN { RS = "\x1e"; skip = "^(Version [0-9]|README|CI:|Scripts|pair\\.sh|connect\\.sh|install\\.sh)" }
    NF == 0 { next }
    {
        n = split($0, l, "\n")
        if (l[1] ~ skip) next
        print "- " l[1]
        sub_open = 0
        for (i = 2; i <= n; i++) {
            if (l[i] ~ /^- /) { printf "%s  %s", (sub_open ? "\n" : ""), l[i]; sub_open = 1 }
            else if (sub_open && l[i] ~ /^  +[^ ]/) { t = l[i]; sub(/^ +/, "", t); printf " %s", t }
            else if (sub_open) { print ""; sub_open = 0 }
        }
        if (sub_open) print ""
    }'
if [ -n "$prev" ]; then
    echo
    echo "Full diff: [$prev...$tag](https://github.com/dbuezas/t9-app-launcher/compare/$prev...$tag)"
fi
