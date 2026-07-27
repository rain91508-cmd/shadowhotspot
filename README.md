# shadowhotspot

Turn an Android phone into a **Wi‑Fi hotspot that shares its mobile data through a
Shadowsocks server running on the phone itself**. Any device that joins the hotspot
routes its traffic through Shadowsocks (`shadowsocks-rust`) before it leaves over the
phone's cellular link.

```
                         Android phone
                ┌─────────────────────────────────┐
 [Client A] ──▶ │ Wi‑Fi hotspot (wlan0)           │
 [Client B] ──▶ │        │                        │
 [Client C] ──▶ │        ▼                        │
                │  sslocal (redir)  ──encrypt──▶  ssserver ──decrypt──▶ ┐
                │  127.0.0.1:60080      loopback   127.0.0.1:8388       │
                │                                                       ▼
                │                              cellular (rmnet) ──▶ Internet
                └─────────────────────────────────┘
```

---

## Why this design (and what it actually buys you)

Shadowsocks is normally used with the **server placed on the far side** of a firewall.
Here the server lives **on the phone**, so understand exactly what you gain:

| Benefit | Explanation |
|---|---|
| **TTL / hop normalization** | The proxy *terminates* each client connection and opens a **fresh** outbound socket from the phone's own network stack. Outbound packets carry the phone's normal TTL, so carrier **tethering detection based on TTL/hop count is defeated**. |
| **Single-origin traffic** | To the carrier, all traffic looks like it originates from ordinary apps on the phone, not from NAT‑forwarded devices. |
| **LAN encryption** | Traffic between each client and the phone (over Wi‑Fi) is Shadowsocks‑encrypted, so other people on the hotspot LAN can't sniff it. |

What it does **not** do: the carrier still sees the *real* (decrypted) destination
traffic leaving the phone, because the phone is the exit node. This is **not**
censorship circumvention — for that you need a Shadowsocks server *outside* the
restricted network.

---

## Two ways to run it — pick one

### Path A — Transparent proxy (recommended, **requires root**)
Clients need **zero configuration**. Root lets us use `iptables` to redirect all
forwarded hotspot traffic into a local `sslocal` (redir mode), which tunnels to the
local `ssserver`, which makes the real outbound connections.

- Runs: `ssserver` + `sslocal --redir` on the phone.
- Setup: `scripts/setup-transparent-proxy.sh` (needs `su`).
- Clients: just join the Wi‑Fi, nothing to install.

### Path B — Manual client proxy (**no root needed**)
The phone runs only `ssserver`. Each client runs a Shadowsocks client
(`sslocal`, or a GUI app) pointing at the phone's hotspot IP.

- Runs: `ssserver` on the phone.
- Clients: install a Shadowsocks client → see `client/README.md`.

> Enabling the actual Wi‑Fi hotspot toggle on modern Android generally still needs
> either the Settings UI (manual tap) or root. The phone's built‑in tethering already
> sets up `ip_forward` + `MASQUERADE`; our scripts only add the Shadowsocks redirect
> on top.

---

## Quick start

Prerequisites on your computer: `adb`, `curl`, `tar` (with `xz`).
Enable **USB debugging** on the phone and connect it.

```bash
# 1. Download the prebuilt Android binaries (arm64 by default)
./scripts/00-download-binaries.sh

# 2. Set a real password everywhere
./scripts/02-gen-config.sh          # generates a strong password into the configs

# 3. Push binaries + configs to the phone
./scripts/01-push-to-device.sh

# 4a. Start the server (both paths need this)
adb shell /data/local/tmp/shadowhotspot/run-ssserver.sh

# 4b. (Path A only, root) start redirector + install iptables rules
adb shell su -c /data/local/tmp/shadowhotspot/run-sslocal-redir.sh
adb shell su -c /data/local/tmp/shadowhotspot/setup-transparent-proxy.sh
```

Then turn on the Wi‑Fi hotspot in Android Settings and connect a client.

- **Path A:** the client browses immediately.
- **Path B:** configure the client per `client/README.md`
  (server = phone's hotspot IP, e.g. `192.168.43.1`, port `8388`).

To undo Path A: `adb shell su -c /data/local/tmp/shadowhotspot/teardown-transparent-proxy.sh`

---

## Layout

```
shadowhotspot/
├── README.md
├── config/
│   ├── ssserver.json          # the on-phone Shadowsocks server
│   └── sslocal-redir.json     # local transparent redirector (Path A)
├── scripts/
│   ├── 00-download-binaries.sh   # fetch prebuilt aarch64/x86_64 android binaries
│   ├── 01-push-to-device.sh      # adb push everything to the phone
│   ├── 02-gen-config.sh          # inject a strong password into configs
│   ├── build-from-source.sh      # optional: cross-compile with cargo-ndk
│   ├── run-ssserver.sh           # (runs on device) start ssserver
│   ├── run-sslocal-redir.sh      # (runs on device) start sslocal redir
│   ├── setup-transparent-proxy.sh   # (runs on device, root) iptables rules
│   └── teardown-transparent-proxy.sh
└── client/
    └── README.md              # Path B client configuration
```

## Releases

A self-contained release tarball is built by `scripts/release.sh` — it bundles the
configs, the host/device scripts, and the prebuilt Shadowsocks binaries into a
single `releases/shadowhotspot-<version>.tar.xz` you can drop onto any machine
that has `adb`.

```bash
# build a release tarball + create tag v0.1.0
./scripts/release.sh 0.1.0

# also build the companion Android app APK (needs Android SDK + env)
BUILD_ANDROID=1 ./scripts/release.sh 0.1.0

# build AND publish a GitHub Release (needs the `gh` CLI authenticated)
PUBLISH=1 ./scripts/release.sh 0.1.0
```

The GitHub Action in `.github/workflows/release.yml` runs this automatically on
every `v*` tag push, producing the tarball and publishing it as a GitHub Release.

Tag and push to trigger a release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

> The prebuilt binaries are fetched on demand by `scripts/00-download-binaries.sh`
> (and by the release script), so they are **not** stored in the repository.

## Legal / responsible use

Only share a connection you are entitled to share. Bypassing an operator's tethering
policy may violate your carrier's terms of service or local regulations. You are
responsible for how you use this.
