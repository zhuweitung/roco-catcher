package com.roco.catcher.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.roco.catcher.data.EvolutionChainRepository
import com.roco.catcher.data.SettingsStore
import com.roco.catcher.notification.CaptureNotifier
import kotlin.math.min

class CaptureMonitorService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settingsStore: SettingsStore
    private lateinit var chains: EvolutionChainRepository
    private lateinit var notifier: CaptureNotifier

    @Volatile
    private var shouldRun = false

    @Volatile
    private var client: HelperSseClient? = null

    private var reconnectBackoffMillis = INITIAL_RECONNECT_MILLIS

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        chains = EvolutionChainRepository(this)
        notifier = CaptureNotifier(this) { settingsStore.loadBlocking() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring(markPaused = true)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMonitoring(markPaused = false)
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (CaptureTaskManager.currentState().config == null) {
            stopSelf()
            return
        }

        shouldRun = true
        reconnectBackoffMillis = INITIAL_RECONNECT_MILLIS
        CaptureTaskManager.markConnecting()
        startForeground(FOREGROUND_ID, notifier.monitorNotification(CaptureTaskManager.currentState()))
        startTicker()
        openSse()
    }

    private fun openSse() {
        if (!shouldRun) return

        val config = CaptureTaskManager.currentState().config ?: run {
            stopSelf()
            return
        }
        val settings = settingsStore.loadBlocking()

        client?.cancel()
        CaptureTaskManager.markConnecting()
        refreshForeground()

        val nextClient = HelperSseClient(
            settings = settings,
            user = config.user,
            listener = object : HelperSseClient.Listener {
                override fun onOpen() {
                    mainHandler.post {
                        if (!shouldRun) return@post
                        reconnectBackoffMillis = INITIAL_RECONNECT_MILLIS
                        CaptureTaskManager.markConnected()
                        refreshForeground()
                    }
                }

                override fun onEvent(rawJson: String, eventName: String?) {
                    CaptureTaskManager.handleRawEvent(
                        rawJson = rawJson,
                        eventName = eventName,
                        petNameResolver = { chains.petName(it) },
                        alertSink = notifier,
                    )
                    refreshForeground()
                }

                override fun onClosed() {
                    mainHandler.post {
                        if (!shouldRun) return@post
                        CaptureTaskManager.markReconnecting("SSE 连接已断开")
                        refreshForeground()
                        scheduleReconnect()
                    }
                }

                override fun onFailure(message: String?) {
                    mainHandler.post {
                        if (!shouldRun) return@post
                        CaptureTaskManager.markReconnecting(message ?: "SSE 连接异常")
                        refreshForeground()
                        scheduleReconnect()
                    }
                }
            },
        )

        client = nextClient
        runCatching { nextClient.connect() }
            .onFailure { error ->
                CaptureTaskManager.markReconnecting(error.message ?: "SSE 连接异常")
                refreshForeground()
                scheduleReconnect()
            }
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        val delay = reconnectBackoffMillis
        reconnectBackoffMillis = min(reconnectBackoffMillis * 2, MAX_RECONNECT_MILLIS)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private val reconnectRunnable = Runnable {
        if (shouldRun) openSse()
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!shouldRun) return
            CaptureTaskManager.tick(notifier)
            refreshForeground()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun refreshForeground() {
        mainHandler.post {
            if (shouldRun) {
                val notification = notifier.monitorNotification(CaptureTaskManager.currentState())
                startForeground(FOREGROUND_ID, notification)
            }
        }
    }

    private fun stopMonitoring(markPaused: Boolean) {
        shouldRun = false
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        client?.cancel()
        client = null
        if (markPaused) {
            CaptureTaskManager.pauseByUser()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val FOREGROUND_ID = 1001
        private const val INITIAL_RECONNECT_MILLIS = 1000L
        private const val MAX_RECONNECT_MILLIS = 30_000L
        const val ACTION_START = "com.roco.catcher.action.START_MONITOR"
        const val ACTION_STOP = "com.roco.catcher.action.STOP_MONITOR"

        fun start(context: Context) {
            val intent = Intent(context, CaptureMonitorService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureMonitorService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

