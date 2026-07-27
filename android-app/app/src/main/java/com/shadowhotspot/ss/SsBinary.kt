package com.shadowhotspot.ss

import android.content.Context
import com.shadowhotspot.data.AppConfig
import org.json.JSONObject
import java.io.File

/**
 * Locates the bundled ssserver executable and writes its runtime config.
 *
 * The binary is shipped inside the APK as `jniLibs/arm64-v8a/libssserver.so`.
 * Android extracts everything under `jniLibs` into the app's native library
 * directory ([android.content.pm.ApplicationInfo.nativeLibraryDir]) with the
 * executable bit set — this is the only reliable way to ship a runnable binary
 * that survives Android's W^X / exec restrictions on app data dirs.
 */
object SsBinary {

    /** Absolute path to the extracted, executable ssserver binary. */
    fun executablePath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libssserver.so").absolutePath

    fun isAvailable(context: Context): Boolean =
        File(executablePath(context)).canExecute()

    /** Writes an ssserver JSON config to the app's files dir and returns it. */
    fun writeConfig(context: Context, cfg: AppConfig): File {
        val json = JSONObject().apply {
            put("server", "0.0.0.0")
            put("server_port", cfg.serverPort)
            put("password", cfg.password)
            put("method", cfg.method)
            put("mode", "tcp_and_udp")
            put("timeout", 300)
            put("no_delay", true)
        }
        val file = File(context.filesDir, "ssserver.json")
        file.writeText(json.toString(2))
        return file
    }
}
