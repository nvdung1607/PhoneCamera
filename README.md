# 📷 Phone Camera — Tài liệu Kỹ thuật (Tiếng Việt)

> Ứng dụng biến điện thoại Android thành **hệ thống camera an ninh nội bộ** sử dụng giao thức RTSP qua mạng WiFi.
> Stream chỉ hoạt động khi app đang mở — không chạy ngầm.

---

## 1. Tổng quan Chức năng

Ứng dụng hoạt động theo mô hình **2 vai trò**:

| Vai trò | Màn hình | Mô tả |
|---|---|---|
| 📹 **Máy Quay** | Streamer | Phát luồng RTSP qua WiFi nội bộ, hiển thị ai đang xem |
| 🖥️ **Màn hình Xem** | Viewer | Xem đồng thời tối đa **4 camera**, điều khiển chất lượng từ xa |

---

## 2. Công nghệ Sử dụng

### Ngôn ngữ & Nền tảng
- **Kotlin** — ngôn ngữ chính
- **Android API 26+** (Android 8.0 trở lên)
- **Jetpack Compose** — UI hoàn toàn bằng Compose, không dùng XML layout

### Kiến trúc
- **MVVM** (Model – View – ViewModel)
- **Unidirectional Data Flow** — dữ liệu chỉ chảy một chiều: Repository → ViewModel → UI

### Thư viện chính
| Thư viện | Vai trò |
|---|---|
| `RootEncoder (RtspServerCamera2)` | Phát luồng RTSP từ camera điện thoại |
| `Media3 ExoPlayer` | Nhận và phát luồng RTSP phía Viewer (low-latency) |
| `Android NSD (mDNS)` | Tự động tìm kiếm camera trong mạng LAN |
| `DataStore Preferences` | Lưu cấu hình camera cục bộ |
| `kotlinx.serialization` | Serialize CameraConfig sang JSON |
| `Accompanist Permissions` | Xử lý xin quyền runtime |
| `Navigation Compose` | Điều hướng giữa 3 màn hình |
| `Coroutines + StateFlow` | Lập trình bất đồng bộ, quản lý trạng thái |

### Design Pattern
- **Repository Pattern** — `CameraRepository` trừu tượng hóa DataStore
- **Sealed Class** — `PlayerState` (Idle/Loading/Playing/Error), `Screen`
- **Observer Pattern** — UI lắng nghe `StateFlow` qua `collectAsStateWithLifecycle()`
- **Custom TCP Protocol** — `ControlServer` nhận lệnh điều khiển từ xa (port 8081)

---

## 3. Cấu trúc Thư mục

```
app/src/main/java/com/example/phonecamera/
│
├── MainActivity.kt              # Activity duy nhất, NavHost
├── navigation/
│   └── Screen.kt                # 3 route: Home, Streamer, Viewer
│
├── data/
│   ├── CameraRepository.kt      # CameraConfig + DataStore
│   └── nsd/
│       ├── NsdHelper.kt         # mDNS: đăng ký & tìm kiếm
│       └── DiscoveredCamera.kt  # Model camera tìm qua NSD
│
├── home/
│   ├── HomeScreen.kt
│   └── HomeViewModel.kt
│
├── streamer/
│   ├── StreamerScreen.kt        # UI phát camera (landscape)
│   ├── StreamerViewModel.kt     # Quản lý RtspServerCamera2 trực tiếp
│   └── ControlServer.kt         # TCP server port 8081 nhận lệnh từ Viewer
│
├── viewer/
│   ├── ViewerScreen.kt          # UI xem 4 camera
│   ├── ViewerViewModel.kt       # Quản lý slot, NSD, điều khiển từ xa
│   └── components/
│       ├── CameraCell.kt        # Ô camera (ExoPlayer + quality control)
│       ├── AddEditCameraDialog.kt
│       └── DiscoveryBottomSheet.kt
│
├── ui/theme/
│   ├── Color.kt, Theme.kt, Type.kt
│
└── utils/
    └── AppLog.kt                # Logger tập trung, tag "PhoneCamera"
```

---

## 4. Các Màn hình

### 4.1 Home Screen
- **Portrait** cố định
- 2 thẻ lựa chọn: Máy Quay / Màn hình Xem
- Kiểm tra quyền **CAMERA + RECORD_AUDIO** — thiếu thì vô hiệu thẻ Máy Quay

