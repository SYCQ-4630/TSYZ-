package com.selfuse.cellmap.map

import kotlin.math.pow

/**
 * 标记随地图层级等比缩放：双指扩散（放大）时变大，双指聚拢（缩小）时变小。
 * 使用指数曲线贴近地图比例尺，低层级保持轻量，高层级便于点击。
 */
object MarkerScaling {

    const val RING_MIN_ZOOM = 16.0

    fun iconScale(zoom: Double): Float {
        val raw = 0.6 * 2.0.pow((zoom - 15.0) * 0.18)
        return raw.coerceIn(0.42, 1.35).toFloat()
    }

    fun ringSpacing(zoom: Double): Float {
        if (zoom < RING_MIN_ZOOM) return 0f
        return MarkerRings.SPACING_PX * (iconScale(zoom) / 0.6f) * 0.9f
    }
}
