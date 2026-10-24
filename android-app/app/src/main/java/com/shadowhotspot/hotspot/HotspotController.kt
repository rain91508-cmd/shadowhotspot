package com.shadowhotspot.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Hotspot control via **Wi-Fi Direct Group Owner (GO)** instead of the standard
 * Local-Only Hotspot API.
 *
 * Why this approach:
 *  - The GO always gets a fixed, well-known address on `p2p0`
 *    (`192.168.49.1`, optionally read from the interface). Clients that join the
 *    `DIRECT-xx` SSID receive a `192.168.49.x` address via the GO's DHCP server
 *    and can reach any local server on the phone directly over TCP/UDP. For our
 *    purpose (clients reach `ssserver` at `0.0.0.0:8388`) this works with NO
 *    proxy and NO internet routing — exactly what we need.
 *  - Unlike Local-Only Hotspot, the gateway is predictable, so the UI doesn't
 *    need fragile address guessing.
 *
 * Custom SSID/password:
 *  - On Android 13+ (API 33+) the public `WifiP2pConfig.Builder` lets a normal
 *    app set the GO's network name + passphrase (must start with `DIRECT-`, and
 *    the passphrase must be 8-63 chars). This works WITHOUT reflection and WITHOUT
 *    `ACCESS_FINE_LOCATION` — `NEARBY_WIFI_DEVICES` is declared `neverForLocation`.
 *  - Because the permission is `neverForLocation`, the OS redacts the group's
 *    `networkName`/`passphrase` when read back, so we cache the values we set and
 *    display those.
 *
 * Limitations:
 *  - This device cannot run STA (Wi-Fi client) and the AP/GO simultaneously, so
 *    the active Wi-Fi *client* link must be disconnected first.
 */
object HotspotController {

    data class HotspotInfo(
        val ssid: String = "",
        val password: String = "",
        val gateway: String? = null,
    )

    /** Android assigns the Wi-Fi Direct Group Owner this address on p2p0. */
    private const val DEFAULT_GO_IP = "192.168.49.1"

    @Volatile
    private var p2p: WifiP2pManager? = null

    @Volatile
    private var channel: WifiP2pManager.Channel? = null

    @Volatile
    private var group: WifiP2pGroup? = null

    /**
     * True while the Wi-Fi Direct group (AP) is active. Driven by the
     * start/success/failure/stop callbacks rather than the cached [group] object,
     * so a transient null from requestGroupInfo can't make the UI or the
     * server-gating logic think the AP is down.
     */
    @Volatile
    private var active: Boolean = false

    /** True while a start() is in flight. Re-entry is allowed again after a short
     *  timeout, so a stuck P2P callback can never permanently block the AP. */
    @Volatile
    private var starting: Boolean = false
    @Volatile
    private var startAttemptMs: Long = 0

    fun isStarting(): Boolean = starting

    /** Credentials the user requested, kept for display when the OS redacts them. */
    @Volatile
    private var configuredSsid = ""

    @Volatile
    private var configuredPassword = ""

    /**
     * True while the Wi-Fi Direct group (AP) is active. The AP comes up
     * independently of any connected client; only the Shadowsocks server is gated
     * on client presence (see the ViewModel polling loop), so this must never be
     * tied to client count.
     */
    fun isActive(): Boolean = active

    /**
     * Re-initializes the P2P manager + channel. Needed because the system keeps
     * the Wi-Fi Direct Group Owner up across an app *process* death on this ROM,
     * but our in-memory `p2p`/`channel`/`group`/`configured*` references are gone
     * after the kill. Without this, the polling loop sees no group and blanks the
     * SSID/password after the app is backgrounded and reopened. Re-attaching lets
     * us re-query the live group and recover the displayed credentials.
     */
    fun attach(context: Context) {
        val appCtx = context.applicationContext
        if (p2p == null) {
            p2p = appCtx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        }
        if (channel == null) {
            channel = p2p?.initialize(appCtx, Looper.getMainLooper(), null)
        }
    }

