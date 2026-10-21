package com.selfuse.cellmap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectorGeometryTest {

    @Test
    fun `parse azimuth from common spreadsheet text`() {
        assertEquals(30.0, SectorGeometry.parseAzimuth("30")!!, 1e-9)
        assertEquals(30.0, SectorGeometry.parseAzimuth(" 30° ")!!, 1e-9)
        assertEquals(30.5, SectorGeometry.parseAzimuth("30.5 度")!!, 1e-9)
        assertEquals(350.0, SectorGeometry.parseAzimuth("-10")!!, 1e-9)
        assertEquals(10.0, SectorGeometry.parseAzimuth("370")!!, 1e-9)
        assertNull(SectorGeometry.parseAzimuth(null))
        assertNull(SectorGeometry.parseAzimuth(""))
        assertNull(SectorGeometry.parseAzimuth("无"))
    }

    @Test
    fun `position key merges identical coordinates only`() {
        assertEquals(
            SectorGeometry.positionKey(30.1234567, 120.7654321),
            SectorGeometry.positionKey(30.1234567, 120.7654321)
        )
        assertTrue(
            SectorGeometry.positionKey(30.1234567, 120.7654321) !=
                SectorGeometry.positionKey(30.1234568, 120.7654321)
        )
        assertTrue(
            SectorGeometry.positionKey(30.1234567, 120.7654321) !=
                SectorGeometry.positionKey(30.1234567, 120.7654322)
        )
    }

    @Test
    fun `wedge ring starts and ends at center`() {
        val ring = SectorGeometry.wedge(30.0, 120.0, 45.0, 100.0)
        assertEquals(SectorGeometry.ARC_SEGMENTS + 3, ring.size)
        assertEquals(ring.first(), ring.last())
        assertEquals(30.0 to 120.0, ring.first())
    }

    @Test
    fun `north bearing increases latitude only`() {
        val (lat, lon) = SectorGeometry.destination(30.0, 120.0, 0.0, 111.32)
        assertEquals(30.001, lat, 1e-5)
        assertEquals(120.0, lon, 1e-9)
    }

    @Test
    fun `east bearing increases longitude`() {
        val (lat, lon) = SectorGeometry.destination(30.0, 120.0, 90.0, 100.0)
        assertEquals(30.0, lat, 1e-9)
        assertTrue(lon > 120.0)
    }
}
