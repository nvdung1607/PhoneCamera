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
import com.example.phonecamera.viewer.QualityMode
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
    exoPlayer: ExoPlayer?,
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
    qualityMode: QualityMode = QualityMode.AUTO,
    realtimeFps: Int = 0,
    activeBitrate: Int = 1_200_000,
    onSetRemoteQualityMode: ((QualityMode) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
    ) {
        LaunchedEffect(exoPlayer, isAudioEnabled) {
            exoPlayer?.volume = if (isAudioEnabled) 1f else 0f
        }

        LaunchedEffect(playerState) {
            if (playerState is PlayerState.Loading) {
                delay(30_000L)
                onPlayerError("Hết thời gian chờ kết nối (30s)")
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
                    qualityMode = qualityMode,
                    realtimeFps = realtimeFps,
                    activeBitrate = activeBitrate,
                    onSetRemoteQualityMode = onSetRemoteQualityMode
                )
            else -> EmptyCell(onAddClick)
        }
    }
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
    qualityMode: QualityMode,
    realtimeFps: Int,
    activeBitrate: Int,
    onSetRemoteQualityMode: ((QualityMode) -> Unit)?
) {
    var videoInfo by remember { mutableStateOf("") }
    var showQualityMenu by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
        val listener = VideoFrameMetadataListener { _, _, format, _ ->
            if (videoInfo.isEmpty()) videoInfo = "${format.width}x${format.height}"
        }
        exoPlayer?.setVideoFrameMetadataListener(listener)
        onDispose { exoPlayer?.clearVideoFrameMetadataListener(listener) }
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

            // Hiển thị FPS thực tế đo được từ ExoPlayer (Chỉ xem, không cho chọn FPS)
            if (!showLoadingOverlay) {
                Text(
                    text = "${realtimeFps} FPS",
                    fontSize = 9.sp,
                    color = if (realtimeFps >= 24) GreenOnline else AmberWarning,
                    modifier = Modifier
                        .background(
                            if (realtimeFps >= 24) GreenOnline.copy(alpha = 0.15f) else AmberWarning.copy(alpha = 0.15f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            if (onSetRemoteQualityMode != null && !showLoadingOverlay) {
                Box {
                    val modeLabel = if (qualityMode == QualityMode.AUTO) "Auto" else qualityMode.label.substringBefore(" ")
                    val bitrateLabel = when (activeBitrate) {
                        500_000 -> "500 Kbps"
                        1_200_000 -> "1.2 Mbps"
                        2_000_000 -> "2.0 Mbps"
                        else -> "${activeBitrate / 1000} Kbps"
                    }
                    Text(
                        text = "$modeLabel ($bitrateLabel) ⚙️",
                        fontSize = 9.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .clickable { showQualityMenu = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    DropdownMenu(
                        expanded = showQualityMenu,
                        onDismissRequest = { showQualityMenu = false }
                    ) {
                        QualityMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { 
                                    Text(
                                        text = mode.label, 
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (qualityMode == mode) FontWeight.Bold else FontWeight.Normal
                                    ) 
                                },
                                onClick = { onSetRemoteQualityMode(mode); showQualityMenu = false }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
            } else if (videoInfo.isNotEmpty() && !showLoadingOverlay) {
                Text(text = videoInfo, fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 4.dp))
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
            Button(
                onClick = onRetry,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) { Text("Thử lại", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            
            Button(
                onClick = onEdit,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onErrorContainer,
                    contentColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) { Text("Sửa", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
