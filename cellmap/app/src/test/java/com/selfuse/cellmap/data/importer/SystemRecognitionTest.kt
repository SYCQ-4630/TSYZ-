package com.selfuse.cellmap.data.importer

import com.selfuse.cellmap.core.model.NetworkSystem
import com.selfuse.cellmap.core.model.StationField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemRecognitionTest {

    @Test
    fun `explicit generation tokens win`() {
        assertEquals(NetworkSystem.FIVE_G, NetworkSystem.fromRaw("5G"))
        assertEquals(NetworkSystem.FIVE_G, NetworkSystem.fromRaw("NR3500"))
        assertEquals(NetworkSystem.FOUR_G, NetworkSystem.fromRaw("LTE1800"))
        assertEquals(NetworkSystem.FOUR_G, NetworkSystem.fromRaw("4G"))
        assertEquals(NetworkSystem.FOUR_G, NetworkSystem.fromRaw("FDD-LTE"))
    }

    @Test
    fun `frequency only values are mapped`() {
        assertEquals(NetworkSystem.FOUR_G, NetworkSystem.fromRaw("1.8G"))
        assertEquals(NetworkSystem.FOUR_G, NetworkSystem.fromRaw("2.1G"))
        assertEquals(NetworkSystem.FOUR_G, NetworkSystem.fromRaw("900M"))
        assertEquals(NetworkSystem.FOUR_G, NetworkSystem.fromRaw("800M"))
        assertEquals(NetworkSystem.FIVE_G, NetworkSystem.fromRaw("3.5G"))
        assertEquals(NetworkSystem.FIVE_G, NetworkSystem.fromRaw("2.6G"))
        assertEquals(NetworkSystem.FIVE_G, NetworkSystem.fromRaw("3500MHz"))
        assertEquals(NetworkSystem.FIVE_G, NetworkSystem.fromRaw("4.9G"))
        assertEquals(NetworkSystem.FIVE_G, NetworkSystem.fromRaw("700M"))
    }

    @Test
    fun `overlapping bands are marked ambiguous`() {
        assertTrue(NetworkSystem.isAmbiguousBand("2.1G"))
        assertTrue(NetworkSystem.isAmbiguousBand("900M"))
        assertTrue(NetworkSystem.isAmbiguousBand("800M"))
        assertFalse(NetworkSystem.isAmbiguousBand("1.8G"))
        assertFalse(NetworkSystem.isAmbiguousBand("3.5G"))
        assertNull(NetworkSystem.fromRaw("室分"))
    }

    @Test
    fun `frequency only header maps to system and band`() {
        val mapping = ColumnMapping.autoDetect(
            listOf("纬度", "经度", "承建方", "基站 ID", "小区 ID", "是否共享", "频段")
        )
        assertEquals(6, mapping[StationField.SYSTEM])
        assertEquals(6, mapping[StationField.BAND])
    }

    @Test
    fun `explicit system column is preferred over band column`() {
        val mapping = ColumnMapping.autoDetect(
            listOf("频段", "纬度", "经度", "承建方", "基站 ID", "小区 ID", "是否共享", "制式")
        )
        assertEquals(7, mapping[StationField.SYSTEM])
        assertEquals(0, mapping[StationField.BAND])
    }

    @Test
    fun `band column alone satisfies required system`() {
        val mapping = ColumnMapping.autoDetect(
            listOf("纬度", "经度", "承建方", "基站 ID", "小区 ID", "是否共享", "频率")
        )
        assertEquals(6, mapping[StationField.SYSTEM])
        assertEquals(6, mapping[StationField.BAND])
        assertTrue(ColumnMapping.missingRequired(mapping).isEmpty())
    }
}