### 4.2 Streamer Screen
- Tự chuyển **landscape** + ẩn system bar
- **65% preview camera** | **35% control panel**
- Control panel:
  - Chọn độ phân giải: **360p / 720p / 1080p** (bị khoá khi đang phát; Viewer có thể đổi từ xa)
  - RTSP URL + nút Copy
  - **Card "Đang xem"**: hiện tên thiết bị đang kết nối xem
  - Nút Bắt đầu / Dừng phát
- Tính năng: lật camera trước/sau, auto-dim 30s tiết kiệm pin, badge LIVE nhấp nháy
- **Stream dừng tự động** khi app vào background (ON_PAUSE)

### 4.3 Viewer Screen
- **Portrait**: LazyColumn cuộn dọc
- **Landscape**: lưới 2×2 cố định tỉ lệ 16:9
- **Fullscreen**: xem 1 camera toàn màn hình
- Mỗi ô camera hiển thị: tên, FPS thực tế, độ phân giải, nút Âm thanh / Reload / Fullscreen / Sửa
- **Nút HD** (chỉ với Phone Camera): dropdown chọn 360p / 720p / 1080p → gửi lệnh đến máy Streamer

---

## 5. Kiến trúc MVVM

```
┌──────────────────────────────────────────────────────┐
│                     UI Layer                         │
│  HomeScreen   StreamerScreen      ViewerScreen       │
│      │               │                 │             │
│ HomeViewModel  StreamerViewModel  ViewerViewModel    │
└──────┼───────────────┼─────────────────┼─────────────┘
       │               │                 │
       │    ┌──────────┴──────┐  ┌───────┴──────────┐
       │    │ RtspServerCam2  │  │ CameraRepository  │
       │    │ ControlServer   │  │ (DataStore)       │
       │    │ NsdHelper       │  │ NsdHelper         │
       │    └─────────────────┘  └──────────────────┘
```

**Quy tắc MVVM trong project:**
- **View** (`@Composable`): chỉ gọi hàm trên ViewModel, không tự xử lý logic
- **ViewModel**: giữ `MutableStateFlow` private, expose `StateFlow` read-only
- **Model**: `CameraConfig`, `DiscoveredCamera`, `CameraRepository`

---

## 6. Sơ đồ Lớp (Class Diagram)

```
┌──────────────────┐      ┌───────────────────────────┐
│   CameraConfig   │      │     DiscoveredCamera       │
│──────────────────│      │───────────────────────────│
│ id: Int          │      │ serviceId: String          │
│ name: String     │      │ displayName: String        │
│ host: String     │      │ host: String               │
│ port: Int        │      │ port: Int                  │
│ isPhoneCamera:   │      │ rtspUrl: String (computed) │
│   Boolean        │      └───────────────────────────┘
│──────────────────│                  ▲
│ toRtspUrl(): Str │                  │ produces
└────────┬─────────┘          ┌───────┴──────┐
         │ stored by          │   NsdHelper   │
         ▼                    │──────────────│
┌──────────────────┐          │ registerSvc() │
│ CameraRepository │          │ discoverSvc() │
│──────────────────│          │ stopAll()     │
│ camerasFlow: Flow│          └──────────────┘
│ saveCamera()     │
│ deleteCamera()   │
└──────────────────┘

┌──────────────────────────┐      ┌─────────────────────┐
│    StreamerViewModel     │      │    ControlServer     │
│──────────────────────────│      │─────────────────────│
│ uiState: StateFlow       │◄─────│ port: 8081           │
│ connectedViewers: List   │      │ start(scope, onCmd)  │
│ attachCamera(glView)     │      │ stop()               │
│ startStream()            │      │─────────────────────│
│ stopStream()             │      │ Command:             │
│ switchCamera()           │      │  Hello(name, ip)     │
│ selectResolution(res)    │      │  Bye(name, ip)       │
│ releaseCamera()          │      │  SetQuality(heightP) │
└──────────────────────────┘      └─────────────────────┘

┌──────────────────────────┐
│     ViewerViewModel      │
│──────────────────────────│
│ uiState: StateFlow       │
│ saveCamera()             │
│ deleteCamera()           │
│ retryCamera()            │
│ setRemoteQuality(i, h)   │──TCP──► ControlServer:8081
│ toggleTcp()              │
│ toggleAudio()            │
│ startDiscovery()         │
└──────────────────────────┘

sealed class PlayerState:
  Idle | Loading(attemptId) | Playing | Error(msg)

enum class Resolution:
  P360("360p") | P720("720p") | P1080("1080p")
```

