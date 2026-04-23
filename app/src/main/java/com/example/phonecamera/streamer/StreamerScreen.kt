package com.example.phonecamera.streamer

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phonecamera.ui.theme.*
import com.pedro.library.view.OpenGlView

@Composable
fun StreamerScreen(
    onBack: () -> Unit,
    viewModel: StreamerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val view = LocalView.current

    // Chuyển sang landscape và ẩn system bar khi vào màn hình
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE

        val window = activity?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        onDispose {
            viewModel.releaseCamera()
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
                w.attributes = w.attributes.also { it.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }

    // Dừng stream khi app bị đưa xuống nền
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.stopStream()
    }

    // Auto-dim màn hình sau 30 giây không tương tác
    var isDimmed by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lastInteractionTime) {
        kotlinx.coroutines.delay(30_000L)
        isDimmed = true
    }

    // Điều chỉnh độ sáng thực tế của màn hình
    DisposableEffect(isDimmed) {
        val window = (context as? android.app.Activity)?.window
        window?.attributes = window?.attributes?.also {
            it.screenBrightness = if (isDimmed) 0.01f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        onDispose {}
    }

    // Hiện snackbar khi có lỗi
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
                                glView.post { viewModel.attachCamera(glView) }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Nút điều khiển phía trên
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
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

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = { isDimmed = true },
                                modifier = Modifier.background(OverlayDark, RoundedCornerShape(50))
                            ) {
                                Icon(Icons.Filled.VisibilityOff, "Tắt màn hình", tint = TextSecondary)
                            }
                            IconButton(
                                onClick = { viewModel.switchCamera() },
                                modifier = Modifier.background(OverlayDark, RoundedCornerShape(50))
                            ) {
                                Icon(Icons.Filled.FlipCameraAndroid, "Lật Camera", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // Badge LIVE
                    androidx.compose.animation.AnimatedVisibility(
                        visible = uiState.isStreaming,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        enter = fadeIn(), exit = fadeOut()
                    ) { LiveBadge() }

                    // Hiện độ phân giải đang chọn khi chưa phát
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !uiState.isStreaming,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        enter = fadeIn(), exit = fadeOut()
                    ) {
                        Text(
                            text = uiState.selectedResolution.label,
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
                    // Chọn độ phân giải
                    Column {
                        Text(
                            text = "Chất lượng video",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
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
                                            fontSize = 12.sp,
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

                    // Danh sách máy đang xem
                    ViewersCard(viewers = uiState.connectedViewers)

                    // Nút Bắt đầu / Dừng phát
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

        // Overlay đen tiết kiệm pin
        androidx.compose.animation.AnimatedVisibility(
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
