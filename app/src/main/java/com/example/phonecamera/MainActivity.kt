package com.example.phonecamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.MaterialTheme
import com.example.phonecamera.home.HomeScreen
import com.example.phonecamera.navigation.Screen
import com.example.phonecamera.streamer.StreamerScreen
import com.example.phonecamera.ui.theme.PhoneCameraTheme
import com.example.phonecamera.viewer.ViewerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ngăn không cho màn hình tự động tắt khi đang mở ứng dụng
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            PhoneCameraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route
                    ) {
                        composable(Screen.Home.route) {
                            HomeScreen(
                                onNavigateToStreamer = {
                                    navController.navigate(Screen.Streamer.route)
                                },
                                onNavigateToViewer = {
                                    navController.navigate(Screen.Viewer.route)
                                }
                            )
                        }
                        composable(Screen.Streamer.route) {
                            StreamerScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.Viewer.route) {
                            ViewerScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
