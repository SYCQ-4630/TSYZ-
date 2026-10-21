package com.selfuse.cellmap.map

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 同坐标标记分环渲染：每个环由独立符号图层绘制，图层偏移是静态常量，
 * 不依赖数据驱动表达式，渲染器兼容性最好。
 */
object MarkerRings {

    const val COUNT = 12
    private const val GOLDEN_ANGLE = 2.399963229728653
    private const val SPACING_PX = 10.5

    fun offset(ring: Int): Array<Float> {
        if (ring <= 0) return arrayOf(0f, 0f)
        val angle = ring * GOLDEN_ANGLE
        val radius = SPACING_PX * sqrt(ring.toDouble())
        return arrayOf((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
    }
}
