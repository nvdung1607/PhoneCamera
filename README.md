# 📷 Phone Camera

> Biến điện thoại Android thành **hệ thống camera an ninh nội bộ** — phát và xem luồng RTSP qua WiFi mà không cần internet.
> Stream chỉ hoạt động khi app đang mở trên màn hình.

---

## Mục lục

1. [Tính năng](#1-tính-năng)
2. [Công nghệ](#2-công-nghệ)
3. [Cấu trúc thư mục](#3-cấu-trúc-thư-mục)
4. [Màn hình](#4-màn-hình)
5. [Kiến trúc MVVM](#5-kiến-trúc-mvvm)
6. [Sơ đồ Use Case](#6-sơ-đồ-use-case)
7. [Sơ đồ Lớp](#7-sơ-đồ-lớp)
8. [Sơ đồ Tuần tự](#8-sơ-đồ-tuần-tự)
9. [Giao thức ControlServer](#9-giao-thức-controlserver-tcp-8081)
10. [Vòng đời tài nguyên](#10-vòng-đời-tài-nguyên)
11. [Quyền hệ thống](#11-quyền-hệ-thống)
12. [Luồng khởi động](#12-luồng-khởi-động)
13. [Ghi chú cho developer](#13-ghi-chú-cho-developer)

---

## 1. Tính năng

| Vai trò | Màn hình | Chức năng |
|---|---|---|
| 📹 **Máy Quay** | Streamer | Phát RTSP từ camera điện thoại qua WiFi nội bộ |
| 🖥️ **Màn hình Xem** | Viewer | Xem đồng thời tối đa 4 camera, điều khiển chất lượng từ xa |

**Chi tiết:**
- Tự động phát hiện camera cùng mạng qua **mDNS** (không cần nhập IP thủ công)
- Viewer có thể đổi chất lượng stream (**360p / 720p / 1080p**) của Streamer từ xa
- Streamer hiển thị tên thiết bị đang xem theo thời gian thực
- Chế độ auto-dim màn hình tiết kiệm pin khi dùng làm camera an ninh
- Hỗ trợ cả **TCP** và **UDP** cho RTSP (chuyển đổi từ Viewer)
- ExoPlayer cấu hình low-latency (buffer tối thiểu ~1s)

---

## 2. Công nghệ

| Thành phần | Chi tiết |
|---|---|
| **Ngôn ngữ** | Kotlin |
| **Min SDK** | API 26 (Android 8.0) |
| **UI** | Jetpack Compose + Material 3 |
| **Kiến trúc** | MVVM + Unidirectional Data Flow |
| **State** | `StateFlow` + `collectAsStateWithLifecycle()` |
| **Async** | Kotlin Coroutines (`viewModelScope`, `Dispatchers.IO/Main`) |
| **Phát RTSP** | `RtspServerCamera2` (pedroSG94/RTSP-Server 1.4.1 + RootEncoder 2.7.2) |
| **Xem RTSP** | `Media3 ExoPlayer 1.5.0` với `RtspMediaSource` |
| **Discovery** | `Android NsdManager` (mDNS, service type `_rtspguard._tcp`) |
| **Control** | `ControlServer` — TCP server tự viết trên port 8081 |
| **Lưu trữ** | `DataStore Preferences` + `kotlinx.serialization` (JSON) |
| **Quyền** | `Accompanist Permissions` |
| **Navigation** | `Navigation Compose` |
| **Logging** | `AppLog` — wrapper tập trung, tag `"PhoneCamera"` |

### Design Pattern áp dụng
- **Repository Pattern** — `CameraRepository` ẩn chi tiết DataStore khỏi ViewModel
- **Sealed Class** — `PlayerState`, `ControlServer.Command` mô hình hóa trạng thái an toàn
- **Observer Pattern** — UI subscribe `StateFlow` qua `collectAsStateWithLifecycle()`
- **Custom TCP Protocol** — `ControlServer` nhận lệnh text-based từ Viewer

---

## 3. Cấu trúc thư mục

```
app/src/main/java/com/example/phonecamera/
│
├── MainActivity.kt              # Activity duy nhất, setup NavHost
├── navigation/
│   └── Screen.kt                # sealed class: Home | Streamer | Viewer
│
├── data/
│   ├── CameraRepository.kt      # CameraConfig model + DataStore CRUD
│   └── nsd/
│       ├── NsdHelper.kt         # Đăng ký & khám phá mDNS service
│       └── DiscoveredCamera.kt  # Data class camera tìm được qua NSD
│
├── home/
│   ├── HomeScreen.kt            # Màn hình chọn vai trò
│   └── HomeViewModel.kt         # Quản lý trạng thái quyền
│
├── streamer/
│   ├── StreamerScreen.kt        # UI phát camera (landscape)
│   ├── StreamerViewModel.kt     # Quản lý RtspServerCamera2, ControlServer, NSD
│   └── ControlServer.kt         # TCP server port 8081, nhận lệnh từ Viewer
│
├── viewer/
│   ├── ViewerScreen.kt          # UI xem camera (portrait/landscape/fullscreen)
│   ├── ViewerViewModel.kt       # Quản lý 4 slot, NSD discovery, gửi lệnh TCP
│   └── components/
│       ├── CameraCell.kt        # Ô camera: ExoPlayer + nút điều khiển
│       ├── AddEditCameraDialog.kt
│       └── DiscoveryBottomSheet.kt
│
├── ui/theme/
│   ├── Color.kt                 # Bảng màu Dark/Light + overlay aliases
│   ├── Theme.kt                 # MaterialTheme Dark/Light scheme
│   └── Type.kt                  # Typography
│
└── utils/
    └── AppLog.kt                # Centralized logger
```

---

## 4. Màn hình

### Home Screen
- Luôn **portrait**
- 2 thẻ bấm: **Máy Quay** / **Màn hình Xem**
- Tự động kiểm tra quyền CAMERA + RECORD_AUDIO — thiếu thì disable thẻ Máy Quay

### Streamer Screen
- Tự chuyển **landscape** + ẩn system bar (immersive mode)
- Layout: **65% camera preview** | **35% control panel**
- Control panel bao gồm:
  - Chip chọn độ phân giải (locked khi đang phát, cập nhật khi Viewer đổi từ xa)
  - RTSP URL + nút Copy
  - Card "Đang được xem": hiện tên thiết bị Viewer đang kết nối
  - Nút Bắt đầu / Dừng phát
- Nút lật camera trước/sau
- Auto-dim màn hình sau 30s không tương tác (độ sáng 0.01)
- Badge **LIVE** nhấp nháy khi stream đang chạy
- **Stream tự dừng khi app vào background** (Lifecycle ON_PAUSE)

### Viewer Screen
- **Portrait**: LazyColumn cuộn dọc
- **Landscape**: lưới 2×2 tỉ lệ 16:9
- **Fullscreen**: xem 1 camera toàn màn hình
- Toolbar: nút TCP/UDP toggle, badge đếm camera phát hiện, nút vào landscape
- Mỗi ô camera:
  - Chấm trạng thái (xanh = đang phát, vàng = đang kết nối)
  - FPS badge thực tế + độ phân giải
  - Nút **HD** 🎥 (chỉ với Phone Camera): dropdown 360p / 720p / 1080p → gửi lệnh đổi chất lượng
  - Nút âm thanh, tải lại, toàn màn hình, sửa

---

## 5. Kiến trúc MVVM

```mermaid
graph TD
    subgraph UI["🖼️ UI Layer (Jetpack Compose)"]
        HS[HomeScreen]
        SS[StreamerScreen]
        VS[ViewerScreen]
    end

    subgraph VM["🧠 ViewModel Layer"]
        HVM[HomeViewModel]
        SVM[StreamerViewModel]
        VVM[ViewerViewModel]
    end

    subgraph DATA["💾 Data Layer"]
        REPO[CameraRepository\nDataStore]
        NSD1[NsdHelper\nStreamer side]
        NSD2[NsdHelper\nViewer side]
        CS[ControlServer\nTCP :8081]
        RTSP[RtspServerCamera2]
        EXO[ExoPlayer\nRtspMediaSource]
    end

    HS -->|observes StateFlow| HVM
    SS -->|observes StateFlow| SVM
    VS -->|observes StateFlow| VVM

    HVM -->|reads permissions| HS

    SVM --> RTSP
    SVM --> NSD1
    SVM --> CS

    VVM --> REPO
    VVM --> NSD2
    VVM -->|TCP commands| CS

    REPO -->|Flow| VVM
    CS -->|Command events| SVM
```

**Quy tắc:**
- `@Composable` chỉ **đọc** StateFlow và **gọi hàm** ViewModel — không tự xử lý logic
- ViewModel giữ `private val _uiState = MutableStateFlow(...)`, expose `val uiState = _uiState.asStateFlow()`
- Mọi side-effect chạy trong `viewModelScope.launch {}`

---

## 6. Sơ đồ Use Case

```mermaid
flowchart LR
    U([👤 Người dùng])

    U --> A[Chọn vai trò]
    U --> B[Cấp quyền]

    A --> C[Máy Quay]
    A --> D[Màn hình Xem]

    C --> C1[Chọn độ phân giải]
    C --> C2[Bắt đầu phát]
    C --> C3[Lật camera]
    C --> C4[Tắt màn hình tiết kiệm pin]
    C2 --> C5[Dừng phát]

    D --> D1[Thêm camera thủ công]
    D --> D2[Quét mạng tự động NSD]
    D --> D3[Xem camera]
    D2 --> D4[Thêm 1 chạm]
    D3 --> D5[Đổi chất lượng từ xa]
    D3 --> D6[Tắt bật âm thanh]
    D3 --> D7[Xem toàn màn hình]
    D3 --> D8[Tải lại camera]
```

---

## 7. Sơ đồ Lớp

```mermaid
classDiagram
    class CameraConfig {
        +id: Int
        +name: String
        +host: String
        +port: Int
        +isPhoneCamera: Boolean
        +toRtspUrl() String
    }

    class DiscoveredCamera {
        +serviceId: String
        +displayName: String
        +host: String
        +port: Int
        +rtspUrl: String
    }

    class CameraRepository {
        +camerasFlow: Flow~List~CameraConfig~~
        +saveCamera(config)
        +deleteCamera(id)
    }

    class NsdHelper {
        +SERVICE_TYPE: String$
        +deviceServiceName() String$
        +registerService(port)
        +unregisterService()
        +discoverServices(onFound, onLost)
        +stopDiscovery()
        +stopAll()
    }

    class ControlServer {
        +CONTROL_PORT: Int$
        +port: Int
        +start(scope, onCommand)
        +stop()
    }

    class ControlServerCommand {
        <<sealed>>
        Hello
        Bye
        SetQuality
    }

    class Resolution {
        <<enum>>
        P360
        P720
        P1080
        +label: String
        +width: Int
        +height: Int
        +bitrateBps: Int
    }

    class PlayerState {
        <<sealed>>
        Idle
        Loading
        Playing
        Error
    }

    class StreamerUiState {
        +isStreaming: Boolean
        +useFrontCamera: Boolean
        +selectedResolution: Resolution
        +localIpAddress: String
        +connectedViewers: List~String~
        +rtspUrl: String
    }

    class ViewerUiState {
        +cameras: List~CameraConfig?~
        +playerStates: Map~Int, PlayerState~
        +useTcp: Boolean
        +discoveredCameras: List~DiscoveredCamera~
        +discoveryBadgeCount: Int
    }

    class StreamerViewModel {
        +uiState: StateFlow~StreamerUiState~
        +attachCamera(glView)
        +startStream()
        +stopStream()
        +switchCamera()
        +selectResolution(res)
        +releaseCamera()
        -changeQualityRemote(res)
    }

    class ViewerViewModel {
        +uiState: StateFlow~ViewerUiState~
        +saveCamera(config)
        +deleteCamera(id)
        +retryCamera(index)
        +setRemoteQuality(slot, heightP)
        +toggleTcp()
        +toggleAudio(slot)
        +startDiscovery()
    }

    CameraRepository "1" --> "0..*" CameraConfig
    NsdHelper ..> DiscoveredCamera : produces
    ControlServer ..> ControlServerCommand : parses
    StreamerViewModel --> StreamerUiState
    StreamerViewModel --> NsdHelper
    StreamerViewModel --> ControlServer
    StreamerViewModel --> Resolution
    ViewerViewModel --> ViewerUiState
    ViewerViewModel --> CameraRepository
    ViewerViewModel --> NsdHelper
    ViewerViewModel --> PlayerState
    ViewerUiState --> PlayerState
    StreamerUiState --> Resolution
```

---

## 8. Sơ đồ Tuần tự

### 8.1 Phát Camera (Streamer)

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant SS as StreamerScreen
    participant SVM as StreamerViewModel
    participant CAM as RtspServerCamera2
    participant NSD as NsdHelper

    U->>SS: Mở màn hình Streamer
    SS->>SVM: init()
    SVM->>SVM: loadLocalIp()
    SVM->>SVM: controlServer.start()

    SS->>SVM: attachCamera(openGlView)
    SVM->>CAM: new RtspServerCamera2(glView, port=8080)
    CAM-->>SVM: startPreview()
    SVM-->>SS: isCameraReady = true

    U->>SS: Bấm "BẮT ĐẦU PHÁT"
    SS->>SVM: startStream()
    SVM->>CAM: prepareVideo(res) + prepareAudio()
    SVM->>CAM: startStream()
    CAM-->>SVM: isStreaming = true
    SVM->>NSD: registerService(8080)
    SVM-->>SS: isStreaming = true
    SS-->>U: Badge LIVE nhấp nháy

    U->>SS: App vào background
    SS->>SVM: stopStream() [ON_PAUSE]
    SVM->>CAM: stopStream()
    SVM->>NSD: unregisterService()
```

### 8.2 Xem Camera (Viewer)

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant VS as ViewerScreen
    participant VVM as ViewerViewModel
    participant NSD as NsdHelper
    participant EXO as ExoPlayer

    U->>VS: Mở màn hình Viewer
    VS->>VVM: init()
    VVM->>VVM: repository.camerasFlow.collect()
    VVM->>NSD: discoverServices()

    NSD-->>VVM: onFound(DiscoveredCamera)
    VVM-->>VS: discoveryBadgeCount++

    U->>VS: Bấm badge → DiscoveryBottomSheet
    U->>VS: Chọn camera → addDiscoveredCamera()
    VS->>VVM: saveCamera(config)
    VVM->>VVM: setPlayerState(slot, Loading)
    VS->>EXO: CameraCell khởi tạo ExoPlayer

    EXO->>EXO: delay(500ms) → RtspMediaSource → prepare()
    EXO-->>VVM: STATE_READY → onPlayerReady(slot)
    VVM->>VVM: setPlayerState(slot, Playing)
    VVM-->>VS: spinner ẩn, video hiện
```

### 8.3 Đổi Chất lượng từ Viewer

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant CC as CameraCell
    participant VVM as ViewerViewModel
    participant TCP as TCP:8081
    participant SVM as StreamerViewModel
    participant CAM as RtspServerCamera2

    U->>CC: Bấm nút HD → chọn "720p"
    CC->>VVM: setRemoteQuality(slot=0, 720)
    VVM->>VVM: retryCamera(0) → Loading [phản hồi ngay]

    VVM->>TCP: "SET_QUALITY 720"
    TCP->>SVM: Command.SetQuality(720)
    SVM->>SVM: changeQualityRemote(P720) [Main thread]
    SVM->>CAM: stopStream()
    SVM->>SVM: delay(500ms)
    SVM->>CAM: startStream() ở 720p
    SVM-->>SVM: selectedResolution = P720 [UI chip cập nhật]
    TCP-->>VVM: "OK"

    VVM->>VVM: delay(2000ms) đợi Streamer restart
    VVM->>VVM: retryCamera(0) → Loading
    Note over VVM: ExoPlayer mới kết nối lại
    VVM-->>CC: PlayerState.Playing [video chạy lại]
```

### 8.4 Tracking Viewer (HELLO/BYE)

```mermaid
sequenceDiagram
    participant VVM as ViewerViewModel
    participant TCP as TCP:8081
    participant SVM as StreamerViewModel
    participant SS as StreamerScreen

    Note over VVM: onPlayerReady(slot=0) được gọi
    VVM->>VVM: oldState != Playing && newState == Playing
    VVM->>TCP: "HELLO Pixel-7"
    TCP->>SVM: Command.Hello("Pixel-7", ip)
    SVM->>SVM: connectedViewers += "Pixel-7"
    SVM-->>SS: ViewersCard: "Đang xem: 1 thiết bị · Pixel-7"

    Note over VVM: onCleared() hoặc player lỗi
    VVM->>TCP: "BYE Pixel-7"
    TCP->>SVM: Command.Bye("Pixel-7", ip)
    SVM->>SVM: connectedViewers.remove("Pixel-7")
    SVM-->>SS: ViewersCard: "Chưa có ai xem"
```

### 8.5 Auto-discovery mDNS

```mermaid
sequenceDiagram
    participant STR as Máy Streamer
    participant MDNS as Mạng LAN (mDNS)
    participant VWR as Máy Viewer

    STR->>MDNS: startStream() → NsdHelper.registerService(8080)
    Note over MDNS: Quảng bá _rtspguard._tcp

    VWR->>MDNS: NsdHelper.discoverServices()
    MDNS-->>VWR: onServiceFound(serviceInfo)
    MDNS-->>VWR: onServiceResolved → DiscoveredCamera(host, port)
    VWR->>VWR: discoveryBadgeCount++

    Note over VWR: Người dùng thêm camera 1 chạm
    VWR->>VWR: saveCamera(isPhoneCamera=true)

    STR->>MDNS: stopStream() → unregisterService()
    MDNS-->>VWR: onServiceLost(serviceId)
    VWR->>VWR: PlayerState.Error("Camera đã mất kết nối")
```

---

## 9. Giao thức ControlServer (TCP :8081)

ControlServer là một TCP server text-based, chạy trên máy Streamer. Mỗi kết nối là một lệnh + response.

```mermaid
sequenceDiagram
    participant V as Viewer (client)
    participant S as ControlServer :8081

    V->>S: TCP connect
    V->>S: "HELLO Samsung-Galaxy-S22\n"
    S-->>V: "OK\n"
    S->>S: connectedViewers += "Samsung Galaxy S22"

    V->>S: TCP connect
    V->>S: "SET_QUALITY 720\n"
    S-->>V: "OK\n"
    S->>S: changeQualityRemote(P720)

    V->>S: TCP connect
    V->>S: "BYE Samsung-Galaxy-S22\n"
    S-->>V: "OK\n"
    S->>S: connectedViewers.remove(...)
```

| Lệnh | Format | Ý nghĩa |
|---|---|---|
| HELLO | `HELLO <tên máy>` | Viewer bắt đầu phát stream |
| BYE | `BYE <tên máy>` | Viewer ngừng phát stream |
| SET_QUALITY | `SET_QUALITY <360\|720\|1080>` | Yêu cầu đổi độ phân giải |

Response luôn là `OK` hoặc `ERROR <lý do>`.

---

## 10. Vòng đời Tài nguyên

### StreamerViewModel (Camera + Stream)

```mermaid
stateDiagram-v2
    [*] --> Idle : ViewModel init\nloadLocalIp()\ncontrolServer.start()

    Idle --> Preview : attachCamera(glView)\nRtspServerCamera2.startPreview()

    Preview --> Streaming : startStream()\nprepareVideo + prepareAudio\nstartStream() + NSD.register()

    Streaming --> Preview : stopStream()\nNSD.unregister()\n[ON_PAUSE tự động]

    Streaming --> QualityChange : changeQualityRemote(res)\nstopStream() → delay(500ms) → startStream()

    QualityChange --> Streaming : startStream() mới

    Preview --> [*] : releaseCamera()\nstopPreview() + NSD.stopAll()\ncontrolServer.stop()
```

### ExoPlayer trong CameraCell

```mermaid
stateDiagram-v2
    [*] --> Creating : playerState = Loading\nDisposableEffect kích hoạt

    Creating --> Connecting : delay(500ms)\nExoPlayer.Builder\nRtspMediaSource.prepare()

    Connecting --> Playing : STATE_READY\nonPlayerReady()

    Connecting --> Error : PlaybackException\nonPlayerError(msg)

    Playing --> Error : Network lost\nStream ngắt

    Error --> Creating : retryCamera()\nnew attemptId

    Playing --> Creating : setRemoteQuality()\nnew attemptId sau 2s

    Playing --> [*] : onDispose\nplayer.release()
    Error --> [*] : onDispose\nplayer.release()
```

---

## 11. Quyền Hệ thống

| Quyền | Lý do |
|---|---|
| `CAMERA` | Quay video để phát RTSP |
| `RECORD_AUDIO` | Thu âm trong stream |
| `INTERNET` | Kết nối RTSP (:8080) và ControlServer (:8081) |
| `ACCESS_WIFI_STATE` | Đọc địa chỉ IP WiFi hiện tại |
| `CHANGE_WIFI_MULTICAST_STATE` | Nhận gói mDNS multicast để phát hiện camera |

---

## 12. Luồng Khởi động

```mermaid
flowchart TD
    A[MainActivity.onCreate] --> B[PhoneCameraTheme]
    B --> C[NavHost: startDestination = home]
    C --> D[HomeScreen]
    D --> E{Người dùng chọn}

    E -->|Máy Quay| F[StreamerScreen]
    F --> F1[StreamerViewModel.init\nloadLocalIp\ncontrolServer.start]
    F1 --> F2[AndroidView: OpenGlView]
    F2 --> F3[glView.post: attachCamera\nRtspServerCamera2\nstartPreview]

    E -->|Màn hình Xem| G[ViewerScreen]
    G --> G1[ViewerViewModel.init\nrepo.camerasFlow.collect\nnsdHelper.discoverServices]
    G1 --> G2[4x CameraCell render]
    G2 --> G3[ExoPlayer: delay 500ms\nRtspMediaSource → prepare]
    G3 --> G4[STATE_READY → Playing]
```

---

## 13. Ghi chú cho Developer

### `isPhoneCamera` flag
Camera thêm qua **NSD auto-discovery** tự động có `isPhoneCamera = true`. Chỉ những camera này:
- Hiển thị nút HD để đổi chất lượng từ xa
- Gửi lệnh HELLO/BYE đến ControlServer

Camera thêm **thủ công** (nhập IP) có `isPhoneCamera = false` — không hỗ trợ điều khiển từ xa.

### Thread Safety
`ControlServer` chạy hoàn toàn trên `Dispatchers.IO`. Mọi callback khi nhận lệnh phải dispatch về Main:
```kotlin
// ĐÚNG
viewModelScope.launch(Dispatchers.Main) {
    _uiState.update { ... }
}

// SAI — StateFlow update từ IO thread
_uiState.update { ... } // có thể gây race condition
```

### StateFlow Pattern
```kotlin
// ViewModel
private val _uiState = MutableStateFlow(MyUiState())
val uiState: StateFlow<MyUiState> = _uiState.asStateFlow()

// UI
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```
`collectAsStateWithLifecycle()` tự ngừng collect khi app vào background — tiết kiệm pin.

### ExoPlayer Lifecycle
`DisposableEffect(rtspUrl, useTcp, attemptId)` — bất kỳ tham số nào thay đổi → ExoPlayer cũ bị `release()`, player mới tạo. `retryCamera()` tạo `attemptId` mới để trigger restart.

### AppLog
```kotlin
AppLog.d("message")  // Debug
AppLog.i("message")  // Info
AppLog.w("message")  // Warning
AppLog.e("message")  // Error
AppLog.v("message")  // Verbose
```
Filter trong Logcat: `tag:PhoneCamera`

---

*Cập nhật lần cuối: 2026-04-23*
