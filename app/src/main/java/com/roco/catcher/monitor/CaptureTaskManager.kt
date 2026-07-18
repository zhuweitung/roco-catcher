package com.roco.catcher.monitor

import android.util.Log
import com.roco.catcher.model.CaptureTaskConfig
import com.roco.catcher.model.CaptureTaskState
import com.roco.catcher.model.CaughtPetEvent
import com.roco.catcher.model.ThrowBallEvent
import com.roco.catcher.model.LOW_SPEED_PENDING_MILLIS
import com.roco.catcher.model.LOW_SPEED_WARM_UP_MILLIS
import com.roco.catcher.model.LowSpeedKind
import com.roco.catcher.model.LowSpeedState
import com.roco.catcher.model.TaskStatus
import java.util.concurrent.CopyOnWriteArrayList

object CaptureTaskManager {
    private const val MAX_EVENT_HISTORY = 500
    private const val TASK_START_TOLERANCE_MILLIS = 1_000L
    private const val MAX_FUTURE_EVENT_SKEW_MILLIS = 5L * 60_000L
    private const val EVENT_PET_INFO_CATCH = "pet_info.catch"
    private const val EVENT_THROW_BALL = "throw_ball"
    private const val TAG = "CaptureTaskManager"

    private val listeners = CopyOnWriteArrayList<(CaptureTaskState) -> Unit>()
    private var state = CaptureTaskState()

    @Synchronized
    fun currentState(): CaptureTaskState = state

    @Synchronized
    fun restoreTask(restored: CaptureTaskState, resumeMonitoring: Boolean): Boolean {
        if (restored.config == null || restored.taskStartedAtMillis == null) return false
        val now = System.currentTimeMillis()
        val activeElapsed = restored.activeRunStartedAtMillis
            ?.let { (now - it).coerceAtLeast(0L) }
            ?: 0L
        state = restored.copy(
            status = if (resumeMonitoring) TaskStatus.Connecting else restored.status,
            activeRunStartedAtMillis = null,
            accumulatedRunMillis = restored.accumulatedRunMillis + activeElapsed,
            rateHistory = RateCalculator.history(restored.caughtEvents),
            errorMessage = if (resumeMonitoring) null else restored.errorMessage,
        )
        notifyListenersLocked()
        return true
    }

    fun addListener(listener: (CaptureTaskState) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(currentState())
        return { listeners.remove(listener) }
    }

    @Synchronized
    fun startNewTask(config: CaptureTaskConfig) {
        val now = System.currentTimeMillis()
        val initialLowSpeed = initialLowSpeedState(config, 0L)
        state = CaptureTaskState(
            status = TaskStatus.Connecting,
            config = config,
            taskStartedAtMillis = now,
            lowSpeedState = initialLowSpeed,
        )
        notifyListenersLocked()
    }

    @Synchronized
    fun continueCurrentTask(config: CaptureTaskConfig? = null) {
        val nextConfig = config ?: state.config ?: return
        val effective = effectiveRunMillisLocked(System.currentTimeMillis())
        val targetReached = state.caughtCount >= nextConfig.targetCount
        val nextTargetNotifySent = if (targetReached) state.targetNotifySent else false
        state = state.copy(
            status = TaskStatus.Connecting,
            config = nextConfig,
            errorMessage = null,
            activeRunStartedAtMillis = null,
            targetNotifySent = nextTargetNotifySent,
            targetReachedAtMillis = state.targetReachedAtMillis.takeIf { nextTargetNotifySent },
            lowSpeedState = lowSpeedStateForResume(nextConfig, effective, targetReached),
        )
        notifyListenersLocked()
    }

    @Synchronized
    fun pauseByUser() {
        pauseActiveClockLocked(System.currentTimeMillis())
        state = state.copy(
            status = TaskStatus.Paused,
            activeRunStartedAtMillis = null,
            errorMessage = null,
        )
        notifyListenersLocked()
    }

    @Synchronized
    fun markConnecting() {
        if (state.status == TaskStatus.Paused || state.config == null) return
        state = state.copy(status = TaskStatus.Connecting, errorMessage = null)
        notifyListenersLocked()
    }

