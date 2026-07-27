#!/system/bin/sh
#
# Runs ON the phone. Starts the local transparent redirector (Path A only).
# It listens on :60080 (redirect/tproxy) and tunnels to the local ssserver.
#
DIR=/data/local/tmp/shadowhotspot
cd "$DIR" || exit 1

echo ">> Starting sslocal (redir) on 0.0.0.0:60080 -> 127.0.0.1:8388"
exec "$DIR/sslocal" -c "$DIR/sslocal-redir.json"
