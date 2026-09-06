package com.custom.astrion.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R

/**
 * Shown instead of [Dashboard] whenever MainActivity's charge/dock detection
 * (see `ChargeDockState` there) decides this device is genuinely sitting on
 * its charging dock — not just plugged into a computer over USB, which
 * reports charging identically but isn't what this screen is for.
 *
 * Auto-dims after a stretch of no touches (MainActivity's own timer, driven
 * by [android.app.Activity.onUserInteraction] — see there); `dimmed` here
 * only affects this composable's own content (a light fade), the actual
 * darkness comes from MainActivity lowering the window's own brightness,
 * which this composable has no part in and doesn't need to know about
 * beyond this one flag for its own visual state.
 */
@Composable
fun ChargingScreen(dimmed: Boolean) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val (percent, charging) = rememberBatteryState(context)

    val infiniteTransition = rememberInfiniteTransition(label = "charging-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "charging-pulse-alpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "charging-pulse-scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .graphicsLayer { alpha = if (dimmed) 0.35f else 1f },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        batteryGlyph(
            percent = percent,
            charging = charging,
            modifier = Modifier
                .size(width = 160.dp, height = 88.dp)
                .graphicsLayer {
                    if (percent < 100) {
                        alpha = pulseAlpha
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                }
        )
        Text(
            "$percent%",
            color = theme.primaryText,
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text(
            stringResource(if (percent >= 100) R.string.charging_screen_full else R.string.charging_screen_charging),
            color = theme.mutedText,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
