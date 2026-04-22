package com.example.phonecamera.viewer.components

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import android.view.LayoutInflater
import androidx.media3.ui.PlayerView
import com.example.phonecamera.data.CameraConfig
import com.example.phonecamera.ui.theme.*
import com.example.phonecamera.viewer.PlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "RTSPGuard.CameraCell"

@Composable
fun CameraCell(
    slotIndex: Int,
    config: CameraConfig?,
    playerState: PlayerState,
    useTcp: Boolean,
    isAudioEnabled: Boolean,
    onToggleAudio: () -> Unit,
    onFullscreenClick: () -> Unit,
    isFullscreen: Boolean = false,
    onAddClick: () -> Unit,
    onEditClick: () -> Unit,
    onRetryClick: () -> Unit,
    onPlayerReady: () -> Unit,
    onPlayerError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(NavyCard)
            .border(1.dp, DividerColor, RoundedCornerShape(10.dp))
    ) {
        // ─────────────────────────────────────────────────────────────────
        // IMPORTANT: ExoPlayer is created/managed OUTSIDE any state-keyed
        // composable to prevent recreation when Loading→Playing transition
        // occurs. The player is keyed ONLY on the RTSP URL.
        // ─────────────────────────────────────────────────────────────────
        val shouldRunPlayer = config != null &&
                (playerState is PlayerState.Loading || playerState is PlayerState.Playing)

        val attemptId = (playerState as? PlayerState.Loading)?.attemptId ?: 0L

        val exoPlayer: ExoPlayer? = if (shouldRunPlayer && config != null) {
            rememberLowLatencyExoPlayer(
                rtspUrl = config.toRtspUrl(),
                useTcp = useTcp,
                attemptId = attemptId,
                onPlayerReady = {
                    Log.i(TAG, "slot=$slotIndex ▶ onPlayerReady")
                    onPlayerReady()
                },
                onPlayerError = { msg ->
                    Log.e(TAG, "slot=$slotIndex ✗ onPlayerError: $msg")
                    onPlayerError(msg)
                }
            )
        } else null

        LaunchedEffect(exoPlayer, isAudioEnabled) {
            exoPlayer?.volume = if (isAudioEnabled) 1f else 0f
        }

        // Connection Timeout Logic
        LaunchedEffect(playerState) {
            if (playerState is PlayerState.Loading) {
                delay(10000L) // 10 seconds
                onPlayerError("Hết thời gian chờ kết nối (10s)")
            }
        }

        when {
            config == null -> EmptyCell(onAddClick = onAddClick)

            playerState is PlayerState.Error -> ErrorCell(
                name = config.name,
                message = playerState.message,
                onRetry = onRetryClick,
                onEdit = onEditClick
            )

            playerState is PlayerState.Loading || playerState is PlayerState.Playing -> {
                Log.d(TAG, "slot=$slotIndex state=$playerState → ActivePlayerCell for ${config.toRtspUrl()}")
                ActivePlayerCell(
                    config = config,
                    exoPlayer = exoPlayer,
                    showLoadingOverlay = playerState is PlayerState.Loading,
                    isAudioEnabled = isAudioEnabled,
                    onToggleAudio = onToggleAudio,
                    onReload = onRetryClick,
                    onFullscreenClick = onFullscreenClick,
                    isFullscreen = isFullscreen,
                    onEdit = onEditClick
                )
            }

            playerState is PlayerState.Idle -> LoadingCell(name = config.name)

            else -> EmptyCell(onAddClick = onAddClick)
        }
    }
}

