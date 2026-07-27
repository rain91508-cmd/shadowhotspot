package com.shadowhotspot.monitor

import android.net.TrafficStats
import android.os.Process

/**
 * Measures the live data rate of the traffic handled by this app's processes —
 * i.e. the bundled Shadowsocks server (ssserver runs under this app's UID, so
 * its inbound/outbound bytes are exactly the traffic flowing "through the
 * server").
 *
 * Primary source: [TrafficStats] per-UID counters. If those are unavailable
 * (some ROMs return -1), it falls back to the Wi-Fi Direct interface (`p2p0`)
 * byte counters from `/proc/net/dev` — the only client traffic on that
 * interface is to/from the server.
 */
object TrafficMonitor {

    data class Sample(val rxBytes: Long, val txBytes: Long)

    private val uid = Process.myUid()

    fun sample(): Sample {
        var rx = safe { TrafficStats.getUidRxBytes(uid) }
        var tx = safe { TrafficStats.getUidTxBytes(uid) }
        if (rx < 0 || tx < 0) {
            readP2pBytes()?.let { (r, t) -> rx = r; tx = t }
        }
        return Sample(if (rx < 0) 0 else rx, if (tx < 0) 0 else tx)
    }

    private fun safe(block: () -> Long): Long = runCatching(block).getOrDefault(-1)

    /** Sums RX/TX bytes over every up interface whose name starts with "p2p". */
    private fun readP2pBytes(): Pair<Long, Long>? = runCatching {
        val lines = java.io.File("/proc/net/dev").readLines()
        var rx = 0L
        var tx = 0L
        var found = false
        for (line in lines.drop(2)) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 10) continue
            val name = parts[0].removeSuffix(":")
            if (!name.startsWith("p2p", ignoreCase = true)) continue
            found = true
            rx += parts[1].toLongOrNull() ?: 0L   // receive bytes
            tx += parts[9].toLongOrNull() ?: 0L    // transmit bytes
        }
        if (!found) null else Pair(rx, tx)
    }.getOrNull()
}
