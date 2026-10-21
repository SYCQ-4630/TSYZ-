package com.selfuse.cellmap.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.util.concurrent.ConcurrentHashMap

/** 星形临时标识，与四个基站分类的圆形/方形图标形成形状区分。 */
object TempMarkerIcons {

    private const val SIZE = 64
    private val cache = ConcurrentHashMap<Int, Bitmap>()

    fun bitmap(color: Int): Bitmap = cache.getOrPut(color) { draw(color) }

    fun clear() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }

    private fun draw(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = SIZE / 2f
        val outer = SIZE / 2f - 6f
        val inner = outer * 0.45f
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = Math.toRadians(-90.0 + i * 36.0)
            val x = center + radius * Math.cos(angle).toFloat()
            val y = center + radius * Math.sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, border)
        return bitmap
    }
}
