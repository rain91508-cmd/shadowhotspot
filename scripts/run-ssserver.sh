#!/system/bin/sh
#
# Runs ON the phone. Starts the Shadowsocks server.
# Needed for BOTH Path A (transparent) and Path B (manual client).
#
DIR=/data/local/tmp/shadowhotspot
cd "$DIR" || exit 1

echo ">> Starting ssserver on 0.0.0.0:8388"
# Run in foreground so you can see logs; add '&' or use a service manager to daemonize.
exec "$DIR/ssserver" -c "$DIR/ssserver.json"
