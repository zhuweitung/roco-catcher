package com.roco.catcher.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.roco.catcher.data.EvolutionChainRepository
import com.roco.catcher.data.SettingsStore
import com.roco.catcher.model.CaptureTaskState
import com.roco.catcher.model.TaskStatus
import com.roco.catcher.notification.CaptureNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.min

class CaptureMonitorService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settingsStore: SettingsStore
    private lateinit var chains: EvolutionChainRepository
    private lateinit var notifier: CaptureNotifier
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceChannel = Channel<CaptureTaskState>(Channel.CONFLATED)
    private lateinit var persistenceJob: Job

    @Volatile
    private var shouldRun = false

    @Volatile
    private var client: HelperSseClient? = null

    @Volatile
    private var connectionGeneration = 0L

    private var currentNetwork: Network? = null
    private var networkCallbackRegistered = false
    @Volatile
    private var reconnectBackoffMillis = INITIAL_RECONNECT_MILLIS

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        chains = EvolutionChainRepository(this)
        notifier = CaptureNotifier(this) { settingsStore.loadBlocking() }
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:CaptureMonitor")
            .apply { setReferenceCounted(false) }
        persistenceJob = persistenceScope.launch {
            for (snapshot in persistenceChannel) {
                settingsStore.saveActiveTask(snapshot)
            }
        }
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

    override fun onTimeout(startId: Int, fgsType: Int) {
        CaptureTaskManager.markFailed("后台监听服务已超时")
        persistTaskState()
        stopMonitoring(markPaused = false)
    }

    private fun startMonitoring() {
        shouldRun = true
        promoteToForeground()
        if (!ensureTaskReady()) {
            shouldRun = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        reconnectBackoffMillis = INITIAL_RECONNECT_MILLIS
        acquireWakeLock()
        registerNetworkCallback()
        CaptureTaskManager.markConnecting()
        refreshForeground()
        startTicker()
        if (currentNetwork != null) {
            openSse()
        } else {
            CaptureTaskManager.markReconnecting("等待网络连接")
            refreshForeground()
        }
        persistTaskState()
    }

    private fun openSse() {
        if (!shouldRun || currentNetwork == null) return

        val config = CaptureTaskManager.currentState().config ?: run {
            stopSelf()
            return
        }
        val settings = settingsStore.loadBlocking()

        val generation = ++connectionGeneration
        client?.cancel()
        CaptureTaskManager.markConnecting()
        refreshForeground()
        persistTaskState()

        val nextClient = HelperSseClient(
            settings = settings,
            user = config.user,
            listener = object : HelperSseClient.Listener {
                override fun onOpen() {
                    if (!isCurrentConnection(generation)) return
                    reconnectBackoffMillis = INITIAL_RECONNECT_MILLIS
                    CaptureTaskManager.markConnected()
                    refreshForeground()
                    persistTaskState()
                }

                override fun onEvent(rawJson: String, eventName: String?, eventId: String?) {
                    if (!isCurrentConnection(generation)) return
                    CaptureTaskManager.handleRawEvent(
                        rawJson = rawJson,
                        eventName = eventName,
                        eventId = eventId,
                        connectionGeneration = generation,
                        petNameResolver = { chains.petName(it) },
                        alertSink = notifier,
                    )
                    refreshForeground()
                    persistTaskState()
                }

                override fun onClosed() {
                    mainHandler.post {
                        if (!isCurrentConnection(generation)) return@post
                        CaptureTaskManager.markReconnecting("SSE 连接已断开")
                        refreshForeground()
                        persistTaskState()
                        scheduleReconnect()
                    }
                }

                override fun onFailure(message: String?) {
                    mainHandler.post {
                        if (!isCurrentConnection(generation)) return@post
                        CaptureTaskManager.markReconnecting(message ?: "SSE 连接异常")
                        refreshForeground()
                        persistTaskState()
                        scheduleReconnect()
                    }
                }
            },
        )

        client = nextClient
        runCatching { nextClient.connect() }
            .onFailure { error ->
                if (!isCurrentConnection(generation)) return@onFailure
                CaptureTaskManager.markReconnecting(error.message ?: "SSE 连接异常")
                refreshForeground()
                persistTaskState()
                scheduleReconnect()
            }
    }

    private fun scheduleReconnect() {
        if (!shouldRun || currentNetwork == null) return
        val delay = reconnectBackoffMillis
        reconnectBackoffMillis = min(reconnectBackoffMillis * 2, MAX_RECONNECT_MILLIS)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private val reconnectRunnable = Runnable {
        if (shouldRun) openSse()
    }

    private fun isCurrentConnection(generation: Long): Boolean {
        return shouldRun && generation == connectionGeneration
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post {
                if (!shouldRun) return@post
                val networkChanged = currentNetwork != null && currentNetwork != network
                currentNetwork = network
                reconnectBackoffMillis = INITIAL_RECONNECT_MILLIS
                if (networkChanged || client == null) {
                    mainHandler.removeCallbacks(reconnectRunnable)
                    openSse()
                }
            }
        }

        override fun onLost(network: Network) {
            mainHandler.post {
                if (!shouldRun || currentNetwork != network) return@post
                currentNetwork = null
                connectionGeneration += 1L
                client?.cancel()
                client = null
                mainHandler.removeCallbacks(reconnectRunnable)
                CaptureTaskManager.markReconnecting("网络已断开")
                refreshForeground()
                persistTaskState()
            }
        }
    }

    private fun registerNetworkCallback() {
        currentNetwork = connectivityManager.activeNetwork
        if (networkCallbackRegistered) return
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        networkCallbackRegistered = false
        currentNetwork = null
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!shouldRun) return
            val before = CaptureTaskManager.currentState()
            CaptureTaskManager.tick(notifier)
            val after = CaptureTaskManager.currentState()
            if (before.lowSpeedState != after.lowSpeedState ||
                before.targetNotifySent != after.targetNotifySent
            ) {
                persistTaskState()
            }
            refreshForeground()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun refreshForeground() {
        mainHandler.post {
            if (shouldRun) {
                promoteToForeground()
            }
        }
    }

    private fun promoteToForeground() {
        val notification = notifier.monitorNotification(CaptureTaskManager.currentState())
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun ensureTaskReady(): Boolean {
        if (CaptureTaskManager.currentState().config != null) return true
        val restored = settingsStore.loadActiveTaskBlocking() ?: return false
        if (restored.status !in RESTORABLE_STATUSES) return false
        return CaptureTaskManager.restoreTask(restored, resumeMonitoring = true)
    }

    private fun persistTaskState() {
        val snapshot = CaptureTaskManager.currentState()
        if (snapshot.config != null) persistenceChannel.trySend(snapshot)
    }

    private fun flushTaskState() {
        persistTaskState()
        persistenceChannel.close()
        runBlocking { persistenceJob.join() }
        persistenceScope.cancel()
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun stopMonitoring(markPaused: Boolean) {
        shouldRun = false
        connectionGeneration += 1L
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        unregisterNetworkCallback()
        client?.cancel()
        client = null
        if (markPaused) {
            CaptureTaskManager.pauseByUser()
        }
        flushTaskState()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val FOREGROUND_ID = 1001
        private const val INITIAL_RECONNECT_MILLIS = 1000L
        private const val MAX_RECONNECT_MILLIS = 30_000L
        private val RESTORABLE_STATUSES = setOf(
            TaskStatus.Connecting,
            TaskStatus.Running,
            TaskStatus.Reconnecting,
        )
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

