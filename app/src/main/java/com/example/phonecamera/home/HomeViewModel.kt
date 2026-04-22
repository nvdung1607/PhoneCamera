package com.example.phonecamera.home

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "RTSPGuard.Home"

data class HomeUiState(
    val cameraPermissionGranted: Boolean = false,
    val audioPermissionGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = false
) {
    val allStreamerPermissionsGranted: Boolean
        get() = cameraPermissionGranted && audioPermissionGranted

    val canOpenViewer: Boolean
        get() = true
}

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onPermissionsResult(
        cameraGranted: Boolean,
        audioGranted: Boolean,
        notificationGranted: Boolean
    ) {
        Log.d(TAG, "onPermissionsResult: camera=$cameraGranted, audio=$audioGranted, notification=$notificationGranted")
        _uiState.value = HomeUiState(
            cameraPermissionGranted = cameraGranted,
            audioPermissionGranted = audioGranted,
            notificationPermissionGranted = notificationGranted
        )
        if (!cameraGranted) Log.w(TAG, "⚠ CAMERA permission NOT granted")
        if (!audioGranted)  Log.w(TAG, "⚠ RECORD_AUDIO permission NOT granted")
    }
}