---

## 7. Sơ đồ Use Case

```
                        [Người dùng]
                             │
          ┌──────────────────┼─────────────────┐
          ▼                  ▼                 ▼
    [Chọn Máy Quay]   [Chọn Viewer]     [Cấp quyền]
          │                  │
    ┌─────┴─────┐      ┌─────┴──────────────────┐
    ▼           ▼      ▼           ▼             ▼
[Chọn độ   [Bắt đầu] [Thêm camera] [Quét mạng] [Xem camera]
 phân giải]  [phát]  [thủ công]   [tự động]         │
    │                                      ┌─────────┤
    │                                      ▼         ▼
    │                               [Đổi chất    [Tắt/Bật
    │                                lượng từ xa]  âm thanh]
    ▼
[Lật camera / Tắt màn hình]
```

---

## 8. Sơ đồ Tuần tự — Phát Camera (Streamer)

```
User      StreamerScreen    StreamerViewModel    RtspServerCamera2   NsdHelper
 │              │                  │                    │                │
 │──[mở màn]──►│                  │                    │                │
 │              │──[init VM]──────►│                    │                │
 │              │                  │──[ControlServer.start()]            │
 │              │                  │                    │                │
 │              │──[OpenGlView ready]                   │                │
 │              │──[attachCamera(glView)]───────────────►│               │
 │              │                  │◄──[isCameraReady=true]              │
 │              │                  │                    │                │
 │──[bấm PHÁT]─►│                 │                    │                │
 │              │──[startStream()]─►│                  │                │
 │              │                  │──[prepareVideo/Audio]──────────────►│
 │              │                  │──[startStream()]──►│                │
 │              │                  │──[registerService(8080)]────────────►│
 │              │◄──[isStreaming=true]                  │                │
 │              │──[hiện LIVE badge]                    │                │
```

---

## 9. Sơ đồ Tuần tự — Đổi Chất lượng Từ xa (Viewer → Streamer)

```
ViewerViewModel        TCP:8081         StreamerViewModel     ViewerViewModel
      │                    │                   │                    │
 setRemoteQuality(1, 720)  │                   │                    │
      │──retryCamera(1)──────────────────────────────────────────►  │
      │  (Loading spinner) │                   │                    │
      │──"SET_QUALITY 720"─►│                  │                    │
      │                    │──[onCommand]──────►│                   │
      │                    │                   │──[Main thread]     │
      │                    │                   │──stopStream()      │
      │                    │                   │──delay(500ms)      │
      │                    │                   │──startStream(720p) │
      │                    │                   │──[UI chip = 720p]  │
      │◄──────"OK"─────────│                   │                    │
      │──delay(2000ms)──────────────────────────────────────────── │
      │──retryCamera(1)     │                   │                    │
      │  (ExoPlayer reconnect → Playing)        │                    │
```

---

## 10. Sơ đồ Tuần tự — Tracking Viewer (HELLO/BYE)

```
ViewerViewModel    TCP:8081     StreamerViewModel    StreamerScreen
      │                │               │                  │
 onPlayerReady(0)       │              │                  │
 (state: Playing)       │              │                  │
      │──"HELLO Pixel 7"►│             │                  │
      │                │──[onCommand]──►│                 │
      │                │               │──update connectedViewers│
      │                │               │──["Pixel 7"]────►│
      │                │               │                  │──[ViewersCard: "Đang xem: 1"]
      │                │               │                  │
 onCleared()           │               │                  │
      │──"BYE Pixel 7"─►│              │                  │
      │                │──[onCommand]──►│                 │
      │                │               │──connectedViewers = []
      │                │               │──[]─────────────►│
      │                │               │                  │──[ViewersCard: "Chưa có ai xem"]
```

---

## 11. Vòng đời Tài nguyên

