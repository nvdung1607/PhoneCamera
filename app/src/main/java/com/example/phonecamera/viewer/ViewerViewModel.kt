package com.example.phonecamera.viewer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phonecamera.data.CameraConfig
import com.example.phonecamera.data.CameraRepository
import com.example.phonecamera.data.nsd.DiscoveredCamera
import com.example.phonecamera.data.nsd.NsdHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "RTSPGuard.Viewer"

sealed class PlayerState {
    data object Idle : PlayerState()
    data object Loading : PlayerState()
    data object Playing : PlayerState()
    data class Error(val message: String) : PlayerState()
}

data class ViewerUiState(
    val cameras: List<CameraConfig?> = List(4) { null },
    val playerStates: Map<Int, PlayerState> = emptyMap(),
    val snackbarMessage: String? = null,
    val isScanning: Boolean = false,
    val useTcp: Boolean = false, // Default to UDP
    val selectedAudioSlot: Int? = null, // Which slot's audio is currently unmuted
    val discoveredCameras: List<DiscoveredCamera> = emptyList(),
    val discoveryBadgeCount: Int = 0
) {
    fun playerStateFor(index: Int): PlayerState = playerStates[index] ?: PlayerState.Idle

    val occupiedRtspUrls: Set<String>
        get() = cameras.filterNotNull().map { it.toRtspUrl() }.toSet()

    val firstEmptySlot: Int?
        get() = cameras.indexOfFirst { it == null }.takeIf { it >= 0 }
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CameraRepository(application)
    private val nsdHelper = NsdHelper(application)

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        Log.d(TAG, "ViewerViewModel init")
        viewModelScope.launch {
            repository.camerasFlow.collect { savedCameras ->
                Log.d(TAG, "Loaded ${savedCameras.size} cameras from DataStore: ${savedCameras.map { "slot${it.id}=${it.host}:${it.port}" }}")
                val slots = MutableList<CameraConfig?>(4) { null }
                savedCameras.forEach { cam -> if (cam.id in 0..3) slots[cam.id] = cam }
                _uiState.update { current ->
                    // Auto-trigger Loading for slots that have a config but no active player state
                    // This ensures ExoPlayer starts when the app opens with saved cameras
                    val newStates = current.playerStates.toMutableMap()
                    slots.forEachIndexed { i, cam ->
                        if (cam != null) {
                            val existingState = newStates[i]
                            if (existingState == null || existingState is PlayerState.Idle) {
                                Log.d(TAG, "Auto-triggering Loading for slot $i (${cam.host}:${cam.port})")
                                newStates[i] = PlayerState.Loading
                            }
                        }
                    }
                    current.copy(cameras = slots, playerStates = newStates)
                }
            }
        }
        startDiscovery()
    }

    // ─── Discovery ────────────────────────────────────────────────────────

    fun startDiscovery() {
        if (_uiState.value.isScanning) {
            Log.d(TAG, "startDiscovery() skipped — already scanning")
            return
        }
        Log.d(TAG, "startDiscovery() — starting NSD scan for _rtspguard._tcp")
        _uiState.update { it.copy(isScanning = true) }

        nsdHelper.discoverServices(
            onFound = { camera ->
                viewModelScope.launch(Dispatchers.Main) {
                    Log.i(TAG, "✅ Camera found: ${camera.displayName} @ ${camera.host}:${camera.port}")
                    _uiState.update { state ->
                        val updated = state.discoveredCameras.toMutableList()
                        val idx = updated.indexOfFirst { it.serviceId == camera.serviceId }
                        if (idx >= 0) updated[idx] = camera else updated.add(camera)
                        val newCount = updated.count { it.rtspUrl !in state.occupiedRtspUrls }
                        
                        // Auto-reconnect if the discovered camera matches an existing offline slot by name
                        val newStates = state.playerStates.toMutableMap()
                        val updatedCameras = state.cameras.toMutableList()
                        state.cameras.forEachIndexed { i, cam ->
                            if (cam != null && cam.name == camera.displayName) {
                                Log.i(TAG, "Auto-reconnecting slot $i for ${camera.displayName} at ${camera.host}:${camera.port}")
                                newStates[i] = PlayerState.Loading
                                
                                // Update IP/Port in case it changed
                                if (cam.host != camera.host || cam.port != camera.port) {
                                    val newCam = cam.copy(host = camera.host, port = camera.port)
                                    updatedCameras[i] = newCam
                                    viewModelScope.launch { repository.saveCamera(newCam) }
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
                    Log.i(TAG, "❌ Camera lost: serviceId=$serviceId")
                    _uiState.update { state ->
                        val lostCamera = state.discoveredCameras.find { it.serviceId == serviceId }
                        val newStates = state.playerStates.toMutableMap()

                        if (lostCamera != null) {
                            Log.w(TAG, "Camera offline: ${lostCamera.displayName} @ ${lostCamera.host}:${lostCamera.port}")
                            // Find all slots streaming from this camera and mark them as Offline instead of deleting
                            state.cameras.forEachIndexed { i, cam ->
                                if (cam != null && cam.name == lostCamera.displayName) {
                                    Log.w(TAG, "Marking slot $i as Offline (was streaming from ${lostCamera.host})")
                                    newStates[i] = PlayerState.Error("Camera đã mất kết nối")
                                }
                            }
                        }

                        val updated = state.discoveredCameras.filter { it.serviceId != serviceId }
                        val newCount = updated.count { it.rtspUrl !in state.occupiedRtspUrls }
                        state.copy(discoveredCameras = updated, discoveryBadgeCount = newCount, playerStates = newStates)
                    }
                }
            }
        )
    }

    fun stopDiscovery() {
        Log.d(TAG, "stopDiscovery()")
        nsdHelper.stopDiscovery()
        _uiState.update { it.copy(isScanning = false) }
    }

    fun addDiscoveredCamera(camera: DiscoveredCamera, targetSlot: Int? = null) {
        val slot = targetSlot ?: _uiState.value.firstEmptySlot
        if (slot == null) {
            Log.w(TAG, "addDiscoveredCamera: no empty slot available")
            return
        }
        Log.d(TAG, "addDiscoveredCamera: ${camera.displayName} → slot $slot (${camera.rtspUrl})")
        val config = CameraConfig(
            id = slot,
            name = camera.displayName,
            host = camera.host,
            port = camera.port
        )
        saveCamera(config)
        _uiState.update { it.copy(discoveryBadgeCount = maxOf(0, it.discoveryBadgeCount - 1)) }
    }

    fun clearDiscoveryBadge() {
        _uiState.update { it.copy(discoveryBadgeCount = 0) }
    }

    // ─── Camera CRUD ──────────────────────────────────────────────────────

    fun saveCamera(config: CameraConfig) {
        Log.d(TAG, "saveCamera: slot=${config.id} name='${config.name}' url=${config.toRtspUrl()}")
        viewModelScope.launch {
            repository.saveCamera(config)
            setPlayerState(config.id, PlayerState.Loading)
        }
    }

    fun deleteCamera(id: Int) {
        Log.d(TAG, "deleteCamera: slot=$id")
        viewModelScope.launch {
            repository.deleteCamera(id)
            setPlayerState(id, PlayerState.Idle)
        }
    }

    fun onPlayerReady(index: Int) {
        Log.i(TAG, "▶ Player slot $index → Playing")
        setPlayerState(index, PlayerState.Playing)
    }

    fun onPlayerError(index: Int, errorMsg: String) {
        Log.e(TAG, "✗ Player slot $index error: $errorMsg")
        setPlayerState(index, PlayerState.Error(errorMsg))
    }

    fun retryCamera(index: Int) {
        val cam = _uiState.value.cameras.getOrNull(index) ?: return
        Log.i(TAG, "Retrying camera in slot $index (${cam.host}:${cam.port})")
        val newStates = _uiState.value.playerStates.toMutableMap()
        newStates[index] = PlayerState.Loading
        _uiState.value = _uiState.value.copy(playerStates = newStates)
    }

    fun toggleTcp() {
        _uiState.update { state ->
            val isTcp = !state.useTcp
            Log.d(TAG, "Toggling TCP mode to: $isTcp. Reloading all active cameras.")
            val newStates = state.playerStates.toMutableMap()
            // Force reload by setting all active players back to Loading
            state.cameras.forEachIndexed { i, cam ->
                if (cam != null && newStates[i] is PlayerState.Playing) {
                    newStates[i] = PlayerState.Loading
                }
            }
            state.copy(useTcp = isTcp, playerStates = newStates)
        }
    }

    fun toggleAudio(slotIndex: Int) {
        _uiState.update { state ->
            val newAudioSlot = if (state.selectedAudioSlot == slotIndex) null else slotIndex
            Log.d(TAG, "Toggling audio: slot $slotIndex. New selected audio slot: $newAudioSlot")
            state.copy(selectedAudioSlot = newAudioSlot)
        }
    }

    fun dismissSnackbar() { _uiState.update { it.copy(snackbarMessage = null) } }

    private fun setPlayerState(index: Int, state: PlayerState) {
        Log.v(TAG, "setPlayerState: slot=$index → $state")
        _uiState.update { current ->
            current.copy(playerStates = current.playerStates.toMutableMap().also { it[index] = state })
        }
    }

    override fun onCleared() {
        Log.d(TAG, "ViewerViewModel onCleared — stopping NSD")
        nsdHelper.stopAll()
        super.onCleared()
    }
}
