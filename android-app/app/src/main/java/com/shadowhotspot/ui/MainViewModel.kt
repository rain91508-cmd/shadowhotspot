package com.shadowhotspot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shadowhotspot.data.AppConfig
import com.shadowhotspot.data.ConfigStore
import com.shadowhotspot.hotspot.HotspotController
import com.shadowhotspot.monitor.TrafficMonitor
import com.shadowhotspot.service.SsServerService
import com.shadowhotspot.ss.SsBinary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.SecureRandom
import android.util.Base64

/** Stop ssserver this long after the last client disconnects (power saving). */
private const val IDLE_TIMEOUT_MS = 5_000L

data class UiState(
    val config: AppConfig = AppConfig(),
    val serverRunning: Boolean = false,
    val binaryAvailable: Boolean = false,
    val hotspotAddress: String? = null,
    val hotspotActive: Boolean = false,
    val hotspotSsid: String = "",
    val hotspotPassword: String = "",
    val clientCount: Int = 0,
    val rxSpeed: Long = 0,
    val txSpeed: Long = 0,
    val dataUsed: Long = 0,
    val lastLog: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConfigStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Cumulative (RX+TX) bytes counted by [TrafficMonitor] at the moment the
     * user last pressed Start. Data used since then = current total − this value.
     */
    @Volatile
    private var startBaselineBytes = 0L

    init {
        viewModelScope.launch {
            val cfg = store.config.first()
            _state.value = _state.value.copy(
                config = cfg,
                binaryAvailable = SsBinary.isAvailable(getApplication()),
            )
        }
        // Lightweight polling loop to reflect service + hotspot state in the UI, and
        // to lazily start/stop the Shadowsocks server based on connected clients.
        viewModelScope.launch {
            var idleSince: Long? = null
            var prevSample = TrafficMonitor.sample()
            var prevSampleTime = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()
                // Re-attach after a process restart so we can re-discover the
                // (still-running) Group Owner and recover its SSID/password.
                HotspotController.attach(getApplication())
                // Always query client/group state — this refreshes the cached group
                // even right after a restart, so isActive() below is accurate.
                val clients = HotspotController.clientCount(getApplication())
                val active = HotspotController.isActive()
                val cfg = _state.value.config
                val serverUp = SsServerService.running

                // Throughput of the server: delta of cumulative bytes over the
                // elapsed time since the previous sample.
                val sample = TrafficMonitor.sample()
                val dt = (now - prevSampleTime).coerceAtLeast(1)
                val rxSpeed = (sample.rxBytes - prevSample.rxBytes).coerceAtLeast(0) * 1000 / dt
                val txSpeed = (sample.txBytes - prevSample.txBytes).coerceAtLeast(0) * 1000 / dt
                prevSample = sample
                prevSampleTime = now

                // Total data pushed through the server since the last Start press.
                val dataUsed = ((sample.rxBytes + sample.txBytes) - startBaselineBytes).coerceAtLeast(0)

                if (active && clients > 0) {
                    // A client is associated -> make sure the server is up.
                    idleSince = null
                    if (!serverUp) SsServerService.start(getApplication(), cfg)
                } else if (active && clients == 0 && serverUp) {
                    // No clients -> stop the server after an idle grace period.
                    if (idleSince == null) idleSince = now
                    if (now - idleSince > IDLE_TIMEOUT_MS) {
                        SsServerService.stop(getApplication())
                    }
                } else {
                    idleSince = null
                }

                // Recover the displayed SSID/password. After a process restart the
                // cached values are gone and the OS redacts group.networkName/
                // passphrase, so fall back to the persisted config — but ONLY when a
                // custom profile was actually applied (valid name + 8-63 char
                // passphrase). For an auto-generated AP the real name is unknown, so
                // we leave it blank rather than fabricating one from the default.
                val customApplied =
                    cfg.hotspotSsid.isNotBlank() && cfg.hotspotPassword.length in 8..63
                val info = if (active) HotspotController.currentInfo(getApplication()) else null
                val dispSsid = if (active) {
                    info?.ssid?.takeIf { it.isNotBlank() }
                        ?: (if (customApplied) HotspotController.ensureDirectPrefix(cfg.hotspotSsid) else "")
                } else ""
                val dispPassword = if (active) {
                    info?.password?.takeIf { it.isNotBlank() }
                        ?: (if (customApplied) cfg.hotspotPassword else "")
                } else ""

                _state.value = _state.value.copy(
                    serverRunning = SsServerService.running,
                    hotspotAddress = HotspotController.detectHotspotAddress(getApplication()),
                    hotspotActive = active,
                    hotspotSsid = dispSsid,
                    hotspotPassword = dispPassword,
                    clientCount = clients,
                    rxSpeed = rxSpeed,
                    txSpeed = txSpeed,
                    dataUsed = dataUsed,
                    lastLog = SsServerService.lastLog,
                )
                delay(1500)
            }
        }
    }

    fun updateConfig(transform: (AppConfig) -> AppConfig) {
        val newCfg = transform(_state.value.config)
        _state.value = _state.value.copy(config = newCfg)
        // Persist off the UI thread. The previous implementation used
        // `runBlocking { store.save(...) }` on the main thread, which blocks input
        // on every keystroke and, on this ROM, could drop the final write — so the
        // manually-typed Wi-Fi (AP) password appeared forgotten after a reopen. The
        // OS redacts the live group passphrase, so display recovery depends entirely
        // on this persisted value being durable.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.save(newCfg) }
        }
    }

    fun generatePassword() {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pw = Base64.encodeToString(bytes, Base64.NO_WRAP)
        updateConfig { it.copy(password = pw) }
    }

    /** Generates a random 12-char Wi-Fi AP passphrase (valid WPA2-PSK length). */
    fun generateApPassword() {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val pw = (1..12).map { chars.random() }.joinToString("")
        updateConfig { it.copy(hotspotPassword = pw) }
    }

    /**
     * Master ON: bring up the Wi-Fi Direct AP (GO) and put the foreground
     * service into idle mode (protected from being killed, but ssserver is only
     * spawned once a client actually connects — see the polling loop).
     */
    fun startAll() {
        val cfg = _state.value.config
        if (cfg.password.isBlank()) {
            _state.value = _state.value.copy(lastLog = "Set a Shadowsocks password first")
            return
        }
        if (!_state.value.binaryAvailable) {
            _state.value = _state.value.copy(lastLog = "Shadowsocks binary missing")
            return
        }
        // Decide whether to apply a custom AP profile. Requires a non-blank name and
        // a valid WPA2-PSK passphrase (8-63 chars). Otherwise Android auto-generates.
        val apSsid = cfg.hotspotSsid.trim()
        val apPassword = cfg.hotspotPassword
        val useCustom = apSsid.isNotBlank() && apPassword.length in 8..63
        if (apSsid.isNotBlank() && apPassword.length !in 8..63) {
            _state.value = _state.value.copy(
                lastLog = "AP password must be 8-63 characters, or leave both AP fields blank for an auto-generated network.",
            )
            return
        }
        HotspotController.start(
            getApplication(),
            ssid = if (useCustom) apSsid else null,
            password = if (useCustom) apPassword else null,
            onStarted = { info ->
                // Begin counting data used from this moment.
                startBaselineBytes = run {
                    val s = TrafficMonitor.sample()
                    s.rxBytes + s.txBytes
                }
                _state.value = _state.value.copy(
                    hotspotActive = true,
                    hotspotSsid = info?.ssid ?: "",
                    hotspotPassword = info?.password ?: "",
                    dataUsed = 0,
                    lastLog = if (info?.ssid.isNullOrBlank())
                        "Wi-Fi AP started (auto-generated network)"
                    else
                        "Wi-Fi AP started: ${info?.ssid}",
                )
            },
            onFailed = { reason ->
                _state.value = _state.value.copy(
                    hotspotActive = false,
                    lastLog = "Wi-Fi AP failed (code $reason). " +
                        "Disconnect from any Wi-Fi network (stay on cellular) and tap Stop then Start.",
                )
            },
        )
        SsServerService.startIdle(getApplication())
    }

    /** Master OFF: stop the server and tear down the AP. */
    fun stopAll() {
        SsServerService.stop(getApplication())
        HotspotController.stop()
        startBaselineBytes = 0L
        _state.value = _state.value.copy(
            hotspotActive = false,
            hotspotSsid = "",
            hotspotPassword = "",
            clientCount = 0,
            serverRunning = false,
            dataUsed = 0,
        )
    }
}
