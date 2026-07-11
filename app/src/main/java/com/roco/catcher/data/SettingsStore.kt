package com.roco.catcher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.roco.catcher.model.AppSettings
import com.roco.catcher.model.CaptureTaskState
import com.roco.catcher.model.RecentTaskSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "roco_catcher_settings")

class SettingsStore(private val context: Context) {
    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val port = prefs[KEY_PORT]?.takeIf { it > 0 }
        AppSettings(
            helperIp = prefs[KEY_IP].orEmpty(),
            helperPort = port,
            targetNotifyEnabled = prefs[KEY_TARGET_NOTIFY_ENABLED] ?: true,
            lowSpeedNotifyEnabled = prefs[KEY_LOW_SPEED_NOTIFY_ENABLED] ?: true,
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

    suspend fun loadActiveTask(): CaptureTaskState? {
        val encoded = context.settingsDataStore.data.first()[KEY_ACTIVE_TASK_STATE] ?: return null
        return runCatching { json.decodeFromString(CaptureTaskState.serializer(), encoded) }.getOrNull()
    }

    fun loadActiveTaskBlocking(): CaptureTaskState? = runBlocking { loadActiveTask() }

    suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_IP] = settings.helperIp.trim()
            prefs[KEY_PORT] = settings.helperPort ?: 0
            prefs[KEY_TARGET_NOTIFY_ENABLED] = settings.targetNotifyEnabled
            prefs[KEY_LOW_SPEED_NOTIFY_ENABLED] = settings.lowSpeedNotifyEnabled
            prefs.remove(KEY_TARGET_NOTIFY_MODE)
            prefs.remove(KEY_LOW_SPEED_NOTIFY_MODE)
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

    suspend fun saveActiveTask(state: CaptureTaskState) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_TASK_STATE] = json.encodeToString(CaptureTaskState.serializer(), state)
        }
    }

    private companion object {
        val KEY_IP = stringPreferencesKey("helper_ip")
        val KEY_PORT = intPreferencesKey("helper_port")
        val KEY_TARGET_NOTIFY_ENABLED = booleanPreferencesKey("target_notify_enabled")
        val KEY_LOW_SPEED_NOTIFY_ENABLED = booleanPreferencesKey("low_speed_notify_enabled")
        val KEY_TARGET_NOTIFY_MODE = stringPreferencesKey("target_notify_mode")
        val KEY_LOW_SPEED_NOTIFY_MODE = stringPreferencesKey("low_speed_notify_mode")
        val KEY_RECENT_TARGET_COUNT = intPreferencesKey("recent_target_count")
        val KEY_RECENT_MIN_RATE = doublePreferencesKey("recent_min_rate")
        val KEY_RECENT_UID = stringPreferencesKey("recent_uid")
        val KEY_RECENT_TARGET_NAME = stringPreferencesKey("recent_target_name")
        val KEY_ACTIVE_TASK_STATE = stringPreferencesKey("active_task_state")
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

