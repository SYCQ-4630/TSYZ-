package com.selfuse.cellmap.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun stationDao(): StationDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun spatialIndexDao(): SpatialIndexDao
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration(true)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createIndexTables(db)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        createIndexTables(db)
                    }
                })
                .build()

        /**
         * 部分 Android 17 设备的系统 SQLite 未编译 rtree/fts4 模块，虚拟表会直接抛
         * "no such module"，导致建库事务失败并在启动时闪退。这里改用普通表 + B-Tree 索引，
         * 功能等价且不依赖任何可选 SQLite 模块。
         */
        private fun createIndexTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS station_rtree (" +
                    "id INTEGER PRIMARY KEY NOT NULL, " +
                    "minLat REAL NOT NULL, maxLat REAL NOT NULL, " +
                    "minLon REAL NOT NULL, maxLon REAL NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_station_rtree_lat ON station_rtree(minLat, maxLat)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_station_rtree_lon ON station_rtree(minLon, maxLon)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS optimized_rtree (" +
                    "id INTEGER PRIMARY KEY NOT NULL, " +
                    "minLat REAL NOT NULL, maxLat REAL NOT NULL, " +
                    "minLon REAL NOT NULL, maxLon REAL NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_optimized_rtree_lat ON optimized_rtree(minLat, maxLat)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_optimized_rtree_lon ON optimized_rtree(minLon, maxLon)"
            )
        }

        private fun rebuildIndexTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR REPLACE INTO station_rtree(id, minLat, maxLat, minLon, maxLon) " +
                    "SELECT id, lat, lat, lon, lon FROM stations"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO optimized_rtree(id, minLat, maxLat, minLon, maxLon) " +
                    "SELECT id, lat, lat, lon, lon FROM optimized_markers"
            )
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "bandwidth",
                    "uplinkFreq",
                    "downlinkFreq",
                    "deployment",
                    "azimuth",
                    "pci",
                    "multiCarrier",
                    "antennaGain",
                    "aauCount",
                    "rruCount"
                ).forEach { column ->
                    db.execSQL("ALTER TABLE stations ADD COLUMN $column TEXT")
                }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS station_rtree")
                db.execSQL("DROP TABLE IF EXISTS optimized_rtree")
                db.execSQL("DROP TABLE IF EXISTS station_fts")
                createIndexTables(db)
                rebuildIndexTables(db)
            }
        }
    }
}