    @Synchronized
    fun markConnected() {
        if (state.status == TaskStatus.Paused || state.config == null) return
        val now = System.currentTimeMillis()
        state = state.copy(
            status = TaskStatus.Running,
            activeRunStartedAtMillis = state.activeRunStartedAtMillis ?: now,
            errorMessage = null,
        )
        notifyListenersLocked()
    }

    @Synchronized
    fun markReconnecting(message: String?) {
        if (state.status == TaskStatus.Paused || state.config == null) return
        pauseActiveClockLocked(System.currentTimeMillis())
        state = state.copy(
            status = TaskStatus.Reconnecting,
            activeRunStartedAtMillis = null,
            errorMessage = message,
        )
        notifyListenersLocked()
    }

    @Synchronized
    fun markFailed(message: String?) {
        pauseActiveClockLocked(System.currentTimeMillis())
        state = state.copy(
            status = TaskStatus.Failed,
            activeRunStartedAtMillis = null,
            errorMessage = message,
        )
        notifyListenersLocked()
    }

    @Synchronized
    fun handleRawEvent(
        rawJson: String,
        eventName: String?,
        eventId: String?,
        connectionGeneration: Long,
        petNameResolver: (String) -> String?,
        alertSink: CaptureAlertSink,
    ) {
        val resolvedEventName = eventName?.takeIf { it.isNotBlank() }
            ?: EventParser.readEventName(rawJson)
            ?: return
        val receivedAtMillis = System.currentTimeMillis()

        when (resolvedEventName) {
            EVENT_PET_INFO_CATCH -> {
                val payload = EventParser.parsePetCatch(rawJson) ?: return
                val occurredAtMillis = payload.occurredAtMillis
                    ?.takeIf { it <= receivedAtMillis + MAX_FUTURE_EVENT_SKEW_MILLIS }
                    ?: receivedAtMillis
                handlePetChanged(
                    baseConfId = payload.baseConfId,
                    gid = payload.gid,
                    occurredAtMillis = occurredAtMillis,
                    receivedAtMillis = receivedAtMillis,
                    eventId = eventId,
                    connectionGeneration = connectionGeneration,
                    petNameResolver = petNameResolver,
                    alertSink = alertSink,
                )
            }
            EVENT_THROW_BALL -> {
                val payload = EventParser.parseThrowBall(rawJson) ?: return
                val occurredAtMillis = payload.occurredAtMillis
                    ?.takeIf { it <= receivedAtMillis + MAX_FUTURE_EVENT_SKEW_MILLIS }
                    ?: receivedAtMillis
                handleThrowBall(
                    ballId = payload.ballId,
                    occurredAtMillis = occurredAtMillis,
                    receivedAtMillis = receivedAtMillis,
                    eventId = eventId,
                    connectionGeneration = connectionGeneration,
                )
            }
            else -> return
        }
    }

    @Synchronized
    fun tick(alertSink: CaptureAlertSink) {
        if (state.status == TaskStatus.Running) {
            val now = System.currentTimeMillis()
            if (evaluateTargetReachedLocked(now, alertSink)) return
            evaluateLowSpeedLocked(now, alertSink)
            notifyListenersLocked()
        }
    }