/**
 * Creates a low-latency configured ExoPlayer for RTSP live streams.
 * Keyed only on [rtspUrl] so it SURVIVES state changes (Loading → Playing)
 * without being released and recreated.
 *
 * Low-latency settings:
 * - minBufferMs=300ms, maxBufferMs=2000ms (vs defaults 15s/50s for VOD)
 * - These small buffers force ExoPlayer to render frames faster
 * - 24fps RTSP = ~42ms/frame; 300ms buffer = ~7 frames ahead
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun rememberLowLatencyExoPlayer(
    rtspUrl: String,
    useTcp: Boolean,
    attemptId: Long,
    onPlayerReady: () -> Unit,
    onPlayerError: (String) -> Unit
): ExoPlayer? {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(rtspUrl, useTcp, attemptId) {
        var player: ExoPlayer? = null
        val job = CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "Waiting 500ms before connecting to $rtspUrl (TCP=$useTcp)")
            delay(500)
            Log.d(TAG, "ExoPlayer creating (low-latency) for $rtspUrl")

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1000,   // minBufferMs (1s)
                    5000,   // maxBufferMs (5s)
                    500,    // bufferForPlaybackMs (500ms)
                    1000    // bufferForPlaybackAfterRebufferMs (1s)
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val newPlayer = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()

            newPlayer.apply {
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(useTcp) // Sử dụng UDP/TCP tuỳ chọn
                    .setTimeoutMs(15_000L)
                    .createMediaSource(MediaItem.fromUri(rtspUrl))
                setMediaSource(source)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        Log.d(TAG, "ExoPlayer state[$rtspUrl]: $state")
                        if (state == Player.STATE_READY) {
                            Log.i(TAG, "ExoPlayer READY for $rtspUrl")
                            onPlayerReady()
                        }
                    }
                    override fun onVideoSizeChanged(size: VideoSize) {
                        val fmt = videoFormat
                        Log.i(TAG, "Video format — ${size.width}x${size.height} " +
                                "@ ${fmt?.frameRate?.let { "%.1f".format(it) } ?: "?"} fps " +
                                "codec=${fmt?.sampleMimeType} " +
                                "bitrate=${fmt?.bitrate?.let { "${it/1000}kbps" } ?: "?"}")
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val msg = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                                "Không kết nối được mạng"
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                                "Hết thời gian chờ (>15s)"
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                                "Máy chủ từ chối kết nối"
                            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                                "Luồng không đồng bộ — Thử lại"
                            else -> "Lỗi [${error.errorCode}]: ${error.message?.take(60) ?: "?"}"
                        }
                        Log.e(TAG, "ExoPlayer error[$rtspUrl]: code=${error.errorCode} cause=${error.cause?.message}")
                        onPlayerError(msg)
                    }
                })
                Log.d(TAG, "ExoPlayer prepare() + playWhenReady for $rtspUrl")
                prepare()
                playWhenReady = true
            }
            player = newPlayer
            exoPlayer = newPlayer
        }

        onDispose {
            job.cancel()
            Log.d(TAG, "ExoPlayer release for $rtspUrl")
            player?.release()
            exoPlayer = null
        }
    }

    return exoPlayer
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ActivePlayerCell(
    config: CameraConfig,
    exoPlayer: ExoPlayer?,
    showLoadingOverlay: Boolean,
    isAudioEnabled: Boolean,
    onToggleAudio: () -> Unit,
    onReload: () -> Unit,
    onFullscreenClick: () -> Unit,
    isFullscreen: Boolean,
    onEdit: () -> Unit
) {
    // ── Real-time FPS counter ──────────────────────────────────────────────
    // AtomicLong counts frames rendered; a 1-second coroutine snapshots it
    val frameCounter = remember { AtomicLong(0) }
    var displayFps by remember { mutableStateOf(0) }
    var videoInfo by remember { mutableStateOf("") }  // "640x480"

    // Register frame metadata listener to count every rendered frame
    DisposableEffect(exoPlayer) {
        val listener = VideoFrameMetadataListener { _, _, format, _ ->
            frameCounter.incrementAndGet()
            if (videoInfo.isEmpty()) {
                videoInfo = "${format.width}x${format.height}"
            }
        }
        exoPlayer?.setVideoFrameMetadataListener(listener)
        onDispose { exoPlayer?.clearVideoFrameMetadataListener(listener) }
    }

    // Sample frame count every second → actual FPS
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(1000L)
            val count = frameCounter.getAndSet(0)
            if (count > 0) {
                displayFps = count.toInt()
                Log.d(TAG, "[${config.name}] Actual FPS: $displayFps  resolution: $videoInfo")
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // PlayerView always rendered — ExoPlayer delivers frames continuously
        AndroidView(
            factory = { ctx ->
                val view = LayoutInflater.from(ctx).inflate(com.example.phonecamera.R.layout.view_exo_player, null) as PlayerView
                view.apply {
                    player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view -> view.player = exoPlayer }
        )

        // Top bar: status dot + camera name + FPS badge + edit button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OverlayDark)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (showLoadingOverlay) CyanNeon else GreenOnline,
                        RoundedCornerShape(50)
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = config.name,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold
            )
            // FPS badge — shows only when playing
            if (!showLoadingOverlay && displayFps > 0) {
                Text(
                    text = "${displayFps}fps",
                    fontSize = 9.sp,
                    color = if (displayFps >= 24) GreenOnline else AmberWarning,
                    modifier = Modifier
                        .background(
                            if (displayFps >= 24) GreenOnline.copy(alpha = 0.12f)
                            else AmberWarning.copy(alpha = 0.12f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (videoInfo.isNotEmpty() && !showLoadingOverlay) {
                Text(
                    text = videoInfo,
                    fontSize = 9.sp,
                    color = TextHint,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            
            // Audio toggle button
            IconButton(
                onClick = onToggleAudio, 
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (isAudioEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                    contentDescription = "Bật/Tắt âm thanh",
                    tint = if (isAudioEnabled) CyanNeon else TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Reload toggle button
            IconButton(
                onClick = onReload,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Tải lại camera",
                    tint = CyanNeon,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Fullscreen toggle button
            IconButton(
                onClick = onFullscreenClick,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = "Toàn màn hình",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Outlined.Edit, "Sửa camera",
                    tint = TextSecondary, modifier = Modifier.size(14.dp)
                )
            }
        }

        // Spinner overlay — fades out when ExoPlayer fires STATE_READY
        AnimatedVisibility(
            visible = showLoadingOverlay,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Đang kết nối...", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
    }
}

// ─── Passive cells ────────────────────────────────────────────────────────────

@Composable
private fun EmptyCell(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().clickable(onClick = onAddClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.AddCircleOutline, "Thêm camera",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Thêm Camera", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingCell(name: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = CyanNeon, strokeWidth = 2.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
        Text("Đang khởi động...", style = MaterialTheme.typography.bodySmall,
            color = TextHint, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCell(name: String, message: String, onRetry: () -> Unit, onEdit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(RedErrorSurface).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.WifiOff, null, tint = RedError, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelSmall,
            color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(2.dp))
        Text(message, style = MaterialTheme.typography.bodySmall,
            color = RedError, textAlign = TextAlign.Center,
            fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onRetry, modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanNeon),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanNeon.copy(alpha = 0.5f))
            ) { Text("Thử lại", fontSize = 11.sp) }
            OutlinedButton(
                onClick = onEdit, modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                border = androidx.compose.foundation.BorderStroke(1.dp, DividerColor)
            ) { Text("Sửa", fontSize = 11.sp) }
        }
    }
}
