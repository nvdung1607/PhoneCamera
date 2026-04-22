package com.example.phonecamera.streamer

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "RTSPGuard.Streamer"

enum class Resolution(val label: String, val width: Int, val height: Int, val bitrateBps: Int) {
    P360("360p", 640, 360, 500_000), // 0.5 Mbps
    P720("720p", 1280, 720, 1_200_000), // 1.2 Mbps
    P1080("1080p", 1920, 1080, 2_000_000) // 2 Mbps
}

data class StreamerUiState(
    val isStreaming: Boolean = false,
    val useFrontCamera: Boolean = false,
    val selectedResolution: Resolution = Resolution.P360, // Default to 360p
    val localIpAddress: String = "",
    val streamPort: Int = 8080,
    val errorMessage: String? = null,
    val isBound: Boolean = false,
    val isCameraReady: Boolean = false   // true once OpenGlView is attached
) {
    val rtspUrl: String get() = "rtsp://$localIpAddress:$streamPort"
}

class StreamerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(StreamerUiState())
    val uiState: StateFlow<StreamerUiState> = _uiState.asStateFlow()

    private var streamService: RtspStreamService? = null

    private var serviceBound = false

    /**
     * Holds the OpenGlView reference so we can attach it as soon as the service connects,
     * solving the race condition between UI layout and async service binding.
     */
    private var pendingGlView: OpenGlView? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RtspStreamService.StreamBinder
            streamService = binder.getService()
            serviceBound = true
            _uiState.update { it.copy(isBound = true, isStreaming = streamService?.isStreaming == true) }
            Log.d(TAG, "Service connected")

            // Attach the OpenGlView if the UI was ready before service connected
            pendingGlView?.let { glView ->
                Log.d(TAG, "Attaching pending OpenGlView after service connected")
                streamService?.attachCamera(glView, _uiState.value.useFrontCamera)
                _uiState.update { it.copy(isCameraReady = true) }
                pendingGlView = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamService = null
            serviceBound = false
            _uiState.update { it.copy(
                isBound = false,
                isStreaming = false,
                isCameraReady = false
            ) }
            Log.d(TAG, "Service disconnected")
        }
    }

    init {
        loadLocalIp()
        bindToService()
    }

    private fun loadLocalIp() {
        try {
            val wifiManager = getApplication<Application>()
                .applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val rawIp = wifiManager.connectionInfo.ipAddress
            val ip = Formatter.formatIpAddress(rawIp)
            _uiState.value = _uiState.value.copy(localIpAddress = ip)
            Log.d(TAG, "Local IP: $ip")
        } catch (e: Exception) {
            Log.w(TAG, "Could not get local IP: ${e.message}")
        }
    }

    private fun bindToService() {
        val ctx = getApplication<Application>()
        val intent = RtspStreamService.startIntent(ctx)
        ctx.startService(intent)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Called from Compose UI when the OpenGlView is ready.
     * Stores the view and attaches immediately if service is already bound.
     * If service is not yet bound, the view will be attached in [serviceConnection.onServiceConnected].
     */
    fun attachCameraToService(glView: OpenGlView) {
        if (serviceBound) {
            Log.d(TAG, "Service already bound — attaching OpenGlView immediately")
            streamService?.attachCamera(glView, _uiState.value.useFrontCamera)
            _uiState.update { it.copy(isCameraReady = true) }
        } else {
            Log.d(TAG, "Service not yet bound — OpenGlView stored, will attach on connect")
            pendingGlView = glView
        }
    }

    fun startStream() {
        val state = _uiState.value
        val svc = streamService

        // Detailed error diagnostics
        if (svc == null) {
            _uiState.value = state.copy(
                errorMessage = "Dịch vụ camera chưa sẵn sàng. Vui lòng đợi và thử lại."
            )
            return
        }
        if (!state.isCameraReady) {
            _uiState.value = state.copy(
                errorMessage = "Camera chưa được khởi tạo. Vui lòng đợi và thử lại."
            )
            return
        }

        val res = state.selectedResolution
        val result = svc.startStreaming(
            width = res.width,
            height = res.height,
            fps = 30,
            bitrateBps = res.bitrateBps,
            useFrontCamera = state.useFrontCamera
        )

        when (result) {
            is StreamResult.Success -> {
                _uiState.value = state.copy(isStreaming = true, errorMessage = null)
            }
            is StreamResult.Error -> {
                Log.e(TAG, "Stream error: ${result.reason}")
                _uiState.value = state.copy(isStreaming = false, errorMessage = result.reason)
            }
        }
    }

    fun stopStream() {
        streamService?.stopStreaming()
        _uiState.value = _uiState.value.copy(isStreaming = false)
    }

    fun switchCamera() {
        val state = _uiState.value
        streamService?.switchCamera()
        _uiState.value = state.copy(useFrontCamera = !state.useFrontCamera)
    }

    fun selectResolution(resolution: Resolution) {
        if (_uiState.value.isStreaming) return
        _uiState.value = _uiState.value.copy(selectedResolution = resolution)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        try { getApplication<Application>().unbindService(serviceConnection) } catch (_: Exception) {}
    }
}