    fun start(
        context: Context,
        ssid: String? = null,
        password: String? = null,
        onStarted: (HotspotInfo?) -> Unit,
        onFailed: (Int) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        // Re-entry guard: ignore only a genuinely in-flight start; allow a retry
        // once the previous attempt is older than the watchdog window so a stuck
        // P2P callback can never permanently block the AP.
        if (starting && now - startAttemptMs < WATCHDOG_MS) {
            Log.d("ShadowHotspot", "start: ignored (start already in flight)")
            return
        }
        starting = true
        startAttemptMs = now
        val appCtx = context.applicationContext
        val mgr = appCtx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: run { starting = false; return onFailed(-1) }
        p2p = mgr

        // Remember the credentials the user asked for so we can display them even
        // when the OS redacts the group's networkName/passphrase (the
        // NEARBY_WIFI_DEVICES permission is declared neverForLocation, so the
        // framework won't hand back the SSID/passphrase it considers location data).
        configuredSsid = ssid?.let { ensureDirectPrefix(it) } ?: ""
        configuredPassword = password ?: ""

        // This device cannot run a Wi-Fi *station* (STA) and a P2P Group Owner at
        // the same time: while STA is associated, createGroup() fails with ERROR.
        // disconnect() also disables auto-join, but the association takes a moment
        // to actually release the radio — so wait for it before touching P2P.
        val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        runCatching { wm?.disconnect() }

        val ch = channel ?: mgr.initialize(appCtx, Looper.getMainLooper(), null)
        channel = ch

        val desiredCustom =
            configuredSsid.isNotBlank() && configuredPassword.length in 8..63

        // Creates the group. If the custom-credential config is rejected (this ROM
        // returns ERROR=0 for a WifiP2pConfig carrying networkName/passphrase), we
        // permanently fall back to the plain createGroup() which lets Android
        // auto-generate the network — the AP still comes up, just with OS-chosen
        // credentials that we read back afterwards.
        var usedPlainFallback = false
        var createNewGroup: (Int) -> Unit = {}
        createNewGroup = { attempt ->
            val config = if (usedPlainFallback) null
            else buildP2pConfigSafe(configuredSsid, configuredPassword)
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("ShadowHotspot", "createGroup onSuccess; GO is up (plain=${usedPlainFallback})")
                    // Report the AP active immediately with known credentials,
                    // without waiting on the (sometimes slow) requestGroupInfo.
                    active = true
                    starting = false
                    onStarted(currentInfo(appCtx))
                    requestGroupWithRetry(mgr, ch, appCtx, onStarted, attempts = 10)
                }

                override fun onFailure(reason: Int) {
                    Log.d("ShadowHotspot", "createGroup onFailure reason=$reason (attempt ${attempt + 1}, plain=$usedPlainFallback)")
                    if (config != null) {
                        // Custom credentials were rejected — retry once with plain
                        // createGroup so the AP still comes up.
                        Log.d("ShadowHotspot", "custom createGroup rejected; falling back to plain createGroup")
                        usedPlainFallback = true
                        Handler(Looper.getMainLooper()).postDelayed({ createNewGroup(0) }, 800)
                        return
                    }
                    val transient = reason == WifiP2pManager.ERROR || reason == WifiP2pManager.BUSY
                    if (transient && attempt < 3) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            createNewGroup(attempt + 1)
                        }, 1200)
                    } else {
                        group = null
                        active = false
                        starting = false
                        onFailed(reason)
                    }
                }
            }
            Log.d("ShadowHotspot", "createGroup invoked (customConfig=${config != null}, attempt ${attempt + 1})")
            runCatching {
                if (config != null) mgr.createGroup(ch, config, listener)
                else mgr.createGroup(ch, listener)
            }.onFailure { t ->
                Log.e("ShadowHotspot", "createGroup threw", t)
                starting = false
                onFailed(WifiP2pManager.ERROR)
            }
        }

        // This ROM keeps a Group Owner (GO) alive across app kills, so a P2P group
        // often already exists when we're asked to start. Query it first:
        //  - If a group is already up and we don't need specific credentials, adopt
        //    it — the AP is up immediately, no teardown needed.
        //  - Otherwise tear the old group down (watchdog if removeGroup never
        //    answers) and create a fresh one.
        val queryAndStart: () -> Unit = {
            Log.d("ShadowHotspot", "start: querying existing P2P group (desiredCustom=$desiredCustom)")
            mgr.requestGroupInfo(ch) { existing ->
                if (existing != null) {
                    if (!desiredCustom) {
                        Log.d("ShadowHotspot", "start: adopting existing P2P group (GO already up)")
                        group = existing
                        active = true
                        starting = false
                        onStarted(currentInfo(appCtx))
                    } else {
                        var proceeded = false
                        val proceed = {
                            if (!proceeded) {
                                proceeded = true
                                Log.d("ShadowHotspot", "start: removeGroup done/timeout -> createGroup")
                                createNewGroup(0)
                            }
                        }
                        Log.d("ShadowHotspot", "start: removing existing group to apply custom creds")
                        mgr.removeGroup(
                            ch,
                            object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    Log.d("ShadowHotspot", "removeGroup onSuccess")
                                    proceed()
                                }
                                override fun onFailure(reason: Int) {
                                    Log.d("ShadowHotspot", "removeGroup onFailure reason=$reason")
                                    proceed()
                                }
                            },
                        )
                        Handler(Looper.getMainLooper()).postDelayed(proceed, WATCHDOG_MS)
                    }
                } else {
                    Log.d("ShadowHotspot", "start: no existing group -> createGroup")
                    createNewGroup(0)
                }
            }
        }

        waitForStaDisconnect(wm, STA_WAIT_MS, queryAndStart)
    }

    /** Polls until the Wi-Fi station link is gone (or timeout), then runs [onReady]. */
    private fun waitForStaDisconnect(wm: WifiManager?, timeoutMs: Long, onReady: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val startMs = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                val connected = try {
                    val info = wm?.connectionInfo
                    info != null && info.networkId != -1
                } catch (_: Throwable) {
                    false
                }
                val elapsed = SystemClock.elapsedRealtime() - startMs
                if (!connected || elapsed >= timeoutMs) {
                    Log.d("ShadowHotspot", "STA released (connected=$connected, waited=${elapsed}ms)")
                    onReady()
                } else {
                    handler.postDelayed(this, 200)
                }
            }
        }
        handler.post(poll)
    }

    /** Max time we wait for a P2P callback before allowing a fresh start attempt. */
    private const val WATCHDOG_MS = 4000L

    /** Max time we wait for the Wi-Fi station link to release the radio. */
    private const val STA_WAIT_MS = 5000L

    /**
     * Wi-Fi Direct group SSIDs must begin with the literal `DIRECT-` prefix. If the
     * user-supplied name already starts with it (case-insensitive) we keep it as-is,
     * otherwise we prepend it so createGroup() accepts the profile.
     */
    internal fun ensureDirectPrefix(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.startsWith("DIRECT-", ignoreCase = true)) trimmed
        else "DIRECT-$trimmed"
    }

    /**
     * Builds a [WifiP2pConfig] carrying a custom network name + passphrase.
     * Returns null when a custom profile can't be used (SDK < 29, or the
     * passphrase isn't a valid WPA2-PSK length 8-63), in which case the caller
     * falls back to a parameter-less createGroup() and Android auto-generates one.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildP2pConfig(ssid: String, password: String): WifiP2pConfig? {
        if (ssid.isBlank()) return null
        // WPA2-PSK passphrase must be 8-63 printable ASCII characters.
        if (password.length !in 8..63) return null
        return runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(ssid.take(32))
                .setPassphrase(password)
                .build()
        }.getOrNull()
    }

    /** SDK-safe wrapper around [buildP2pConfig]. */
    private fun buildP2pConfigSafe(ssid: String, password: String): WifiP2pConfig? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            buildP2pConfig(ssid, password)
        } else {
            null
        }

    private fun requestGroupWithRetry(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        context: Context,
        onStarted: (HotspotInfo?) -> Unit,
        attempts: Int,
    ) {
        if (attempts <= 0) {
            // Couldn't read group details, but the GO is likely up. Fall back to
            // the known default address and whatever credentials we configured.
            val gw = detectHotspotAddress(context) ?: DEFAULT_GO_IP
            Log.d("ShadowHotspot", "P2P GO creds: (group info unavailable) ssid='$configuredSsid' gw=$gw")
            active = true
            onStarted(HotspotInfo(configuredSsid, configuredPassword, gw))
            return
        }
        mgr.requestGroupInfo(ch) { g ->
            if (g == null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    requestGroupWithRetry(mgr, ch, context, onStarted, attempts - 1)
                }, 300)
            } else {
                group = g
                active = true
                onStarted(currentInfo(context))
            }
        }
    }

    /** Stops the GO by removing the P2P group. */
    fun stop() {
        val mgr = p2p
        val ch = channel
        if (mgr != null && ch != null) {
            runCatching {
                mgr.removeGroup(
                    ch,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {}
                        override fun onFailure(reason: Int) {}
                    },
                )
            }
        }
        group = null
        active = false
    }

    /**
     * Number of clients currently associated with the GO. Used to lazily start
     * the Shadowsocks server only when a client is present (power saving).
     * Refreshes the cached group info and resumes with the live client count.
     */
    suspend fun clientCount(context: Context): Int = suspendCancellableCoroutine { cont ->
        val mgr = p2p
        val ch = channel
        if (mgr == null || ch == null) {
            cont.resume(0)
            return@suspendCancellableCoroutine
        }
        mgr.requestGroupInfo(ch) { g ->
            // Only cache the group when present; a transient null must NOT clear
            // our active state (see [isActive] / the ViewModel polling loop).
            if (g != null) group = g
            Log.d("ShadowHotspot", "clientCount=${g?.clientList?.size ?: 0} active=$active")
            cont.resume(g?.clientList?.size ?: 0)
        }
    }

    /** Reads the current SSID/password/gateway, preferring configured values. */
    fun currentInfo(context: Context): HotspotInfo {
        val g = group
        // With NEARBY_WIFI_DEVICES declared neverForLocation, getNetworkName()/
        // getPassphrase() are redacted to empty, so fall back to what we set.
        val ssid = g?.networkName?.takeIf { it.isNotBlank() } ?: configuredSsid
        val password = g?.passphrase?.takeIf { it.isNotBlank() } ?: configuredPassword
        val gateway = detectHotspotAddress(context) ?: DEFAULT_GO_IP
        Log.d(
            "ShadowHotspot",
            "P2P GO creds: ssid='$ssid' passLen=${password.length} gw=$gateway",
        )
        return HotspotInfo(ssid, password, gateway)
    }

    /**
     * Returns the IPv4 address of the `p2p0` (Wi-Fi Direct) interface — the GO
     * address clients use to reach the Shadowsocks server. Falls back to the
     * standard `192.168.49.1` if the interface isn't readable yet.
     */
    fun detectHotspotAddress(context: Context): String? {
        return try {
            val enumeration = NetworkInterface.getNetworkInterfaces() ?: return null
            val interfaces: List<NetworkInterface> = Collections.list(enumeration)
            for (nif in interfaces) {
                val name: String = nif.name?.lowercase() ?: continue
                if (!nif.isUp || !name.startsWith("p2p")) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.")) return ip
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
