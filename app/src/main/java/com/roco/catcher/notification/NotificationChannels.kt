package com.roco.catcher.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.roco.catcher.model.NotifyMode

object NotificationChannels {
    const val MONITOR_STATUS = "monitor_status"

    fun targetReached(mode: NotifyMode): String = "target_reached_${mode.name.lowercase()}"

    fun lowSpeed(mode: NotifyMode): String = "low_speed_${mode.name.lowercase()}"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
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

        NotifyMode.entries.forEach { mode ->
            manager.createNotificationChannel(
                alertChannel(
                    id = targetReached(mode),
                    name = "目标达成提醒 - ${mode.label}",
                    description = "捕获数量达到目标时提醒",
                    mode = mode,
                ),
            )
            manager.createNotificationChannel(
                alertChannel(
                    id = lowSpeed(mode),
                    name = "低速提醒 - ${mode.label}",
                    description = "当前捕获速率低于阈值时提醒",
                    mode = mode,
                ),
            )
        }
    }

    private fun alertChannel(
        id: String,
        name: String,
        description: String,
        mode: NotifyMode,
    ): NotificationChannel {
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
        channel.description = description

        val shouldSound = mode == NotifyMode.Sound || mode == NotifyMode.SoundAndVibrate
        val shouldVibrate = mode == NotifyMode.Vibrate || mode == NotifyMode.SoundAndVibrate

        if (shouldSound) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            channel.setSound(uri, attributes)
        } else {
            channel.setSound(null, null)
        }

        channel.enableVibration(shouldVibrate)
        if (shouldVibrate) {
            channel.vibrationPattern = longArrayOf(0L, 350L, 150L, 350L)
        }
        return channel
    }
}

