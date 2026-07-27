#!/usr/bin/env bash
#
# Generate a strong password and inject it into both config files,
# replacing the CHANGE_ME_STRONG_PASSWORD placeholder.
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_CFG="$ROOT_DIR/config/ssserver.json"
REDIR_CFG="$ROOT_DIR/config/sslocal-redir.json"

# 32 random bytes, base64 — works for aes-256-gcm and 2022-blake3-aes-256-gcm.
if command -v openssl >/dev/null 2>&1; then
    PASS="$(openssl rand -base64 32)"
else
    PASS="$(head -c 32 /dev/urandom | base64)"
fi

for f in "$SERVER_CFG" "$REDIR_CFG"; do
    # portable in-place sed
    tmp="$(mktemp)"
    sed "s#CHANGE_ME_STRONG_PASSWORD#${PASS//#/\\#}#g" "$f" > "$tmp"
    mv "$tmp" "$f"
done

echo ">> Password written to config/ssserver.json and config/sslocal-redir.json"
echo ">> password = $PASS"
echo ">> (Path B clients must use this exact password + method aes-256-gcm)"
