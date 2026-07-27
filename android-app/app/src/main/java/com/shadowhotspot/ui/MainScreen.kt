package com.shadowhotspot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.widget.ImageView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    onConfigChange: ((com.shadowhotspot.data.AppConfig) -> com.shadowhotspot.data.AppConfig) -> Unit,
    onGeneratePassword: () -> Unit,
    onGenerateApPassword: () -> Unit,
    onStartAll: () -> Unit,
    onStopAll: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("ShadowHotspot") }) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ControlCard(state, onStartAll, onStopAll)
            StatusCard(state)
            ConfigCard(state, onConfigChange, onGeneratePassword)
            ApSettingsCard(state, onConfigChange, onGenerateApPassword)
            QrCard(state)
            if (state.lastLog.isNotBlank()) LogCard(state.lastLog)
        }
    }
}

@Composable
private fun StatusCard(state: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            StatusRow("Wi-Fi AP", if (state.hotspotActive) "ON" else "off")
            StatusRow(
                "Shadowsocks server",
                when {
                    !state.hotspotActive -> "off"
                    state.serverRunning -> "running (${state.clientCount} client(s))"
                    else -> "idle (starts on connect)"
                },
            )
            StatusRow("Server binary", if (state.binaryAvailable) "bundled" else "MISSING")
            StatusRow("Server address", "${state.hotspotAddress ?: "—"}:${state.config.serverPort}")
            if (state.hotspotActive) {
                StatusRow("Data used", formatBytes(state.dataUsed))
            }
            if (state.serverRunning) {
                StatusRow("Speed ↓ (to server)", formatSpeed(state.rxSpeed))
                StatusRow("Speed ↑ (from server)", formatSpeed(state.txSpeed))
            }
            val apInvalid = state.config.hotspotSsid.isNotBlank() &&
                !isApPasswordValid(state.config.hotspotPassword)
            if (apInvalid) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "AP password must be 8–63 printable characters to use a custom " +
                        "network; otherwise leave both AP name & password blank so Android " +
                        "generates one. With an invalid password the Wi-Fi AP will not start.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigCard(
    state: UiState,
    onConfigChange: ((com.shadowhotspot.data.AppConfig) -> com.shadowhotspot.data.AppConfig) -> Unit,
    onGeneratePassword: () -> Unit,
) {
    val cfg = state.config
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Shadowsocks server config", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = cfg.serverPort.toString(),
                onValueChange = { v ->
                    val p = v.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0
                    onConfigChange { it.copy(serverPort = p) }
                },
                label = { Text("Server port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = cfg.password,
                    onValueChange = { v -> onConfigChange { it.copy(password = v) } },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onGeneratePassword) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Generate password")
                }
            }

            MethodDropdown(cfg.method) { m -> onConfigChange { it.copy(method = m) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApSettingsCard(
    state: UiState,
    onConfigChange: ((com.shadowhotspot.data.AppConfig) -> com.shadowhotspot.data.AppConfig) -> Unit,
    onGenerateApPassword: () -> Unit,
) {
    val cfg = state.config
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Wi-Fi AP name & password", style = MaterialTheme.typography.titleMedium)
            Text(
                "Set a custom network name (auto-prefixed with DIRECT-) and an 8–63 " +
                    "character password. Leave both blank to let Android generate a " +
                    "random network instead.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = cfg.hotspotSsid,
                onValueChange = { v -> onConfigChange { it.copy(hotspotSsid = v) } },
                label = { Text("Network name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = cfg.hotspotPassword,
                    onValueChange = { v -> onConfigChange { it.copy(hotspotPassword = v) } },
                    label = { Text("AP password (8-63)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onGenerateApPassword) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Generate AP password")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrCard(state: UiState) {
    val address = state.hotspotAddress
    if (!state.hotspotActive || address == null) return
    val cfg = state.config
    val uri = remember(address, cfg.serverPort, cfg.method, cfg.password) {
        com.shadowhotspot.ss.ShadowsocksUri.build(
            method = cfg.method,
            password = cfg.password,
            host = address,
            port = cfg.serverPort,
        )
    }
    val bitmap = remember(uri) { QrGenerator.encode(uri, 512) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Scan to import (Shadowsocks client)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Join this phone's Wi-Fi ($address) first, then scan with a Shadowsocks " +
                    "client (Shadowrocket, v2rayNG, NekoBox, …) to import automatically.",
                style = MaterialTheme.typography.bodySmall,
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    AndroidView(factory = { ImageView(it).apply { setImageBitmap(bitmap) } })
                } else {
                    Text("QR unavailable", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(uri, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MethodDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("Encryption method") },
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select method")
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            com.shadowhotspot.data.AppConfig.SUPPORTED_METHODS.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m) },
                    onClick = { onSelect(m); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ControlCard(
    state: UiState,
    onStartAll: () -> Unit,
    onStopAll: () -> Unit,
) {
    val running = state.hotspotActive || state.serverRunning
    val canStart = state.config.password.isNotBlank() && state.binaryAvailable

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Wi-Fi access point", style = MaterialTheme.typography.titleMedium)
            Text(
                "Creates a local Wi-Fi network (no internet uplink) so clients can join " +
                    "and reach this phone's Shadowsocks server. You can set a custom " +
                    "network name & password below; otherwise Android generates one. " +
                    "The server only starts once a client connects, and shuts down 5 " +
                        "seconds after the last client disconnects to save power.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = state.hotspotSsid,
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text("Network name (SSID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.hotspotPassword,
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Clients connect to: ${state.hotspotAddress ?: "—"}:${state.config.serverPort}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.hotspotActive && state.hotspotAddress == null) {
                Text(
                    "AP is on but no gateway yet. On this device the hotspot can't run " +
                        "while Wi‑Fi is connected to another network — disconnect from " +
                        "Wi‑Fi (stay on cellular) and tap Stop then Start again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (running) {
                OutlinedButton(onClick = onStopAll, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop Wi-Fi AP & server")
                }
            } else {
                ElevatedButton(
                    onClick = onStartAll,
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start Wi-Fi AP & server")
                }
            }
        }
    }
}

@Composable
private fun LogCard(log: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Last log", style = MaterialTheme.typography.titleMedium)
            Text(log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** A custom Wi-Fi AP passphrase must be 8-63 printable ASCII characters. */
private fun isApPasswordValid(pw: String): Boolean {
    if (pw.length !in 8..63) return false
    return pw.all { it.code in 32..126 }
}

/** Formats a bytes-per-second value as a human-readable data rate. */
private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 B/s"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var v = bytesPerSec.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return "%.1f %s".format(v, units[i])
}

/** Formats a cumulative byte count (e.g. total data used) as a human-readable size. */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return "%.1f %s".format(v, units[i])
}
