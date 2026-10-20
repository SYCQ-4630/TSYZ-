package com.selfuse.cellmap.map

import android.content.Context
import android.util.Log
import org.maplibre.android.MapLibre
import java.util.concurrent.atomic.AtomicReference

/**
 * MapLibre 的 native 库加载失败会抛出 UnsatisfiedLinkError/Error，
 * 这类错误可以捕获。把初始化从 Application 移到地图页，并在失败时
 * 展示错误页，避免主界面一起闪退。
 */
object MapLibreRuntime {

    private val failure = AtomicReference<Throwable?>(null)

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context): Result<Unit> {
        if (initialized) return Result.success(Unit)
        failure.get()?.let { return Result.failure(it) }

        return try {
            MapLibre.getInstance(context.applicationContext)
            initialized = true
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "MapLibre initialization failed", t)
            failure.set(t)
            Result.failure(t)
        }
    }

    private const val TAG = "MapLibreRuntime"
}
