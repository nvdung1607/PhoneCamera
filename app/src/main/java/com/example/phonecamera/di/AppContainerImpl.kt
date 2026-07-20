package com.example.phonecamera.di

import android.content.Context
import com.example.phonecamera.data.CameraRepository
import com.example.phonecamera.network.CameraControlClient
import com.example.phonecamera.network.NsdHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainerImpl(override val context: Context) : AppContainer {
    override val cameraRepository: CameraRepository by lazy {
        CameraRepository(context)
    }

    override val nsdHelper: NsdHelper by lazy {
        NsdHelper(context)
    }

    override val cameraControlClient: CameraControlClient by lazy {
        CameraControlClient()
    }

    override val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
