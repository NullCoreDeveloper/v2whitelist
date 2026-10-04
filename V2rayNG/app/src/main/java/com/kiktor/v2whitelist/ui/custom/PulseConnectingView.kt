package com.kiktor.v2whitelist.ui.custom

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.kiktor.v2whitelist.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * PulseConnectingView creates a modern radar-pulse & orbital glow animation
 * around the main connect button during server searching & connection negotiation.
 */
class PulseConnectingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    // Base button dimensions (button radius is 110dp)
    private val buttonRadius = 110f * density
    private val maxWaveRadius = 152f * density

    // Paints
    private val waveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val waveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val orbitArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val dotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val orbitRect = RectF()

    // Animators
    private var pulseAnimator: ValueAnimator? = null
    private var rotateAnimator: ValueAnimator? = null

    private var pulseFraction = 0f
    private var rotationAngle = 0f

    // Theme color (default orange accent, adapts to theme)
    private var activeColor = ContextCompat.getColor(context, R.color.color_fab_active)

    init {
        // Wave count: 3 waves with phase offsets 0.0, 0.33, 0.66
        updateColors()
    }

    fun setColor(color: Int) {
        activeColor = color
        updateColors()
        invalidate()
    }

    private fun updateColors() {
        waveStrokePaint.color = activeColor
        waveFillPaint.color = activeColor
        dotGlowPaint.color = activeColor
    }

    fun startAnimation() {
        if (pulseAnimator?.isRunning == true) return
        visibility = VISIBLE

        pulseAnimator?.cancel()
        rotateAnimator?.cancel()

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulseFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        rotateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        pulseAnimator?.cancel()
        rotateAnimator?.cancel()
        pulseAnimator = null
        rotateAnimator = null
        visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            startAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        if (cx <= 0 || cy <= 0) return

        // 1. Draw 3 Expanding Pulse Waves
        val waveCount = 3
        for (i in 0 until waveCount) {
            val phaseOffset = i.toFloat() / waveCount
            val progress = (pulseFraction + phaseOffset) % 1.0f

            // Radius interpolates from button edge to maxWaveRadius
            val currentRadius = buttonRadius + progress * (maxWaveRadius - buttonRadius)

            // Alpha curve: fast ramp-up, then smooth exponential fade-out
            val alpha = when {
                progress < 0.15f -> (progress / 0.15f * 0.7f)
                else -> ((1f - progress) / 0.85f * 0.7f)
            }.coerceIn(0f, 0.7f)

            val strokeAlpha = (alpha * 255).toInt()
            val fillAlpha = (alpha * 35).toInt()

            if (strokeAlpha > 0) {
                waveFillPaint.alpha = fillAlpha
                canvas.drawCircle(cx, cy, currentRadius, waveFillPaint)

                waveStrokePaint.alpha = strokeAlpha
                canvas.drawCircle(cx, cy, currentRadius, waveStrokePaint)
            }
        }

        // 2. Draw Orbiting Radar Arc around button edge
        val orbitRadius = buttonRadius + 5f * density
        orbitRect.set(cx - orbitRadius, cy - orbitRadius, cx + orbitRadius, cy + orbitRadius)

        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)

        val transparentColor = Color.argb(0, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        val sweep = SweepGradient(
            cx, cy,
            intArrayOf(transparentColor, transparentColor, activeColor),
            floatArrayOf(0f, 0.65f, 1f)
        )
        orbitArcPaint.shader = sweep
        canvas.drawArc(orbitRect, 0f, 360f, false, orbitArcPaint)

        // Bright leading dot on the arc tip
        val dotRadius = 4f * density
        val dotX = cx + orbitRadius
        val dotY = cy
        canvas.drawCircle(dotX, dotY, dotRadius, dotGlowPaint)

        canvas.restore()

        // 3. Premium Particles Effect
        if (com.kiktor.v2whitelist.handler.MmkvManager.decodeSettingsBool(com.kiktor.v2whitelist.AppConfig.PREF_PREMIUM_PULSE, true)) {
            val numParticles = 12
            for (i in 0 until numParticles) {
                // simple deterministic pseudo-random based on rotation Angle and index
                val pAngle = (rotationAngle * 1.5f + i * (360f / numParticles)) % 360f
                val pProgress = ((pulseFraction + i.toFloat() / numParticles) % 1.0f)
                val pRadius = buttonRadius + pProgress * (maxWaveRadius * 1.2f - buttonRadius)
                
                val px = cx + pRadius * kotlin.math.cos(Math.toRadians(pAngle.toDouble())).toFloat()
                val py = cy + pRadius * kotlin.math.sin(Math.toRadians(pAngle.toDouble())).toFloat()
                
                val pAlpha = ((1f - pProgress) * 255).toInt().coerceIn(0, 255)
                dotGlowPaint.alpha = pAlpha
                canvas.drawCircle(px, py, 2f * density, dotGlowPaint)
            }
        }
    }
}
