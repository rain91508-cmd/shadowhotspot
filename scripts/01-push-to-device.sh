#!/usr/bin/env bash
#
# Push binaries, configs and on-device scripts to the phone via adb.
# /data/local/tmp is writable and allows execution for the adb shell user
# (no root required just to place/run files there).
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT_DIR/dist"
DEVICE_DIR="/data/local/tmp/shadowhotspot"

command -v adb >/dev/null 2>&1 || { echo "adb not found in PATH"; exit 1; }
[ -x "$DIST/ssserver" ] || { echo "Run scripts/00-download-binaries.sh first"; exit 1; }

echo ">> Waiting for device"
adb wait-for-device

echo ">> Creating $DEVICE_DIR"
adb shell mkdir -p "$DEVICE_DIR"

echo ">> Pushing binaries"
adb push "$DIST/ssserver" "$DEVICE_DIR/"
adb push "$DIST/sslocal"  "$DEVICE_DIR/"

echo ">> Pushing configs"
adb push "$ROOT_DIR/config/ssserver.json"      "$DEVICE_DIR/"
adb push "$ROOT_DIR/config/sslocal-redir.json" "$DEVICE_DIR/"

echo ">> Pushing on-device scripts"
for s in run-ssserver.sh run-sslocal-redir.sh setup-transparent-proxy.sh teardown-transparent-proxy.sh; do
    adb push "$ROOT_DIR/scripts/$s" "$DEVICE_DIR/"
done

echo ">> Setting permissions"
adb shell chmod 755 \
    "$DEVICE_DIR/ssserver" "$DEVICE_DIR/sslocal" \
    "$DEVICE_DIR/run-ssserver.sh" "$DEVICE_DIR/run-sslocal-redir.sh" \
    "$DEVICE_DIR/setup-transparent-proxy.sh" "$DEVICE_DIR/teardown-transparent-proxy.sh"

echo ">> Done. Files on device under $DEVICE_DIR:"
adb shell ls -l "$DEVICE_DIR"
