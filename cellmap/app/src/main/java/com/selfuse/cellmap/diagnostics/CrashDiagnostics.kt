package com.selfuse.cellmap.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.selfuse.cellmap.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将启动步骤、未捕获异常和系统退出原因写入应用私有目录，并同步一份到
 * Download/cellmap-diagnostics.txt，方便在没有 adb 的情况下取回日志。
 */
object CrashDiagnostics {

    private const val TAG = "CrashDiagnostics"
    private const val DIR = "diagnostics"
    private const val EVENTS_LOG = "events.log"
    const val DOWNLOAD_FILE_NAME = "cellmap-diagnostics.txt"
    private const val PREFS = "cellmap_diagnostics"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { recordUncaught(app, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
        log(app, "Application.onCreate")
        Thread(
            { runCatching { captureExitInfo(app) } },
            "cellmap-diagnostics"
        ).apply { isDaemon = true }.start()
    }

    fun log(context: Context, step: String) {
        appendEvent(context, "[startup] $step")
    }

    private fun recordUncaught(app: Application, thread: Thread, error: Throwable) {
        appendEvent(
            app,
            buildString {
                appendLine("[crash] thread=${thread.name}")
                appendLine(error.stackTraceToString())
            }
        )
        publish(app)
    }

    private fun captureExitInfo(app: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            publish(app)
            return
        }
        val manager = app.getSystemService(ActivityManager::class.java) ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        var newest = lastSeen
        val report = StringBuilder()

        manager.getHistoricalProcessExitReasons(app.packageName, 0, 10)
            .asSequence()
            .filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
            .forEach { info ->
                newest = maxOf(newest, info.timestamp)
                report.appendLine("=== exit at ${formatTime(info.timestamp)}")
                report.appendLine(
                    "reason=${reasonName(info.reason)} status=${info.status} " +
                        "importance=${info.importance} pss=${info.pss} rss=${info.rss}"
                )
                report.appendLine("description=${info.description}")
                runCatching {
                    info.traceInputStream?.use { input ->
                        val text = input.bufferedReader().readText()
                        report.appendLine(text.take(MAX_TRACE_CHARS))
                    }
                }.onFailure {
                    report.appendLine("trace unavailable: $it")
                }
            }

        if (report.isNotEmpty()) {
            appendEvent(app, "[exit-info]\n$report")
            prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, newest).apply()
        }
        publish(app)
    }

    private fun appendEvent(context: Context, text: String) {
        runCatching {
            val file = eventFile(context)
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { stream ->
                stream.write(("${formatTime(System.currentTimeMillis())} $text\n").toByteArray())
                stream.fd.sync()
            }
        }.onFailure { Log.e(TAG, "Unable to write diagnostics", it) }
    }

    private fun publish(context: Context) {
        val text = runCatching { buildReport(context) }.getOrElse {
            Log.e(TAG, "Unable to build diagnostics report", it)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToDownloads(context, text)
        } else {
            runCatching {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                File(dir, DOWNLOAD_FILE_NAME).writeText(text)
            }
        }
    }

    private fun buildReport(context: Context): String = buildString {
        appendLine("CellMap diagnostics")
        appendLine("time=${formatTime(System.currentTimeMillis())}")
        appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("package=${context.packageName}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
        appendLine("display=${Build.DISPLAY}")
        appendLine("fingerprint=${Build.FINGERPRINT}")
        appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("pageSize=${pageSize()}")
        appendLine("--- events ---")
        appendLine(runCatching { eventFile(context).readText() }.getOrDefault("<unavailable>"))
    }

    private fun pageSize(): Long = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE)
    }.getOrDefault(-1L)

    private fun eventFile(context: Context): File =
        File(File(context.filesDir, DIR), EVENTS_LOG)

    private fun publishToDownloads(context: Context, text: String) {
        runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(DOWNLOAD_FILE_NAME),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    resolver.delete(
                        ContentUris.withAppendedId(collection, cursor.getLong(0)),
                        null,
                        null
                    )
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, DOWNLOAD_FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri: Uri = resolver.insert(collection, values) ?: return@runCatching
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }.onFailure { Log.e(TAG, "Unable to publish diagnostics to Downloads", it) }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> reason.toString()
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))

    private const val MAX_TRACE_CHARS = 200_000
}
