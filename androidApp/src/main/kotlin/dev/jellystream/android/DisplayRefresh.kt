package dev.jellystream.android

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import kotlin.math.abs

/**
 * Match the TV's refresh rate to the file. 24p on a 60 Hz panel is the
 * 3:2 pulldown that makes film judder; Android TV can switch modes.
 */
fun applyDisplayRefresh(activity: Activity?, frameRate: Double?) {
    if (activity == null || Build.VERSION.SDK_INT < 23) return
    val preferred = dev.jellystream.shared.DisplayRefresh.preferredRate(frameRate)
    val attrs = activity.window.attributes
    if (preferred == null) {
        attrs.preferredDisplayModeId = 0
        activity.window.attributes = attrs
        return
    }
    val mode = activity.display?.supportedModes
        ?.minByOrNull { abs(it.refreshRate - preferred.toFloat()) }
        ?: return
    attrs.preferredDisplayModeId = mode.modeId
    activity.window.attributes = attrs
}

fun clearDisplayRefresh(activity: Activity?) {
    if (activity == null || Build.VERSION.SDK_INT < 23) return
    val attrs = activity.window.attributes
    attrs.preferredDisplayModeId = 0
    activity.window.attributes = attrs
}
