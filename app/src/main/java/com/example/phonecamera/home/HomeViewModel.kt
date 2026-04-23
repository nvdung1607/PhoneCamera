package com.example.phonecamera.home

import androidx.lifecycle.ViewModel
import com.example.phonecamera.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HomeUiState(
    val cameraPermissionGranted: Boolean = false,
    val audioPermissionGranted: Boolean = false
) {
    val allStreamerPermissionsGranted: Boolean
        get() = cameraPermissionGranted && audioPermissionGranted
}

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onPermissionsResult(cameraGranted: Boolean, audioGranted: Boolean) {
        AppLog.d("onPermissionsResult: camera=$cameraGranted, audio=$audioGranted")
        _uiState.value = HomeUiState(
            cameraPermissionGranted = cameraGranted,
            audioPermissionGranted = audioGranted
        )
        if (!cameraGranted) AppLog.w("CAMERA permission NOT granted")
        if (!audioGranted) AppLog.w("RECORD_AUDIO permission NOT granted")
    }
}
