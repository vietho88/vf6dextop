package moe.n4tsu.dextop

/** Display-local rectangle, independent of Android framework classes. */
internal data class WorkspaceBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    companion object {
        /** Retains window size when possible, then moves the whole window into the usable area. */
        fun fit(source: WorkspaceBounds, usable: WorkspaceBounds): WorkspaceBounds {
            if (usable.isEmpty || source.isEmpty) return usable
            // Long arithmetic also handles old or malformed coordinates near Int limits.
            val width = (source.right.toLong() - source.left)
                .coerceAtMost(usable.right.toLong() - usable.left)
            val height = (source.bottom.toLong() - source.top)
                .coerceAtMost(usable.bottom.toLong() - usable.top)
            val left = source.left.toLong().coerceIn(usable.left.toLong(), usable.right - width)
            val top = source.top.toLong().coerceIn(usable.top.toLong(), usable.bottom - height)
            return WorkspaceBounds(left.toInt(), top.toInt(), (left + width).toInt(), (top + height).toInt())
        }
    }
}
