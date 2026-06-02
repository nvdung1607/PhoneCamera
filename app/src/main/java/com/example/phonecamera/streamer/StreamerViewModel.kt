package com.example.phonecamera.streamer

import android.app.Application
import java.net.NetworkInterface
import java.util.Collections
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phonecamera.network.ControlServer
import com.example.phonecamera.network.NsdHelper
import com.example.phonecamera.utils.AppLog
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.gl.AspectRatioMode
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
    val connectedViewers: List<String> = emptyList(),
    val fps: Int = 30,
    val pinCode: String = "",
    val hasFrontCamera: Boolean = true,
    val hasBackCamera: Boolean = true
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

    private val repository = com.example.phonecamera.data.CameraRepository(application)

    init {
        loadLocalIp()
        checkCameraHardware()
        startControlServer()
        viewModelScope.launch {
            try {
                val savedPin = repository.getSavedStreamerPin()
                if (!savedPin.isNullOrEmpty()) {
                    _uiState.update { it.copy(pinCode = savedPin) }
                    AppLog.i("Restored persistent Streamer PIN: $savedPin")
                }
            } catch (e: Exception) {
                AppLog.e("Error loading saved Streamer PIN", e)
            }
        }
    }

    private fun checkCameraHardware() {
        AppLog.d("checkCameraHardware()")
        try {
            val cameraManager = getApplication<Application>().getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val ids = cameraManager.cameraIdList
            var hasFront = false
            var hasBack = false
            for (id in ids) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                    hasFront = true
                } else if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    hasBack = true
                }
            }
            _uiState.update { it.copy(hasFrontCamera = hasFront, hasBackCamera = hasBack, useFrontCamera = hasFront && !hasBack) }
            AppLog.i("Hardware checked: hasFrontCamera=$hasFront, hasBackCamera=$hasBack")
        } catch (e: Exception) {
            AppLog.e("Failed to check camera hardware", e)
        }
    }

    private fun loadLocalIp() {
        AppLog.d("loadLocalIp()")
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            var foundIp = "0.0.0.0"
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: continue
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4) {
                            foundIp = sAddr
                            break
                        }
                    }
                }
                if (foundIp != "0.0.0.0") break
            }
            _uiState.update { it.copy(localIpAddress = foundIp) }
        } catch (e: Exception) {
            AppLog.w("Could not get local IP: ${e.message}")
        }
    }

    private fun startControlServer() {
        AppLog.d("startControlServer()")
        controlServer.start(viewModelScope) { cmd ->
            val expectedPin = _uiState.value.pinCode
            if (expectedPin.isNotEmpty() && cmd.pin != expectedPin) {
                AppLog.e("Authentication failed: received PIN='${cmd.pin}', expected='$expectedPin'")
                return@start "invalid PIN"
            }
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
                    val res = Resolution.entries.firstOrNull { it.height == cmd.heightP }
                    if (res != null) {
                        AppLog.i("Remote quality change → ${res.label} (from ${cmd.fromIp})")
                        changeQualityRemote(res)
                    }
                }
                is ControlServer.Command.SetFps -> {
                    AppLog.i("Remote FPS change → ${cmd.fps} (from ${cmd.fromIp})")
                    changeFpsRemote(cmd.fps)
                }
            }
            null
        }
    }

    /** Gọi khi OpenGlView đã sẵn sàng. Khởi tạo camera và bắt đầu preview. */
    fun attachCamera(glView: OpenGlView) {
        AppLog.d("attachCamera()")
        val facing = cameraFacing()
        glView.setAspectRatioMode(AspectRatioMode.Adjust)
        val res = _uiState.value.selectedResolution
        rtspCamera = RtspServerCamera2(glView, connectChecker, RTSP_PORT).also {
            it.startPreview(facing, res.width, res.height)
        }
        _uiState.update { it.copy(isCameraReady = true) }
        AppLog.d("Camera attached (facing=$facing, resolution=${res.width}x${res.height})")
    }

    fun startStream() {
        AppLog.d("startStream()")
        val cam = rtspCamera ?: return _uiState.update {
            it.copy(errorMessage = "Camera chưa sẵn sàng. Vui lòng thử lại.")
        }
        val res = _uiState.value.selectedResolution
        val currentFps = _uiState.value.fps

        if (cam.isOnPreview) {
            cam.stopPreview()
        }

        // Sinh mã PIN ngẫu nhiên 4 chữ số nếu chưa có (chỉ yêu cầu kết nối lần đầu)
        val generatedPin = if (_uiState.value.pinCode.isEmpty()) {
            (1000..9999).random().toString()
        } else {
            _uiState.value.pinCode
        }
        _uiState.update { it.copy(pinCode = generatedPin) }
        viewModelScope.launch {
            try {
                repository.saveStreamerPin(generatedPin)
            } catch (e: Exception) {
                AppLog.e("Error saving Streamer PIN", e)
            }
        }
        cam.getStreamClient().setAuthorization("admin", generatedPin)
        AppLog.i("RTSP Stream PIN (reused/generated/persisted): $generatedPin")

        val rotation = CameraHelper.getCameraOrientation(getApplication())
        if (!cam.prepareVideo(res.width, res.height, currentFps, res.bitrateBps, rotation)) {
            _uiState.update { it.copy(errorMessage = "Không thể chuẩn bị encoder video (${res.label}).") }
            return
        }
        if (!cam.prepareAudio()) {
            _uiState.update { it.copy(errorMessage = "Không thể khởi tạo microphone. Kiểm tra quyền RECORD_AUDIO.") }
            return
        }
        cam.startPreview(cameraFacing(), res.width, res.height)
        cam.startStream()

        if (cam.isStreaming) {
            nsdHelper.registerService(RTSP_PORT)
            _uiState.update { it.copy(isStreaming = true, errorMessage = null) }
            AppLog.i("Stream started on port $RTSP_PORT at ${res.label} at $currentFps fps")
        } else {
            _uiState.update { it.copy(errorMessage = "Không thể bắt đầu phát. Kiểm tra quyền Camera.") }
        }
    }

    fun stopStream() {
        AppLog.d("stopStream()")
        nsdHelper.unregisterService()
        rtspCamera?.let { if (it.isStreaming) it.stopStream() }
        _uiState.update { it.copy(isStreaming = false, connectedViewers = emptyList()) }
        AppLog.i("Stream stopped (PIN is preserved)")
    }

    /** Đổi chất lượng từ xa (lệnh từ Viewer). Tự động restart stream trên Main thread. */
    private fun changeQualityRemote(resolution: Resolution) {
        AppLog.d("changeQualityRemote(resolution=${resolution.label})")
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

    private fun changeFpsRemote(fps: Int) {
        AppLog.d("changeFpsRemote(fps=$fps)")
        if (fps !in listOf(15, 24, 30)) return
        viewModelScope.launch(Dispatchers.Main) {
            val wasStreaming = _uiState.value.isStreaming
            _uiState.update { it.copy(fps = fps) }
            if (wasStreaming) {
                rtspCamera?.let { if (it.isStreaming) it.stopStream() }
                delay(500L)
                startStream()
            }
        }
    }

    fun selectFps(fps: Int) {
        AppLog.d("selectFps(fps=$fps)")
        if (!_uiState.value.isStreaming) {
            _uiState.update { it.copy(fps = fps) }
        }
    }

    fun switchCamera() {
        AppLog.d("switchCamera()")
        val state = _uiState.value
        if (!state.hasFrontCamera || !state.hasBackCamera) {
            _uiState.update { it.copy(errorMessage = "Thiết bị không hỗ trợ đủ cả camera trước và sau.") }
            return
        }
        rtspCamera?.switchCamera()
        _uiState.update { it.copy(useFrontCamera = !it.useFrontCamera) }
    }

    fun selectResolution(resolution: Resolution) {
        AppLog.d("selectResolution(resolution=${resolution.label})")
        if (!_uiState.value.isStreaming) {
            _uiState.update { it.copy(selectedResolution = resolution) }
        }
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    /** Dừng stream và giải phóng tài nguyên. Gọi khi rời khỏi màn hình. */
    fun releaseCamera() {
        AppLog.d("releaseCamera()")
        nsdHelper.stopAll()
        rtspCamera?.apply {
            if (isStreaming) stopStream()
            if (isOnPreview) stopPreview()
        }
        rtspCamera = null
        _uiState.update { it.copy(isStreaming = false, isCameraReady = false, connectedViewers = emptyList(), pinCode = "") }
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
