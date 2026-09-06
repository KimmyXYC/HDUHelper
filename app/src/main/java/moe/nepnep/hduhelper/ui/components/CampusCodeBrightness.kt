package moe.nepnep.hduhelper.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Window-only override: no system brightness permission or preference changes. */
@Composable
fun CampusCodeBrightness(active: Boolean) {
    val activity = LocalActivity.current
    LifecycleResumeEffect(active, activity) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness
        if (active && window != null) window.attributes = window.attributes.apply { screenBrightness = 1f }
        onPauseOrDispose {
            if (active && window != null && previous != null) {
                window.attributes = window.attributes.apply { screenBrightness = previous }
            }
        }
    }
}
