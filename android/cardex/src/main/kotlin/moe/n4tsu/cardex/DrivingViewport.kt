package moe.n4tsu.cardex

import kotlin.math.roundToInt

/** Pixel bounds with exclusive right and bottom edges. */
internal data class DrivingBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = right <= left || bottom <= top
}

/** Pure geometry shared by surface rendering and click routing. */
internal object DrivingViewport {
    fun resolve(
        width: Int,
        height: Int,
        visibleArea: DrivingBounds?,
        stableArea: DrivingBounds?,
    ): DrivingBounds? {
        if (width <= 0 || height <= 0) return null
        val surface = DrivingBounds(0, 0, width, height)
        // Empty or entirely off-surface callbacks carry no usable geometry.
        val visible = visibleArea?.intersection(surface)
        val stable = stableArea?.intersection(surface)
        return when {
            visible != null && stable != null -> stable.intersection(visible) ?: visible
            visible != null -> visible
            stable != null -> stable
            else -> surface
        }
    }

    /** Centers an aspect-preserving image inside the available host area. */
    fun fit(contentWidth: Int, contentHeight: Int, area: DrivingBounds): DrivingBounds {
        if (contentWidth <= 0 || contentHeight <= 0 || area.isEmpty) return area
        val scale = minOf(
            area.width.toDouble() / contentWidth,
            area.height.toDouble() / contentHeight,
        )
        val width = (contentWidth * scale).roundToInt().coerceIn(1, area.width)
        val height = (contentHeight * scale).roundToInt().coerceIn(1, area.height)
        val left = area.left + (area.width - width) / 2
        val top = area.top + (area.height - height) / 2
        return DrivingBounds(left, top, left + width, top + height)
    }

    /** Ignores letterboxing and maps rendered pixels into the input display. */
    fun mapClick(
        x: Float,
        y: Float,
        renderedArea: DrivingBounds,
        inputWidth: Int,
        inputHeight: Int,
    ): Pair<Float, Float>? {
        if (!x.isFinite() || !y.isFinite() || renderedArea.isEmpty ||
            inputWidth <= 0 || inputHeight <= 0
        ) return null
        if (x < renderedArea.left || x >= renderedArea.right ||
            y < renderedArea.top || y >= renderedArea.bottom
        ) return null
        val mappedX = ((x.toDouble() - renderedArea.left) / renderedArea.width * inputWidth)
            .toFloat().coerceIn(0f, exclusiveMaximum(inputWidth))
        val mappedY = ((y.toDouble() - renderedArea.top) / renderedArea.height * inputHeight)
            .toFloat().coerceIn(0f, exclusiveMaximum(inputHeight))
        return mappedX to mappedY
    }

    private fun DrivingBounds.intersection(other: DrivingBounds): DrivingBounds? {
        val clipped = DrivingBounds(
            maxOf(left, other.left),
            maxOf(top, other.top),
            minOf(right, other.right),
            minOf(bottom, other.bottom),
        )
        return clipped.takeUnless { it.isEmpty }
    }

    // A Float conversion can round the last input coordinate to the edge.
    private fun exclusiveMaximum(size: Int): Float {
        val edge = size.toFloat()
        return if (edge.toDouble() < size.toDouble()) edge else Math.nextDown(edge)
    }
}
