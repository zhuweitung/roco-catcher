package com.roco.capture.notify.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.roco.capture.notify.model.RatePoint
import kotlin.math.max

class RateChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(203, 213, 225)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(59, 130, 246)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(249, 115, 22)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(71, 85, 105)
        textSize = 28f
    }

    private var points: List<RatePoint> = emptyList()

    fun setPoints(value: List<RatePoint>) {
        points = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + 48f
        val top = paddingTop + 24f
        val right = width - paddingRight - 16f
        val bottom = height - paddingBottom - 40f
        if (right <= left || bottom <= top) return

        canvas.drawLine(left, top, left, bottom, axisPaint)
        canvas.drawLine(left, bottom, right, bottom, axisPaint)

        if (points.isEmpty()) {
            canvas.drawText("暂无速率数据", left + 24f, (top + bottom) / 2f, textPaint)
            return
        }

        val visible = points.takeLast(24)
        val maxRate = max(1.0, visible.maxOf { it.ratePerMinute })
        val xStep = if (visible.size == 1) 0f else (right - left) / (visible.size - 1)
        val path = Path()

        visible.forEachIndexed { index, point ->
            val x = if (visible.size == 1) (left + right) / 2f else left + xStep * index
            val y = bottom - ((point.ratePerMinute / maxRate).toFloat() * (bottom - top))
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
            canvas.drawCircle(x, y, 6f, dotPaint)
        }

        canvas.drawPath(path, linePaint)
        canvas.drawText("${maxRate.toInt()}/分", 4f, top + 10f, textPaint)
        canvas.drawText("0", 16f, bottom + 10f, textPaint)
    }
}
