package com.selfuse.cellmap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import kotlin.math.ceil

/**
 * 扇区名称使用位图标签渲染，系统字体直接绘制中文，避免地图字体缺少中文字形。
 * 标签为白底圆角胶囊，悬浮在扇区外侧，由引导线连接。
 */
object SectorLabelIcons {

    private const val TEXT_SIZE_SP = 11f
    private const val MAX_TEXT_DP = 132f
    private const val PADDING_H_DP = 6f
    private const val PADDING_V_DP = 4f
    private const val BORDER_DP = 1f

    private val labelTypeface: Typeface by lazy {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun iconName(text: String): String = "sector-label:$text"

    fun bitmap(context: Context, text: String): Bitmap {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP, metrics)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(27, 27, 27)
            this.textSize = textSize
            typeface = labelTypeface
        }
        val maxTextWidth = MAX_TEXT_DP * density
        val display = TextUtils.ellipsize(text, paint, maxTextWidth, TextUtils.TruncateAt.END).toString()
        val textWidth = paint.measureText(display)
        val padH = PADDING_H_DP * density
        val padV = PADDING_V_DP * density
        val border = BORDER_DP * density
        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val width = ceil(textWidth + padH * 2 + border * 2).toInt().coerceAtLeast(1)
        val height = ceil(textHeight + padV * 2 + border * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(242, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(144, 164, 174)
            style = Paint.Style.STROKE
            strokeWidth = border
        }
        val rect = RectF(border, border, width - border, height - border)
        val radius = rect.height() / 2f
        canvas.drawRoundRect(rect, radius, radius, background)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        canvas.drawText(display, padH + border, padV + border - fontMetrics.top, paint)
        return bitmap
    }
}
