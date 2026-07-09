package com.roco.capture.notify.data

import android.content.Context
import com.roco.capture.notify.model.AppSettings
import com.roco.capture.notify.model.NotifyMode

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("capture_notify_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val port = prefs.getInt(KEY_PORT, 0).takeIf { it > 0 }
        return AppSettings(
            helperIp = prefs.getString(KEY_IP, "") ?: "",
            helperPort = port,
            targetNotifyMode = NotifyMode.fromName(prefs.getString(KEY_TARGET_NOTIFY_MODE, null)),
            lowSpeedNotifyMode = NotifyMode.fromName(prefs.getString(KEY_LOW_SPEED_NOTIFY_MODE, null)),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_IP, settings.helperIp.trim())
            .putInt(KEY_PORT, settings.helperPort ?: 0)
            .putString(KEY_TARGET_NOTIFY_MODE, settings.targetNotifyMode.name)
            .putString(KEY_LOW_SPEED_NOTIFY_MODE, settings.lowSpeedNotifyMode.name)
            .apply()
    }

    fun saveRecentTask(targetCount: Int, minRate: Double, uid: String?, targetName: String?) {
        prefs.edit()
            .putInt(KEY_RECENT_TARGET_COUNT, targetCount)
            .putFloat(KEY_RECENT_MIN_RATE, minRate.toFloat())
            .putString(KEY_RECENT_UID, uid)
            .putString(KEY_RECENT_TARGET_NAME, targetName)
            .apply()
    }

    fun recentTargetCount(): Int = prefs.getInt(KEY_RECENT_TARGET_COUNT, 1).coerceAtLeast(1)

    fun recentMinRate(): Double = prefs.getFloat(KEY_RECENT_MIN_RATE, 0f).toDouble().coerceAtLeast(0.0)

    fun recentUid(): String? = prefs.getString(KEY_RECENT_UID, null)

    fun recentTargetName(): String? = prefs.getString(KEY_RECENT_TARGET_NAME, null)

    private companion object {
        const val KEY_IP = "helper_ip"
        const val KEY_PORT = "helper_port"
        const val KEY_TARGET_NOTIFY_MODE = "target_notify_mode"
        const val KEY_LOW_SPEED_NOTIFY_MODE = "low_speed_notify_mode"
        const val KEY_RECENT_TARGET_COUNT = "recent_target_count"
        const val KEY_RECENT_MIN_RATE = "recent_min_rate"
        const val KEY_RECENT_UID = "recent_uid"
        const val KEY_RECENT_TARGET_NAME = "recent_target_name"
    }
}
