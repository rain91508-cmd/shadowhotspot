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

    /**
     * Action to run once the hotspot permission is granted while the user is
     * starting the hotspot. Kept here so the permission prompt appears exactly
     * when Start is pressed, not at app launch.
     */
    private var pendingStart: (() -> Unit)? = null

    private val requestHotspotPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStart
            pendingStart = null
            if (granted) action?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()

        setContent {
            ShadowHotspotTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsState()
                MainScreen(
                    state = state,
                    onConfigChange = { transform -> vm.updateConfig(transform) },
                    onGeneratePassword = vm::generatePassword,
                    onGenerateApPassword = vm::generateApPassword,
                    onStartAll = { startWithPermission(vm::startAll) },
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

    /**
     * The permission Android requires to create a Wi-Fi hotspot on this OS version:
     *  - Android 13+ (API 33+): NEARBY_WIFI_DEVICES
     *  - Android 12 and below:   ACCESS_FINE_LOCATION (the platform gates Wi-Fi
     *    Direct / createGroup on it; NEARBY_WIFI_DEVICES didn't exist yet).
     * ACCESS_FINE_LOCATION is declared with maxSdkVersion="32", so newer phones
     * never ask for it; it is still declared because a runtime permission can only
     * be requested if it appears in the manifest.
     */
    private fun requiredHotspotPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    /**
     * Starts the hotspot, requesting the required permission first — lazily, only
     * when the user presses Start and only on the Android versions that need it.
     */
    private fun startWithPermission(start: () -> Unit) {
        val perm = requiredHotspotPermission()
        val granted = ContextCompat.checkSelfPermission(
            this, perm
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            start()
            return
        }
        pendingStart = start
        requestHotspotPerm.launch(perm)
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
