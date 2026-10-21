package com.selfuse.cellmap.map

import com.selfuse.cellmap.core.model.MapMarker
import com.selfuse.cellmap.core.model.MarkerStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerRingTest {

    @Test
    fun `unique coordinates keep zero offset`() {
        val markers = listOf(marker(1, 30.0, 120.0), marker(2, 30.1, 120.1))
        assertTrue(GeoJsonBuilder.markerRings(markers).isEmpty())
    }

    @Test
    fun `same coordinates are fanned out`() {
        val markers = listOf(
            marker(1, 30.0, 120.0),
            marker(2, 30.0, 120.0),
            marker(3, 30.0, 120.0)
        )
        val rings = GeoJsonBuilder.markerRings(markers)
        assertEquals(3, rings.size)
        assertEquals(setOf(0, 1, 2), rings.values.toSet())
        assertNotEquals(rings[0], rings[1])
        assertTrue(MarkerRings.offset(1).any { it != 0f })
    }

    private fun marker(id: Long, lat: Double, lon: Double) = MapMarker(
        id = id,
        lat = lat,
        lon = lon,
        style = MarkerStyle.UNICOM_SHARED,
        system = "4G",
        title = "cell-$id",
        stationId = "s$id",
        cellId = "c$id"
    )
}
