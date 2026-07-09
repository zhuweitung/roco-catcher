package com.roco.capture.notify.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.roco.capture.notify.data.EvolutionChainRepository
import com.roco.capture.notify.data.SettingsStore
import com.roco.capture.notify.notification.CaptureNotifier
import java.io.IOException
import kotlin.math.min

class CaptureMonitorService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settingsStore: SettingsStore
    private lateinit var chains: EvolutionChainRepository
    private lateinit var notifier: CaptureNotifier

    @Volatile
    private var shouldRun = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var client: HelperSseClient? = null

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        chains = EvolutionChainRepository(this)
        notifier = CaptureNotifier(this) { settingsStore.load() }
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
        CaptureTaskManager.markConnecting()
        startForeground(FOREGROUND_ID, notifier.monitorNotification(CaptureTaskManager.currentState()))
        startTicker()

        if (worker?.isAlive == true) return
        worker = Thread(::runSseLoop, "capture-sse-loop").apply { start() }
    }

    private fun runSseLoop() {
        var backoffMillis = 1000L

        while (shouldRun) {
            val config = CaptureTaskManager.currentState().config ?: break
            val settings = settingsStore.load()
            val nextClient = HelperSseClient(
                settings = settings,
                user = config.user,
                listener = object : HelperSseClient.Listener {
                    override fun onOpen() {
                        backoffMillis = 1000L
                        CaptureTaskManager.markConnected()
                        refreshForeground()
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
                        if (shouldRun) {
                            CaptureTaskManager.markReconnecting("SSE 连接已断开")
                        }
                    }
                },
            )

            client = nextClient
            try {
                CaptureTaskManager.markConnecting()
                refreshForeground()
                nextClient.connectAndRead()
            } catch (error: IOException) {
                if (shouldRun) {
                    CaptureTaskManager.markReconnecting(error.message ?: "SSE 连接异常")
                }
            } catch (error: RuntimeException) {
                if (shouldRun) {
                    CaptureTaskManager.markReconnecting(error.message ?: "SSE 处理异常")
                }
            } finally {
                client = null
                refreshForeground()
            }

            if (shouldRun) {
                sleepQuietly(backoffMillis)
                backoffMillis = min(backoffMillis * 2, 30_000L)
            }
        }

        if (shouldRun) {
            CaptureTaskManager.markFailed("监听已停止")
            refreshForeground()
        }
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
        client?.cancel()
        worker?.interrupt()
        mainHandler.removeCallbacks(tickRunnable)
        if (markPaused) {
            CaptureTaskManager.pauseByUser()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val FOREGROUND_ID = 1001
        const val ACTION_START = "com.roco.capture.notify.action.START_MONITOR"
        const val ACTION_STOP = "com.roco.capture.notify.action.STOP_MONITOR"

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
