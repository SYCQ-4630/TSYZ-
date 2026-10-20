package com.selfuse.cellmap.di

import android.content.Context
import com.selfuse.cellmap.data.db.AppDatabase
import com.selfuse.cellmap.data.db.StationWriter
import com.selfuse.cellmap.data.key.KeyRepository
import com.selfuse.cellmap.data.key.TiandituTiles
import com.selfuse.cellmap.data.prefs.AppPreferences
import com.selfuse.cellmap.data.repo.AppLogger
import com.selfuse.cellmap.data.repo.DataMaintenanceRepository
import com.selfuse.cellmap.data.repo.ImportRepository
import com.selfuse.cellmap.data.repo.LabelRepository
import com.selfuse.cellmap.data.repo.OptimizationRepository
import com.selfuse.cellmap.data.repo.ReadinessRepository
import com.selfuse.cellmap.data.repo.StationRepository
import com.selfuse.cellmap.data.repo.TaskCenterRepository
import com.selfuse.cellmap.data.security.KeystoreCipher
import com.selfuse.cellmap.map.BaseMapProvider
import com.selfuse.cellmap.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase = AppDatabase.get(appContext)
    val prefs = AppPreferences(appContext)
    val cipher = KeystoreCipher()
    val logger = AppLogger(database, applicationScope)
    val readiness = ReadinessRepository(prefs, database, logger, applicationScope)
    val writer = StationWriter(database)
    val tiles = TiandituTiles()
    val keyRepository = KeyRepository(prefs, cipher, tiles, logger)
    val stationRepository = StationRepository(database)
    val importRepository = ImportRepository(
        context = appContext,
        db = database,
        writer = writer,
        prefs = prefs,
        readiness = readiness,
        logger = logger
    )
    val labelRepository = LabelRepository(database, writer, logger)
    val optimizationRepository = OptimizationRepository(
        context = appContext,
        db = database,
        writer = writer,
        readiness = readiness,
        prefs = prefs,
        logger = logger
    )
    val scheduler = WorkScheduler(appContext, prefs)
    val taskCenter = TaskCenterRepository(
        context = appContext,
        prefs = prefs,
        scheduler = scheduler,
        importRepository = importRepository,
        logger = logger
    )
    val dataMaintenance = DataMaintenanceRepository(database, writer, readiness, logger)
    val baseMapProvider = BaseMapProvider(keyRepository, tiles, logger)

    fun start() {
        applicationScope.launch {
            runCatching { logger.prune() }
            runCatching { keyRepository.reconcile() }
            runCatching { readiness.reconcile() }
        }
    }
}
