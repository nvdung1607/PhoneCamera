package com.example.phonecamera.navigation

import kotlinx.serialization.Serializable

sealed interface Screen {
    @Serializable
    data object Home : Screen

    @Serializable
    data object Streamer : Screen

    @Serializable
    data object Viewer : Screen
}
