package com.example.phonecamera.di

import android.content.Context
import com.example.phonecamera.data.CameraRepository
import com.example.phonecamera.network.CameraControlClient
import com.example.phonecamera.network.NsdHelper
import kotlinx.coroutines.CoroutineScope

interface AppContainer {
    val context: Context
    val cameraRepository: CameraRepository
    val nsdHelper: NsdHelper
    val cameraControlClient: CameraControlClient
    val applicationScope: CoroutineScope
}
