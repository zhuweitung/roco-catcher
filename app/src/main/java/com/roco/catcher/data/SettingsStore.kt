package com.roco.catcher.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.roco.catcher.model.AppSettings
import com.roco.catcher.model.NotifyMode
import com.roco.catcher.model.RecentTaskSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.settingsDataStore by preferencesDataStore(name = "roco_catcher_settings")

class SettingsStore(private val context: Context) {
    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val port = prefs[KEY_PORT]?.takeIf { it > 0 }
        AppSettings(
            helperIp = prefs[KEY_IP].orEmpty(),
            helperPort = port,
            targetNotifyMode = NotifyMode.fromName(prefs[KEY_TARGET_NOTIFY_MODE]),
            lowSpeedNotifyMode = NotifyMode.fromName(prefs[KEY_LOW_SPEED_NOTIFY_MODE]),
        )
    }

    val recentTaskFlow: Flow<RecentTaskSettings> = context.settingsDataStore.data.map { prefs ->
        RecentTaskSettings(
            targetCount = (prefs[KEY_RECENT_TARGET_COUNT] ?: 1).coerceAtLeast(1),
            minRatePerMinute = (prefs[KEY_RECENT_MIN_RATE] ?: 0.0).coerceAtLeast(0.0),
            uid = prefs[KEY_RECENT_UID],
            targetName = prefs[KEY_RECENT_TARGET_NAME],
        )
    }

    suspend fun load(): AppSettings = settingsFlow.first()

    fun loadBlocking(): AppSettings = runBlocking { load() }

    suspend fun loadRecentTask(): RecentTaskSettings = recentTaskFlow.first()

    suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_IP] = settings.helperIp.trim()
            prefs[KEY_PORT] = settings.helperPort ?: 0
            prefs[KEY_TARGET_NOTIFY_MODE] = settings.targetNotifyMode.name
            prefs[KEY_LOW_SPEED_NOTIFY_MODE] = settings.lowSpeedNotifyMode.name
        }
    }

    suspend fun saveRecentTask(targetCount: Int, minRate: Double, uid: String?, targetName: String?) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_RECENT_TARGET_COUNT] = targetCount
            prefs[KEY_RECENT_MIN_RATE] = minRate
            uid?.let { prefs[KEY_RECENT_UID] = it } ?: prefs.remove(KEY_RECENT_UID)
            targetName?.let { prefs[KEY_RECENT_TARGET_NAME] = it } ?: prefs.remove(KEY_RECENT_TARGET_NAME)
        }
    }

    private companion object {
        val KEY_IP = stringPreferencesKey("helper_ip")
        val KEY_PORT = intPreferencesKey("helper_port")
        val KEY_TARGET_NOTIFY_MODE = stringPreferencesKey("target_notify_mode")
        val KEY_LOW_SPEED_NOTIFY_MODE = stringPreferencesKey("low_speed_notify_mode")
        val KEY_RECENT_TARGET_COUNT = intPreferencesKey("recent_target_count")
        val KEY_RECENT_MIN_RATE = doublePreferencesKey("recent_min_rate")
        val KEY_RECENT_UID = stringPreferencesKey("recent_uid")
        val KEY_RECENT_TARGET_NAME = stringPreferencesKey("recent_target_name")
    }
}

