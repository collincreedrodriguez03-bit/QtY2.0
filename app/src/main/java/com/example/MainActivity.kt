package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.QtyViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HighThinkingScreen
import com.example.ui.screens.TelemetryScreen
import com.example.ui.screens.WalkForwardScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: QtyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val uiState by viewModel.uiState.collectAsState()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            val navBackStackEntry by navController.currentBackStackEntryAsState()
                            val currentRoute = navBackStackEntry?.destination?.route

                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                                label = { Text("Dashboard") },
                                selected = currentRoute == "dashboard",
                                onClick = {
                                    navController.navigate("dashboard") {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )

                            NavigationBarItem(
                                icon = { Icon(Icons.Default.List, contentDescription = "Walk-Forward") },
                                label = { Text("OOS Audit") },
                                selected = currentRoute == "walkforward",
                                onClick = {
                                    navController.navigate("walkforward") {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )

                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Star, contentDescription = "High Thinking") },
                                label = { Text("AI Audit") },
                                selected = currentRoute == "thinking",
                                onClick = {
                                    navController.navigate("thinking") {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )

                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Info, contentDescription = "Telemetry") },
                                label = { Text("Telemetry") },
                                selected = currentRoute == "telemetry",
                                onClick = {
                                    navController.navigate("telemetry") {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "dashboard",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("dashboard") {
                            DashboardScreen(
                                state = uiState,
                                onTogglePolling = { viewModel.toggleLivePolling() },
                                onRefresh = { viewModel.runSingleTickUpdate() }
                            )
                        }
                        composable("walkforward") {
                            WalkForwardScreen(
                                state = uiState,
                                onRunAudit = { horizon -> viewModel.runWalkForwardAudit(horizon) }
                            )
                        }
                        composable("thinking") {
                            HighThinkingScreen(
                                state = uiState,
                                onRequestAudit = { prompt -> viewModel.requestHighThinkingAudit(prompt) }
                            )
                        }
                        composable("telemetry") {
                            TelemetryScreen(state = uiState)
                        }
                    }
                }
            }
        }
    }
}
