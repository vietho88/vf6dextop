package moe.n4tsu.dextop

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager

internal class WorkspaceLayoutEngine(
    private val context: Context,
    private val environment: DesktopEnvironment,
    private val tag: String
) {
    fun fit(displayId: Int, displayWidth: Int, displayHeight: Int, source: Rect): Rect {
        val usable = usableArea(displayId, displayWidth, displayHeight)
        val fitted = WorkspaceBounds.fit(
            WorkspaceBounds(source.left, source.top, source.right, source.bottom),
            WorkspaceBounds(usable.left, usable.top, usable.right, usable.bottom),
        )
        return Rect(fitted.left, fitted.top, fitted.right, fitted.bottom)
    }

    fun position(
        displayId: Int,
        displayWidth: Int,
        displayHeight: Int,
        position: String
    ): Rect {
        val usable = usableArea(displayId, displayWidth, displayHeight)
        val centerX = usable.left + usable.width() / 2
        val centerY = usable.top + usable.height() / 2
        val third = usable.width() / 3
        return when (position) {
            "left" -> Rect(usable.left, usable.top, usable.left + third, usable.bottom)
            "right" -> Rect(usable.left + third * 2, usable.top, usable.right, usable.bottom)
            "half_left" -> Rect(usable.left, usable.top, centerX, usable.bottom)
            "half_right" -> Rect(centerX, usable.top, usable.right, usable.bottom)
            "top_left" -> Rect(usable.left, usable.top, centerX, centerY)
            "top_right" -> Rect(centerX, usable.top, usable.right, centerY)
            "bottom_half" -> Rect(usable.left, centerY, usable.right, usable.bottom)
            "top_half" -> Rect(usable.left, usable.top, usable.right, centerY)
            "grid_top_left" -> Rect(usable.left, usable.top, centerX, centerY)
            "grid_top_right" -> Rect(centerX, usable.top, usable.right, centerY)
            "grid_bottom_left" -> Rect(usable.left, centerY, centerX, usable.bottom)
            "grid_bottom_right" -> Rect(centerX, centerY, usable.right, usable.bottom)
            "wide_left" -> Rect(usable.left, usable.top, usable.left + third * 2, usable.bottom)
            "narrow_right" -> Rect(usable.left + third * 2, usable.top, usable.right, usable.bottom)
            "narrow_left" -> Rect(usable.left, usable.top, usable.left + third, usable.bottom)
            "wide_right" -> Rect(usable.left + third, usable.top, usable.right, usable.bottom)
            "right_top" -> Rect(usable.left + third * 2, usable.top, usable.right, centerY)
            "right_bottom" -> Rect(usable.left + third * 2, centerY, usable.right, usable.bottom)
            else -> Rect(usable.left + third, usable.top, usable.left + third * 2, usable.bottom)
        }
    }

    private fun usableArea(displayId: Int, width: Int, height: Int): Rect {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: return Rect(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(displayMetrics)
        val actualWidth = displayMetrics.widthPixels.takeIf { it > 0 } ?: width.coerceAtLeast(1)
        val actualHeight = displayMetrics.heightPixels.takeIf { it > 0 } ?: height.coerceAtLeast(1)
        val full = Rect(0, 0, actualWidth, actualHeight)
        if (!environment.supportsLaunchBounds) return full
        val displayContext = context.createDisplayContext(display)
        val metrics = runCatching {
            displayContext.getSystemService(WindowManager::class.java).maximumWindowMetrics
        }.getOrNull() ?: return full
        val types = WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement()
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(types)
        // Display metrics own the logical viewport. OEM WindowMetrics bounds can
        // lag behind a forced resize; use that snapshot only for its insets.
        val left = insets.left.coerceIn(0, actualWidth - 1)
        val top = insets.top.coerceIn(0, actualHeight - 1)
        return Rect(
            left,
            top,
            (actualWidth - insets.right.coerceAtLeast(0)).coerceIn(left + 1, actualWidth),
            (actualHeight - insets.bottom.coerceAtLeast(0)).coerceIn(top + 1, actualHeight),
        )
    }
}
