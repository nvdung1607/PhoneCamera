package com.example.phonecamera.streamer

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phonecamera.data.nsd.NsdHelper
import com.example.phonecamera.utils.AppLog
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val RTSP_PORT = 8080

enum class Resolution(val label: String, val width: Int, val height: Int, val bitrateBps: Int) {
    P360("360p", 640, 360, 500_000),
    P720("720p", 1280, 720, 1_200_000),
    P1080("1080p", 1920, 1080, 2_000_000)
}

data class StreamerUiState(
    val isStreaming: Boolean = false,
    val useFrontCamera: Boolean = false,
    val selectedResolution: Resolution = Resolution.P360,
    val localIpAddress: String = "",
    val errorMessage: String? = null,
    val isCameraReady: Boolean = false,
    /** Danh sách tên thiết bị đang xem (gửi qua ControlServer) */
    val connectedViewers: List<String> = emptyList()
) {
    val rtspUrl: String get() = "rtsp://$localIpAddress:$RTSP_PORT"
}

class StreamerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(StreamerUiState())
    val uiState: StateFlow<StreamerUiState> = _uiState.asStateFlow()

    private var rtspCamera: RtspServerCamera2? = null
    private val nsdHelper = NsdHelper(application)
    private val controlServer = ControlServer()

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) = AppLog.d("Client connecting: $url")
        override fun onConnectionSuccess() = AppLog.i("Client connected")
        override fun onConnectionFailed(reason: String) = AppLog.e("Connection failed: $reason")
        override fun onNewBitrate(bitrate: Long) = AppLog.v("Bitrate: ${bitrate / 1000} kbps")
        override fun onDisconnect() = AppLog.i("Client disconnected")
        override fun onAuthError() = AppLog.e("Auth error")
        override fun onAuthSuccess() {}
    }

    init {
        loadLocalIp()
        startControlServer()
    }

    private fun loadLocalIp() {
        try {
            @Suppress("DEPRECATION")
            val rawIp = (getApplication<Application>()
                .getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .connectionInfo.ipAddress
            _uiState.update { it.copy(localIpAddress = Formatter.formatIpAddress(rawIp)) }
        } catch (e: Exception) {
            AppLog.w("Could not get local IP: ${e.message}")
        }
    }

    private fun startControlServer() {
        controlServer.start(viewModelScope) { cmd ->
            when (cmd) {
                is ControlServer.Command.Hello -> {
                    AppLog.i("Viewer connected: ${cmd.deviceName} (${cmd.ip})")
                    _uiState.update { s ->
                        s.copy(connectedViewers = (s.connectedViewers + cmd.deviceName).distinct())
                    }
                }
                is ControlServer.Command.Bye -> {
                    AppLog.i("Viewer disconnected: ${cmd.deviceName}")
                    _uiState.update { s ->
                        s.copy(connectedViewers = s.connectedViewers.filter { it != cmd.deviceName })
                    }
                }
                is ControlServer.Command.SetQuality -> {
                    val res = Resolution.entries.firstOrNull { it.height == cmd.heightP } ?: return@start
                    AppLog.i("Remote quality change → ${res.label} (from ${cmd.fromIp})")
                    changeQualityRemote(res)
                }
            }
        }
    }

    /** Gọi khi OpenGlView đã sẵn sàng. Khởi tạo camera và bắt đầu preview. */
    fun attachCamera(glView: OpenGlView) {
        val facing = cameraFacing()
        rtspCamera = RtspServerCamera2(glView, connectChecker, RTSP_PORT).also {
            it.startPreview(facing)
        }
        _uiState.update { it.copy(isCameraReady = true) }
        AppLog.d("Camera attached (facing=$facing)")
    }

    fun startStream() {
        val cam = rtspCamera ?: return _uiState.update {
            it.copy(errorMessage = "Camera chưa sẵn sàng. Vui lòng thử lại.")
        }
        val res = _uiState.value.selectedResolution

        if (!cam.prepareVideo(res.width, res.height, 30, res.bitrateBps, 0)) {
            _uiState.update { it.copy(errorMessage = "Không thể chuẩn bị encoder video (${res.label}).") }
            return
        }
        if (!cam.prepareAudio()) {
            _uiState.update { it.copy(errorMessage = "Không thể khởi tạo microphone. Kiểm tra quyền RECORD_AUDIO.") }
            return
        }
        cam.startPreview(cameraFacing())
        cam.startStream()

        if (cam.isStreaming) {
            nsdHelper.registerService(RTSP_PORT)
            _uiState.update { it.copy(isStreaming = true, errorMessage = null) }
            AppLog.i("Stream started on port $RTSP_PORT at ${res.label}")
        } else {
            _uiState.update { it.copy(errorMessage = "Không thể bắt đầu phát. Kiểm tra quyền Camera.") }
        }
    }

    fun stopStream() {
        nsdHelper.unregisterService()
        rtspCamera?.let { if (it.isStreaming) it.stopStream() }
        _uiState.update { it.copy(isStreaming = false) }
        AppLog.i("Stream stopped")
    }

    /** Đổi chất lượng từ xa (lệnh từ Viewer). Tự động restart stream trên Main thread. */
    private fun changeQualityRemote(resolution: Resolution) {
        viewModelScope.launch(Dispatchers.Main) {
            val wasStreaming = _uiState.value.isStreaming
            _uiState.update { it.copy(selectedResolution = resolution) }
            if (wasStreaming) {
                rtspCamera?.let { if (it.isStreaming) it.stopStream() }
                delay(500L)
                startStream()
            }
        }
    }

    fun switchCamera() {
        rtspCamera?.switchCamera()
        _uiState.update { it.copy(useFrontCamera = !it.useFrontCamera) }
    }

    fun selectResolution(resolution: Resolution) {
        if (!_uiState.value.isStreaming) {
            _uiState.update { it.copy(selectedResolution = resolution) }
        }
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    /** Dừng stream và giải phóng tài nguyên. Gọi khi rời khỏi màn hình. */
    fun releaseCamera() {
        nsdHelper.stopAll()
        rtspCamera?.apply {
            if (isStreaming) stopStream()
            if (isOnPreview) stopPreview()
        }
        rtspCamera = null
        _uiState.update { it.copy(isStreaming = false, isCameraReady = false, connectedViewers = emptyList()) }
        AppLog.d("Camera released")
    }

    override fun onCleared() {
        controlServer.stop()
        releaseCamera()
        super.onCleared()
    }

    private fun cameraFacing() =
        if (_uiState.value.useFrontCamera) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
}
