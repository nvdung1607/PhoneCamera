# 🔄 Sequence Diagrams — PhoneCamera

## Diagram 1: App Khởi Động (App Launch)

```mermaid
sequenceDiagram
    actor User
    participant OS as Android OS
    participant MA as MainActivity
    participant NC as NavController
    participant HS as HomeScreen
    participant HVM as HomeViewModel
    participant ACC as AccompanistPermissions

    User->>OS: Tap app icon
    OS->>MA: onCreate(savedInstanceState)
    activate MA

    MA->>MA: window.addFlags(FLAG_KEEP_SCREEN_ON)
    Note over MA: Ngăn màn hình tắt tự động

    MA->>MA: enableEdgeToEdge()
    Note over MA: Cho phép UI vẽ đến tận cạnh màn hình

    MA->>MA: setContent { PhoneCameraTheme }
    MA->>NC: rememberNavController()

    MA->>NC: NavHost(startDestination = Screen.Home)
    NC->>HS: composable<Screen.Home> → render HomeScreen

    activate HS
    HS->>HVM: viewModel() ← inject/create
    activate HVM
    HVM-->>HS: uiState = HomeUiState(false, false)
    deactivate HVM

    HS->>ACC: rememberMultiplePermissionsState([CAMERA, RECORD_AUDIO])
    ACC->>OS: Check current permission status
    OS-->>ACC: Not granted

    HS-->>User: Hiển thị màn hình Home với nút request permissions
    deactivate HS
    deactivate MA
```

---

## Diagram 2: Streamer — Bắt Đầu Phát Camera

```mermaid
sequenceDiagram
    actor User
    participant SS as StreamerScreen
    participant SVM as StreamerViewModel
    participant NSD as NsdHelper
    participant RTSP as RtspServerCamera2
    participant CAM as Camera2 Hardware
    participant CS as ControlServer

    User->>SS: Navigate to StreamerScreen
    activate SS

    SS->>SS: DisposableEffect(Unit) — request landscape + hide system bars
    SS->>SVM: viewModel() created
    activate SVM

    SVM->>SVM: init { loadLocalIp() }
    SVM->>SVM: checkCameraHardware() → hasFront=true, hasBack=true
    SVM->>CS: startControlServer() → listen port 8081

    SS->>SS: AndroidView { OpenGlView(ctx) }
    SS->>SVM: glView.post { attachCamera(glView) }
    activate SVM

    SVM->>RTSP: RtspServerCamera2(glView, connectChecker, 8080)
    activate RTSP
    RTSP->>CAM: startPreview(BACK, 640, 360)
    CAM-->>RTSP: Preview frames started
    RTSP-->>SVM: camera ready
    deactivate RTSP

    SVM->>SVM: _uiState.update { isCameraReady = true }
    SVM-->>SS: uiState.isCameraReady = true

    SS-->>User: Show camera preview

    User->>SS: Tap "BẮT ĐẦU PHÁT"
    SS->>SVM: startStream()

    SVM->>SVM: generate PIN (random 4 digits)
    SVM->>SVM: cam.prepareVideo(width, height, fps, bitrate, rotation)
    SVM->>SVM: cam.prepareAudio()
    SVM->>RTSP: startStream()
    activate RTSP
    RTSP->>CAM: start encoding frames
    RTSP-->>SVM: isStreaming = true
    deactivate RTSP

    SVM->>NSD: registerService(port=8080)
    activate NSD
    NSD->>NSD: NsdManager.registerService("_rtspguard._tcp", 8080)
    Note over NSD: Broadcast mDNS announcement to LAN
    deactivate NSD

    SVM->>SVM: _uiState.update { isStreaming = true }
    SVM-->>SS: uiState.isStreaming = true

    SS-->>User: Show LIVE badge + RTSP URL

    deactivate SVM
    deactivate SS
```

---

## Diagram 3: Viewer — Tìm và Kết Nối Camera

