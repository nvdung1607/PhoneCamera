package com.example.phonecamera.streamer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phonecamera.data.CameraRepository
import com.example.phonecamera.network.ControlServer
import com.example.phonecamera.network.NsdHelper
import com.example.phonecamera.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.Collections

const val RTSP_PORT = 8080

enum class Resolution(val label: String, val width: Int, val height: Int, val bitrateBps: Int) {
    P360("360p", 640, 360, 500_000),
    P720("720p", 1280, 720, 1_200_000),
    P1080("1080p", 1920, 1080, 2_000_000)
}

sealed interface StreamerCommand {
    data class StartStream(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val useFrontCamera: Boolean,
        val pinCode: String
    ) : StreamerCommand
    data object StopStream : StreamerCommand
    data object SwitchCamera : StreamerCommand
    data class SetBitrate(val bitrateBps: Int) : StreamerCommand
}

data class StreamerUiState(
    val isStreaming: Boolean = false,
    val useFrontCamera: Boolean = false,
    val selectedResolution: Resolution = Resolution.P720,
    val localIpAddress: String = "",
    val errorMessage: String? = null,
    val isCameraReady: Boolean = false,
    /** List of viewer device names sent via ControlServer */
    val connectedViewers: List<String> = emptyList(),
    val fps: Int = 30,
    val pinCode: String = "",
    val hasFrontCamera: Boolean = true,
    val hasBackCamera: Boolean = true
) {
    val rtspUrl: String get() = "rtsp://$localIpAddress:$RTSP_PORT"
}

class StreamerViewModel(
    private val repository: CameraRepository,
    private val nsdHelper: NsdHelper,
    private val applicationScope: CoroutineScope
) : ViewModel() {

    private val _uiState = MutableStateFlow(StreamerUiState())
    val uiState: StateFlow<StreamerUiState> = _uiState.asStateFlow()

    private val _commands = MutableSharedFlow<StreamerCommand>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commands: SharedFlow<StreamerCommand> = _commands.asSharedFlow()

    private val controlServer = ControlServer()

    init {
        loadLocalIp()
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

    fun onCameraHardwareChecked(hasFront: Boolean, hasBack: Boolean) {
        AppLog.d("onCameraHardwareChecked(hasFront=$hasFront, hasBack=$hasBack)")
        _uiState.update {
            it.copy(
                hasFrontCamera = hasFront,
                hasBackCamera = hasBack,
                useFrontCamera = hasFront && !hasBack
            )
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
                is ControlServer.Command.SetBitrate -> {
                    AppLog.i("Remote Bitrate change → ${cmd.bitrate} bps (from ${cmd.fromIp})")
                    changeBitrateRemote(cmd.bitrate)
                }
            }
            null
        }
    }

    fun startStream() {
        AppLog.d("startStream()")
        val res = _uiState.value.selectedResolution
        val currentFps = _uiState.value.fps

        // Generate persistent 4-digit PIN if not present
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

        // Notify View to start streaming via Command Flow
        _commands.tryEmit(
            StreamerCommand.StartStream(
                width = res.width,
                height = res.height,
                fps = currentFps,
                bitrate = res.bitrateBps,
                useFrontCamera = _uiState.value.useFrontCamera,
                pinCode = generatedPin
            )
        )
    }

    fun stopStream() {
        AppLog.d("stopStream()")
        _commands.tryEmit(StreamerCommand.StopStream)
        onStreamStopped()
    }

    fun onStreamStartedSuccess() {
        AppLog.d("onStreamStartedSuccess()")
        nsdHelper.registerService(RTSP_PORT)
        _uiState.update { it.copy(isStreaming = true, errorMessage = null) }
    }

    fun onStreamStartFailed(reason: String) {
        AppLog.e("onStreamStartFailed: $reason")
        _uiState.update { it.copy(isStreaming = false, errorMessage = reason) }
    }

    fun onStreamStopped() {
        AppLog.d("onStreamStopped()")
        nsdHelper.unregisterService()
        _uiState.update { it.copy(isStreaming = false, connectedViewers = emptyList()) }
    }

    fun onCameraPreviewReady(ready: Boolean) {
        AppLog.d("onCameraPreviewReady($ready)")
        _uiState.update { it.copy(isCameraReady = ready) }
    }

    private fun changeQualityRemote(resolution: Resolution) {
        AppLog.d("changeQualityRemote(resolution=${resolution.label})")
        viewModelScope.launch(Dispatchers.Main) {
            val wasStreaming = _uiState.value.isStreaming
            _uiState.update { it.copy(selectedResolution = resolution) }
            if (wasStreaming) {
                stopStream()
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
                stopStream()
                delay(500L)
                startStream()
            }
        }
    }

    private fun changeBitrateRemote(bitrateBps: Int) {
        AppLog.d("changeBitrateRemote(bitrateBps=$bitrateBps)")
        _commands.tryEmit(StreamerCommand.SetBitrate(bitrateBps))
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
        _uiState.update { it.copy(useFrontCamera = !it.useFrontCamera) }
        _commands.tryEmit(StreamerCommand.SwitchCamera)
    }

    fun selectResolution(resolution: Resolution) {
        AppLog.d("selectResolution(resolution=${resolution.label})")
        if (!_uiState.value.isStreaming) {
            _uiState.update { it.copy(selectedResolution = resolution) }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun releaseCamera() {
        AppLog.d("releaseCamera()")
        _commands.tryEmit(StreamerCommand.StopStream)
        nsdHelper.stopAll()
        _uiState.update {
            it.copy(
                isStreaming = false,
                isCameraReady = false,
                connectedViewers = emptyList(),
                pinCode = ""
            )
        }
    }

    override fun onCleared() {
        controlServer.stop()
        releaseCamera()
        super.onCleared()
    }
}
