package com.kiktor.v2whitelist.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.kiktor.v2whitelist.R
import java.util.LinkedList

class LiveSpeedGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val MAX_POINTS = 60 // 60 seconds of history if updated every second
    private val rxHistory = LinkedList<Double>()
    private val txHistory = LinkedList<Double>()

    private val rxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_fab_active)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val txPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, android.R.color.holo_blue_light)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, android.R.color.transparent)
        style = Paint.Style.FILL
    }
    
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider_color_light)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val rxPath = Path()
    private val txPath = Path()

    fun addSpeeds(rx: Double, tx: Double) {
        if (rxHistory.size >= MAX_POINTS) rxHistory.removeFirst()
        if (txHistory.size >= MAX_POINTS) txHistory.removeFirst()

        rxHistory.add(rx)
        txHistory.add(tx)
        
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        
        // Draw background and grid
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        
        // Draw some grid lines
        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
        canvas.drawLine(0f, h / 4f, w, h / 4f, gridPaint)
        canvas.drawLine(0f, h * 3f / 4f, w, h * 3f / 4f, gridPaint)
        
        if (rxHistory.isEmpty()) return

        val maxSpeed = maxOf(
            (rxHistory.maxOrNull() ?: 1.0),
            (txHistory.maxOrNull() ?: 1.0),
            1024.0 // Minimum 1 KB/s scale
        ).toFloat()

        val stepX = w / (MAX_POINTS - 1).toFloat()

        drawPath(canvas, rxHistory, rxPath, rxPaint, h, stepX, maxSpeed)
        drawPath(canvas, txHistory, txPath, txPaint, h, stepX, maxSpeed)
    }

    private fun drawPath(canvas: Canvas, history: List<Double>, path: Path, paint: Paint, h: Float, stepX: Float, maxSpeed: Float) {
        path.reset()
        val startX = (MAX_POINTS - history.size) * stepX
        
        history.forEachIndexed { index, speed ->
            val x = startX + index * stepX
            // Y is inverted (0 is top, h is bottom)
            val y = h - ((speed.toFloat() / maxSpeed) * h)
            
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, paint)
    }
}
