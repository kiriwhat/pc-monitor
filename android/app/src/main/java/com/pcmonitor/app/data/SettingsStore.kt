package com.pcmonitor.app.data

import android.content.Context
import android.content.SharedPreferences

/** 设置持久化（SharedPreferences，无需额外依赖） */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pc_monitor_settings", Context.MODE_PRIVATE)

    var mode: String
        get() = prefs.getString("mode", "relay") ?: "relay"
        set(v) = prefs.edit().putString("mode", v).apply()

    var serverUrl: String
        get() = prefs.getString("serverUrl", "") ?: ""
        set(v) = prefs.edit().putString("serverUrl", v).apply()

    var localUrl: String
        get() = prefs.getString("localUrl", "") ?: ""
        set(v) = prefs.edit().putString("localUrl", v).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    var deviceId: String
        get() = prefs.getString("deviceId", "default") ?: "default"
        set(v) = prefs.edit().putString("deviceId", v).apply()

    var intervalMs: Int
        get() = prefs.getInt("intervalMs", 2000)
        set(v) = prefs.edit().putInt("intervalMs", v).apply()

    fun load(): ConnectionSettings =
        ConnectionSettings(mode, serverUrl, localUrl, token, deviceId, intervalMs)

    fun save(s: ConnectionSettings) {
        mode = s.mode
        serverUrl = s.serverUrl
        localUrl = s.localUrl
        token = s.token
        deviceId = s.deviceId
        intervalMs = s.intervalMs
    }
}
