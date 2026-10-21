package com.selfuse.cellmap.map

/**
 * 标记随地图层级连续放大：低层级保持轻量，高层级放大到易点击的尺寸。
 */
object MarkerScaling {

    fun iconScale(zoom: Double): Float {
        val progress = ((zoom - 10.0) / 10.0).coerceIn(0.0, 1.0)
        return (0.52 + progress * 0.48).toFloat()
    }

    fun ringSpacing(zoom: Double): Float {
        val progress = ((zoom - 11.0) / 9.0).coerceIn(0.0, 1.0).toFloat()
        return MarkerRings.SPACING_PX * (0.95f + progress * 0.55f)
    }
}
