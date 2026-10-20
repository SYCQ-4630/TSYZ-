package com.selfuse.cellmap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.selfuse.cellmap.diagnostics.CrashDiagnostics
import com.selfuse.cellmap.ui.navigation.CellMapNavHost
import com.selfuse.cellmap.ui.theme.CellMapTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashDiagnostics.log(this, "MainActivity.onCreate")
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            CellMapTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CellMapNavHost()
                }
            }
        }
        CrashDiagnostics.log(this, "MainActivity content set")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
