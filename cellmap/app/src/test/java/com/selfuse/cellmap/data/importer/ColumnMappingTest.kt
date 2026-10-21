package com.selfuse.cellmap.data.importer

import com.selfuse.cellmap.core.model.StationField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColumnMappingTest {

    @Test
    fun `new index fields are matched exactly`() {
        val detection = ColumnMapping.detect(
            listOf(
                "纬度", "经度", "承建方", "基站 ID", "小区 ID", "是否共享", "频段",
                "基站名称", "小区名称", "小区覆盖类", "天线挂高", "带宽",
                "上行中心频点", "下行中心频点", "基站部署方式", "小区方位角",
                "PCI", "多载波标识", "天线增益", "AAU数量", "RRU数量"
            )
        )
        val mapping = detection.mapping
        assertEquals(7, mapping[StationField.SITE_NAME])
        assertEquals(8, mapping[StationField.CELL_NAME])
        assertEquals(9, mapping[StationField.CATEGORY])
        assertEquals(10, mapping[StationField.ANTENNA_HEIGHT])
        assertEquals(11, mapping[StationField.BANDWIDTH])
        assertEquals(12, mapping[StationField.UPLINK_FREQ])
        assertEquals(13, mapping[StationField.DOWNLINK_FREQ])
        assertEquals(14, mapping[StationField.DEPLOYMENT])
        assertEquals(15, mapping[StationField.AZIMUTH])
        assertEquals(16, mapping[StationField.PCI])
        assertEquals(17, mapping[StationField.MULTI_CARRIER])
        assertEquals(18, mapping[StationField.ANTENNA_GAIN])
        assertEquals(19, mapping[StationField.AAU_COUNT])
        assertEquals(20, mapping[StationField.RRU_COUNT])
        assertTrue(detection.suggestions.isEmpty())
    }

    @Test
    fun `antenna height synonyms map to one field`() {
        listOf("天线挂高", "天线总挂高", "小区总挂高", "小区高度", "小区挂高").forEach { header ->
            val mapping = ColumnMapping.autoDetect(
                listOf("纬度", "经度", "承建方", "基站 ID", "小区 ID", "是否共享", "频段", header)
            )
            assertEquals(header, 7, mapping[StationField.ANTENNA_HEIGHT])
        }
    }

    @Test
    fun `near header becomes category suggestion instead of silent mapping`() {
        val detection = ColumnMapping.detect(
            listOf("纬度", "经度", "承建方", "基站 ID", "小区 ID", "是否共享", "频段", "带宽(MHz)", "小区高度（米）")
        )
        assertFalse(detection.mapping.containsKey(StationField.BANDWIDTH))
        assertFalse(detection.mapping.containsKey(StationField.ANTENNA_HEIGHT))
        val suggested = detection.suggestions.associate { it.field to it.column }
        assertEquals(7, suggested[StationField.BANDWIDTH])
        assertEquals(8, suggested[StationField.ANTENNA_HEIGHT])
    }

    @Test
    fun `uplink frequency is not stolen by band fuzzy match`() {
        val mapping = ColumnMapping.autoDetect(
            listOf("纬度", "经度", "承建方", "基站 ID", "小区 ID", "是否共享", "频段", "上行频率")
        )
        assertEquals(7, mapping[StationField.UPLINK_FREQ])
        assertEquals(6, mapping[StationField.BAND])
    }
}
