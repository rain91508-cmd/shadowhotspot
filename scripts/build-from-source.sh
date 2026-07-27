#!/usr/bin/env bash
#
# OPTIONAL: build shadowsocks-rust for Android from source.
# Only needed if you don't want the prebuilt binaries from 00-download-binaries.sh.
#
# Requirements:
#   - Rust toolchain (rustup)
#   - Android NDK installed, ANDROID_NDK_HOME exported
#   - cargo-ndk:  cargo install cargo-ndk
#
set -euo pipefail

VERSION="${SS_VERSION:-v1.24.0}"
TARGET="${SS_TARGET:-aarch64-linux-android}"   # or x86_64-linux-android
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="$ROOT_DIR/.src/shadowsocks-rust"
OUT_DIR="$ROOT_DIR/dist"
mkdir -p "$OUT_DIR" "$(dirname "$SRC_DIR")"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your Android NDK path}"

if [ ! -d "$SRC_DIR" ]; then
    git clone --depth 1 --branch "$VERSION" \
        https://github.com/shadowsocks/shadowsocks-rust.git "$SRC_DIR"
fi

rustup target add "$TARGET"

cd "$SRC_DIR"
# Build only the two binaries we need, with the default aead + aead-2022 ciphers.
cargo ndk -t "$TARGET" build --release \
    --bin ssserver --bin sslocal \
    --no-default-features \
    --features "server local local-redir stream-cipher aead-cipher aead-cipher-2022"

BIN_DIR="$SRC_DIR/target/$TARGET/release"
cp "$BIN_DIR/ssserver" "$BIN_DIR/sslocal" "$OUT_DIR/"
echo ">> Built binaries copied to $OUT_DIR"
ls -l "$OUT_DIR/ssserver" "$OUT_DIR/sslocal"
