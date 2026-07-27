#!/system/bin/sh
#
# Runs ON the phone as ROOT (su). Path A only.
#
# Redirects traffic FORWARDED from hotspot clients into the local sslocal
# redirector, so clients need no configuration at all.
#
# Prereqs already running:
#   - ssserver           (run-ssserver.sh)
#   - sslocal --redir    (run-sslocal-redir.sh) listening on :60080
#   - Wi-Fi hotspot enabled (Android already sets ip_forward + MASQUERADE)
#
# Adjust these to your device. Find them with: `ip addr` and `ip route`.
#   AP_IF   = hotspot interface   (often wlan0, ap0, swlan0)
#   REDIR_PORT must match sslocal-redir.json local_port
#
AP_IF="${AP_IF:-wlan0}"
REDIR_PORT="${REDIR_PORT:-60080}"
TABLE=100
MARK=1

echo ">> Enabling IP forwarding"
echo 1 > /proc/sys/net/ipv4/ip_forward

######################## TCP (NAT REDIRECT) ###################################
iptables -t nat -N SHADOWSOCKS 2>/dev/null
iptables -t nat -F SHADOWSOCKS

# Never proxy traffic destined to private / special ranges
for net in 0.0.0.0/8 10.0.0.0/8 127.0.0.0/8 169.254.0.0/16 \
           172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do
    iptables -t nat -A SHADOWSOCKS -d "$net" -j RETURN
done

# Redirect the rest (forwarded TCP from clients) to sslocal redir
iptables -t nat -A SHADOWSOCKS -p tcp -j REDIRECT --to-ports "$REDIR_PORT"

# Hook: only traffic coming IN from the hotspot interface
iptables -t nat -C PREROUTING -i "$AP_IF" -p tcp -j SHADOWSOCKS 2>/dev/null \
    || iptables -t nat -A PREROUTING -i "$AP_IF" -p tcp -j SHADOWSOCKS

######################## UDP (TPROXY) #########################################
# Policy routing so TPROXY-marked packets go to the local socket
ip rule add fwmark $MARK lookup $TABLE 2>/dev/null || true
ip route add local 0.0.0.0/0 dev lo table $TABLE 2>/dev/null || true

iptables -t mangle -N SHADOWSOCKS_UDP 2>/dev/null
iptables -t mangle -F SHADOWSOCKS_UDP
for net in 0.0.0.0/8 10.0.0.0/8 127.0.0.0/8 169.254.0.0/16 \
           172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do
    iptables -t mangle -A SHADOWSOCKS_UDP -d "$net" -j RETURN
done
iptables -t mangle -A SHADOWSOCKS_UDP -p udp -j TPROXY \
    --on-port "$REDIR_PORT" --tproxy-mark $MARK

iptables -t mangle -C PREROUTING -i "$AP_IF" -p udp -j SHADOWSOCKS_UDP 2>/dev/null \
    || iptables -t mangle -A PREROUTING -i "$AP_IF" -p udp -j SHADOWSOCKS_UDP

echo ">> Transparent proxy active on $AP_IF -> 127.0.0.1:$REDIR_PORT"
echo ">> Clients on the hotspot now go through Shadowsocks automatically."
