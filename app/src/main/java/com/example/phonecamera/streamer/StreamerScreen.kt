package com.example.phonecamera.streamer

import android.view.SurfaceHolder
import com.pedro.library.view.OpenGlView
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phonecamera.ui.theme.*

@Composable
fun StreamerScreen(
    onBack: () -> Unit,
    viewModel: StreamerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Force Landscape orientation & Immersive Mode for Streamer
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        
        // Hide System UI
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val windowDispose = activity?.window
            if (windowDispose != null) {
                WindowCompat.getInsetsController(windowDispose, view).show(WindowInsetsCompat.Type.systemBars())
                val lp = windowDispose.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                windowDispose.attributes = lp
            }
        }
    }

    var isDimmed by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Auto-dim after 30 seconds of inactivity
    LaunchedEffect(lastInteractionTime) {
        kotlinx.coroutines.delay(30_000L)
        isDimmed = true
    }

    // Modify physical window brightness when dimmed
    DisposableEffect(isDimmed) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val lp = window.attributes
            if (isDimmed) {
                lp.screenBrightness = 0.01f // Minimum brightness
            } else {
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // Restore
            }
            window.attributes = lp
        }
        onDispose {}
    }

    // Show error snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Long)
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
                            if (isDimmed) isDimmed = false
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
                    containerColor = RedErrorSurface,
                    contentColor = RedError,
                    dismissAction = {
                        TextButton(onClick = { data.dismiss() }) {
                            Text("Đóng", color = RedError)
                        }
                    }
                ) {
                    Text(data.visuals.message)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ─── Main Content (Landscape Row) ───
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // ─── Camera Preview (65% width) ───
                Box(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight()
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            OpenGlView(ctx).also { glView ->
                                // post{} ensures the view is fully laid out before attaching
                                glView.post { viewModel.attachCameraToService(glView) }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay Controls (Top Area)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Back Button
                        IconButton(
                            onClick = {
                                if (uiState.isStreaming) viewModel.stopStream()
                                onBack()
                            },
                            modifier = Modifier
                                .background(OverlayDark, RoundedCornerShape(50))
                        ) {
                            Icon(Icons.Filled.ArrowBack, "Quay lại", tint = Color.White)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Dim Screen Button
                            IconButton(
                                onClick = { isDimmed = true },
                                modifier = Modifier
                                    .background(OverlayDark, RoundedCornerShape(50))
                            ) {
                                Icon(Icons.Filled.VisibilityOff, "Tắt màn hình", tint = TextSecondary)
                            }

                            // Flip Camera Button
                            IconButton(
                                onClick = { viewModel.switchCamera() },
                                modifier = Modifier
                                    .background(OverlayDark, RoundedCornerShape(50))
                            ) {
                                Icon(Icons.Filled.FlipCameraAndroid, "Lật Camera", tint = CyanNeon)
                            }
                        }
                    }

                    // Live indicator
                    this@Row.AnimatedVisibility(
                        visible = uiState.isStreaming,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        LiveBadge()
                    }

                    // Resolution overlay when not streaming
                    this@Row.AnimatedVisibility(
                        visible = !uiState.isStreaming,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        enter = fadeIn(),
                        exit = fadeOut()
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

                // ─── Control Panel (35% width) ───
                Column(
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight()
                        .background(NavyMid)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Resolution chips (disabled while streaming)
                    Column {
                        Text(
                            text = "Chất lượng",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextHint
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Resolution.entries.forEach { resolution ->
                                val selected = uiState.selectedResolution == resolution
                                FilterChip(
                                    selected = selected,
                                    enabled = !uiState.isStreaming,
                                    onClick = { viewModel.selectResolution(resolution) },
                                    label = {
                                        Text(
                                            text = resolution.label,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 11.sp
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanNeon.copy(alpha = 0.2f),
                                        selectedLabelColor = CyanNeon,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = !uiState.isStreaming,
                                        selected = selected,
                                        selectedBorderColor = CyanNeon.copy(alpha = 0.5f),
                                        borderColor = DividerColor,
                                        borderWidth = 1.dp,
                                        selectedBorderWidth = 1.dp
                                    )
                                )
                            }
                        }
                    }

                    // RTSP URL display
                    RtspUrlCard(
                        url = uiState.rtspUrl,
                        onCopy = { clipboard.setText(AnnotatedString(uiState.rtspUrl)) }
                    )

                    // Start / Stop button
                    Button(
                        onClick = {
                            if (uiState.isStreaming) viewModel.stopStream()
                            else viewModel.startStream()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isStreaming) RedError else CyanNeon,
                            contentColor = if (uiState.isStreaming) Color.White else NavyDeep
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
        } // End of Scaffold content padding

        // ─── Black Overlay for Battery Saving ───
        AnimatedVisibility(
            visible = isDimmed,
            enter = fadeIn(animationSpec = tween(800)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Chạm để sáng màn hình",
                    color = Color.White.copy(alpha = 0.2f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    } // End of outer Box
} // End of StreamerScreen

// StreamerTopBar removed to save vertical space.

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
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(RedError.copy(alpha = alpha), RoundedCornerShape(50))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RtspUrlCard(url: String, onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NavyCard)
            .border(1.dp, DividerColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Link,
            contentDescription = null,
            tint = CyanNeon,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("URL để kết nối", style = MaterialTheme.typography.labelSmall, color = TextHint)
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                color = CyanNeon,
                fontWeight = FontWeight.Medium
            )
        }
        IconButton(
            onClick = {
                onCopy()
                copied = true
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                contentDescription = "Sao chép URL",
                tint = if (copied) GreenOnline else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
