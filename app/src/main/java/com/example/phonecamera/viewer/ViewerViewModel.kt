package com.example.phonecamera.viewer

import android.content.Context
import com.example.phonecamera.utils.AppLog
import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.CoroutineScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.rtsp.RtspMediaSource



sealed class PlayerState {
    data object Idle : PlayerState()
    data class Loading(
        val message: String = "Đang kết nối...",
        val attemptId: Long = System.currentTimeMillis()
    ) : PlayerState()
    data object Playing : PlayerState()
    data class Error(val message: String) : PlayerState()
}

enum class QualityMode(val label: String, val height: Int, val fps: Int, val bitrateBps: Int) {
    AUTO("Tự động", 720, 24, 1_200_000),
    LD("Mượt (360p)", 360, 15, 500_000),
    SD("Chuẩn (720p)", 720, 24, 1_200_000),
    HD("Sắc nét (1080p)", 1080, 30, 2_000_000)
}

data class ViewerUiState(
    val cameras: List<CameraConfig?> = List(4) { null },
    val playerStates: Map<Int, PlayerState> = emptyMap(),
    val snackbarMessage: String? = null,
    val isScanning: Boolean = false,
    val useTcp: Boolean = false, // Default to UDP as requested by user
    val selectedAudioSlot: Int? = null, // Which slot's audio is currently unmuted
    val discoveredCameras: List<DiscoveredCamera> = emptyList(),
    val discoveryBadgeCount: Int = 0,
    val realtimeFps: Map<Int, Int> = emptyMap(),
    val qualityModes: Map<Int, QualityMode> = emptyMap(),
    val activeBitrates: Map<Int, Int> = emptyMap()
) {
    fun playerStateFor(index: Int): PlayerState = playerStates[index] ?: PlayerState.Idle
    fun qualityModeFor(index: Int): QualityMode = qualityModes[index] ?: QualityMode.AUTO
    fun fpsFor(index: Int): Int = realtimeFps[index] ?: 0
    fun activeBitrateFor(index: Int): Int = activeBitrates[index] ?: 1_200_000

    val occupiedHosts: Set<String>
        get() = cameras.filterNotNull().map { it.host }.toSet()

    val firstEmptySlot: Int?
        get() = cameras.indexOfFirst { it == null }.takeIf { it >= 0 }
}

