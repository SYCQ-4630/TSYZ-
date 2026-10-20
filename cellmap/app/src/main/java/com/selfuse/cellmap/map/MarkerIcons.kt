package com.selfuse.cellmap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.selfuse.cellmap.core.model.MarkerStyle
import java.util.concurrent.ConcurrentHashMap

/**
 * Marker 图标在首次进入地图时绘制并缓存，样式切换或数据刷新不重复解码。
 * 颜色 + 形状双重编码，色弱用户仍可区分。
 */
object MarkerIconCache {
    private const val SIZE = 64
    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun icons(context: Context): Map<String, Bitmap> = MarkerStyle.entries.associate { style ->
        style.iconName to cache.getOrPut(style.iconName) { draw(style) }
    }

    fun clear() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }

    private fun draw(style: MarkerStyle): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.colorHex.toInt()
            this.style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        if (style.isSquare) {
            val rect = RectF(12f, 12f, SIZE - 12f, SIZE - 12f)
            canvas.drawRoundRect(rect, 5f, 5f, fill)
            canvas.drawRoundRect(rect, 5f, 5f, border)
        } else {
            canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f - 13f, fill)
            canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f - 13f, border)
        }
        return bitmap
    }
}
