package com.selfuse.cellmap.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        StationEntity::class,
        ImportBatchEntity::class,
        AppLogEntity::class,
        OptimizationRunEntity::class,
        OptimizedMarkerEntity::class,
        OptimizedClusterEntity::class,
        OptimizedChunkEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun stationDao(): StationDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun spatialIndexDao(): SpatialIndexDao
    abstract fun stationFtsDao(): StationFtsDao
    abstract fun logDao(): LogDao
    abstract fun optimizationDao(): OptimizationDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "cellmap.db")
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createVirtualTables(db)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        createVirtualTables(db)
                    }
                })
                .build()

        /**
         * R-Tree 与 FTS 都是 SQLite 虚表，Room 不把它们建模成 Entity。
         * 两者都在 onOpen 中以 IF NOT EXISTS 兜底，保证升级/恢复后仍存在。
         */
        private fun createVirtualTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS station_rtree " +
                    "USING rtree(id, minLat, maxLat, minLon, maxLon)"
            )
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS optimized_rtree " +
                    "USING rtree(id, minLat, maxLat, minLon, maxLon)"
            )
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS station_fts " +
                    "USING fts4(searchText, tokenize=unicode61)"
            )
        }
    }
}
