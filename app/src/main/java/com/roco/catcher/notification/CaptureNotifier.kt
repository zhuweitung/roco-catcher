package com.roco.catcher.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.roco.catcher.MainActivity
import com.roco.catcher.model.AppSettings
import com.roco.catcher.model.CaptureTaskState
import com.roco.catcher.monitor.CaptureAlertSink
import com.roco.catcher.monitor.CaptureTaskManager
import java.util.Locale

class CaptureNotifier(
    private val context: Context,
    private val settingsProvider: () -> AppSettings,
) : CaptureAlertSink {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        NotificationChannels.ensure(context)
    }

    override fun onTargetReached(state: CaptureTaskState) {
        val settings = settingsProvider()
        if (!settings.targetNotifyEnabled) return
        val config = state.config ?: return
        val notification = baseBuilder(NotificationChannels.TARGET_REACHED)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("捕获目标已达成")
            .setContentText("${config.target.displayName} 已捕获 ${state.caughtCount}/${config.targetCount}")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "${config.target.displayName} 已捕获 ${state.caughtCount}/${config.targetCount}，监听会继续运行，可手动暂停。",
                ),
            )
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(TARGET_REACHED_ID, notification)
    }

    override fun onLowSpeed(state: CaptureTaskState, currentRate: Double) {
        val settings = settingsProvider()
        if (!settings.lowSpeedNotifyEnabled) return
        val config = state.config ?: return
        val rate = formatRate(currentRate)
        val minRate = formatRate(config.minRatePerMinute)
        val notification = baseBuilder(NotificationChannels.LOW_SPEED)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("捕获速率偏低")
            .setContentText("${config.target.displayName} 当前 $rate/分钟，低于 $minRate/分钟")
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(LOW_SPEED_ID, notification)
    }

    fun monitorNotification(state: CaptureTaskState): Notification {
        val config = state.config
        val currentRate = formatRate(CaptureTaskManager.currentRate())
        val text = if (config == null) {
            "未开始监听"
        } else {
            "${config.target.displayName} ${state.caughtCount}/${config.targetCount}，当前 $currentRate/分钟"
        }
        return baseBuilder(NotificationChannels.MONITOR_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("洛克捕手：${state.status.label}")
            .setContentText(text)
            .setOngoing(state.status != com.roco.catcher.model.TaskStatus.Paused)
            .build()
    }

    private fun baseBuilder(channelId: String): Notification.Builder {
        return Notification.Builder(context, channelId)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    private fun notifyIfAllowed(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(id, notification)
    }

    private fun formatRate(value: Double): String {
        return String.format(Locale.US, "%.1f", value)
    }

    private companion object {
        const val TARGET_REACHED_ID = 2001
        const val LOW_SPEED_ID = 2002
    }
}

