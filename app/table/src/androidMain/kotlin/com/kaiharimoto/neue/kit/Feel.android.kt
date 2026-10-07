package com.kaiharimoto.neue.kit

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.kaiharimoto.mastertool.core.haptics.Haptic

@Composable
actual fun rememberFeel(): (Haptic) -> Unit {
    val view = LocalView.current
    return remember(view) { { haptic -> view.performHapticFeedback(constant(haptic)) } }
}

/**
 * The vocabulary in Android's own words. The newer constants are the ones the
 * system tunes per actuator; an older tablet gets the nearest it has.
 */
private fun constant(haptic: Haptic): Int {
    val api = Build.VERSION.SDK_INT
    return when (haptic) {
        Haptic.PEEK -> HapticFeedbackConstants.LONG_PRESS
        Haptic.LIFT -> when {
            api >= 34 -> HapticFeedbackConstants.DRAG_START
            api >= 30 -> HapticFeedbackConstants.GESTURE_START
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }
        Haptic.DETENT, Haptic.FLIP ->
            if (api >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        Haptic.LAND ->
            if (api >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.KEYBOARD_TAP
        Haptic.STACK, Haptic.DEAL ->
            if (api >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        Haptic.SLIDE ->
            if (api >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.KEYBOARD_TAP
        else -> HapticFeedbackConstants.CLOCK_TICK
    }
}
