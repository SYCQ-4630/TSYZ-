package com.selfuse.cellmap.data.repo

import com.selfuse.cellmap.core.model.LabelStats
import com.selfuse.cellmap.core.model.MarkerStyle
import com.selfuse.cellmap.core.util.Normalizer
import com.selfuse.cellmap.data.db.AppDatabase
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.LogModule
import com.selfuse.cellmap.data.db.StationWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LabelRunResult(val stats: LabelStats, val processed: Int)

class LabelRepository(
    private val db: AppDatabase,
    private val writer: StationWriter,
    private val logger: AppLogger
) {
    suspend fun run(
        isCancelled: () -> Boolean,
        onProgress: (percent: Int, processed: Int, total: Int, message: String) -> Unit
    ): LabelRunResult = withContext(Dispatchers.IO) {
        val total = db.stationDao().count()
        if (total <= 0) throw IllegalStateException("没有基站数据，请先导入 XLSX")
        var processed = 0
        var unclassified = 0
        var unicomShared = 0
        var telecomShared = 0
        var unicomPrivate = 0
        var telecomPrivate = 0
        var afterId = 0L

        while (true) {
            if (isCancelled()) throw CancellationException("标注已暂停")
            val page = db.stationDao().pageForLabel(afterId, PAGE_SIZE)
            if (page.isEmpty()) break
            val updates = LinkedHashMap<Long, MarkerStyle?>(page.size)
            page.forEach { station ->
                val style = Normalizer.markerStyle(station.builder, station.shared)
                updates[station.id] = style
                when (style) {
                    MarkerStyle.UNICOM_SHARED -> unicomShared++
                    MarkerStyle.TELECOM_SHARED -> telecomShared++
                    MarkerStyle.UNICOM_PRIVATE -> unicomPrivate++
                    MarkerStyle.TELECOM_PRIVATE -> telecomPrivate++
                    null -> unclassified++
                }
            }
            writer.applyLabels(updates, System.currentTimeMillis())
            afterId = page.last().id
            processed += page.size
            val percent = (processed * 100 / total).coerceIn(0, 99)
            onProgress(
                percent,
                processed,
                total,
                "已标注 $processed / $total · 未归类 $unclassified"
            )
        }

        val classified = unicomShared + telecomShared + unicomPrivate + telecomPrivate
        val stats = LabelStats(
            total = total,
            classified = classified,
            unclassified = unclassified,
            unicomShared = unicomShared,
            telecomShared = telecomShared,
            unicomPrivate = unicomPrivate,
            telecomPrivate = telecomPrivate,
            completedAt = System.currentTimeMillis()
        )
        onProgress(100, total, total, "标注完成：$classified 条可渲染，$unclassified 条未归类")
        logger.log(
            LogModule.LABEL,
            LogLevel.INFO,
            "分类标注完成",
            "total=$total classified=$classified unclassified=$unclassified " +
                "uniShared=$unicomShared teleShared=$telecomShared " +
                "uniPrivate=$unicomPrivate telePrivate=$telecomPrivate"
        )
        LabelRunResult(stats, processed)
    }

    private companion object {
        const val PAGE_SIZE = 1_000
    }
}
