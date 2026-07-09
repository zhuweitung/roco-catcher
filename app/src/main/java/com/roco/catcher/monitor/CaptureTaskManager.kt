package com.roco.catcher.monitor

import com.roco.catcher.model.CaptureTaskConfig
import com.roco.catcher.model.CaptureTaskState
import com.roco.catcher.model.CaughtPetEvent
import com.roco.catcher.model.LowSpeedKind
import com.roco.catcher.model.LowSpeedState
import com.roco.catcher.model.TaskStatus
import java.util.concurrent.CopyOnWriteArrayList

object CaptureTaskManager {
    private const val WARM_UP_MILLIS = 60_000L
    private const val LOW_SPEED_PENDING_MILLIS = 30_000L
    private const val MAX_EVENT_HISTORY = 500

    private val listeners = CopyOnWriteArrayList<(CaptureTaskState) -> Unit>()
    private var state = CaptureTaskState()

    @Synchronized
    fun currentState(): CaptureTaskState = state

    fun addListener(listener: (CaptureTaskState) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(currentState())
        return { listeners.remove(listener) }
    }

    @Synchronized
    fun startNewTask(config: CaptureTaskConfig) {
        val initialLowSpeed = initialLowSpeedState(config, 0L)
        state = CaptureTaskState(
            status = TaskStatus.Connecting,
            config = config,
            lowSpeedState = initialLowSpeed,
        )
        notifyListenersLocked()
    }

    @Synchronized
    fun continueCurrentTask() {
        val config = state.config ?: return
        val effective = effectiveRunMillisLocked(System.currentTimeMillis())
        state = state.copy(
            status = TaskStatus.Connecting,
            errorMessage = null,
            activeRunStartedAtMillis = null,
            lowSpeedState = lowSpeedStateForResume(config, effective, state.targetReached),
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
        petNameResolver: (String) -> String?,
        alertSink: CaptureAlertSink,
    ) {
        val payload = CaptureEventParser.parse(rawJson, eventName) ?: return
        handlePetChanged(payload.baseConfId, payload.gid, petNameResolver, alertSink)
    }

    @Synchronized
    fun tick(alertSink: CaptureAlertSink) {
        if (state.status == TaskStatus.Running) {
            evaluateLowSpeedLocked(System.currentTimeMillis(), alertSink)
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
            return RateCalculator.currentRate(state.caughtEvents, effectiveRunMillisLocked(nowMillis))
        }
    }

    @Synchronized
    private fun handlePetChanged(
        baseConfId: String,
        gid: Long,
        petNameResolver: (String) -> String?,
        alertSink: CaptureAlertSink,
    ) {
        val config = state.config ?: return
        if (state.status != TaskStatus.Running) return
        if (!config.target.targetBaseConfIds.contains(baseConfId)) return
        if (state.caughtGids.contains(gid)) return

        val now = System.currentTimeMillis()
        val effective = effectiveRunMillisLocked(now)
        val event = CaughtPetEvent(
            gid = gid,
            baseConfId = baseConfId,
            petName = petNameResolver(baseConfId),
            caughtAtMillis = now,
            effectiveRunMillis = effective,
        )
        val events = (state.caughtEvents + event).takeLast(MAX_EVENT_HISTORY)
        val gids = state.caughtGids + gid
        var next = state.copy(
            caughtGids = gids,
            caughtEvents = events,
            rateHistory = RateCalculator.history(events),
        )

        val reachedNow = gids.size >= config.targetCount && !next.targetNotifySent
        if (reachedNow) {
            next = next.copy(
                targetNotifySent = true,
                targetReachedAtMillis = now,
                lowSpeedState = LowSpeedState(LowSpeedKind.SuppressedAfterTargetReached, effective),
            )
            state = next
            notifyListenersLocked()
            alertSink.onTargetReached(next)
            return
        }

        state = next
        evaluateLowSpeedLocked(now, alertSink)
        notifyListenersLocked()
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

        val currentRate = RateCalculator.currentRate(state.caughtEvents, effective)
        val current = state.lowSpeedState
        val next = when (current.kind) {
            LowSpeedKind.Disabled -> LowSpeedState(LowSpeedKind.WarmingUp, effective)
            LowSpeedKind.WarmingUp -> {
                if (effective - current.startedEffectiveMillis < WARM_UP_MILLIS) {
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