class ViewerViewModel(
    private val context: Context,
    private val repository: CameraRepository,
    private val nsdHelper: NsdHelper,
    private val controlClient: CameraControlClient,
    private val applicationScope: CoroutineScope
) : ViewModel() {

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
        startMonitoringLoop()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun preparePlayer(index: Int, config: CameraConfig, useTcp: Boolean, debounceMs: Long = 500L) {
        AppLog.d("preparePlayer(index=$index, config=${config.name}, useTcp=$useTcp, debounceMs=$debounceMs)")
        // Cancel any pending connect job for this slot immediately
        prepareJobs[index]?.cancel()
        
        // Release the player immediately to close the socket and free up the Server side resource
        releasePlayer(index)
        
        // Launch new connection task with configurable debounce delay
        prepareJobs[index] = viewModelScope.launch(Dispatchers.Main) {
            if (debounceMs > 0) {
                AppLog.d("Waiting ${debounceMs}ms before connecting slot $index (${config.toRtspUrl()})")
                delay(debounceMs)
            }
            
            val context = this@ViewerViewModel.context
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

    /**
     * Tái kết nối nhanh bằng cách reuse ExoPlayer instance hiện tại.
     * Không tạo object mới, không debounce — phù hợp khi đổi độ phân giải.
     * Nếu player hiện tại không tồn tại, fallback về preparePlayer() với debounceMs=0.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun reconnectPlayer(index: Int, config: CameraConfig, useTcp: Boolean) {
        AppLog.d("reconnectPlayer(index=$index, config=${config.name})")
        val existingPlayer = activePlayers[index]
        if (existingPlayer != null) {
            // Reuse existing player: stop → set new source → prepare
            // Nhanh hơn nhiều so với release + tạo mới (không GC, không socket overhead)
            prepareJobs[index]?.cancel()
            prepareJobs[index] = viewModelScope.launch(Dispatchers.Main) {
                try {
                    existingPlayer.stop()
                    val source = RtspMediaSource.Factory()
                        .setForceUseRtpTcp(useTcp)
                        .setTimeoutMs(30_000L)
                        .createMediaSource(MediaItem.fromUri(config.toRtspUrl()))
                    existingPlayer.setMediaSource(source)
                    existingPlayer.prepare()
                    existingPlayer.playWhenReady = true
                    AppLog.d("reconnectPlayer slot $index: reusing player, reconnecting...")
                } catch (e: Exception) {
                    AppLog.e("reconnectPlayer slot $index failed, falling back to preparePlayer", e)
                    preparePlayer(index, config, useTcp, debounceMs = 0L)
                }
            }
        } else {
            // Không có player → tạo mới không debounce
            preparePlayer(index, config, useTcp, debounceMs = 0L)
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
        
        val retries = retryCounts[index] ?: 0
        if (retries < 5) {
            retryCounts[index] = retries + 1
            setPlayerState(index, PlayerState.Loading("Mất kết nối. Đang thử lại (lần ${retries + 1}/5)..."))
            viewModelScope.launch {
                // 3 lần retry đầu dùng delay ngắn hơn (500ms) để recover nhanh sau khi đổi độ phân giải
                // Sau đó tăng dần lên 1500ms cho các lần retry tiếp theo (tránh flood)
                val retryDelayMs = if (retries < 3) 500L else 1500L
                delay(retryDelayMs)
                val cam = _uiState.value.cameras.getOrNull(index)
                val currentState = _uiState.value.playerStates[index]
                if (cam != null && (currentState is PlayerState.Error || currentState is PlayerState.Loading)) {
                    AppLog.i("Auto-retrying connection for slot $index (attempt ${retries + 1}/5, delay was ${retryDelayMs}ms)...")
                    retryCamera(index)
                }
            }
        } else {
            setPlayerState(index, PlayerState.Error("Không thể kết nối lại sau 5 lần thử. Lỗi: $errorMsg"))
        }
    }

    fun retryCamera(index: Int) {
        val cam = _uiState.value.cameras.getOrNull(index) ?: return
        AppLog.i("Retrying camera in slot $index (${cam.host}:${cam.port})")
        val newStates = _uiState.value.playerStates.toMutableMap()
        newStates[index] = PlayerState.Loading("Đang kết nối lại...")
        _uiState.value = _uiState.value.copy(playerStates = newStates)
        // Dùng reconnectPlayer để reuse ExoPlayer instance — nhanh hơn preparePlayer
        reconnectPlayer(index, cam, _uiState.value.useTcp)
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
                    newStates[i] = PlayerState.Loading("Đang kết nối lại...")
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

    private fun getCurrentBitrateFor(slotIndex: Int): Int {
        return _uiState.value.activeBitrateFor(slotIndex)
    }

    private fun setCurrentBitrateFor(slotIndex: Int, bitrate: Int) {
        _uiState.update { current ->
            val bitrates = current.activeBitrates.toMutableMap()
            bitrates[slotIndex] = bitrate
            current.copy(activeBitrates = bitrates)
        }
    }

    private fun getLowerBitrate(current: Int): Int {
        return when {
            current > 1_200_000 -> 1_200_000
            else -> 500_000
        }
    }

    private fun getHigherBitrate(current: Int): Int {
        return when {
            current < 1_200_000 -> 1_200_000
            else -> 2_000_000
        }
    }

    fun setRemoteQualityMode(slotIndex: Int, mode: QualityMode) {
        AppLog.d("setRemoteQualityMode(slotIndex=$slotIndex, mode=$mode)")
        val cam = _uiState.value.cameras.getOrNull(slotIndex) ?: return
        if (!cam.isPhoneCamera) {
            AppLog.w("setRemoteQualityMode: slot $slotIndex is not a Phone Camera")
            return
        }
        
        _uiState.update { current ->
            val modes = current.qualityModes.toMutableMap()
            modes[slotIndex] = mode
            current.copy(qualityModes = modes)
        }
        
        val height = if (mode != QualityMode.AUTO) mode.height else QualityMode.SD.height
        val bitrateBps = if (mode != QualityMode.AUTO) mode.bitrateBps else QualityMode.SD.bitrateBps
        val label = if (mode != QualityMode.AUTO) mode.label else "Tự động (${QualityMode.SD.label})"

        AppLog.i("setRemoteQualityMode: slot=$slotIndex → $label @ ${cam.host}")
        setPlayerState(slotIndex, PlayerState.Loading("Đang chuyển sang $label..."))

        viewModelScope.launch {
            // Gửi lệnh đổi chất lượng đến streamer.
            // Pedro stopStream() chỉ dừng RTP — TCP connection vẫn giữ nguyên.
            // Viewer reconnect nhanh qua reconnectPlayer() sau khi streamer reconfigure xong.
            val response = controlClient.setQuality(cam.host, height, cam.pinCode)
            AppLog.d("SET_QUALITY response: $response")

            if (response == "OK") {
                setCurrentBitrateFor(slotIndex, bitrateBps)
                // Streamer đang reconfigure encoder (~100-200ms).
                // Chờ tối thiểu 150ms để encoder restart, sau đó reconnect ngay.
                // reconnectPlayer() reuse ExoPlayer hiện tại — không tạo object mới, không debounce.
                delay(150L)
                reconnectPlayer(slotIndex, cam, _uiState.value.useTcp)
            } else {
                setPlayerState(slotIndex, PlayerState.Error("Không thể chuyển chất lượng: $response"))
                _uiState.update { it.copy(snackbarMessage = "Lỗi khi đổi chất lượng: $response") }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startMonitoringLoop() {
        viewModelScope.launch(Dispatchers.Main) {
            val lastRenderedFrameCounts = mutableMapOf<Int, Long>()
            val abrCooldowns = mutableMapOf<Int, Long>()
            val lowBufferAccumulator = mutableMapOf<Int, Int>()
            val highBufferAccumulator = mutableMapOf<Int, Int>()
            
            while (true) {
                delay(1000L)
                val currentUiState = _uiState.value
                val currentTime = System.currentTimeMillis()
                
                val newFpsMap = mutableMapOf<Int, Int>()
                activePlayers.forEach { (slotIndex, player) ->
                    val totalFrames = (player as? ExoPlayer)?.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L
                    val lastFrames = lastRenderedFrameCounts[slotIndex] ?: 0L
                    val diff = (totalFrames - lastFrames).toInt().coerceAtLeast(0)
                    newFpsMap[slotIndex] = diff
                    lastRenderedFrameCounts[slotIndex] = totalFrames
                }
                
                _uiState.update { current ->
                    current.copy(realtimeFps = newFpsMap)
                }
                
                if (currentTime % 2000 < 1000) {
                    activePlayers.forEach { (slotIndex, player) ->
                        val mode = currentUiState.qualityModes[slotIndex] ?: QualityMode.AUTO
                        if (mode == QualityMode.AUTO) {
                            val exoPlayer = player as? ExoPlayer ?: return@forEach
                            val bufferMs = exoPlayer.bufferedPosition - exoPlayer.currentPosition
                            val isPlaying = exoPlayer.playbackState == Player.STATE_READY && exoPlayer.playWhenReady
                            
                            if (isPlaying) {
                                val cam = currentUiState.cameras.getOrNull(slotIndex) ?: return@forEach
                                val currentBitrate = getCurrentBitrateFor(slotIndex)
                                val cooldownExpired = currentTime > (abrCooldowns[slotIndex] ?: 0L)
                                
                                if (bufferMs < 1000) {
                                    highBufferAccumulator[slotIndex] = 0
                                    val currentLowCount = (lowBufferAccumulator[slotIndex] ?: 0) + 1
                                    lowBufferAccumulator[slotIndex] = currentLowCount
                                    
                                    if (cooldownExpired || bufferMs < 500) {
                                        val targetBitrate = getLowerBitrate(currentBitrate)
                                        if (targetBitrate != currentBitrate) {
                                            AppLog.i("ABR [Slot $slotIndex]: Buffer low ($bufferMs ms). Downgrading bitrate to $targetBitrate bps")
                                            viewModelScope.launch {
                                                val res = controlClient.setBitrate(cam.host, targetBitrate, cam.pinCode)
                                                if (res == "OK") {
                                                    setCurrentBitrateFor(slotIndex, targetBitrate)
                                                    abrCooldowns[slotIndex] = currentTime + 8000L
                                                    lowBufferAccumulator[slotIndex] = 0
                                                }
                                            }
                                        }
                                    }
                                } else if (bufferMs > 3500) {
                                    lowBufferAccumulator[slotIndex] = 0
                                    val currentHighCount = (highBufferAccumulator[slotIndex] ?: 0) + 1
                                    highBufferAccumulator[slotIndex] = currentHighCount
                                    
                                    if (cooldownExpired && currentHighCount >= 2) {
                                        val targetBitrate = getHigherBitrate(currentBitrate)
                                        if (targetBitrate != currentBitrate) {
                                            AppLog.i("ABR [Slot $slotIndex]: Buffer high ($bufferMs ms). Upgrading bitrate to $targetBitrate bps")
                                            viewModelScope.launch {
                                                val res = controlClient.setBitrate(cam.host, targetBitrate, cam.pinCode)
                                                if (res == "OK") {
                                                    setCurrentBitrateFor(slotIndex, targetBitrate)
                                                    abrCooldowns[slotIndex] = currentTime + 8000L
                                                    highBufferAccumulator[slotIndex] = 0
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    lowBufferAccumulator[slotIndex] = 0
                                    highBufferAccumulator[slotIndex] = 0
                                }
                            }
                        }
                    }
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
                applicationScope.launch(Dispatchers.IO) {
                    try { controlClient.sayBye(cam.host, deviceName, cam.pinCode) } catch (_: Exception) {}
                }
            }
        }
        nsdHelper.stopAll()
        super.onCleared()
    }
}
