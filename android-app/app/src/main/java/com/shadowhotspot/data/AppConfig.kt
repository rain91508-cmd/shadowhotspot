package com.shadowhotspot.data

/**
 * All user-editable settings for the app.
 *
 * The hotspot fields (`hotspotSsid`, `hotspotPassword`) are used as the custom
 * Wi-Fi Direct Group Owner credentials when both are valid (name non-blank,
 * passphrase 8-63 chars). Otherwise the GO is created without a config and
 * Android auto-generates the network.
 */
data class AppConfig(
    // Shadowsocks server
    val serverPort: Int = 8388,
    val password: String = "",
    val method: String = "aes-256-gcm",
    // Custom Wi-Fi AP credentials (applied via WifiP2pConfig on Android 13+)
    val hotspotSsid: String = "ShadowHotspot",
    val hotspotPassword: String = "",
) {
    companion object {
        val SUPPORTED_METHODS = listOf(
            "aes-256-gcm",
            "aes-128-gcm",
            "chacha20-ietf-poly1305",
            "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305",
        )
    }
}
