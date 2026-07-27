package com.shadowhotspot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shadowhotspot.ui.MainScreen
import com.shadowhotspot.ui.MainViewModel
import com.shadowhotspot.ui.ShadowHotspotTheme

class MainActivity : ComponentActivity() {

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val requestNearby =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        maybeRequestLocationPermission()
        maybeRequestNearbyPermission()

        setContent {
            ShadowHotspotTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsState()
                MainScreen(
                    state = state,
                    onConfigChange = { transform -> vm.updateConfig(transform) },
                    onGeneratePassword = vm::generatePassword,
                    onGenerateApPassword = vm::generateApPassword,
                    onStartAll = vm::startAll,
                    onStopAll = vm::stopAll,
                )
            }
        }
        requestIgnoreBatteryOptimizations()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun maybeRequestLocationPermission() {
        // ACCESS_FINE_LOCATION helps read the auto-generated hotspot
        // SSID/password. Without it the AP still starts, but credentials may be empty.
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun maybeRequestNearbyPermission() {
        // On Android 13+ (API 33+), startLocalOnlyHotspot() requires the
        // NEARBY_WIFI_DEVICES runtime permission, otherwise the system throws and
        // the app crashes. This is the fix for the "enable Wi-Fi" crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) requestNearby.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    /**
     * Ask the system to exempt this app from Doze / battery optimization so the
     * foreground service (and thus the Shadowsocks server + hotspot) isn't killed
     * or network-throttled while the screen is off.
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService<PowerManager>() ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (_: Throwable) {
            // Some OEMs restrict this intent; the foreground service still helps.
        }
    }
}
