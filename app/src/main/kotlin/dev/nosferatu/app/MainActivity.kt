package dev.nosferatu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.nosferatu.app.ui.history.HistoryScreen
import dev.nosferatu.app.ui.history.RunDetailScreen
import dev.nosferatu.app.ui.mapdata.MapDataScreen
import dev.nosferatu.app.ui.run.RunScreen
import dev.nosferatu.app.ui.theme.NosferatuTheme

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Run("run", "Run", Icons.Outlined.DirectionsRun),
    History("history", "History", Icons.Outlined.History),
    Map("map", "Map", Icons.Outlined.Layers),
}

private const val RUN_DETAIL = "runs"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark, so system bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            NosferatuTheme(darkTheme = true) {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val current = entry?.destination?.route
                val go = { tab: Tab ->
                    nav.navigate(tab.route) {
                        popUpTo(Tab.Run.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    // The map runs under the status bar; each screen pads its own overlays.
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            for (tab in Tab.entries) {
                                NavigationBarItem(
                                    selected = current == tab.route || (tab == Tab.History && current?.startsWith(RUN_DETAIL) == true),
                                    onClick = { go(tab) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label.uppercase(), style = MaterialTheme.typography.labelMedium) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            }
                        }
                    },
                ) { padding ->
                    NavHost(nav, startDestination = Tab.Run.route, modifier = Modifier.padding(padding)) {
                        composable(Tab.Run.route) {
                            RunScreen(onOpenMapData = { go(Tab.Map) }, onOpenRun = { id -> nav.navigate("$RUN_DETAIL/$id") })
                        }
                        composable(Tab.History.route) { HistoryScreen(onOpen = { id -> nav.navigate("$RUN_DETAIL/$id") }) }
                        composable("$RUN_DETAIL/{id}") { entry ->
                            RunDetailScreen(id = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                        }
                        composable(Tab.Map.route) { MapDataScreen() }
                    }
                }
            }
        }
    }
}
