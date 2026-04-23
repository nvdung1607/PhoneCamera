package com.example.phonecamera.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phonecamera.ui.theme.*
import com.example.phonecamera.viewer.components.AddEditCameraDialog
import com.example.phonecamera.viewer.components.CameraCell
import com.example.phonecamera.viewer.components.DiscoveryBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val view = LocalView.current

    // Toggle Immersive Mode (hide status bar / nav bar) when landscape
    DisposableEffect(isLandscape) {
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            if (isLandscape) {
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val windowDispose = activity?.window
            if (windowDispose != null) {
                WindowCompat.getInsetsController(windowDispose, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // null = closed, -1 = new, 0..3 = edit slot
    var dialogSlot by remember { mutableStateOf<Int?>(null) }
    var showDiscovery by remember { mutableStateOf(false) }
    var fullscreenSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    var wasInGridBeforeFullscreen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(fullscreenSlot) {
        if (fullscreenSlot != null) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            activity?.requestedOrientation = if (wasInGridBeforeFullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            }
        }
    }

    BackHandler(enabled = fullscreenSlot != null) {
        fullscreenSlot = null
    }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.dismissSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isLandscape) {
                TopAppBar(
                    title = { 
                        Text(
                            text = "Xem Camera",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        ) 
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, "Quay lại", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    actions = {
                        // ─── TCP/UDP Toggle Button ───
                        FilledTonalIconToggleButton(
                            checked = uiState.useTcp,
                            onCheckedChange = { viewModel.toggleTcp() },
                            modifier = Modifier.padding(end = 4.dp),
                            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                checkedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                checkedContentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = if (uiState.useTcp) "TCP" else "UDP",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = if (uiState.useTcp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // ─── Scan Button with badge ───
                        BadgedBox(
                            badge = {
                                if (uiState.discoveryBadgeCount > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) {
                                        Text(uiState.discoveryBadgeCount.toString(), fontSize = 10.sp)
                                    }
                                }
                            },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            IconButton(onClick = { showDiscovery = true; viewModel.clearDiscoveryBadge() }) {
                                Icon(
                                    Icons.Outlined.WifiFind, "Tìm camera tự động",
                                    tint = if (uiState.discoveryBadgeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // ─── Fullscreen Landscape Button ───
                        IconButton(onClick = {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                        }) {
                            Icon(Icons.Filled.Fullscreen, "Chế độ lưới ngang", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        },
        // In landscape, we might want a floating exit button
        floatingActionButton = {
            if (isLandscape) {
                FloatingActionButton(
                    onClick = { 
                        if (fullscreenSlot != null) fullscreenSlot = null
                        else activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT 
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Filled.FullscreenExit, "Thoát toàn màn hình")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)

        if (fullscreenSlot != null) {
            val i = fullscreenSlot!!
            CameraCell(
                slotIndex = i,
                config = uiState.cameras.getOrNull(i),
                playerState = uiState.playerStateFor(i),
                useTcp = uiState.useTcp,
                isAudioEnabled = uiState.selectedAudioSlot == i,
                onToggleAudio = { viewModel.toggleAudio(i) },
                onFullscreenClick = { fullscreenSlot = null },
                isFullscreen = true,
                onAddClick = { dialogSlot = i },
                onEditClick = { dialogSlot = i },
                onRetryClick = { viewModel.retryCamera(i) },
                onPlayerReady = { viewModel.onPlayerReady(i) },
                onPlayerError = { err -> viewModel.onPlayerError(i, err) },
                modifier = modifier
            )
        } else if (isLandscape) {
            // ─── LANDSCAPE MODE (Non-scrollable 2x2 Grid Centered) ───
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black), // Background fill for empty spaces
                contentAlignment = Alignment.Center
            ) {
                // This column maintains a perfect 16:9 ratio overall and scales to fit the screen
                Column(
                    modifier = Modifier.aspectRatio(16f / 9f)
                ) {
                    // Top Row (Cameras 0 and 1)
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        CameraCell(
                            slotIndex = 0,
                            config = uiState.cameras.getOrNull(0),
                            playerState = uiState.playerStateFor(0),
                            useTcp = uiState.useTcp,
                            isAudioEnabled = uiState.selectedAudioSlot == 0,
                            onToggleAudio = { viewModel.toggleAudio(0) },
                            onFullscreenClick = { 
                                wasInGridBeforeFullscreen = isLandscape
                                fullscreenSlot = 0 
                            },
                            isFullscreen = false,
                            onAddClick = { dialogSlot = 0 },
                            onEditClick = { dialogSlot = 0 },
                            onRetryClick = { viewModel.retryCamera(0) },
                            onPlayerReady = { viewModel.onPlayerReady(0) },
                            onPlayerError = { err -> viewModel.onPlayerError(0, err) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(1.dp)
                        )
                        CameraCell(
                            slotIndex = 1,
                            config = uiState.cameras.getOrNull(1),
                            playerState = uiState.playerStateFor(1),
                            useTcp = uiState.useTcp,
                            isAudioEnabled = uiState.selectedAudioSlot == 1,
                            onToggleAudio = { viewModel.toggleAudio(1) },
                            onFullscreenClick = { 
                                wasInGridBeforeFullscreen = isLandscape
                                fullscreenSlot = 1 
                            },
                            isFullscreen = false,
                            onAddClick = { dialogSlot = 1 },
                            onEditClick = { dialogSlot = 1 },
                            onRetryClick = { viewModel.retryCamera(1) },
                            onPlayerReady = { viewModel.onPlayerReady(1) },
                            onPlayerError = { err -> viewModel.onPlayerError(1, err) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(1.dp)
                        )
                    }
                    // Bottom Row (Cameras 2 and 3)
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        CameraCell(
                            slotIndex = 2,
                            config = uiState.cameras.getOrNull(2),
                            playerState = uiState.playerStateFor(2),
                            useTcp = uiState.useTcp,
                            isAudioEnabled = uiState.selectedAudioSlot == 2,
                            onToggleAudio = { viewModel.toggleAudio(2) },
                            onFullscreenClick = { 
                                wasInGridBeforeFullscreen = isLandscape
                                fullscreenSlot = 2 
                            },
                            isFullscreen = false,
                            onAddClick = { dialogSlot = 2 },
                            onEditClick = { dialogSlot = 2 },
                            onRetryClick = { viewModel.retryCamera(2) },
                            onPlayerReady = { viewModel.onPlayerReady(2) },
                            onPlayerError = { err -> viewModel.onPlayerError(2, err) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(1.dp)
                        )
                        CameraCell(
                            slotIndex = 3,
                            config = uiState.cameras.getOrNull(3),
                            playerState = uiState.playerStateFor(3),
                            useTcp = uiState.useTcp,
                            isAudioEnabled = uiState.selectedAudioSlot == 3,
                            onToggleAudio = { viewModel.toggleAudio(3) },
                            onFullscreenClick = { 
                                wasInGridBeforeFullscreen = isLandscape
                                fullscreenSlot = 3 
                            },
                            isFullscreen = false,
                            onAddClick = { dialogSlot = 3 },
                            onEditClick = { dialogSlot = 3 },
                            onRetryClick = { viewModel.retryCamera(3) },
                            onPlayerReady = { viewModel.onPlayerReady(3) },
                            onPlayerError = { err -> viewModel.onPlayerError(3, err) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(1.dp)
                        )
                    }
                }
            }
        } else {
            // ─── PORTRAIT MODE (1-Column List) ───
            val activeSlots = uiState.cameras.withIndex().filter { it.value != null }
            val emptySlotIndex = uiState.cameras.indexOfFirst { it == null }

            LazyColumn(
                modifier = modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(activeSlots.size) { i ->
                    val indexedValue = activeSlots[i]
                    val index = indexedValue.index
                    val config = indexedValue.value
                    val state = uiState.playerStateFor(index)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f) // Keep cells 16:9 in list
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    ) {
                        CameraCell(
                            slotIndex = index,
                            config = config,
                            playerState = state,
                            useTcp = uiState.useTcp,
                            isAudioEnabled = uiState.selectedAudioSlot == index,
                            onToggleAudio = { viewModel.toggleAudio(index) },
                            onFullscreenClick = { 
                                wasInGridBeforeFullscreen = isLandscape
                                fullscreenSlot = index 
                            },
                            isFullscreen = false,
                            onAddClick = { dialogSlot = index },
                            onEditClick = { dialogSlot = index },
                            onRetryClick = { viewModel.retryCamera(index) },
                            onPlayerReady = { viewModel.onPlayerReady(index) },
                            onPlayerError = { err -> viewModel.onPlayerError(index, err) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (emptySlotIndex != -1) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        ) {
                            CameraCell(
                                slotIndex = emptySlotIndex,
                                config = null,
                                playerState = PlayerState.Idle,
                                useTcp = uiState.useTcp,
                                isAudioEnabled = false,
                                onToggleAudio = {},
                                onFullscreenClick = {},
                                isFullscreen = false,
                                onAddClick = { dialogSlot = emptySlotIndex },
                                onEditClick = {},
                                onRetryClick = {},
                                onPlayerReady = {},
                                onPlayerError = {},
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    // ─── Add/Edit Camera Dialog ───
    dialogSlot?.let { slot ->
        val existing = uiState.cameras.getOrNull(slot)
        AddEditCameraDialog(
            slotIndex = slot,
            initialConfig = existing,
            onConfirm = { config -> viewModel.saveCamera(config); dialogSlot = null },
            onDelete = if (existing != null) {
                { viewModel.deleteCamera(slot); dialogSlot = null }
            } else null,
            onDismiss = { dialogSlot = null }
        )
    }

    // ─── Discovery Bottom Sheet ───
    if (showDiscovery) {
        DiscoveryBottomSheet(
            isScanning = uiState.isScanning,
            discovered = uiState.discoveredCameras,
            occupiedSlots = uiState.occupiedRtspUrls,
            onAddCamera = { camera ->
                viewModel.addDiscoveredCamera(camera)
                showDiscovery = false
            },
            onDismiss = { showDiscovery = false },
            sheetState = sheetState
        )
    }
}
