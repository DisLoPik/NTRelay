package com.dislopik.ntrealy.ui

import android.content.Context
import android.content.SharedPreferences
import com.dislopik.ntrealy.protocol.NTRCommands
import com.dislopik.ntrealy.protocol.ScreenId
import com.dislopik.ntrealy.render.ColorMode

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ntrelay_settings", Context.MODE_PRIVATE)

    fun load(): StreamConfig = StreamConfig(
        host = prefs.getString(KEY_HOST, "") ?: "",
        viewerPort = prefs.getInt(KEY_VIEWER_PORT, NTRCommands.UDP_PORT),
        quality = prefs.getInt(KEY_QUALITY, 70),
        priority = prefs.getInt(KEY_PRIORITY, 4),
        qos = prefs.getInt(KEY_QOS, 16),
        priorityScreen = runCatching { ScreenId.valueOf(prefs.getString(KEY_PRIORITY_SCREEN, "TOP") ?: "TOP") }
            .getOrDefault(ScreenId.TOP),
        colorMode = runCatching { ColorMode.valueOf(prefs.getString(KEY_COLOR_MODE, "REGULAR") ?: "REGULAR") }
            .getOrDefault(ColorMode.REGULAR),
    )

    fun save(config: StreamConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_VIEWER_PORT, config.viewerPort)
            .putInt(KEY_QUALITY, config.quality)
            .putInt(KEY_PRIORITY, config.priority)
            .putInt(KEY_QOS, config.qos)
            .putString(KEY_PRIORITY_SCREEN, config.priorityScreen.name)
            .putString(KEY_COLOR_MODE, config.colorMode.name)
            .apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        // Not the old "port" key, which held the TCP port (8000)
        const val KEY_VIEWER_PORT = "viewerPort"
        const val KEY_QUALITY = "quality"
        const val KEY_PRIORITY = "priority"
        const val KEY_QOS = "qos"
        const val KEY_PRIORITY_SCREEN = "priorityScreen"
        const val KEY_COLOR_MODE = "colorMode"
    }
}
