package com.example.phonecamera.streamer

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phonecamera.PhoneCameraApp
import com.example.phonecamera.di.ViewModelFactory
import com.example.phonecamera.ui.theme.*
import com.example.phonecamera.utils.AppLog
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import kotlinx.coroutines.flow.collect

@Composable
fun StreamerScreen(
    onBack: () -> Unit,
    viewModel: StreamerViewModel = viewModel(
        factory = ViewModelFactory((LocalContext.current.applicationContext as PhoneCameraApp).container)
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val view = LocalView.current

    // Reference to physical camera engine kept strictly in View layer
    var rtspCamera by remember { mutableStateOf<RtspServerCamera2?>(null) }

    // Check camera hardware on view creation and notify ViewModel
    LaunchedEffect(Unit) {
        try {
            val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
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
            viewModel.onCameraHardwareChecked(hasFront, hasBack)
        } catch (e: Exception) {
            AppLog.e("Failed to check camera hardware", e)
        }
    }

    // Set screen to landscape and hide system bars
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val window = activity?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        onDispose {
            viewModel.releaseCamera()
            rtspCamera?.apply {
                if (isStreaming) stopStream()
                if (isOnPreview) stopPreview()
            }
            rtspCamera = null
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
                w.attributes = w.attributes.also { it.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }

    // Collect command events from ViewModel to control physical camera
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(viewModel.commands, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.commands.collect { cmd ->
                when (cmd) {
                    is StreamerCommand.StartStream -> {
                        rtspCamera?.let { cam ->
                            if (cam.isOnPreview) {
                                cam.stopPreview()
                            }
                            cam.getStreamClient().setAuthorization("admin", cmd.pinCode)
                            val rotation = CameraHelper.getCameraOrientation(context)
                            if (!cam.prepareVideo(cmd.width, cmd.height, cmd.fps, cmd.bitrate, rotation)) {
                                viewModel.onStreamStartFailed("Không thể chuẩn bị encoder video.")
                                return@collect
                            }
                            if (!cam.prepareAudio()) {
                                viewModel.onStreamStartFailed("Không thể khởi tạo microphone.")
                                return@collect
                            }
                            cam.startPreview(
                                if (cmd.useFrontCamera) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK,
                                cmd.width,
                                cmd.height
                            )
                            cam.startStream()
                            if (cam.isStreaming) {
                                viewModel.onStreamStartedSuccess()
                            } else {
                                viewModel.onStreamStartFailed("Không thể bắt đầu phát.")
                            }
                        } ?: viewModel.onStreamStartFailed("Camera chưa sẵn sàng.")
                    }
                    is StreamerCommand.StopStream -> {
                        rtspCamera?.let { cam ->
                            if (cam.isStreaming) cam.stopStream()
                        }
                        viewModel.onStreamStopped()
                    }
                    is StreamerCommand.SwitchCamera -> {
                        rtspCamera?.switchCamera()
                    }
                    is StreamerCommand.SetBitrate -> {
                        rtspCamera?.let { cam ->
                            if (cam.isStreaming) {
                                cam.setVideoBitrateOnFly(cmd.bitrateBps)
                                AppLog.i("Bitrate changed dynamically to ${cmd.bitrateBps} bps")
                            }
                        }
                    }
                }
            }
        }
    }

    // Stop stream when app goes to background
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.stopStream()
        rtspCamera?.let { cam ->
            if (cam.isStreaming) cam.stopStream()
        }
    }

    // Auto-dim screen after 30 seconds of inactivity
    var isDimmed by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lastInteractionTime) {
        kotlinx.coroutines.delay(30_000L)
        isDimmed = true
    }

    // Adjust physical screen brightness
    DisposableEffect(isDimmed) {
        val window = (context as? Activity)?.window
        window?.attributes = window?.attributes?.also {
            it.screenBrightness = if (isDimmed) 0.01f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        onDispose {}
    }

    // Show error snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.dismissError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            isDimmed = false
                            lastInteractionTime = System.currentTimeMillis()
                        }
                    }
                }
            }
    ) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        modifier = Modifier.padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        dismissAction = {
                            TextButton(onClick = { data.dismiss() }) {
                                Text("Đóng", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    ) { Text(data.visuals.message) }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // ─── Camera Preview (65%) ────────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight()
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            OpenGlView(ctx).also { glView ->
                                glView.setAspectRatioMode(AspectRatioMode.Adjust)
                                glView.post {
                                    val checker = object : ConnectChecker {
                                        override fun onConnectionStarted(url: String) = AppLog.d("Client connecting: $url")
                                        override fun onConnectionSuccess() = AppLog.i("Client connected")
                                        override fun onConnectionFailed(reason: String) {
                                            AppLog.e("Connection failed: $reason")
                                            viewModel.onStreamStartFailed(reason)
                                        }
                                        override fun onNewBitrate(bitrate: Long) = AppLog.v("Bitrate: ${bitrate / 1000} kbps")
                                        override fun onDisconnect() = AppLog.i("Client disconnected")
                                        override fun onAuthError() = AppLog.e("Auth error")
                                        override fun onAuthSuccess() {}
                                    }
                                    val cam = RtspServerCamera2(glView, checker, RTSP_PORT).apply {
                                        startPreview(
                                            if (uiState.useFrontCamera) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK,
                                            uiState.selectedResolution.width,
                                            uiState.selectedResolution.height
                                        )
                                    }
                                    rtspCamera = cam
                                    viewModel.onCameraPreviewReady(true)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Top Control row overlay
                    var showPin by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, start = 8.dp, end = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Back button + badges
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (uiState.isStreaming) viewModel.stopStream()
                                    onBack()
                                },
                                modifier = Modifier.background(OverlayDark, RoundedCornerShape(50))
                            ) {
                                Icon(Icons.Filled.ArrowBack, "Quay lại", tint = Color.White)
                            }

                            AnimatedVisibility(
                                visible = uiState.isStreaming,
                                enter = fadeIn(), exit = fadeOut()
                            ) { LiveBadge() }

                            AnimatedVisibility(
                                visible = uiState.isStreaming && uiState.pinCode.isNotEmpty(),
                                enter = fadeIn(), exit = fadeOut()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .background(OverlayDark, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Lock, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (showPin) "PIN: ${uiState.pinCode}" else "PIN: ****",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { showPin = !showPin },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showPin) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                            contentDescription = "Ẩn/Hiện PIN",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Right: Control buttons
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = { isDimmed = true },
                                modifier = Modifier.background(OverlayDark, RoundedCornerShape(50))
                            ) {
                                Icon(Icons.Filled.VisibilityOff, "Tắt màn hình", tint = TextSecondary)
                            }
                            IconButton(
                                onClick = { viewModel.switchCamera() },
                                enabled = uiState.hasFrontCamera && uiState.hasBackCamera,
                                modifier = Modifier.background(
                                    if (uiState.hasFrontCamera && uiState.hasBackCamera) OverlayDark else OverlayDark.copy(alpha = 0.2f),
                                    RoundedCornerShape(50)
                                )
                            ) {
                                Icon(
                                    Icons.Filled.FlipCameraAndroid,
                                    "Lật Camera",
                                    tint = if (uiState.hasFrontCamera && uiState.hasBackCamera) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                            }
                        }
                    }

                    // Resolution overlay when idle
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !uiState.isStreaming,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        enter = fadeIn(), exit = fadeOut()
                    ) {
                        Text(
                            text = "${uiState.selectedResolution.label} | ${uiState.fps} FPS",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier
                                .background(OverlayDark, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // ─── Control Panel (35%) ─────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Resolution & FPS Picker
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Chất lượng video",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Resolution.entries.forEach { res ->
                                val selected = uiState.selectedResolution == res
                                FilterChip(
                                    selected = selected,
                                    enabled = !uiState.isStreaming,
                                    onClick = { viewModel.selectResolution(res) },
                                    label = {
                                        Text(
                                            text = res.label,
                                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                            fontSize = 11.sp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = !uiState.isStreaming,
                                        selected = selected,
                                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                                        borderWidth = 1.dp,
                                        selectedBorderWidth = 1.5.dp
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tốc độ khung hình (FPS)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(15, 24, 30).forEach { fpsValue ->
                                val selected = uiState.fps == fpsValue
                                FilterChip(
                                    selected = selected,
                                    enabled = !uiState.isStreaming,
                                    onClick = { viewModel.selectFps(fpsValue) },
                                    label = {
                                        Text(
                                            text = "${fpsValue} FPS",
                                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                            fontSize = 11.sp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = !uiState.isStreaming,
                                        selected = selected,
                                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                                        borderWidth = 1.dp,
                                        selectedBorderWidth = 1.5.dp
                                    )
                                )
                            }
                        }
                    }

                    // RTSP URL
                    RtspUrlCard(
                        url = uiState.rtspUrl,
                        onCopy = { clipboard.setText(AnnotatedString(uiState.rtspUrl)) }
                    )

                    // Connected Viewers
                    ViewersCard(viewers = uiState.connectedViewers)

                    // Start/Stop button
                    Button(
                        onClick = {
                            if (uiState.isStreaming) viewModel.stopStream()
                            else viewModel.startStream()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            contentColor = if (uiState.isStreaming) Color.White else MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (uiState.isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.isStreaming) "DỪNG PHÁT" else "BẮT ĐẦU PHÁT",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Screen Saver battery overlay
        AnimatedVisibility(
            visible = isDimmed,
            enter = fadeIn(animationSpec = tween(800)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Chạm để sáng màn hình",
                    color = Color.White.copy(alpha = 0.2f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ViewersCard(viewers: List<String>) {
    val isEmpty = viewers.isEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isEmpty) Icons.Outlined.Visibility else Icons.Filled.Visibility,
            contentDescription = null,
            tint = if (isEmpty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = if (isEmpty) "Chưa có ai xem" else "Đang xem: ${viewers.size} thiết bị",
                style = MaterialTheme.typography.labelSmall,
                color = if (isEmpty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            if (viewers.isNotEmpty()) {
                Text(
                    text = viewers.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun LiveBadge() {
    val infiniteTransition = rememberInfiniteTransition(label = "live_blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )
    Row(
        modifier = Modifier
            .background(OverlayDark, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).background(RedError.copy(alpha = alpha), RoundedCornerShape(50)))
        Spacer(modifier = Modifier.width(6.dp))
        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RtspUrlCard(url: String, onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Link, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("URL để kết nối", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
        }
        IconButton(onClick = { onCopy(); copied = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                contentDescription = "Sao chép URL",
                tint = if (copied) GreenOnline else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
