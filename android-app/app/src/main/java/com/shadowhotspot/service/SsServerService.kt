package com.shadowhotspot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.shadowhotspot.MainActivity
import com.shadowhotspot.R
import com.shadowhotspot.data.AppConfig
import com.shadowhotspot.ss.SsBinary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File

/**
 * Foreground service that launches and supervises the bundled `ssserver` process.
 *
 * It is started by the ViewModel in two modes:
 *  - [ACTION_IDLE]: brings the service to the foreground (so Android won't kill
 *    it as a background task) but does NOT spawn ssserver yet. Used while the
 *    hotspot is up but no client is connected (power saving).
 *  - [ACTION_START]: brings it to the foreground AND spawns ssserver. Used once a
 *    Wi-Fi Direct client has joined (lazy activation).
 *  - [ACTION_STOP]: tears everything down.
 */
class SsServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_IDLE -> {
                startForegroundCompat(0, idle = true)
                stopServer()
            }
            else -> {
                val cfg = AppConfig(
                    serverPort = intent?.getIntExtra(EXTRA_PORT, 8388) ?: 8388,
                    password = intent?.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    method = intent?.getStringExtra(EXTRA_METHOD) ?: "aes-256-gcm",
                )
                startForegroundCompat(cfg.serverPort, idle = false)
                startServer(cfg)
            }
        }
        return START_STICKY
    }

    private fun startServer(cfg: AppConfig) {
        stopServer()
        running = true
        scope.launch {
            try {
                val bin = SsBinary.executablePath(this@SsServerService)
                val confFile: File = SsBinary.writeConfig(this@SsServerService, cfg)
                val pb = ProcessBuilder(bin, "-c", confFile.absolutePath)
                    .redirectErrorStream(true)
                pb.directory(filesDir)
                val p = pb.start()
                process = p
                // Drain output to logcat-friendly buffer so the pipe never blocks.
                p.inputStream.bufferedReader().use { r: BufferedReader ->
                    var line = r.readLine()
                    while (line != null) {
                        lastLog = line
                        line = r.readLine()
                    }
                }
                p.waitFor()
            } catch (t: Throwable) {
                lastLog = "ssserver error: ${t.message}"
            } finally {
                running = false
            }
        }
    }

    private fun stopServer() {
        process?.destroy()
        process = null
        running = false
    }

    private fun startForegroundCompat(port: Int, idle: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Shadowsocks server",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(ch)
        }
        val stopPi = android.app.PendingIntent.getService(
            this, 1,
            Intent(this, SsServerService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val contentPi = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (idle) {
            "Idle — waiting for a client to connect"
        } else {
            "Listening on 0.0.0.0:$port"
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shadowsocks")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(contentPi)
            .addAction(0, "Stop", stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.shadowhotspot.STOP"
        const val ACTION_START = "com.shadowhotspot.START"
        const val ACTION_IDLE = "com.shadowhotspot.IDLE"
        const val EXTRA_PORT = "port"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_METHOD = "method"

        private const val CHANNEL_ID = "ss_server"
        private const val NOTIF_ID = 42

        /** Simple observable state for the UI (single-service app). */
        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastLog: String = ""
            private set

        /** Bring the service to the foreground but do NOT start ssserver yet. */
        fun startIdle(context: Context) {
            val i = Intent(context, SsServerService::class.java).apply {
                action = ACTION_IDLE
            }
            context.startForegroundService(i)
        }

        fun start(context: Context, cfg: AppConfig) {
            val i = Intent(context, SsServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, cfg.serverPort)
                putExtra(EXTRA_PASSWORD, cfg.password)
                putExtra(EXTRA_METHOD, cfg.method)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, SsServerService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