```mermaid
sequenceDiagram
    actor User
    participant VS as ViewerScreen
    participant VVM as ViewerViewModel
    participant NSD as NsdHelper
    participant OS as Android NSD Service
    participant CC as CameraControlClient
    participant EXO as ExoPlayer
    participant STREAMER as Streamer Phone

    User->>VS: Navigate to ViewerScreen
    activate VS
    VS->>VVM: viewModel() created
    activate VVM

    VVM->>VVM: init { collect camerasFlow from DataStore }
    Note over VVM: Load saved cameras from disk

    VVM->>NSD: startDiscovery()
    activate NSD
    NSD->>OS: discoverServices("_rtspguard._tcp", DNS_SD)
    OS-->>NSD: onDiscoveryStarted
    deactivate NSD

    VVM->>VVM: _uiState.update { isScanning = true }
    VS-->>User: Show "Đang tìm camera..." badge

    Note over OS,STREAMER: Streamer phone đang broadcast mDNS "_rtspguard._tcp"

    OS->>NSD: onServiceFound(NsdServiceInfo)
    NSD->>OS: resolveService(serviceInfo)
    OS-->>NSD: onServiceResolved(host="192.168.1.x", port=8080)
    NSD->>VVM: onFound(DiscoveredCamera("Pixel-7", "192.168.1.5", 8080))

    VVM->>VVM: _uiState.update { discoveredCameras += camera, badge++ }
    VS-->>User: Show discovery badge (1 camera found)

    User->>VS: Tap discovered camera "Pixel 7"
    VS->>VVM: addDiscoveredCamera(camera, slot=0)

    VVM->>CC: sayHello(host, deviceName, pinCode)
    activate CC
    CC->>STREAMER: TCP connect port 8081
    CC->>STREAMER: Send "HELLO Pixel-8 1234\n"
    STREAMER-->>CC: "OK\n"
    CC-->>VVM: "OK"
    deactivate CC

    VVM->>VVM: repository.saveCamera(config)
    VVM->>EXO: preparePlayer(index=0, config, useTcp=false)
    activate EXO
    EXO->>EXO: ExoPlayer.Builder().setLoadControl(bufferConfig).build()
    EXO->>EXO: RtspMediaSource.Factory().createMediaSource("rtsp://192.168.1.5:8080")
    EXO->>EXO: setMediaSource(source)
    EXO->>EXO: prepare()
    EXO->>STREAMER: RTSP DESCRIBE request (port 8080)
    STREAMER-->>EXO: RTSP session setup
    EXO-->>VVM: onPlaybackStateChanged(STATE_READY)
    deactivate EXO

    VVM->>VVM: setPlayerState(0, PlayerState.Playing)
    VVM->>CC: sayHello (notify streamer we're Playing)

    VS-->>User: Video stream displayed in slot 0
    deactivate VVM
    deactivate VS
```

---

## Diagram 4: Remote Control — Viewer Đổi Chất Lượng Stream

```mermaid
sequenceDiagram
    actor User
    participant VS as ViewerScreen
    participant VVM as ViewerViewModel
    participant CC as CameraControlClient
    participant SVM as StreamerViewModel
    participant CS as ControlServer
    participant RTSP as RtspServerCamera2

    User->>VS: Select "720p" for slot 0
    VS->>VVM: setRemoteQuality(slotIndex=0, heightP=720)

    VVM->>VVM: releasePlayer(0) ← giải phóng ExoPlayer ngay
    VVM->>VVM: setPlayerState(0, PlayerState.Loading)

    VVM->>CC: setQuality(host="192.168.1.5", heightP=720, pinCode="1234")
    activate CC
    CC->>CS: TCP connect port 8081
    CC->>CS: Send "SET_QUALITY 720 1234\n"
    activate CS

    CS->>CS: parseCommand("SET_QUALITY 720 1234")
    CS->>CS: validate PIN "1234" == "1234" ✓
    CS->>SVM: onCommand(SetQuality(heightP=720))
    activate SVM

    SVM->>SVM: changeQualityRemote(Resolution.P720)
    SVM->>RTSP: stopStream()
    SVM->>SVM: delay(500ms)
    SVM->>SVM: startStream() with new resolution 720p

    CS-->>CC: "OK\n"
    deactivate CS
    deactivate SVM

    CC-->>VVM: "OK"
    deactivate CC

    VVM->>VVM: delay(2000ms) ← đợi Streamer restart xong
    VVM->>VVM: preparePlayer(0, cam, useTcp=false)

    VS-->>User: Video reconnects at 720p quality
```

---

## Diagram 5: Disconnect và Cleanup

```mermaid
sequenceDiagram
    actor User
    participant SS as StreamerScreen
    participant SVM as StreamerViewModel
    participant RTSP as RtspServerCamera2
    participant NSD as NsdHelper
    participant CS as ControlServer

    User->>SS: Tap back button (while streaming)
    SS->>SVM: stopStream()
    SVM->>NSD: unregisterService()
    Note over NSD: mDNS "service lost" broadcast → Viewer phones notified

    SVM->>RTSP: stopStream()
    SVM->>SVM: _uiState.update { isStreaming=false, viewers=[] }

    SS->>SS: onDispose { viewModel.releaseCamera() }
    SS->>SVM: releaseCamera()
    SVM->>NSD: stopAll()
    SVM->>RTSP: stopPreview()
    SVM->>SVM: rtspCamera = null

    SVM->>SVM: onCleared { controlServer.stop() }
    SVM->>CS: stop() → ServerSocket.close()
```
