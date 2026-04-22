package com.example.phonecamera.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Streamer : Screen("streamer")
    data object Viewer : Screen("viewer")
}
