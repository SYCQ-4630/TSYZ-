package com.selfuse.cellmap.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.selfuse.cellmap.ui.home.HomeScreen
import com.selfuse.cellmap.ui.imports.ImportScreen
import com.selfuse.cellmap.ui.key.KeysScreen
import com.selfuse.cellmap.ui.label.LabelScreen
import com.selfuse.cellmap.ui.manage.ManageScreen
import com.selfuse.cellmap.ui.map.MapScreen

object Routes {
    const val HOME = "home"
    const val KEYS = "keys"
    const val IMPORT = "import"
    const val LABELS = "labels"
    const val MAP = "map"
    const val MANAGE = "manage"
}

@Composable
fun CellMapNavHost(navController: NavHostController = rememberNavController()) {

    fun open(route: String) {
        navController.navigate(route) {
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenKeys = { open(Routes.KEYS) },
                onOpenImport = { open(Routes.IMPORT) },
                onOpenLabels = { open(Routes.LABELS) },
                onOpenMap = { open(Routes.MAP) },
                onOpenManage = { open(Routes.MANAGE) }
            )
        }
        composable(Routes.KEYS) {
            KeysScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.IMPORT) {
            ImportScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.LABELS) {
            LabelScreen(
                onBack = { navController.popBackStack() },
                onOpenMap = {
                    navController.navigate(Routes.MAP) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.MAP) {
            MapScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.MANAGE) {
            ManageScreen(
                onBack = { navController.popBackStack() },
                onOpenKeys = { open(Routes.KEYS) },
                onOpenImport = { open(Routes.IMPORT) },
                onOpenLabels = { open(Routes.LABELS) },
                onOpenMap = { open(Routes.MAP) }
            )
        }
    }
}
