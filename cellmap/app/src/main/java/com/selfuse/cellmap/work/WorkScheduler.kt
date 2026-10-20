package com.selfuse.cellmap.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.selfuse.cellmap.core.model.TaskType
import com.selfuse.cellmap.data.prefs.AppPreferences
import com.selfuse.cellmap.data.prefs.ImportSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class WorkScheduler(
    context: Context,
    private val prefs: AppPreferences
) {
    private val workManager = WorkManager.getInstance(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun enqueueImport(spec: ImportSpec): Boolean {
        prefs.writeImportSpec(spec)
        val request = baseRequest(TaskType.IMPORT)
            .setInputData(workDataOf("spec" to json.encodeToString(spec)))
            .build()
        workManager.enqueueUniqueWork(
            TaskType.IMPORT.workName,
            ExistingWorkPolicy.KEEP,
            request
        )
        return true
    }

    suspend fun resumeImport(): Boolean {
        val spec = prefs.importSpec() ?: return false
        return enqueueImport(spec)
    }

    fun enqueueLabel() {
        val request = baseRequest(TaskType.LABEL).build()
        workManager.enqueueUniqueWork(TaskType.LABEL.workName, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueOptimize() {
        val request = baseRequest(TaskType.OPTIMIZE).build()
        workManager.enqueueUniqueWork(TaskType.OPTIMIZE.workName, ExistingWorkPolicy.KEEP, request)
    }

    suspend fun cancel(type: TaskType) {
        workManager.cancelUniqueWork(type.workName).await()
    }

    private fun baseRequest(type: TaskType): OneTimeWorkRequest.Builder {
        val builder: OneTimeWorkRequest.Builder = when (type) {
            TaskType.IMPORT -> OneTimeWorkRequestBuilder<ImportWorker>()
            TaskType.LABEL -> OneTimeWorkRequestBuilder<LabelWorker>()
            TaskType.OPTIMIZE -> OneTimeWorkRequestBuilder<OptimizeWorker>()
        }
        return builder
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_ALL)
            .addTag("type:${type.name.lowercase()}")
    }

    companion object {
        const val TAG_ALL = "cellmap"
    }
}
