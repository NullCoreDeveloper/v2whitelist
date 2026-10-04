package com.kiktor.v2whitelist.util

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.ScaleAnimation
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.handler.MmkvManager

object PremiumUiHelper {

    fun applyPremiumEffects(
        context: Context,
        rootView: View,
        statusCard: MaterialCardView,
        connectButton: View,
        pulseView: View?
    ) {
        // Mesh Gradients
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREMIUM_GRADIENTS, false)) {
            // Create a dynamic gradient using theme colors
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
            val bgColor = typedValue.data
            
            context.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
            val primaryColor = typedValue.data

            // Check if light theme (if night mode is not yes and UI mode is not night)
            val isNightMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES || 
                              MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT) == "yes"

            // Blend primary with background to get subtle gradient colors
            // Use stronger blends for light theme so it's not "wyglaznaya" (eyesore) and actually visible
            val blendRatio1 = if (isNightMode) 0.15f else 0.35f
            val blendRatio2 = if (isNightMode) 0.05f else 0.15f

            val blend1 = androidx.core.graphics.ColorUtils.blendARGB(bgColor, primaryColor, blendRatio1)
            val blend2 = androidx.core.graphics.ColorUtils.blendARGB(bgColor, primaryColor, blendRatio2)
            
            val animStyle = MmkvManager.decodeSettingsString(AppConfig.PREF_PREMIUM_GRADIENT_ANIM) ?: "none"
            val isBreathing = animStyle == "breathing" || animStyle == "true"
            
            if (isBreathing) {
                val animDrawable = AnimationDrawable()
                val orientations = arrayOf(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    android.graphics.drawable.GradientDrawable.Orientation.TR_BL,
                    android.graphics.drawable.GradientDrawable.Orientation.BR_TL,
                    android.graphics.drawable.GradientDrawable.Orientation.BL_TR
                )
                for (orientation in orientations) {
                    val frame = android.graphics.drawable.GradientDrawable(
                        orientation,
                        intArrayOf(blend1, blend2, bgColor)
                    )
                    frame.setGradientType(android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT)
                    animDrawable.addFrame(frame, 3000)
                }
                animDrawable.setEnterFadeDuration(2000)
                animDrawable.setExitFadeDuration(2000)
                rootView.background = animDrawable
                animDrawable.start()
            } else {
                val gradient = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(blend1, blend2, bgColor)
                )
                gradient.setGradientType(android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT)
                rootView.background = gradient
                
                // Store the base colors in tags so we can use them for reactive animations
                rootView.setTag(R.id.gradient_base_color1, blend1)
                rootView.setTag(R.id.gradient_base_color2, blend2)
                rootView.setTag(R.id.gradient_bg_color, bgColor)
            }
        }

        // Glassmorphism (Translucent Card)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREMIUM_GLASS, false)) {
            // Instead of RenderEffect which blurs the text, we make the card translucent
            val isNightMode = MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT) == "yes"
            val alphaColor = if (isNightMode) Color.parseColor("#4D000000") else Color.parseColor("#4DFFFFFF")
            statusCard.setCardBackgroundColor(alphaColor)
            statusCard.cardElevation = 0f
            statusCard.strokeWidth = 2
            statusCard.strokeColor = if (isNightMode) Color.parseColor("#33FFFFFF") else Color.parseColor("#33000000")
            
            // Fix text readability against complex gradients
            val tvStatus = statusCard.findViewById<android.widget.TextView>(R.id.tv_status)
            val tvDetail = statusCard.findViewById<android.widget.TextView>(R.id.tv_status_detail)
            val shadowColor = if (isNightMode) Color.parseColor("#80000000") else Color.parseColor("#CCFFFFFF")
            tvStatus?.setShadowLayer(8f, 0f, 2f, shadowColor)
            tvDetail?.setShadowLayer(8f, 0f, 2f, shadowColor)
        }

        // Micro-animations on connect button (Scale morph)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREMIUM_ANIMATIONS, true)) {
            // Apply scale touch listener
            connectButton.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(150).start()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(OvershootInterpolator()).start()
                    }
                }
                false // allow onClick to fire
            }
        }

        // Skeleton & Pulse are typically handled in data loading and connection states respectively
    }

    fun updateGradientState(context: Context, rootView: View, isConnected: Boolean) {
        val animStyle = MmkvManager.decodeSettingsString(AppConfig.PREF_PREMIUM_GRADIENT_ANIM)
        if (animStyle != "reactive" || !MmkvManager.decodeSettingsBool(AppConfig.PREF_PREMIUM_GRADIENTS, false)) return

        val bgColor = rootView.getTag(R.id.gradient_bg_color) as? Int ?: return
        val baseColor1 = rootView.getTag(R.id.gradient_base_color1) as? Int ?: return
        val baseColor2 = rootView.getTag(R.id.gradient_base_color2) as? Int ?: return

        val targetColor1 = if (isConnected) ContextCompat.getColor(context, R.color.colorPing) else baseColor1
        val targetColor2 = if (isConnected) androidx.core.graphics.ColorUtils.blendARGB(bgColor, targetColor1, 0.4f) else baseColor2

        // Optional: Animate the transition
        val currentGradient = rootView.background as? android.graphics.drawable.GradientDrawable
        if (currentGradient != null) {
            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
            val startColor1 = (rootView.getTag(R.id.gradient_base_color1 + 100) as? Int) ?: baseColor1
            val startColor2 = (rootView.getTag(R.id.gradient_base_color2 + 100) as? Int) ?: baseColor2

            animator.addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val c1 = androidx.core.graphics.ColorUtils.blendARGB(startColor1, targetColor1, fraction)
                val c2 = androidx.core.graphics.ColorUtils.blendARGB(startColor2, targetColor2, fraction)
                currentGradient.colors = intArrayOf(c1, c2, bgColor)
            }
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    rootView.setTag(R.id.gradient_base_color1 + 100, targetColor1)
                    rootView.setTag(R.id.gradient_base_color2 + 100, targetColor2)
                }
            })
            animator.duration = 600
            animator.start()
        }
    }

    fun triggerHaptic(view: View) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREMIUM_HAPTIC, true)) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}
