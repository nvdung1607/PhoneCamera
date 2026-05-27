# 🔍 Code Trace — User Journeys

## Journey 1: Điện thoại A trở thành Camera (Streamer)

### Full Call Trace

```
1. User mở app
   └── Android OS → MainActivity.onCreate()
       ├── window.addFlags(FLAG_KEEP_SCREEN_ON)   [line 24]
       ├── enableEdgeToEdge()                      [line 25]
       └── setContent {
           └── NavHost(startDestination = Screen.Home)
               └── composable<Screen.Home> → HomeScreen()

2. HomeScreen hiển thị
   └── HomeScreen.kt (Composable)
       ├── viewModel: HomeViewModel = viewModel()
       ├── uiState = HomeViewModel.uiState.collectAsStateWithLifecycle()
       │   └── HomeUiState(cameraPermissionGranted=false, audioPermissionGranted=false)
       └── [UI renders: 2 buttons + permission status]

3. User tap "Dùng làm Camera"
   └── HomeScreen → onNavigateToStreamer()
       └── navController.navigate(Screen.Streamer)
           └── composable<Screen.Streamer> → StreamerScreen()

4. StreamerScreen khởi tạo
   └── StreamerScreen.kt
       ├── viewModel: StreamerViewModel = viewModel()   [line 42]
       │
       ├── StreamerViewModel.init() {                   [line 69-84]
       │   ├── loadLocalIp()                            [line 70]
       │   │   └── NetworkInterface.getNetworkInterfaces() → find IPv4
       │   │   └── _uiState.update { localIpAddress = "192.168.1.x" }
       │   ├── checkCameraHardware()                    [line 71]
       │   │   └── CameraManager.getCameraCharacteristics()
       │   │   └── _uiState.update { hasFrontCamera=true, hasBackCamera=true }
       │   └── startControlServer()                     [line 72]
       │       └── ControlServer.start(viewModelScope) { cmd -> ... }
       │           └── ServerSocket(8081).accept() [background coroutine on IO]
       │
       ├── DisposableEffect(Unit) {                     [line 51]
       │   ├── requestedOrientation = LANDSCAPE
       │   └── hide system bars
       │
       └── AndroidView { OpenGlView(ctx) }              [line 148-155]
           └── glView.post { viewModel.attachCamera(glView) }

5. attachCamera(glView) được gọi
   └── StreamerViewModel.attachCamera()               [line 172-182]
       ├── glView.setAspectRatioMode(AspectRatioMode.Adjust)
       ├── rtspCamera = RtspServerCamera2(glView, connectChecker, 8080)
       │   └── (thư viện pedroSG94 khởi tạo Camera2 session)
       └── rtspCamera.startPreview(BACK, 640, 360)
           └── Camera2 CaptureSession starts → frames → OpenGlView preview
       └── _uiState.update { isCameraReady = true }

6. User tap "BẮT ĐẦU PHÁT"
   └── StreamerScreen line 367-369
       └── if (!uiState.isStreaming) viewModel.startStream()

7. startStream() execution
   └── StreamerViewModel.startStream()               [line 184-232]
       ├── val cam = rtspCamera ?: return (error)
       ├── cam.stopPreview() [if was on preview]
       │
       ├── Generate PIN:
       │   └── generatedPin = (1000..9999).random().toString()
       │   └── _uiState.update { pinCode = generatedPin }
       │   └── repository.saveStreamerPin(generatedPin)  [DataStore write]
       │
       ├── cam.getStreamClient().setAuthorization("admin", generatedPin)
       │   └── RTSP server will require auth header from clients
       │
       ├── val rotation = CameraHelper.getCameraOrientation(app)
       ├── cam.prepareVideo(640, 360, 30, 500000, rotation)
       │   └── MediaCodec H.264 encoder configured
       ├── cam.prepareAudio()
       │   └── MediaCodec AAC encoder configured
       ├── cam.startPreview(BACK, 640, 360)
       └── cam.startStream()
           └── RtspServerCamera2 begins accepting RTSP connections on :8080

       ├── if cam.isStreaming:
       │   ├── nsdHelper.registerService(8080)
       │   │   └── NsdManager.registerService("_rtspguard._tcp", port=8080)
       │   │   └── mDNS announcement broadcast to LAN
       │   └── _uiState.update { isStreaming = true }

8. UI updates
   └── StreamerScreen recomposes:
       ├── LiveBadge() visible (blinking animation)
       ├── PIN badge visible (if pinCode not empty)
       └── RTSP URL shown: "rtsp://192.168.1.x:8080"
```

---

## Journey 2: Điện thoại B xem Camera (Viewer)

### Full Call Trace

