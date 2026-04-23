package com.example.phonecamera.viewer.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.example.phonecamera.utils.AppLog
import com.example.phonecamera.viewer.PlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

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
    onSetRemoteQuality: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
    ) {
        val shouldRunPlayer = config != null &&
                (playerState is PlayerState.Loading || playerState is PlayerState.Playing)
        val attemptId = (playerState as? PlayerState.Loading)?.attemptId ?: 0L

        val exoPlayer: ExoPlayer? = if (shouldRunPlayer && config != null) {
            rememberLowLatencyExoPlayer(
                rtspUrl = config.toRtspUrl(),
                useTcp = useTcp,
                attemptId = attemptId,
                onPlayerReady = { AppLog.i("slot=$slotIndex ▶ ready"); onPlayerReady() },
                onPlayerError = { msg -> AppLog.e("slot=$slotIndex ✗ $msg"); onPlayerError(msg) }
            )
        } else null

        LaunchedEffect(exoPlayer, isAudioEnabled) {
            exoPlayer?.volume = if (isAudioEnabled) 1f else 0f
        }

        LaunchedEffect(playerState) {
            if (playerState is PlayerState.Loading) {
                delay(10_000L)
                onPlayerError("Hết thời gian chờ kết nối (10s)")
            }
        }

        when {
            config == null -> EmptyCell(onAddClick)
            playerState is PlayerState.Error -> ErrorCell(config.name, playerState.message, onRetryClick, onEditClick)
            playerState is PlayerState.Loading || playerState is PlayerState.Playing ->
                ActivePlayerCell(
                    config = config,
                    exoPlayer = exoPlayer,
                    showLoadingOverlay = playerState is PlayerState.Loading,
                    isAudioEnabled = isAudioEnabled,
                    onToggleAudio = onToggleAudio,
                    onReload = onRetryClick,
                    onFullscreenClick = onFullscreenClick,
                    isFullscreen = isFullscreen,
                    onEdit = onEditClick,
                    onSetRemoteQuality = onSetRemoteQuality
                )
            else -> EmptyCell(onAddClick)
        }
    }
}

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
            delay(500)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 5000, 500, 1000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val newPlayer = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
            newPlayer.apply {
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(useTcp)
                    .setTimeoutMs(15_000L)
                    .createMediaSource(MediaItem.fromUri(rtspUrl))
                setMediaSource(source)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) onPlayerReady()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val msg = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Không kết nối được mạng"
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Hết thời gian chờ (>15s)"
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Máy chủ từ chối kết nối"
                            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Luồng không đồng bộ — Thử lại"
                            else -> "Lỗi [${error.errorCode}]: ${error.message?.take(60) ?: "?"}"
                        }
                        onPlayerError(msg)
                    }
                })
                prepare()
                playWhenReady = true
            }
            player = newPlayer; exoPlayer = newPlayer
        }
        onDispose { job.cancel(); player?.release(); exoPlayer = null }
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
    onEdit: () -> Unit,
    onSetRemoteQuality: ((Int) -> Unit)?
) {
    val frameCounter = remember { AtomicLong(0) }
    var displayFps by remember { mutableStateOf(0) }
    var videoInfo by remember { mutableStateOf("") }
    var showQualityMenu by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
        val listener = VideoFrameMetadataListener { _, _, format, _ ->
            frameCounter.incrementAndGet()
            if (videoInfo.isEmpty()) videoInfo = "${format.width}x${format.height}"
        }
        exoPlayer?.setVideoFrameMetadataListener(listener)
        onDispose { exoPlayer?.clearVideoFrameMetadataListener(listener) }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(1000L)
            val count = frameCounter.getAndSet(0)
            if (count > 0) displayFps = count.toInt()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(
                    com.example.phonecamera.R.layout.view_exo_player, null
                ) as PlayerView).also { it.player = exoPlayer }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.player = exoPlayer }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OverlayDark)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(6.dp).background(
                if (showLoadingOverlay) CyanNeon else GreenOnline, RoundedCornerShape(50)
            ))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = config.name,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold
            )
            if (!showLoadingOverlay && displayFps > 0) {
                Text(
                    text = "${displayFps}fps", fontSize = 9.sp,
                    color = if (displayFps >= 24) GreenOnline else AmberWarning,
                    modifier = Modifier
                        .background(
                            if (displayFps >= 24) GreenOnline.copy(alpha = 0.15f) else AmberWarning.copy(alpha = 0.15f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (videoInfo.isNotEmpty() && !showLoadingOverlay) {
                Text(text = videoInfo, fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 4.dp))
            }

            // Nút đổi chất lượng từ xa (chỉ Phone Camera)
            if (onSetRemoteQuality != null) {
                Box {
                    IconButton(onClick = { showQualityMenu = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Hd, "Đổi chất lượng",
                            tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = showQualityMenu,
                        onDismissRequest = { showQualityMenu = false }
                    ) {
                        listOf(360 to "360p", 720 to "720p", 1080 to "1080p").forEach { (h, label) ->
                            DropdownMenuItem(
                                text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                onClick = { onSetRemoteQuality(h); showQualityMenu = false }
                            )
                        }
                    }
                }
            }

            IconButton(onClick = onToggleAudio, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (isAudioEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                    "Âm thanh",
                    tint = if (isAudioEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onReload, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Outlined.Refresh, "Tải lại", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onFullscreenClick, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    "Toàn màn hình", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Outlined.Edit, "Sửa", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            }
        }

        AnimatedVisibility(
            visible = showLoadingOverlay, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Đang kết nối...", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun EmptyCell(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().clickable(onClick = onAddClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(40.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.AddCircleOutline, "Thêm",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Thêm Camera", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorCell(name: String, message: String, onRetry: () -> Unit, onEdit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.WifiOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(2.dp))
        Text(message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
            fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) { Text("Thử lại", fontSize = 11.sp) }
            OutlinedButton(onClick = onEdit, modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) { Text("Sửa", fontSize = 11.sp) }
        }
    }
}
