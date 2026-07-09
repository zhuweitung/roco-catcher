package com.roco.capture.notify.monitor

import com.roco.capture.notify.model.CaptureTaskState

interface CaptureAlertSink {
    fun onTargetReached(state: CaptureTaskState)
    fun onLowSpeed(state: CaptureTaskState, currentRate: Double)
}
