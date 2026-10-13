package com.kiktor.v2whitelist.desktop.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * PulseConnectingView creates a modern radar-pulse & orbital glow animation
 * around the main connect button during server searching & connection negotiation.
 * Matches Android PulseConnectingView 1:1.
 */
@Composable
fun PulseConnectingView(
    modifier: Modifier = Modifier,
    size: Dp = 320.dp,
    activeColor: Color = Color(0xFFFF9800) // color_fab_active (Orange/Amber)
) {
    val transition = rememberInfiniteTransition(label = "pulse_transition")

    val pulseFraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_fraction"
    )

    val rotationAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation_angle"
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val buttonRadius = 110.dp.toPx()
            val maxWaveRadius = 152.dp.toPx()

            // 1. Draw 3 Expanding Pulse Waves
            val waveCount = 3
            for (i in 0 until waveCount) {
                val phaseOffset = i.toFloat() / waveCount
                val progress = (pulseFraction + phaseOffset) % 1.0f

                val currentRadius = buttonRadius + progress * (maxWaveRadius - buttonRadius)

                val alpha = when {
                    progress < 0.15f -> (progress / 0.15f * 0.7f)
                    else -> ((1f - progress) / 0.85f * 0.7f)
                }.coerceIn(0f, 0.7f)

                if (alpha > 0.01f) {
                    // Fill wave
                    drawCircle(
                        color = activeColor.copy(alpha = alpha * 0.14f),
                        radius = currentRadius,
                        center = Offset(cx, cy)
                    )
                    // Stroke wave
                    drawCircle(
                        color = activeColor.copy(alpha = alpha),
                        radius = currentRadius,
                        center = Offset(cx, cy),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }

            // 2. Draw Orbiting Radar Arc around button edge
            val orbitRadius = buttonRadius + 5.dp.toPx()
            val arcRectOffset = Offset(cx - orbitRadius, cy - orbitRadius)
            val arcRectSize = Size(orbitRadius * 2f, orbitRadius * 2f)

            rotate(degrees = rotationAngle, pivot = Offset(cx, cy)) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to Color.Transparent,
                        0.65f to Color.Transparent,
                        1.0f to activeColor,
                        center = Offset(cx, cy)
                    ),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcRectOffset,
                    size = arcRectSize,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )

                // Bright leading dot on the arc tip
                val dotRadius = 4.dp.toPx()
                val dotX = cx + orbitRadius
                val dotY = cy
                drawCircle(
                    color = activeColor,
                    radius = dotRadius,
                    center = Offset(dotX, dotY)
                )
            }

            // 3. Floating Particles
            val numParticles = 12
            for (i in 0 until numParticles) {
                val pAngle = (rotationAngle * 1.5f + i * (360f / numParticles)) % 360f
                val pProgress = ((pulseFraction + i.toFloat() / numParticles) % 1.0f)
                val pRadius = buttonRadius + pProgress * (maxWaveRadius * 1.2f - buttonRadius)

                val rad = Math.toRadians(pAngle.toDouble())
                val px = cx + (pRadius * cos(rad)).toFloat()
                val py = cy + (pRadius * sin(rad)).toFloat()

                val pAlpha = (1f - pProgress).coerceIn(0f, 1f)
                drawCircle(
                    color = activeColor.copy(alpha = pAlpha * 0.8f),
                    radius = 2.dp.toPx(),
                    center = Offset(px, py)
                )
            }
        }
    }
}
