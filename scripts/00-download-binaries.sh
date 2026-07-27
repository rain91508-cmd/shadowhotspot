#!/usr/bin/env bash
#
# Download prebuilt shadowsocks-rust binaries for Android.
#
# Real phones are almost always arm64  -> aarch64-linux-android (default)
# Android emulator (x86_64 image)      -> set SS_TARGET=x86_64-linux-android
#
set -euo pipefail

VERSION="${SS_VERSION:-v1.24.0}"
TARGET="${SS_TARGET:-aarch64-linux-android}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT_DIR/dist"
mkdir -p "$OUT_DIR"

ASSET="shadowsocks-${VERSION}.${TARGET}.tar.xz"
BASE="https://github.com/shadowsocks/shadowsocks-rust/releases/download/${VERSION}"
URL="${BASE}/${ASSET}"

echo ">> Downloading ${ASSET}"
curl -L --fail -o "$OUT_DIR/$ASSET" "$URL"
curl -L --fail -o "$OUT_DIR/$ASSET.sha256" "$URL.sha256" || true

# Optional integrity check (asset .sha256 is "<hash>  <filename>")
if [ -f "$OUT_DIR/$ASSET.sha256" ] && command -v sha256sum >/dev/null 2>&1; then
    echo ">> Verifying checksum"
    ( cd "$OUT_DIR" && sha256sum -c "$ASSET.sha256" ) || {
        echo "!! checksum verification failed"; exit 1; }
fi

echo ">> Extracting"
tar -C "$OUT_DIR" -xf "$OUT_DIR/$ASSET"

echo ">> Done. Binaries in $OUT_DIR:"
ls -l "$OUT_DIR"/ssserver "$OUT_DIR"/sslocal 2>/dev/null || ls -l "$OUT_DIR"
