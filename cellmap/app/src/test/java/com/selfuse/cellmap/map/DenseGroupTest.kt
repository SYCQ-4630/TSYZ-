package com.selfuse.cellmap.map

import com.selfuse.cellmap.core.model.MapMarker
import com.selfuse.cellmap.core.model.MarkerStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DenseGroupTest {

    @Test
    fun `same coordinate markers merge into one group`() {
        val markers = listOf(
            marker(3, 30.0, 120.0, "c"),
            marker(1, 30.0, 120.0, "a"),
            marker(2, 30.0, 120.0, "b"),
            marker(4, 30.1, 120.1, "d")
        )
        val groups = DenseGroups.build(markers)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].count)
        assertEquals(listOf(1L, 2L, 3L), groups[0].markers.map { it.id })
    }

    @Test
    fun `unique coordinates produce no group`() {
        val markers = listOf(marker(1, 30.0, 120.0, "a"), marker(2, 30.1, 120.1, "b"))
        assertTrue(DenseGroups.build(markers).isEmpty())
    }

    @Test
    fun `icon scale and ring spacing grow with zoom`() {
        assertTrue(MarkerScaling.iconScale(18.0) > MarkerScaling.iconScale(12.0))
        assertTrue(MarkerScaling.ringSpacing(18.0) > MarkerScaling.ringSpacing(12.0))
        assertTrue(MarkerScaling.iconScale(18.0) <= 1.0f)
    }

    private fun marker(id: Long, lat: Double, lon: Double, cell: String) = MapMarker(
        id = id,
        lat = lat,
        lon = lon,
        style = MarkerStyle.UNICOM_SHARED,
        system = "4G",
        title = "cell-$cell",
        stationId = "s",
        cellId = cell
    )
}
