package com.selfuse.cellmap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TempMarkerPaletteTest {

    @Test
    fun `color is deterministic and fully opaque`() {
        repeat(20) { index ->
            val id = index.toLong()
            val first = TempMarkerPalette.colorFor(id)
            assertEquals(first, TempMarkerPalette.colorFor(id))
            assertEquals(0xFF, (first ushr 24) and 0xFF)
        }
    }

    @Test
    fun `consecutive ids differ clearly`() {
        val colors = (1L..10L).map(TempMarkerPalette::colorFor)
        assertEquals(colors.size, colors.distinct().size)
        colors.zipWithNext().forEach { (a, b) ->
            val distance = channelDistance(a, b)
            assertTrue("adjacent colors should be distinguishable", distance > 120)
        }
    }

    @Test
    fun `hsl bounds stay valid`() {
        listOf(0f, 90f, 180f, 270f, 359f).forEach { hue ->
            val color = TempMarkerPalette.hslToArgb(hue, 0.86f, 0.48f)
            assertEquals(0xFF, (color ushr 24) and 0xFF)
            assertTrue((color and 0xFFFFFF) != 0)
        }
    }

    private fun channelDistance(a: Int, b: Int): Int {
        val dr = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)
        val dg = ((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
    }
}
