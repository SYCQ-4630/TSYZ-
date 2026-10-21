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
        val groups = DenseGroups.build(markers, DenseGroups.MIN_ZOOM)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].count)
        assertEquals(listOf(1L, 2L, 3L), groups[0].markers.map { it.id })
    }

    @Test
    fun `nearby markers merge at max zoom but stay separate when zoomed out`() {
        val markers = listOf(
            marker(1, 30.000000, 120.000000, "a"),
            marker(2, 30.000010, 120.000000, "b"),
            marker(3, 30.100000, 120.100000, "c")
        )
        assertTrue(DenseGroups.build(markers, DenseGroups.MIN_ZOOM).isNotEmpty())
        assertTrue(DenseGroups.build(markers, 15.0).isEmpty())
    }

    @Test
    fun `unique coordinates produce no group`() {
        val markers = listOf(marker(1, 30.0, 120.0, "a"), marker(2, 30.1, 120.1, "b"))
        assertTrue(DenseGroups.build(markers, DenseGroups.MIN_ZOOM).isEmpty())
    }

    @Test
    fun `icon scale grows with zoom and rings collapse when zoomed out`() {
        assertTrue(MarkerScaling.iconScale(20.0) > MarkerScaling.iconScale(12.0))
        assertTrue(MarkerScaling.ringSpacing(15.0) == 0f)
        assertTrue(MarkerScaling.ringSpacing(20.0) > MarkerScaling.ringSpacing(16.0))
        assertTrue(MarkerScaling.iconScale(20.0) <= 1.35f)
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
