package com.example.phonecamera.viewer

import android.app.Application
import com.example.phonecamera.utils.AppLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phonecamera.data.CameraConfig
import com.example.phonecamera.data.CameraRepository
import com.example.phonecamera.network.DiscoveredCamera
import com.example.phonecamera.network.NsdHelper
import com.example.phonecamera.network.ControlServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.example.phonecamera.network.CameraControlClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.rtsp.RtspMediaSource



sealed class PlayerState {
    data object Idle : PlayerState()
    data class Loading(val attemptId: Long = System.currentTimeMillis()) : PlayerState()
    data object Playing : PlayerState()
    data class Error(val message: String) : PlayerState()
}

data class ViewerUiState(
    val cameras: List<CameraConfig?> = List(4) { null },
    val playerStates: Map<Int, PlayerState> = emptyMap(),
    val snackbarMessage: String? = null,
    val isScanning: Boolean = false,
    val useTcp: Boolean = false, // Default to UDP as requested by user
    val selectedAudioSlot: Int? = null, // Which slot's audio is currently unmuted
    val discoveredCameras: List<DiscoveredCamera> = emptyList(),
    val discoveryBadgeCount: Int = 0
) {
    fun playerStateFor(index: Int): PlayerState = playerStates[index] ?: PlayerState.Idle

    val occupiedHosts: Set<String>
        get() = cameras.filterNotNull().map { it.host }.toSet()

    val firstEmptySlot: Int?
        get() = cameras.indexOfFirst { it == null }.takeIf { it >= 0 }
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CameraRepository(application)
    private val nsdHelper = NsdHelper(application)
    private val controlClient = CameraControlClient()

    private val activePlayers = mutableMapOf<Int, ExoPlayer>()
    private val prepareJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    private val _playersState = MutableStateFlow<Map<Int, ExoPlayer>>(emptyMap())
    val playersState: StateFlow<Map<Int, ExoPlayer>> = _playersState.asStateFlow()

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        AppLog.d("ViewerViewModel init")
        viewModelScope.launch {
            repository.camerasFlow.collect { savedCameras ->
                AppLog.d("Loaded ${savedCameras.size} cameras from DataStore: ${savedCameras.map { "slot${it.id}=${it.host}:${it.port}" }}")
                val slots = MutableList<CameraConfig?>(4) { null }
                savedCameras.forEach { cam -> if (cam.id in 0..3) slots[cam.id] = cam }
                
                val current = _uiState.value
                val newStates = current.playerStates.toMutableMap()
                
                slots.forEachIndexed { i, cam ->
                    if (cam != null) {
                        val existingState = newStates[i]
                        if (existingState == null || existingState is PlayerState.Idle || activePlayers[i] == null) {
                            AppLog.d("Auto-triggering Loading & preparing player for slot $i (${cam.host}:${cam.port})")
                            newStates[i] = PlayerState.Loading()
                            preparePlayer(i, cam, current.useTcp)
                        }
                    } else {
                        releasePlayer(i)
                        newStates[i] = PlayerState.Idle
                    }
                }
                _uiState.update { it.copy(cameras = slots, playerStates = newStates) }
            }
        }
        startDiscovery()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun preparePlayer(index: Int, config: CameraConfig, useTcp: Boolean) {
        AppLog.d("preparePlayer(index=$index, config=${config.name}, useTcp=$useTcp)")
        // Cancel any pending connect job for this slot immediately
        prepareJobs[index]?.cancel()
        
        // Release the player immediately to close the socket and free up the Server side resource
        releasePlayer(index)
        
        // Launch new connection task with 500ms debounce/delay
        prepareJobs[index] = viewModelScope.launch(Dispatchers.Main) {
            AppLog.d("Waiting 500ms before connecting slot $index (${config.toRtspUrl()})")
            delay(500L)
            
            val context = getApplication<Application>()
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(2500, 10000, 1000, 1500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val player = ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(useTcp)
                    .setTimeoutMs(30_000L)
                    .createMediaSource(MediaItem.fromUri(config.toRtspUrl()))
                setMediaSource(source)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> onPlayerReady(index)
                            Player.STATE_BUFFERING -> {
                                AppLog.d("Player slot $index → STATE_BUFFERING")
                                setPlayerState(index, PlayerState.Loading())
                            }
                            Player.STATE_ENDED -> {
                                AppLog.d("Player slot $index → STATE_ENDED")
                                onPlayerError(index, "Luồng video kết thúc")
                            }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val msg = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Không kết nối được mạng"
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Hết thời gian chờ (>15s)"
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Máy chủ từ chối kết nối"
                            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Luồng không đồng bộ — Thử lại"
                            else -> "Lỗi [${error.errorCode}]: ${error.message?.take(60) ?: "?"}"
                        }
                        onPlayerError(index, msg)
                    }
                })
                prepare()
                playWhenReady = true
                volume = if (_uiState.value.selectedAudioSlot == index) 1f else 0f
            }
            activePlayers[index] = player
            _playersState.update { activePlayers.toMap() }
        }
    }

    private fun releasePlayer(index: Int) {
        prepareJobs.remove(index)?.cancel()
        activePlayers.remove(index)?.release()
        _playersState.update { activePlayers.toMap() }
    }

    // ─── Discovery ────────────────────────────────────────────────────────

    fun startDiscovery() {
        if (_uiState.value.isScanning) {
            AppLog.d("startDiscovery() skipped — already scanning")
            return
        }
        AppLog.d("startDiscovery() — starting NSD scan for _rtspguard._tcp")
        _uiState.update { it.copy(isScanning = true) }

        nsdHelper.discoverServices(
            onFound = { camera ->
                viewModelScope.launch(Dispatchers.Main) {
                    AppLog.i("✅ Camera found: ${camera.displayName} @ ${camera.host}:${camera.port}")
                    val current = _uiState.value
                    current.cameras.forEachIndexed { i, cam ->
                        if (cam != null && cam.name == camera.displayName) {
                            AppLog.i("Auto-reconnecting slot $i for ${camera.displayName} at ${camera.host}:${camera.port}")
                            val updatedConfig = cam.copy(host = camera.host, port = camera.port)
                            preparePlayer(i, updatedConfig, current.useTcp)
                            if (cam.host != camera.host || cam.port != camera.port) {
                                repository.saveCamera(updatedConfig)
                            }
                        }
                    }
                    _uiState.update { state ->
                        val updated = state.discoveredCameras.toMutableList()
                        val idx = updated.indexOfFirst { it.serviceId == camera.serviceId }
                        if (idx >= 0) updated[idx] = camera else updated.add(camera)
                        val newCount = updated.count { it.host !in state.occupiedHosts }
                        
                        val newStates = state.playerStates.toMutableMap()
                        val updatedCameras = state.cameras.toMutableList()
                        state.cameras.forEachIndexed { i, cam ->
                            if (cam != null && cam.name == camera.displayName) {
                                newStates[i] = PlayerState.Loading()
                                if (cam.host != camera.host || cam.port != camera.port) {
                                    updatedCameras[i] = cam.copy(host = camera.host, port = camera.port)
                                }
                            }
                        }

                        state.copy(
                            cameras = updatedCameras,
                            discoveredCameras = updated, 
                            discoveryBadgeCount = newCount, 
                            playerStates = newStates
                        )
                    }
                }
            },
            onLost = { serviceId ->
                viewModelScope.launch(Dispatchers.Main) {
                    AppLog.i("❌ Camera lost: serviceId=$serviceId")
                    _uiState.update { state ->
                        val lostCamera = state.discoveredCameras.find { it.serviceId == serviceId }
                        val newStates = state.playerStates.toMutableMap()

                        if (lostCamera != null) {
                            AppLog.w("Camera offline: ${lostCamera.displayName} @ ${lostCamera.host}:${lostCamera.port}")
                            // Find all slots streaming from this camera and mark them as Offline instead of deleting
                            state.cameras.forEachIndexed { i, cam ->
                                if (cam != null && cam.name == lostCamera.displayName) {
                                    AppLog.w("Marking slot $i as Offline (was streaming from ${lostCamera.host})")
                                    newStates[i] = PlayerState.Error("Camera đã mất kết nối")
                                }
                            }
                        }

                        val updated = state.discoveredCameras.filter { it.serviceId != serviceId }
                        val newCount = updated.count { it.host !in state.occupiedHosts }
                        state.copy(discoveredCameras = updated, discoveryBadgeCount = newCount, playerStates = newStates)
                    }
                }
            }
        )
    }

    fun stopDiscovery() {
        AppLog.d("stopDiscovery()")
        nsdHelper.stopDiscovery()
        _uiState.update { it.copy(isScanning = false) }
    }

    fun addDiscoveredCamera(camera: DiscoveredCamera, targetSlot: Int? = null) {
        AppLog.d("addDiscoveredCamera(camera=${camera.displayName}, targetSlot=$targetSlot)")
        val slot = targetSlot ?: _uiState.value.firstEmptySlot
        if (slot == null) {
            AppLog.w("addDiscoveredCamera: no empty slot available")
            return
        }
        AppLog.d("addDiscoveredCamera: ${camera.displayName} → slot $slot (${camera.rtspUrl})")
        val config = CameraConfig(
            id = slot,
            name = camera.displayName,
            host = camera.host,
            port = camera.port,
            isPhoneCamera = true  // Camera được tìm qua NSD → là Phone Camera
        )
        saveCamera(config)
        _uiState.update { it.copy(discoveryBadgeCount = maxOf(0, it.discoveryBadgeCount - 1)) }
    }

    fun clearDiscoveryBadge() {
        AppLog.d("clearDiscoveryBadge()")
        _uiState.update { it.copy(discoveryBadgeCount = 0) }
    }

    // ─── Camera CRUD ──────────────────────────────────────────────────────

    fun saveCamera(config: CameraConfig) {
        AppLog.d("saveCamera(slot=${config.id}, name='${config.name}', host=${config.host})")
        AppLog.d("saveCamera: slot=${config.id} name='${config.name}' url=${config.toRtspUrl()}")
        viewModelScope.launch {
            if (config.isPhoneCamera) {
                _uiState.update { it.copy(snackbarMessage = "Đang kiểm tra kết nối...") }
                val deviceName = NsdHelper.deviceServiceName()
                val response = withContext(Dispatchers.IO) {
                    controlClient.sayHello(config.host, deviceName, config.pinCode)
                }
                AppLog.d("saveCamera verification response: $response")
                if (response == null) {
                    _uiState.update { it.copy(snackbarMessage = "Không thể kết nối đến camera. Vui lòng kiểm tra IP/Port.") }
                    return@launch
                } else if (response.contains("invalid PIN", ignoreCase = true) || response.contains("ERROR", ignoreCase = true)) {
                    _uiState.update { it.copy(snackbarMessage = "Mã PIN không chính xác. Kết nối bị từ chối.") }
                    return@launch
                }
            }
            // PIN is correct or not a phone camera, save configuration and start
            _uiState.update { it.copy(snackbarMessage = null) }
            repository.saveCamera(config)
            preparePlayer(config.id, config, _uiState.value.useTcp)
            setPlayerState(config.id, PlayerState.Loading())
        }
    }

    fun deleteCamera(id: Int) {
        AppLog.d("deleteCamera(id=$id)")
        val cam = _uiState.value.cameras.getOrNull(id)
        if (cam != null && cam.isPhoneCamera) {
            val deviceName = NsdHelper.deviceServiceName()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    AppLog.d("Sending BYE command for slot $id on deletion to ${cam.host}")
                    controlClient.sayBye(cam.host, deviceName, cam.pinCode)
                } catch (e: Exception) {
                    AppLog.e("Failed to send BYE on delete", e)
                }
            }
        }
        viewModelScope.launch {
            repository.deleteCamera(id)
            releasePlayer(id)
            setPlayerState(id, PlayerState.Idle)
        }
    }

    private val retryCounts = mutableMapOf<Int, Int>()

    fun onPlayerReady(index: Int) {
        AppLog.i("▶ Player slot $index → Playing")
        retryCounts[index] = 0 // Reset retry count on success
        setPlayerState(index, PlayerState.Playing)
    }

    fun onPlayerError(index: Int, errorMsg: String) {
        AppLog.e("✗ Player slot $index error: $errorMsg")
        setPlayerState(index, PlayerState.Error(errorMsg))
        
        val retries = retryCounts[index] ?: 0
        if (retries < 5) {
            retryCounts[index] = retries + 1
            viewModelScope.launch {
                delay(3000L)
                val cam = _uiState.value.cameras.getOrNull(index)
                val currentState = _uiState.value.playerStates[index]
                if (cam != null && (currentState is PlayerState.Error || currentState is PlayerState.Loading)) {
                    AppLog.i("Auto-retrying connection for slot $index (attempt ${retries + 1}/5)...")
                    retryCamera(index)
                }
            }
        }
    }

    fun retryCamera(index: Int) {
        val cam = _uiState.value.cameras.getOrNull(index) ?: return
        AppLog.i("Retrying camera in slot $index (${cam.host}:${cam.port})")
        val newStates = _uiState.value.playerStates.toMutableMap()
        newStates[index] = PlayerState.Loading()
        _uiState.value = _uiState.value.copy(playerStates = newStates)
        preparePlayer(index, cam, _uiState.value.useTcp)
    }

    fun toggleTcp() {
        AppLog.d("toggleTcp()")
        _uiState.update { state ->
            val isTcp = !state.useTcp
            AppLog.d("Toggling TCP mode to: $isTcp. Reloading all active cameras.")
            val newStates = state.playerStates.toMutableMap()
            // Force reload by setting all active players back to Loading
            state.cameras.forEachIndexed { i, cam ->
                if (cam != null) {
                    newStates[i] = PlayerState.Loading()
                    preparePlayer(i, cam, isTcp)
                }
            }
            state.copy(useTcp = isTcp, playerStates = newStates)
        }
    }

    fun toggleAudio(slotIndex: Int) {
        AppLog.d("toggleAudio(slotIndex=$slotIndex)")
        _uiState.update { state ->
            val newAudioSlot = if (state.selectedAudioSlot == slotIndex) null else slotIndex
            AppLog.d("Toggling audio: slot $slotIndex. New selected audio slot: $newAudioSlot")
            activePlayers.forEach { (idx, player) ->
                player.volume = if (idx == newAudioSlot) 1f else 0f
            }
            state.copy(selectedAudioSlot = newAudioSlot)
        }
    }

    fun dismissSnackbar() {
        AppLog.d("dismissSnackbar()")
        _uiState.update { it.copy(snackbarMessage = null) } }

    /**
     * Gửi lệnh đổi chất lượng tới máy Streamer qua TCP ControlServer.
     * Chỉ hoạt động với camera có [CameraConfig.isPhoneCamera] = true.
     */
    fun setRemoteQuality(slotIndex: Int, heightP: Int) {
        AppLog.d("setRemoteQuality(slotIndex=$slotIndex, heightP=$heightP)")
        val cam = _uiState.value.cameras.getOrNull(slotIndex) ?: return
        if (!cam.isPhoneCamera) {
            AppLog.w("setRemoteQuality: slot $slotIndex is not a Phone Camera")
            return
        }
        AppLog.i("setRemoteQuality: slot=$slotIndex → ${heightP}p @ ${cam.host}")
        
        // Put player to Loading and release connection immediately so it doesn't show error state
        releasePlayer(slotIndex)
        _uiState.update { current ->
            val states = current.playerStates.toMutableMap()
            states[slotIndex] = PlayerState.Loading()
            current.copy(playerStates = states)
        }
        
        viewModelScope.launch {
            val response = controlClient.setQuality(cam.host, heightP, cam.pinCode)
            AppLog.d("SET_QUALITY response: $response")
            if (response == "OK") {
                // Đợi streamer dừng và khởi động lại stream xong mới bắt đầu kết nối lại
                delay(2000L)
                val activeCam = _uiState.value.cameras.getOrNull(slotIndex)
                if (activeCam != null) {
                    preparePlayer(slotIndex, activeCam, _uiState.value.useTcp)
                }
            } else {
                _uiState.update { it.copy(snackbarMessage = "Lỗi khi đổi chất lượng: $response") }
                // Restore old player connection
                val activeCam = _uiState.value.cameras.getOrNull(slotIndex)
                if (activeCam != null) {
                    preparePlayer(slotIndex, activeCam, _uiState.value.useTcp)
                }
            }
        }
    }

    fun setRemoteFps(slotIndex: Int, fps: Int) {
        AppLog.d("setRemoteFps(slotIndex=$slotIndex, fps=$fps)")
        val cam = _uiState.value.cameras.getOrNull(slotIndex) ?: return
        if (!cam.isPhoneCamera) {
            AppLog.w("setRemoteFps: slot $slotIndex is not a Phone Camera")
            return
        }
        AppLog.i("setRemoteFps: slot=$slotIndex → ${fps}fps @ ${cam.host}")
        
        // Put player to Loading and release connection immediately so it doesn't show error state
        releasePlayer(slotIndex)
        _uiState.update { current ->
            val states = current.playerStates.toMutableMap()
            states[slotIndex] = PlayerState.Loading()
            current.copy(playerStates = states)
        }
        
        viewModelScope.launch {
            val response = controlClient.setFps(cam.host, fps, cam.pinCode)
            AppLog.d("SET_FPS response: $response")
            if (response == "OK") {
                delay(2000L)
                val activeCam = _uiState.value.cameras.getOrNull(slotIndex)
                if (activeCam != null) {
                    preparePlayer(slotIndex, activeCam, _uiState.value.useTcp)
                }
            } else {
                _uiState.update { it.copy(snackbarMessage = "Lỗi khi đổi FPS: $response") }
                val activeCam = _uiState.value.cameras.getOrNull(slotIndex)
                if (activeCam != null) {
                    preparePlayer(slotIndex, activeCam, _uiState.value.useTcp)
                }
            }
        }
    }

    private fun setPlayerState(index: Int, state: PlayerState) {
        AppLog.d("setPlayerState(index=$index, state=$state)")
        
        val cam = _uiState.value.cameras.getOrNull(index)
        if (cam?.isPhoneCamera == true) {
            val oldState = _uiState.value.playerStates[index]
            val deviceName = NsdHelper.deviceServiceName()
            if (oldState !is PlayerState.Playing && state is PlayerState.Playing) {
                viewModelScope.launch(Dispatchers.IO) { controlClient.sayHello(cam.host, deviceName, cam.pinCode) }
            } else if (oldState is PlayerState.Playing && state !is PlayerState.Playing) {
                viewModelScope.launch(Dispatchers.IO) { controlClient.sayBye(cam.host, deviceName, cam.pinCode) }
            }
        }

        _uiState.update { current ->
            current.copy(playerStates = current.playerStates.toMutableMap().also { it[index] = state })
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        AppLog.d("ViewerViewModel onCleared")
        prepareJobs.values.forEach { it.cancel() }
        prepareJobs.clear()
        activePlayers.values.forEach { it.release() }
        activePlayers.clear()
        _playersState.value = emptyMap()
        
        val deviceName = NsdHelper.deviceServiceName()
        _uiState.value.cameras.forEachIndexed { index, cam ->
            if (cam?.isPhoneCamera == true && _uiState.value.playerStates[index] is PlayerState.Playing) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    try { controlClient.sayBye(cam.host, deviceName, cam.pinCode) } catch (_: Exception) {}
                }
            }
        }
        nsdHelper.stopAll()
        super.onCleared()
    }
}