    fun effectiveRunMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        synchronized(this) {
            return effectiveRunMillisLocked(nowMillis)
        }
    }

    fun averageRate(nowMillis: Long = System.currentTimeMillis()): Double {
        synchronized(this) {
            return RateCalculator.averageRate(state.caughtCount, effectiveRunMillisLocked(nowMillis))
        }
    }

    fun currentRate(nowMillis: Long = System.currentTimeMillis()): Double {
        synchronized(this) {
            return RateCalculator.currentRate(state.caughtEvents, nowMillis)
        }
    }

    @Synchronized
    private fun handleThrowBall(
        ballId: Long?,
        occurredAtMillis: Long,
        receivedAtMillis: Long,
        eventId: String?,
        connectionGeneration: Long,
    ) {
        if (state.config == null) return
        if (state.status != TaskStatus.Running) return
        val taskStartedAtMillis = state.taskStartedAtMillis ?: return
        if (occurredAtMillis < taskStartedAtMillis - TASK_START_TOLERANCE_MILLIS) {
            Log.d(
                TAG,
                "Ignored pre-task throw_ball ballId=$ballId occurredAt=$occurredAtMillis " +
                    "receivedAt=$receivedAtMillis eventId=$eventId generation=$connectionGeneration",
            )
            return
        }

        // No dedup for throw_ball: every event counts once.
        val event = ThrowBallEvent(
            ballId = ballId,
            thrownAtMillis = occurredAtMillis,
            receivedAtMillis = receivedAtMillis,
        )
        val events = (state.throwBallEvents + event)
            .sortedBy(ThrowBallEvent::thrownAtMillis)
            .takeLast(MAX_EVENT_HISTORY)
        state = state.copy(
            throwBallCount = state.throwBallCount + 1,
            throwBallEvents = events,
        )
        Log.d(
            TAG,
            "Accepted throw_ball ballId=$ballId occurredAt=$occurredAtMillis " +
                "receivedAt=$receivedAtMillis count=${state.throwBallCount} " +
                "eventId=$eventId generation=$connectionGeneration",
        )
        notifyListenersLocked()
    }

    @Synchronized
    private fun handlePetChanged(
        baseConfId: String,
        gid: Long,
        occurredAtMillis: Long,
        receivedAtMillis: Long,
        eventId: String?,
        connectionGeneration: Long,
        petNameResolver: (String) -> String?,
        alertSink: CaptureAlertSink,
    ) {
        val config = state.config ?: return
        if (state.status != TaskStatus.Running) return
        if (!config.target.targetBaseConfIds.contains(baseConfId)) return
        if (state.caughtGids.contains(gid)) return
        val taskStartedAtMillis = state.taskStartedAtMillis ?: return
        if (occurredAtMillis < taskStartedAtMillis - TASK_START_TOLERANCE_MILLIS) {
            Log.d(
                TAG,
                "Ignored pre-task event gid=$gid occurredAt=$occurredAtMillis " +
                    "receivedAt=$receivedAtMillis eventId=$eventId generation=$connectionGeneration",
            )
            return
        }

        val deliveryDelayMillis = (receivedAtMillis - occurredAtMillis).coerceAtLeast(0L)
        val effective = (effectiveRunMillisLocked(receivedAtMillis) - deliveryDelayMillis).coerceAtLeast(0L)
        val event = CaughtPetEvent(
            gid = gid,
            baseConfId = baseConfId,
            petName = petNameResolver(baseConfId),
            caughtAtMillis = occurredAtMillis,
            receivedAtMillis = receivedAtMillis,
            effectiveRunMillis = effective,
        )
        val events = (state.caughtEvents + event)
            .sortedWith(compareBy(CaughtPetEvent::caughtAtMillis, CaughtPetEvent::gid))
            .takeLast(MAX_EVENT_HISTORY)
        val gids = state.caughtGids + gid
        val next = state.copy(
            caughtGids = gids,
            caughtEvents = events,
            rateHistory = RateCalculator.history(events),
        )

        state = next
        Log.d(
            TAG,
            "Accepted event gid=$gid occurredAt=$occurredAtMillis receivedAt=$receivedAtMillis " +
                "delayMs=$deliveryDelayMillis eventId=$eventId generation=$connectionGeneration",
        )
        if (evaluateTargetReachedLocked(receivedAtMillis, alertSink)) return
        evaluateLowSpeedLocked(receivedAtMillis, alertSink)
        notifyListenersLocked()
    }

    private fun evaluateTargetReachedLocked(now: Long, alertSink: CaptureAlertSink): Boolean {
        val config = state.config ?: return false
        if (state.targetNotifySent || state.caughtCount < config.targetCount) return false

        val effective = effectiveRunMillisLocked(now)
        state = state.copy(
            targetNotifySent = true,
            targetReachedAtMillis = now,
            lowSpeedState = LowSpeedState(LowSpeedKind.SuppressedAfterTargetReached, effective),
        )
        notifyListenersLocked()
        alertSink.onTargetReached(state)
        return true
    }

    private fun evaluateLowSpeedLocked(now: Long, alertSink: CaptureAlertSink) {
        val config = state.config ?: return
        val minRate = config.minRatePerMinute
        val effective = effectiveRunMillisLocked(now)

        if (minRate <= 0.0) {
            if (state.lowSpeedState.kind != LowSpeedKind.Disabled) {
                state = state.copy(lowSpeedState = LowSpeedState(LowSpeedKind.Disabled, effective))
            }
            return
        }

        if (state.targetReached) {
            if (state.lowSpeedState.kind != LowSpeedKind.SuppressedAfterTargetReached) {
                state = state.copy(
                    lowSpeedState = LowSpeedState(LowSpeedKind.SuppressedAfterTargetReached, effective),
                )
            }
            return
        }

        val currentRate = RateCalculator.currentRate(state.caughtEvents, now)
        val current = state.lowSpeedState
        val next = when (current.kind) {
            LowSpeedKind.Disabled -> LowSpeedState(LowSpeedKind.WarmingUp, effective)
            LowSpeedKind.WarmingUp -> {
                if (effective - current.startedEffectiveMillis < LOW_SPEED_WARM_UP_MILLIS) {
                    current
                } else if (currentRate < minRate) {
                    LowSpeedState(LowSpeedKind.Pending, effective)
                } else {
                    LowSpeedState(LowSpeedKind.Normal, effective)
                }
            }
            LowSpeedKind.Normal -> {
                if (currentRate < minRate) LowSpeedState(LowSpeedKind.Pending, effective) else current
            }
            LowSpeedKind.Pending -> {
                if (currentRate >= minRate) {
                    LowSpeedState(LowSpeedKind.Normal, effective)
                } else if (effective - current.startedEffectiveMillis >= LOW_SPEED_PENDING_MILLIS) {
                    LowSpeedState(LowSpeedKind.Alerted, effective)
                } else {
                    current
                }
            }
            LowSpeedKind.Alerted -> {
                if (currentRate >= minRate) LowSpeedState(LowSpeedKind.Normal, effective) else current
            }
            LowSpeedKind.SuppressedAfterTargetReached -> current
        }

        if (next != current) {
            state = state.copy(lowSpeedState = next)
            if (next.kind == LowSpeedKind.Alerted) {
                alertSink.onLowSpeed(state, currentRate)
            }
        }
    }

    private fun initialLowSpeedState(config: CaptureTaskConfig, effectiveMillis: Long): LowSpeedState {
        return if (config.minRatePerMinute <= 0.0) {
            LowSpeedState(LowSpeedKind.Disabled, effectiveMillis)
        } else {
            LowSpeedState(LowSpeedKind.WarmingUp, effectiveMillis)
        }
    }

    private fun lowSpeedStateForResume(
        config: CaptureTaskConfig,
        effectiveMillis: Long,
        targetReached: Boolean,
    ): LowSpeedState {
        return when {
            targetReached -> LowSpeedState(LowSpeedKind.SuppressedAfterTargetReached, effectiveMillis)
            config.minRatePerMinute <= 0.0 -> LowSpeedState(LowSpeedKind.Disabled, effectiveMillis)
            else -> LowSpeedState(LowSpeedKind.WarmingUp, effectiveMillis)
        }
    }

    private fun pauseActiveClockLocked(now: Long) {
        val startedAt = state.activeRunStartedAtMillis ?: return
        val elapsed = (now - startedAt).coerceAtLeast(0L)
        state = state.copy(
            accumulatedRunMillis = state.accumulatedRunMillis + elapsed,
            activeRunStartedAtMillis = null,
        )
    }

    private fun effectiveRunMillisLocked(now: Long): Long {
        val active = state.activeRunStartedAtMillis
        val activeElapsed = if (active != null) (now - active).coerceAtLeast(0L) else 0L
        return state.accumulatedRunMillis + activeElapsed
    }

    private fun notifyListenersLocked() {
        val snapshot = state
        listeners.forEach { listener -> listener(snapshot) }
    }
}

