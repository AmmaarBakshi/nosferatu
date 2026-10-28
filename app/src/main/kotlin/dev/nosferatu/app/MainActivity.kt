package dev.nosferatu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsRun
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
import dev.nosferatu.app.ui.mapdata.MapDataScreen
import dev.nosferatu.app.ui.run.LiveMapScreen
import dev.nosferatu.app.ui.theme.NosferatuTheme

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Run("run", "Run", Icons.Outlined.DirectionsRun),
    Map("map", "Map", Icons.Outlined.Layers),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            for (tab in Tab.entries) {
                                NavigationBarItem(
                                    selected = current == tab.route,
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
                        composable(Tab.Run.route) { LiveMapScreen(onOpenMapData = { go(Tab.Map) }) }
                        composable(Tab.Map.route) { MapDataScreen() }
                    }
                }
            }
        }
    }
}
