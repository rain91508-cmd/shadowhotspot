#!/system/bin/sh
#
# Runs ON the phone as ROOT (su). Removes the Path A transparent proxy rules.
#
AP_IF="${AP_IF:-wlan0}"
TABLE=100
MARK=1

# TCP
iptables -t nat -D PREROUTING -i "$AP_IF" -p tcp -j SHADOWSOCKS 2>/dev/null || true
iptables -t nat -F SHADOWSOCKS 2>/dev/null || true
iptables -t nat -X SHADOWSOCKS 2>/dev/null || true

# UDP
iptables -t mangle -D PREROUTING -i "$AP_IF" -p udp -j SHADOWSOCKS_UDP 2>/dev/null || true
iptables -t mangle -F SHADOWSOCKS_UDP 2>/dev/null || true
iptables -t mangle -X SHADOWSOCKS_UDP 2>/dev/null || true

ip rule del fwmark $MARK lookup $TABLE 2>/dev/null || true
ip route del local 0.0.0.0/0 dev lo table $TABLE 2>/dev/null || true

echo ">> Transparent proxy rules removed."
