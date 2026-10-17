#!/usr/bin/env bash
# Puts the qsh client for Android into the APK sources as libqsh.so.
# Usage: scripts/fetch-qsh.sh <qsh-aarch64-android.tar.gz URL or local path>
# The archive is the `qsh-aarch64-android` asset of a qsh release.
set -euo pipefail

src="${1:?usage: $0 <URL or path of qsh-aarch64-android.tar.gz>}"
dest="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [[ -f "$src" ]]; then
    cp "$src" "$work/qsh.tar.gz"
else
    curl -fsSL -o "$work/qsh.tar.gz" "$src"
fi
tar -xzf "$work/qsh.tar.gz" -C "$work"
bin="$(find "$work" -type f -name qsh | head -n1)"
[[ -n "$bin" ]] || { echo "no qsh binary in the archive" >&2; exit 1; }
# AArch64 ELF: magic, 64-bit class, machine 0xB7.
[[ "$(head -c 4 "$bin" | od -An -tx1 | tr -d ' ')" == "7f454c46" && "$(od -An -tx1 -j18 -N1 "$bin" | tr -d ' ')" == "b7" ]] \
    || { echo "qsh in the archive is not an aarch64 ELF binary" >&2; exit 1; }

mkdir -p "$dest"
install -m755 "$bin" "$dest/libqsh.so"
echo "installed $dest/libqsh.so ($("$bin" --version 2>/dev/null || echo 'version unknown on this host'))"
