package com.roco.catcher.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

object NotificationChannels {
    const val MONITOR_STATUS = "monitor_status"
    const val TARGET_REACHED = "target_reached"
    const val LOW_SPEED = "low_speed"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        deleteLegacyAlertChannels(manager)
        manager.createNotificationChannel(
            NotificationChannel(
                MONITOR_STATUS,
                "监听状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "捕获监听前台服务状态"
                setSound(null, null)
                enableVibration(false)
            },
        )

        manager.createNotificationChannel(
            alertChannel(
                id = TARGET_REACHED,
                name = "目标达成提醒",
                description = "捕获数量达到目标时提醒",
            ),
        )
        manager.createNotificationChannel(
            alertChannel(
                id = LOW_SPEED,
                name = "低速提醒",
                description = "当前捕获速率低于阈值时提醒",
            ),
        )
    }

    private fun alertChannel(
        id: String,
        name: String,
        description: String,
    ): NotificationChannel {
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
        channel.description = description

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        channel.setSound(uri, attributes)
        channel.enableVibration(true)
        channel.vibrationPattern = longArrayOf(0L, 350L, 150L, 350L)
        return channel
    }

    private fun deleteLegacyAlertChannels(manager: NotificationManager) {
        listOf("sound", "vibrate", "soundandvibrate").forEach { mode ->
            manager.deleteNotificationChannel("target_reached_$mode")
            manager.deleteNotificationChannel("low_speed_$mode")
        }
    }
}

