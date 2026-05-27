# 🔁 State Diagrams — PhoneCamera

## State Diagram 1: StreamerViewModel UI State

```mermaid
stateDiagram-v2
    [*] --> CameraInitializing : onCreate / attachCamera()

    CameraInitializing --> CameraReady : RtspServerCamera2 initialized\nstartPreview() OK
    CameraInitializing --> Error : Camera hardware not found

    CameraReady --> Streaming : startStream() called\nprepareVideo() + prepareAudio() OK\nRtspServerCamera2.startStream() OK

    CameraReady --> Error : prepareVideo() fails\nor prepareAudio() fails\nor cam.isStreaming == false

    Streaming --> CameraReady : stopStream() called\nor ON_PAUSE lifecycle event

    Streaming --> Streaming : switchCamera()\nchangeQualityRemote()\nchangeFpsRemote()\n[stream briefly stops & restarts]

    Streaming --> Streaming : Viewer connects\n→ connectedViewers += name
    Streaming --> Streaming : Viewer disconnects\n→ connectedViewers -= name

    CameraReady --> Released : releaseCamera() / onCleared()
    Streaming --> Released : releaseCamera() / onCleared()
    Error --> Released : releaseCamera() / onCleared()

    Released --> [*]

    note right of Streaming
        isStreaming = true
        NSD service registered
        ControlServer listening port 8081
        RTSP server serving port 8080
    end note

    note right of CameraReady
        isCameraReady = true
        isStreaming = false
        Camera preview active
    end note
```

---

## State Diagram 2: ViewerViewModel — Player State (per slot)

```mermaid
stateDiagram-v2
    [*] --> Idle : Slot empty (no camera configured)

    Idle --> Loading : saveCamera() called\nor auto-load from DataStore

    Loading --> Playing : ExoPlayer STATE_READY\n(RTSP connection established)
    Loading --> Error : ExoPlayer error\nor timeout (>30s)

    Playing --> Loading : STATE_BUFFERING\nor network hiccup

    Playing --> Error : STATE_ENDED\nor PlaybackException\nor Camera went offline (NSD onLost)

    Error --> Loading : retryCamera() called\n(auto-retry, max 5 times, delay 3s)
    Error --> Loading : User manually taps retry

    Playing --> Idle : deleteCamera(id) called
    Loading --> Idle : deleteCamera(id) called
    Error --> Idle : deleteCamera(id) called

    Loading --> Loading : toggleTcp()\n[forced reconnect]
    Playing --> Loading : toggleTcp()\n[forced reconnect]
    Playing --> Loading : setRemoteQuality()\nor setRemoteFps()\n[wait 2s for Streamer restart]

    note right of Playing
        ExoPlayer.playWhenReady = true
        RTSP/RTP frames being decoded
        Video rendered on Surface
        HELLO sent to Streamer
    end note

    note right of Error
        Max 5 auto-retries with 3s delay
        Error message shown in UI
        Retry button visible
    end note
```

---

## State Diagram 3: NSD Discovery State (Viewer side)

```mermaid
stateDiagram-v2
    [*] --> Idle : ViewerViewModel created

    Idle --> Scanning : startDiscovery() called\nNsdManager.discoverServices()

    Scanning --> Scanning : onServiceFound\n→ resolveService()\n→ onFound callback\n→ discoveredCameras list updated

    Scanning --> Scanning : onServiceLost\n→ camera removed from list\n→ slot marked as Error

    Scanning --> Idle : stopDiscovery() called\nNsdManager.stopServiceDiscovery()

    Scanning --> Error_NSD : onStartDiscoveryFailed\n(network unavailable)

    Error_NSD --> Idle : Manual retry

    note right of Scanning
        isScanning = true
        discoveredCameras: mutable list
        discoveryBadgeCount: unviewed count
    end note
```

---

## State Diagram 4: Screen Navigation State

```mermaid
stateDiagram-v2
    [*] --> HomeScreen : App start\nNavHost(startDestination = Screen.Home)

    HomeScreen --> PermissionRequest : Tap "Dùng làm Camera"\npermissions not granted

    PermissionRequest --> HomeScreen : Permissions denied\nor partial grant

    PermissionRequest --> StreamerScreen : CAMERA + RECORD_AUDIO\nboth granted

    HomeScreen --> StreamerScreen : Tap "Dùng làm Camera"\n(all permissions already granted)

    HomeScreen --> ViewerScreen : Tap "Xem Camera"

    StreamerScreen --> HomeScreen : Back button\n(popBackStack)

    ViewerScreen --> HomeScreen : Back button\n(popBackStack)

    StreamerScreen --> StreamerScreen : Orientation locked to LANDSCAPE\nSystem bars hidden

    note right of StreamerScreen
        requestedOrientation = LANDSCAPE
        Hide system bars
        Keep screen ON
        Auto-dim after 30s idle
    end note

    note right of ViewerScreen
        Normal orientation
        System bars visible
        Multiple camera slots
    end note
```

---

## State Diagram 5: ControlServer Command Handling

```mermaid
stateDiagram-v2
    [*] --> Listening : start() called\nServerSocket(8081).accept()

    Listening --> ProcessingClient : Client connects (TCP)

    ProcessingClient --> Authenticating : Read line from client

    Authenticating --> AuthFailed : PIN mismatch\n(pinCode not empty AND cmd.pin != expected)
    Authenticating --> Dispatch : PIN valid\nor no PIN set

    AuthFailed --> ResponseSent : Send "ERROR invalid PIN"
    ResponseSent --> Listening : Close client socket

    Dispatch --> HandleHello : Command = "HELLO name pin"
    Dispatch --> HandleBye : Command = "BYE name pin"
    Dispatch --> HandleSetQuality : Command = "SET_QUALITY 720 pin"
    Dispatch --> HandleSetFps : Command = "SET_FPS 30 pin"
    Dispatch --> UnknownCommand : Command not recognized

    HandleHello --> ResponseSent : connectedViewers += name\nSend "OK"
    HandleBye --> ResponseSent : connectedViewers -= name\nSend "OK"
    HandleSetQuality --> ResponseSent : changeQualityRemote()\nSend "OK"
    HandleSetFps --> ResponseSent : changeFpsRemote()\nSend "OK"
    UnknownCommand --> ResponseSent : Send "ERROR unknown command"

    ResponseSent --> Listening : Ready for next client

    Listening --> [*] : stop() called\nServerSocket.close()
```