### Camera & Stream (StreamerViewModel)
```
Mở StreamerScreen
  └─► ViewModel.init():
        ├─ loadLocalIp()
        └─ ControlServer.start()   ← lắng nghe :8081

  └─► AndroidView factory → OpenGlView
        └─► glView.post { attachCamera(glView) }
              └─► RtspServerCamera2(glView, ..., 8080)
                  └─► startPreview()

Bấm "BẮT ĐẦU PHÁT"
  └─► startStream() → prepareVideo → prepareAudio → startPreview → startStream
        └─► NsdHelper.registerService(8080)

App vào background (ON_PAUSE)
  └─► stopStream() ← stream dừng tự động

Rời StreamerScreen (onDispose)
  └─► releaseCamera() → stopStream + stopPreview + camera = null
      ControlServer.stop()
      NsdHelper.stopAll()
```

### ExoPlayer (CameraCell)
```
playerState = Loading
  └─► delay(500ms) → ExoPlayer.Builder → RtspMediaSource → prepare → play
        └─► STATE_READY → onPlayerReady() → PlayerState.Playing

setRemoteQuality(720)
  └─► retryCamera() → Loading [ngay]
      TCP "SET_QUALITY 720" → Streamer restart (500ms + 2000ms)
      retryCamera() → Loading → ExoPlayer mới → Playing

Composable xóa
  └─► onDispose { player.release() }
```

---

## 12. Giao thức ControlServer (TCP :8081)

| Lệnh (Viewer → Streamer) | Mô tả | Response |
|---|---|---|
| `HELLO <tên máy>` | Viewer bắt đầu xem | `OK` |
| `BYE <tên máy>` | Viewer ngừng xem | `OK` |
| `SET_QUALITY <360\|720\|1080>` | Đổi độ phân giải | `OK` hoặc `ERROR` |

**Khi nào Viewer gửi:**
- `HELLO` → khi `PlayerState` chuyển sang `Playing`
- `BYE` → khi `PlayerState` rời `Playing`, hoặc `ViewModel.onCleared()`
- `SET_QUALITY` → khi người dùng bấm nút HD trên ô camera

---

## 13. Quyền Hệ thống

| Quyền | Lý do |
|---|---|
| `CAMERA` | Quay video |
| `RECORD_AUDIO` | Thu âm |
| `INTERNET` | Kết nối RTSP và TCP ControlServer |
| `ACCESS_WIFI_STATE` | Lấy địa chỉ IP WiFi |
| `CHANGE_WIFI_MULTICAST_STATE` | Nhận gói mDNS multicast |

---

## 14. Luồng Code Khởi động

```
1. MainActivity.onCreate()
2.   └─► NavHost(start = "home")
3.        └─► HomeScreen → HomeViewModel (check permissions)

[Chọn Máy Quay]
4.   └─► navController.navigate("streamer")
5.        └─► StreamerScreen
6.             └─► StreamerViewModel.init():
7.                  ├─ loadLocalIp()
8.                  └─ controlServer.start(viewModelScope, onCommand)
9.             └─► AndroidView { OpenGlView }
10.                 └─► viewModel.attachCamera(glView)

[Chọn Màn hình Xem]
4.   └─► navController.navigate("viewer")
5.        └─► ViewerScreen
6.             └─► ViewerViewModel.init():
7.                  ├─ repository.camerasFlow.collect → load saved cameras
8.                  └─ nsdHelper.discoverServices()
9.             └─► 4× CameraCell → ExoPlayer → RTSP connect
```

---

## 15. Ghi chú Quan trọng cho Developer

> **StateFlow vs LiveData**: Dùng `StateFlow` (Kotlin Coroutines). `collectAsStateWithLifecycle()` tự ngừng collect khi app background — tiết kiệm pin.

> **isPhoneCamera flag**: Camera thêm qua NSD tự động có `isPhoneCamera=true`. Chỉ những camera này mới hiện nút HD và gửi lệnh HELLO/BYE. Camera thêm thủ công không hỗ trợ điều khiển từ xa.

> **Thread safety**: `ControlServer` chạy trên `Dispatchers.IO`. Mọi thay đổi StateFlow phải về `Dispatchers.Main` (dùng `viewModelScope.launch(Dispatchers.Main)` hoặc `withContext(Dispatchers.Main)`).

> **ExoPlayer lifecycle**: Được tạo/hủy bởi `DisposableEffect(rtspUrl, useTcp, attemptId)`. Thay đổi bất kỳ tham số nào → ExoPlayer cũ bị `release()`, player mới được tạo.

> **AppLog**: Toàn bộ log qua `AppLog.d/i/w/e/v()` với tag `"PhoneCamera"` — filter dễ hơn trong Logcat.

---

*Cập nhật lần cuối: 2026-04-23*