```
1. User mở app → chọn "Xem Camera"
   └── navController.navigate(Screen.Viewer)
       └── ViewerScreen()

2. ViewerViewModel.init()                             [line 67-95]
   ├── collect camerasFlow from DataStore
   │   └── CameraRepository.camerasFlow
   │       └── dataStore.data.map { decode JSON }
   │       └── [returns saved cameras from previous sessions]
   │
   └── startDiscovery()                              [line 164-238]
       ├── _uiState.update { isScanning = true }
       └── nsdHelper.discoverServices(onFound, onLost)
           └── NsdManager.discoverServices("_rtspguard._tcp", DNS_SD, listener)

3. NSD discovers Streamer phone
   └── NsdHelper.discoverServices onFound callback  [line 97-99]
       └── resolveService(serviceInfo, onFound)     [line 124-141]
           └── nsdManager.resolveService(serviceInfo, ResolveListener)
               └── onServiceResolved(info):
                   ├── host = info.host.hostAddress  → "192.168.1.5"
                   ├── camera = DiscoveredCamera(
                   │     serviceId = "Pixel-7",
                   │     displayName = "Pixel 7",
                   │     host = "192.168.1.5",
                   │     port = 8080
                   │   )
                   └── onFound(camera) → ViewerViewModel callback

4. ViewerViewModel processes onFound                [line 173-211]
   ├── Check if any saved slot matches this camera name → auto-reconnect
   └── _uiState.update {
       ├── discoveredCameras += camera
       └── discoveryBadgeCount = count of unoccupied cameras
       }

5. User taps discovered camera in Discovery panel
   └── ViewerScreen → viewModel.addDiscoveredCamera(camera, targetSlot=null)
       └── ViewerViewModel.addDiscoveredCamera()   [line 246-263]
           ├── slot = firstEmptySlot (= 0 nếu chưa có camera)
           ├── config = CameraConfig(
           │     id = 0, name = "Pixel 7",
           │     host = "192.168.1.5", port = 8080,
           │     isPhoneCamera = true, pinCode = ""
           │   )
           └── saveCamera(config)

6. saveCamera() với isPhoneCamera = true            [line 272-297]
   ├── controlClient.sayHello(host, deviceName, pinCode)
   │   └── CameraControlClient.sayHello()
   │       └── sendCommand("192.168.1.5", "HELLO Pixel-8 ", timeoutMs=3000)
   │           └── Socket().connect(InetSocketAddress("192.168.1.5", 8081), 3000)
   │           └── write("HELLO Pixel-8 \n")
   │           └── readLine() → "ERROR invalid PIN" or "OK"
   │
   ├── if response contains "invalid PIN": show snackbar error, return
   ├── repository.saveCamera(config)   → DataStore write
   └── preparePlayer(0, config, useTcp=false)

7. preparePlayer(index=0, config, useTcp=false)     [line 97-154]
   ├── prepareJobs[0]?.cancel()   [debounce]
   ├── releasePlayer(0)           [cleanup old player]
   └── viewModelScope.launch {
       ├── delay(500ms)           [debounce wait]
       │
       ├── loadControl = DefaultLoadControl.Builder()
       │   .setBufferDurationsMs(2500, 10000, 1000, 1500)
       │   .build()
       │
       ├── player = ExoPlayer.Builder(context)
       │   .setLoadControl(loadControl).build()
       │
       ├── source = RtspMediaSource.Factory()
       │   .setForceUseRtpTcp(false)      [UDP mode]
       │   .setTimeoutMs(30_000)
       │   .createMediaSource(MediaItem.fromUri("rtsp://admin:1234@192.168.1.5:8080"))
       │
       ├── player.setMediaSource(source)
       ├── player.addListener(...)         [STATE_READY, BUFFERING, error callbacks]
       ├── player.prepare()               [trigger RTSP DESCRIBE request]
       └── player.playWhenReady = true

8. ExoPlayer negotiates RTSP session
   ├── → DESCRIBE rtsp://192.168.1.5:8080 RTSP/1.0
   ├── ← 200 OK (SDP with H.264 + AAC)
   ├── → SETUP rtsp://... RTSP/1.0 (port negotiation)
   ├── ← 200 OK (session ID)
   └── → PLAY rtsp://... RTSP/1.0
       └── Streamer begins sending RTP packets

9. onPlaybackStateChanged(STATE_READY)
   └── ViewerViewModel.onPlayerReady(index=0)       [line 322-326]
       ├── retryCounts[0] = 0
       └── setPlayerState(0, PlayerState.Playing)
           └── (isPhoneCamera == true) → controlClient.sayHello(cam.host, deviceName, pinCode)
               └── Streamer's connectedViewers += "Pixel-8"

10. UI renders video
    └── ViewerScreen slot 0:
        ├── PlayerView(player) → video frames displayed
        └── PlayerState.Playing → no loading indicator
```

---

## Hàm quan trọng nhất — Cross-reference

| Hàm | File | Được gọi bởi | Gọi đến |
|-----|------|-------------|---------|
| `attachCamera()` | StreamerViewModel | StreamerScreen (AndroidView.post) | RtspServerCamera2, _uiState |
| `startStream()` | StreamerViewModel | StreamerScreen (button click) | prepareVideo, prepareAudio, startStream, NsdHelper, DataStore |
| `preparePlayer()` | ViewerViewModel | saveCamera, retryCamera, toggleTcp, startDiscovery | ExoPlayer, releasePlayer |
| `resolveService()` | NsdHelper | discoverServices (NsdManager callback) | NsdManager.resolveService, DiscoveredCamera |
| `sendCommand()` | CameraControlClient | sayHello, sayBye, setQuality, setFps | Socket, Dispatchers.IO |
| `parseCommand()` | ControlServer | start() (ServerSocket accept loop) | Command sealed class |
| `setPlayerState()` | ViewerViewModel | onPlayerReady, onPlayerError, preparePlayer | CameraControlClient (HELLO/BYE), _uiState |
