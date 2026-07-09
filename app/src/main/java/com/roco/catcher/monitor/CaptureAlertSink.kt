package com.roco.catcher.monitor

import com.roco.catcher.model.CaptureTaskState

interface CaptureAlertSink {
    fun onTargetReached(state: CaptureTaskState)
    fun onLowSpeed(state: CaptureTaskState, currentRate: Double)
}

