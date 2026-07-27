# Client setup — Path B (no root on the phone)

Use this when the phone runs **only** `ssserver` (no `iptables`/root). Each client
device joins the hotspot and runs a Shadowsocks client pointed at the phone.

## 1. Find the phone's hotspot IP

On the phone's hotspot network the gateway is usually one of:

- `192.168.43.1`  (classic Android tethering)
- `192.168.1.1` / `192.168.0.1` (vendor dependent)

From a connected client you can check the default gateway (that's the phone):

- Linux/macOS: `ip route | grep default`  → `default via 192.168.43.1 ...`
- Windows:     `ipconfig` → "Default Gateway"

## 2. Connection parameters

| Field    | Value                                  |
|----------|----------------------------------------|
| Server   | phone hotspot IP (e.g. `192.168.43.1`) |
| Port     | `8388`                                 |
| Password | the value printed by `02-gen-config.sh`|
| Method   | `aes-256-gcm`                          |

## 3a. Desktop client (shadowsocks-rust `sslocal`)

Download the matching `sslocal` for the client OS from
<https://github.com/shadowsocks/shadowsocks-rust/releases>, then create `client.json`:

```json
{
    "server": "192.168.43.1",
    "server_port": 8388,
    "password": "PASTE_THE_SAME_PASSWORD",
    "method": "aes-256-gcm",
    "locals": [
        { "protocol": "socks", "local_address": "127.0.0.1", "local_port": 1080 },
        { "protocol": "http",  "local_address": "127.0.0.1", "local_port": 1081 }
    ],
    "mode": "tcp_and_udp"
}
```

Run it:

```bash
sslocal -c client.json
```

Now point apps/OS at the local proxy:
- SOCKS5 `127.0.0.1:1080`, or
- HTTP `127.0.0.1:1081`

Quick test:

```bash
curl -x socks5h://127.0.0.1:1080 https://ifconfig.me
```

## 3b. GUI / phone clients

Any standard Shadowsocks client works — enter the same four parameters:
- **Windows/macOS:** Shadowsocks, ShadowsocksX-NG, or use `sslocal` above.
- **Android/iOS:** Shadowsocks, Shadowrocket, etc.
- Or scan an `ss://` URL generated on the phone:

```bash
# on the phone (or anywhere with ssurl from the same release archive)
ssurl --encode config/ssserver.json   # replace host 0.0.0.0 with the hotspot IP first
```

## Notes

- Only TCP+UDP apps that respect the system/SOCKS proxy will be tunneled. For
  full transparent behavior with zero client config, use **Path A** (root) instead.
- If a client can reach the phone (`ping 192.168.43.1`) but not the internet,
  verify `ssserver` is actually running and the password/method match exactly.
