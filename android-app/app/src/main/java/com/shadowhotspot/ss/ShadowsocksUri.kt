package com.shadowhotspot.ss

import android.util.Base64
import java.net.URLEncoder

/**
 * Builds the standard Shadowsocks SIP002 connection URI that clients (Shadowrocket,
 * Shadowsocks for Android, v2rayNG, NekoBox, …) can scan/import:
 *
 *     ss://<base64url(method:password)>@<host>:<port>#<tag>
 *
 * The client must already be associated with this phone's Wi-Fi AP, so `host` is the
 * phone's hotspot gateway (e.g. 192.168.49.1) rather than a public address.
 */
object ShadowsocksUri {
    fun build(
        method: String,
        password: String,
        host: String,
        port: Int,
        tag: String = "ShadowHotspot",
    ): String {
        val userinfo = Base64.encodeToString(
            "$method:$password".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val frag = URLEncoder.encode(tag, "UTF-8")
        return "ss://$userinfo@$host:$port#$frag"
    }
}
