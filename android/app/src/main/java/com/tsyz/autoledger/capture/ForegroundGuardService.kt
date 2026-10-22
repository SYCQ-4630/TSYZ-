package com.tsyz.autoledger.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tsyz.autoledger.AutoLedgerApp
import com.tsyz.autoledger.health.HealthChannel
import com.tsyz.autoledger.notification.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 低优先级前台通知 + 定期心跳，提升国产 ROM 后台存活率。 */
class ForegroundGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                (application as? AutoLedgerApp)?.container?.health?.beat(
                    HealthChannel.FOREGROUND,
                    "guard alive"
                )
                delay(6L * 60L * 60L * 1000L)
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = Notifications.serviceNotification(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, Notifications.SERVICE_NOTIFICATION_ID, notification, type)
        }
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ForegroundGuardService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ForegroundGuardService::class.java)) }
        }
    }
}
