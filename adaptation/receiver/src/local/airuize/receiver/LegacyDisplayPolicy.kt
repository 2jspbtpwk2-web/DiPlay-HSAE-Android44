package local.airuize.receiver

/** Display choices use the app's usable area, which may exclude a permanent OEM control bar. */
internal object LegacyDisplayPolicy {
    const val AUTO = "auto"
    const val SMOOTH = "smooth"
    const val CLEAR = "clear"
    const val MANUAL = "manual"
    val modes = listOf(AUTO, SMOOTH, CLEAR, MANUAL)

    data class Size(val width: Int, val height: Int)

    fun mode(stored: String?, width: Int?, height: Int?, lowResolution: Boolean): String {
        if (stored in modes) return stored!!
        val w = width ?: if (lowResolution) 800 else 1024
        val h = height ?: if (lowResolution) 480 else 600
        // These were the two old hard-coded defaults, including installs that saved them explicitly.
        return if ((width == null && height == null) || (w == 1024 && h == 600) || (w == 800 && h == 480)) AUTO else MANUAL
    }

    fun resolve(mode: String, availableWidth: Int, availableHeight: Int, customWidth: Int, customHeight: Int): Size {
        if (mode == MANUAL) return Size(customWidth.coerceIn(640, 1920), customHeight.coerceIn(360, 1920))
        val w = availableWidth.takeIf { it > 0 } ?: 1024
        val h = availableHeight.takeIf { it > 0 } ?: 600
        val shortLimit = if (mode == SMOOTH) 640 else if (mode == CLEAR) 1080 else 720
        // Android 4.4 i.MX6 codecs need conservative dimensions; 1072 is the last full 16-pixel block below 1080.
        val factor = minOf(1.0, shortLimit.toDouble() / minOf(w, h), 1072.0 / maxOf(w, h))
        fun aligned(value: Int): Int = (((value * factor / 16.0) + 0.5).toInt() * 16).coerceIn(16, 1072)
        return Size(aligned(w), aligned(h))
    }

    fun label(mode: String): String = when (mode) {
        SMOOTH -> "流畅优先"
        CLEAR -> "清晰优先（最长1080）"
        MANUAL -> "自定义（保留已有设置）"
        else -> "自动（均衡，适配横竖屏）"
    }

    /** Fit without stretching or cropping; touch coordinates remain local to this rectangle. */
    fun fit(availableWidth: Int, availableHeight: Int, videoWidth: Int, videoHeight: Int): Size {
        if (availableWidth <= 0 || availableHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return Size(0, 0)
        val width = minOf(availableWidth.toLong(), availableHeight.toLong() * videoWidth / videoHeight).toInt().coerceAtLeast(1)
        return Size(width, (width.toLong() * videoHeight / videoWidth).toInt().coerceIn(1, availableHeight))
    }
}
