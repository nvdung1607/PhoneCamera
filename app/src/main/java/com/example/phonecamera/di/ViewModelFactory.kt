package com.example.phonecamera.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.phonecamera.home.HomeViewModel
import com.example.phonecamera.streamer.StreamerViewModel
import com.example.phonecamera.viewer.ViewerViewModel

class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(StreamerViewModel::class.java) -> {
                StreamerViewModel(
                    repository = container.cameraRepository,
                    nsdHelper = container.nsdHelper,
                    applicationScope = container.applicationScope
                ) as T
            }
            modelClass.isAssignableFrom(ViewerViewModel::class.java) -> {
                ViewerViewModel(
                    context = container.context,
                    repository = container.cameraRepository,
                    nsdHelper = container.nsdHelper,
                    controlClient = container.cameraControlClient,
                    applicationScope = container.applicationScope
                ) as T
            }
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> {
                HomeViewModel() as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
