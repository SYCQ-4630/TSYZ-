package com.selfuse.cellmap.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class TempMarker(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val content: String,
    val color: Int,
    val createdAt: Long
)

/** 黄金角配色：编号连续时色相大幅跳变，颜色稳定可复现。 */
object TempMarkerPalette {

    private const val GOLDEN_ANGLE = 137.508

    fun colorFor(id: Long): Int {
        val hue = ((id * GOLDEN_ANGLE) % 360.0 + 360.0) % 360.0
        return hslToArgb(hue.toFloat(), 0.86f, 0.48f)
    }

    fun hslToArgb(hue: Float, saturation: Float, lightness: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f / 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        if (s == 0f) {
            val v = (l * 255f).toInt().coerceIn(0, 255)
            return argb(v, v, v)
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val r = hueToRgb(p, q, h + 1f / 3f)
        val g = hueToRgb(p, q, h)
        val b = hueToRgb(p, q, h - 1f / 3f)
        return argb((r * 255f).toInt(), (g * 255f).toInt(), (b * 255f).toInt())
    }

    private fun hueToRgb(p: Float, q: Float, t: Float): Float {
        var value = t
        if (value < 0f) value += 1f
        if (value > 1f) value -= 1f
        return when {
            value < 1f / 6f -> p + (q - p) * 6f * value
            value < 1f / 2f -> q
            value < 2f / 3f -> p + (q - p) * (2f / 3f - value) * 6f
            else -> p
        }
    }

    private fun argb(r: Int, g: Int, b: Int): Int {
        val red = r.coerceIn(0, 255)
        val green = g.coerceIn(0, 255)
        val blue = b.coerceIn(0, 255)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
