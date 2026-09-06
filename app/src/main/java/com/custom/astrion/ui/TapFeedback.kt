package com.custom.astrion.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.Role

/**
 * Tap feedback — the tiny "tick" sound the device's own Android UI menus
 * play when you touch them. Jetpack Compose's plain
 * [androidx.compose.foundation.clickable] does NOT play this sound by
 * default (it only shows a ripple), so every tappable element in the
 * dashboard is silent unless we opt in.
 *
 * The sound is fired by a lambda provided through [LocalTapFeedback] so a
 * single setting in MainActivity (persisted, exposed on the settings page)
 * can gate it app-wide without every card having to read the preference.
 * The default value is a no-op so cards and previews that render without a
 * provider stay silent and side-effect-free.
 *
 * See [android.media.AudioManager.playSoundEffect] (specifically
 * [AudioManager.FX_KEY_CLICK]) for the underlying API.
 */
val LocalTapFeedback = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Drop-in replacement for [Modifier.clickable] that also plays the tap
 * feedback sound through [LocalTapFeedback]. Use this anywhere the dashboard
 * handles a tap (scene buttons, remote keys, dots, dialogs, close
 * affordances) so the device gives the same "tap" notice its own Android
 * menus do.
 *
 * The feedback fires before [onClick] so it lands with the touch, not after
 * the (possibly slow) action — matching how platform views behave.
 */
fun Modifier.tapClickable(enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit): Modifier =
    composed {
        val feedback = LocalTapFeedback.current
        clickable(enabled = enabled, onClickLabel = onClickLabel, role = role) {
            feedback()
            onClick()
        }
    }
