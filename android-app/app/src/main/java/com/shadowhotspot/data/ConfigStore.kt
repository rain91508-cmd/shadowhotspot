package com.shadowhotspot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shadowhotspot")

/** Persists [AppConfig] via Jetpack DataStore. */
class ConfigStore(private val context: Context) {

    private object Keys {
        val PORT = intPreferencesKey("server_port")
        val PASSWORD = stringPreferencesKey("password")
        val METHOD = stringPreferencesKey("method")
        val SSID = stringPreferencesKey("hotspot_ssid")
        val HOTSPOT_PW = stringPreferencesKey("hotspot_password")
    }

    val config: Flow<AppConfig> = context.dataStore.data.map { p ->
        AppConfig(
            serverPort = p[Keys.PORT] ?: 8388,
            password = p[Keys.PASSWORD] ?: "",
            method = p[Keys.METHOD] ?: "aes-256-gcm",
            hotspotSsid = p[Keys.SSID] ?: "ShadowHotspot",
            hotspotPassword = p[Keys.HOTSPOT_PW] ?: "",
        )
    }

    suspend fun save(config: AppConfig) {
        context.dataStore.edit { p ->
            p[Keys.PORT] = config.serverPort
            p[Keys.PASSWORD] = config.password
            p[Keys.METHOD] = config.method
            p[Keys.SSID] = config.hotspotSsid
            p[Keys.HOTSPOT_PW] = config.hotspotPassword
        }
    }
}
