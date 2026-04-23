# 📷 Phone Camera — Tài liệu Kỹ thuật (Tiếng Việt)

> Ứng dụng biến điện thoại Android thành **hệ thống camera an ninh nội bộ** sử dụng giao thức RTSP qua mạng WiFi.

---

## 1. Tổng quan Chức năng

Ứng dụng hoạt động theo mô hình **2 vai trò** — một điện thoại có thể đóng cả hai:

| Vai trò | Tên màn hình | Mô tả |
|---|---|---|
| 📹 **Máy Quay** | Streamer | Dùng camera điện thoại phát luồng RTSP qua mạng nội bộ |
| 🖥️ **Màn hình Xem** | Viewer | Xem đồng thời tối đa **4 camera** từ các điện thoại khác |

---

## 2. Công nghệ Sử dụng

### Ngôn ngữ & Nền tảng
- **Kotlin** — ngôn ngữ chính
- **Android API 26+** (Android 8.0 trở lên)
- **Jetpack Compose** — UI framework hiện đại (thay thế XML layout)

### Kiến trúc
- **MVVM** (Model – View – ViewModel)
- **Unidirectional Data Flow (UDF)** — dữ liệu chỉ chảy một chiều: ViewModel → UI

### Thư viện chính
| Thư viện | Vai trò |
|---|---|
| `RootEncoder (RtspServerCamera2)` | Phát luồng RTSP từ camera điện thoại |
| `Media3 ExoPlayer` | Nhận và phát luồng RTSP (phía Viewer) |
| `Android NSD (mDNS)` | Tự động phát hiện camera trong mạng LAN |
| `DataStore Preferences` | Lưu cấu hình camera cục bộ (thay SharedPreferences) |
| `kotlinx.serialization` | Serialize/deserialize cấu hình camera sang JSON |
| `Accompanist Permissions` | Xử lý xin quyền runtime cho Compose |
| `Navigation Compose` | Điều hướng giữa các màn hình |
| `Coroutines + Flow` | Lập trình bất đồng bộ (async) |

### Đa luồng & Coroutine
- **`viewModelScope.launch`** — chạy tác vụ I/O trong scope của ViewModel
- **`Dispatchers.Main`** — cập nhật UI thread an toàn
- **`StateFlow`** — stream trạng thái phản ứng (reactive state)
- **`DisposableEffect`** — quản lý vòng đời của ExoPlayer, NSD, màn hình
- **`LaunchedEffect`** — side-effect theo lifecycle của Composable

### Design Pattern
- **Foreground Service** (`RtspStreamService`) — giữ tiến trình phát camera sống khi app bị minimize
- **Binder Pattern** — giao tiếp giữa `StreamerViewModel` và `RtspStreamService`
- **Repository Pattern** — `CameraRepository` trừu tượng hóa việc lưu/đọc dữ liệu
- **Sealed Class** — `PlayerState`, `StreamResult`, `Screen` để mô hình hóa trạng thái an toàn
- **Observer Pattern** — UI lắng nghe `StateFlow` từ ViewModel

---

## 3. Cấu trúc Thư mục

```
app/src/main/java/com/example/phonecamera/
│
├── MainActivity.kt              # Activity duy nhất, chứa NavHost
├── navigation/
│   └── Screen.kt                # Định nghĩa 3 route: Home, Streamer, Viewer
│
├── data/
│   ├── CameraRepository.kt      # Lưu/đọc CameraConfig từ DataStore
│   └── nsd/
│       ├── NsdHelper.kt         # Wrapper mDNS: đăng ký & tìm kiếm service
│       └── DiscoveredCamera.kt  # Model camera tìm được qua mDNS
│
├── home/
│   ├── HomeScreen.kt            # Màn hình chọn vai trò
│   └── HomeViewModel.kt         # Quản lý trạng thái quyền
│
├── streamer/
│   ├── StreamerScreen.kt        # Màn hình phát camera (landscape)
│   ├── StreamerViewModel.kt     # Logic phát stream, bind service
│   └── RtspStreamService.kt     # Foreground Service chạy RTSP server
│
├── viewer/
│   ├── ViewerScreen.kt          # Màn hình xem 4 camera
│   ├── ViewerViewModel.kt       # Logic quản lý slot camera, NSD discovery
│   └── components/
│       ├── CameraCell.kt        # Ô camera đơn lẻ (ExoPlayer)
│       ├── AddEditCameraDialog.kt  # Dialog thêm/sửa camera thủ công
│       └── DiscoveryBottomSheet.kt # Bottom sheet hiện camera tìm tự động
│
├── ui/theme/
│   ├── Color.kt                 # Bảng màu custom
│   ├── Theme.kt                 # MaterialTheme Dark/Light
│   └── Type.kt                  # Typography
│
└── utils/
    └── AppLog.kt                # Logger tập trung (tag "PhoneCamera")
```

---

## 4. Các Màn hình

### 4.1 Home Screen (Màn hình Chọn Vai trò)
- Luôn ở **portrait** (dọc)
- Hiển thị **2 thẻ lựa chọn**: Máy Quay / Màn hình Xem
- Kiểm tra quyền **CAMERA + RECORD_AUDIO** — nếu thiếu, thẻ Máy Quay bị vô hiệu và hiển thị banner cảnh báo
- Animation: icon shield nhấp nháy, thẻ bấm có hiệu ứng scale bounce

### 4.2 Streamer Screen (Màn hình Phát Camera)
- Tự chuyển sang **landscape** (ngang) + ẩn system bar (immersive mode)
- Bố cục: **65% camera preview** | **35% control panel**
- Control panel gồm:
  - Chip chọn độ phân giải: **360p / 720p / 1080p** (bị khoá khi đang phát)
  - Thẻ RTSP URL + nút Copy
  - Nút **BẮT ĐẦU PHÁT / DỪNG PHÁT**
- Tính năng: lật camera trước/sau, tắt màn hình tiết kiệm pin (auto-dim 30s)
- Badge **LIVE** nhấp nháy khi đang phát

### 4.3 Viewer Screen (Màn hình Xem Camera)
- **Portrait**: danh sách cuộn dọc (LazyColumn), mỗi ô tỉ lệ 16:9
- **Landscape**: lưới 2×2 cố định tỉ lệ 16:9 tổng thể
- **Fullscreen**: xem 1 camera toàn màn hình
- Top bar có: nút Quay lại, toggle **TCP/UDP**, nút quét mạng (có badge đếm)
- Mỗi ô camera (`CameraCell`) hiển thị: tên, FPS thực tế, độ phân giải, nút âm thanh/reload/fullscreen/sửa

---

## 5. Kiến trúc MVVM — Luồng Dữ liệu

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                              │
│  HomeScreen  ──  StreamerScreen  ──  ViewerScreen            │
│      │                │                   │                  │
│  HomeViewModel   StreamerViewModel    ViewerViewModel        │
│      │                │                   │                  │
└──────┼────────────────┼───────────────────┼──────────────────┘
       │                │                   │
       │         ┌──────┴──────┐     ┌──────┴──────┐
       │         │RtspStream   │     │CameraRepo   │
       │         │Service      │     │(DataStore)  │
       │         │(Foreground) │     └─────────────┘
       │         └─────────────┘            │
       │                                    │
       └────────────────────────────────────┘
                   Data Layer
               CameraRepository ── NsdHelper
```

**Cách MVVM hoạt động trong project:**

1. **Model** = `CameraConfig` (data class), `DiscoveredCamera`, `CameraRepository`, `RtspStreamService`
2. **ViewModel** = `HomeViewModel`, `StreamerViewModel`, `ViewerViewModel`
   - Giữ `MutableStateFlow<UiState>` private
   - Expose `StateFlow<UiState>` read-only ra UI
   - Xử lý toàn bộ logic nghiệp vụ
3. **View** = các `@Composable` function
   - Gọi `collectAsStateWithLifecycle()` để observe StateFlow
   - Chỉ gọi hàm trên ViewModel, không tự xử lý logic

---

## 6. Sơ đồ Use Case (User Case Diagram)

```
┌─────────────────────────────────────────────────────────┐
│                    Phone Camera App                      │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │              Actor: Người dùng                   │   │
│  └──────────────────┬───────────────────────────────┘   │
│                     │                                   │
│         ┌───────────┼───────────────┐                   │
│         ▼           ▼               ▼                   │
│   [Chọn vai trò] [Cấp quyền]  [Xem camera]             │
│         │                          │                    │
│    ┌────┴─────┐              ┌─────┴──────┐             │
│    ▼          ▼              ▼            ▼             │
│ [Phát RTSP] [Xem]  [Thêm camera] [Quét mạng tự động]  │
│    │                    │                │              │
│    ├─[Chọn độ phân giải] ├─[Nhập thủ công] ├─[Thêm 1 chạm]│
│    ├─[Lật camera]        └─[Xóa camera]                │
│    ├─[Dừng phát]                                       │
│    └─[Tắt màn hình]                                    │
└─────────────────────────────────────────────────────────┘
```

---

## 7. Sơ đồ Lớp (Class Diagram)

```
┌──────────────────┐       ┌──────────────────────────┐
│   CameraConfig   │       │      DiscoveredCamera     │
│──────────────────│       │──────────────────────────│
│ id: Int          │       │ serviceId: String         │
│ name: String     │       │ displayName: String       │
│ host: String     │       │ host: String              │
│ port: Int        │       │ port: Int                 │
│ username: String │       │ rtspUrl: String (get)     │
│ password: String │       └──────────────────────────┘
│──────────────────│                   ▲
│ toRtspUrl(): Str │                   │ produces
└────────┬─────────┘           ┌───────┴──────┐
         │ stored by           │   NsdHelper   │
         ▼                     │──────────────│
┌──────────────────┐           │ registerSvc() │
│ CameraRepository │           │ discoverSvc() │
│──────────────────│           │ stopAll()     │
│ camerasFlow: Flow│           └──────────────┘
│ saveCamera()     │                   ▲
│ deleteCamera()   │                   │ uses
└──────────────────┘          ┌────────┴─────────┐
         ▲                    │  ViewerViewModel  │
         │ uses               │──────────────────│
         │           ┌────────│ uiState: StateFlow│
         │           │        │ saveCamera()      │
         │           │        │ deleteCamera()    │
         │           │        │ retryCamera()     │
         │           │        │ toggleTcp()       │
         │           │        │ toggleAudio()     │
         │           │        │ startDiscovery()  │
         │           │        └──────────────────┘
         │           │                 ▲ observes
         │           │        ┌────────┴────────┐
         │           │        │  ViewerScreen   │
         │           │        │ (Composable)    │
         │           │        │─────────────────│
         │           │        │ CameraCell x4   │
         │           │        │ AddEditDialog   │
         │           │        │ DiscoverySheet  │
         │           │        └─────────────────┘
         │           │
         │    ┌──────┴──────────────┐
         │    │  StreamerViewModel  │
         │    │─────────────────────│
         │    │ uiState: StateFlow  │
         │    │ attachCameraToSvc() │
         │    │ startStream()       │
         │    │ stopStream()        │
         │    │ switchCamera()      │
         │    │ selectResolution()  │
         │    └─────────────────────┘
         │              │ binds to
         │    ┌──────────┴──────────┐
         │    │  RtspStreamService  │ ◄── Foreground Service
         │    │─────────────────────│
         │    │ attachCamera()      │
         │    │ startStreaming()    │
         │    │ stopStreaming()     │
         │    │ switchCamera()      │
         │    │ isStreaming: Bool   │
         │    │─────────────────────│
         │    │ uses NsdHelper      │
         │    │ uses RtspServerCam2 │
         │    └─────────────────────┘

┌──────────────────┐
│  HomeViewModel   │
│──────────────────│
│ uiState: Flow    │
│ onPermissions()  │
└──────────────────┘
         ▲ observes
┌────────┴─────────┐
│   HomeScreen     │
│──────────────────│
│ RoleCard x2      │
│ PermissionBanner │
└──────────────────┘

sealed class PlayerState:
  Idle | Loading(attemptId) | Playing | Error(msg)

sealed class StreamResult:
  Success | Error(reason)

sealed class Screen:
  Home | Streamer | Viewer
```

---

## 8. Sơ đồ Tuần tự — Luồng Phát Camera (Streamer)

```
User       StreamerScreen    StreamerViewModel    RtspStreamService    NsdHelper
 │               │                  │                   │                 │
 │──[mở màn]──►│                  │                   │                 │
 │               │──[init VM]──────►│                   │                 │
 │               │                  │──[startService]──►│                 │
 │               │                  │──[bindService]───►│                 │
 │               │                  │◄─[onConnected]────│                 │
 │               │                  │                   │                 │
 │               │──[OpenGlView ready]                  │                 │
 │               │──[attachCameraToService(glView)]─────►│                │
 │               │                  │──[attachCamera()]─►│               │
 │               │                  │                   │──[RtspCam2()]  │
 │               │                  │                   │──[startPreview]│
 │               │                  │◄─[isCameraReady=true]              │
 │               │◄─[UI update]─────│                   │                 │
 │               │                  │                   │                 │
 │──[bấm PHÁT]─►│                  │                   │                 │
 │               │──[startStream()]─►│                  │                 │
 │               │                  │──[startStreaming()]►│              │
 │               │                  │                   │──[prepareVideo]│
 │               │                  │                   │──[prepareAudio]│
 │               │                  │                   │──[startStream] │
 │               │                  │                   │──[registerSvc]─►│
 │               │                  │◄─[Success]────────│                 │
 │               │◄─[isStreaming=true]                  │                 │
 │               │──[hiện LIVE badge]                   │                 │
 │               │                  │                   │                 │
 │──[bấm DỪNG]─►│                  │                   │                 │
 │               │──[stopStream()]──►│                  │                 │
 │               │                  │──[stopStreaming()]─►│              │
 │               │                  │                   │──[unregister]──►│
 │               │◄─[isStreaming=false]                 │                 │
```

---

## 9. Sơ đồ Tuần tự — Luồng Xem Camera (Viewer)

```
User        ViewerScreen     ViewerViewModel    NsdHelper     CameraCell/ExoPlayer
 │               │                 │                │                │
 │──[mở màn]──►│                 │                │                │
 │               │──[init VM]─────►│                │                │
 │               │                 │──[startDiscovery()]─►│         │
 │               │                 │                │──[mDNS scan]   │
 │               │                 │◄──[onFound(cam)]────│           │
 │               │◄──[UI update]───│                │                │
 │               │                 │                │                │
 │──[bấm + thêm]►│                │                │                │
 │               │──[dialogSlot=0] │                │                │
 │──[nhập IP/Port─►AddEditDialog] │                │                │
 │──[bấm Lưu]──►│                 │                │                │
 │               │──[saveCamera()]─►│               │                │
 │               │                 │──[repo.save()]  │                │
 │               │                 │──[setPlayerState: Loading]       │
 │               │◄──[uiState update]               │                │
 │               │                 │                │                │
 │               │──[CameraCell gets Loading state]─────────────────►│
 │               │                 │                │──[ExoPlayer create]
 │               │                 │                │──[RtspMediaSource]
 │               │                 │                │──[prepare()+play]
 │               │                 │                │◄──[STATE_READY] │
 │               │                 │◄──[onPlayerReady()]──────────────│
 │               │                 │──[setPlayerState: Playing]       │
 │               │◄──[uiState: Playing]             │                │
 │               │──[ẩn spinner, hiện video]         │                │
```

---

## 10. Sơ đồ Tuần tự — Tự động phát hiện Camera (mDNS/NSD)

```
Phone Quay (Streamer)          Mạng LAN (mDNS)        Phone Xem (Viewer)
        │                           │                        │
        │──[startStreaming()]        │                        │
        │──[NsdHelper.registerService(port=8080)]            │
        │                           │◄──[quảng bá _rtspguard._tcp]
        │                           │                        │
        │                           │         [ViewerVM.startDiscovery()]
        │                           │         [NsdHelper.discoverServices()]
        │                           │──[onServiceFound]─────►│
        │                           │──[resolveService]──────►│
        │                           │──[onServiceResolved: ip, port]
        │                           │──[DiscoveredCamera]────►│
        │                           │         [update discoveredCameras list]
        │                           │         [badge count++]
        │                           │                        │
        │──[stopStreaming()]         │                        │
        │──[NsdHelper.unregisterService()]                   │
        │                           │──[onServiceLost]───────►│
        │                           │         [PlayerState.Error("mất kết nối")]
```

---

## 11. Vòng đời & Quản lý Tài nguyên

### RtspStreamService (Foreground Service)
```
App mở Streamer Screen
    └──► startService() + bindService()
         └──► onCreate() → startForeground(notification)
              └──► attachCamera(OpenGlView) → RtspServerCamera2
                   └──► startStreaming() → RTSP server lắng nghe cổng 8080
                        └──► NsdHelper.registerService(8080)

App đóng / ViewModel cleared
    └──► unbindService()
         (Service vẫn chạy vì startForeground)

User bấm "Dừng" hoặc nhấn nút notification
    └──► stopStreaming() + stopSelf()
         └──► onDestroy() → NsdHelper.stopAll()
```

### ExoPlayer (trong CameraCell)
```
playerState = Loading
    └──► rememberLowLatencyExoPlayer() tạo ExoPlayer mới
         └──► delay(500ms) → RtspMediaSource → prepare() → playWhenReady=true
              └──► onPlaybackStateChanged(STATE_READY) → onPlayerReady()
                   └──► playerState = Playing (spinner ẩn, video hiện)

playerState thay đổi URL hoặc useTcp
    └──► DisposableEffect kích hoạt lại
         └──► player cũ bị release()
              └──► player mới được tạo

Composable bị xóa khỏi composition
    └──► onDispose { player.release() }
```

---

## 12. Quyền Hệ thống Yêu cầu

| Quyền | Lý do |
|---|---|
| `CAMERA` | Quay video để phát RTSP |
| `RECORD_AUDIO` | Thu âm thanh trong luồng RTSP |
| `INTERNET` | Kết nối mạng nội bộ |
| `ACCESS_WIFI_STATE` | Lấy địa chỉ IP WiFi hiện tại |
| `CHANGE_WIFI_MULTICAST_STATE` | Cho phép nhận gói mDNS multicast |
| `FOREGROUND_SERVICE` | Chạy service phát camera ngầm |
| `FOREGROUND_SERVICE_CAMERA` | Loại service camera (API 34+) |
| `FOREGROUND_SERVICE_MICROPHONE` | Loại service mic (API 34+) |
| `POST_NOTIFICATIONS` | Hiện notification "đang phát" (API 33+) |
| `WAKE_LOCK` | Giữ CPU thức khi đang stream |

---

## 13. Luồng Code Chạy Khi Khởi động App

```
1. OS → MainActivity.onCreate()
2.   └──► enableEdgeToEdge()
3.   └──► setContent { PhoneCameraTheme { ... } }
4.        └──► NavHost(startDestination = "home")
5.             └──► HomeScreen composable hiển thị
6.                  └──► HomeViewModel khởi tạo (do viewModel())
7.                  └──► LaunchedEffect kiểm tra permission hiện tại
8.                  └──► collectAsStateWithLifecycle() → UI cập nhật

Người dùng chọn Máy Quay:
9.   └──► navController.navigate("streamer")
10.       └──► StreamerScreen composable
11.            └──► StreamerViewModel.init():
12.                 ├──► loadLocalIp() → WifiManager → lấy IP
13.                 └──► bindToService() → startService + bindService
14.            └──► AndroidView { OpenGlView }
15.                 └──► glView.post { viewModel.attachCameraToService(glView) }
16.                      └──► RtspStreamService.attachCamera() → startPreview()

Người dùng chọn Màn hình Xem:
9.   └──► navController.navigate("viewer")
10.       └──► ViewerScreen composable
11.            └──► ViewerViewModel.init():
12.                 ├──► repository.camerasFlow.collect → load saved cameras
13.                 └──► startDiscovery() → NsdHelper.discoverServices()
14.            └──► 4 CameraCell được render
15.                 └──► CameraCell với config → ExoPlayer tạo và kết nối RTSP
```

---

## 14. Ghi chú cho Lập trình viên Mới

> **StateFlow vs LiveData**: Project dùng `StateFlow` (Kotlin Coroutines) thay vì `LiveData` (Java-based). `StateFlow` tích hợp tốt hơn với Compose và Coroutines.

> **`collectAsStateWithLifecycle()`**: Đây là cách đúng để observe StateFlow trong Compose — nó tự động ngừng collect khi app vào background để tiết kiệm pin.

> **`DisposableEffect`**: Dùng khi cần thực hiện cleanup (dọn dẹp) khi Composable bị xóa khỏi màn hình (ví dụ: release ExoPlayer, stop NSD).

> **`AndroidView`**: Dùng để nhúng View truyền thống (OpenGlView, PlayerView) vào trong Compose. Đây là cầu nối giữa hệ thống View cũ và Compose mới.

> **Sealed Class**: Thay vì dùng `String` hay `Int` để biểu diễn trạng thái, project dùng sealed class (`PlayerState`, `StreamResult`) — an toàn hơn và compiler sẽ báo lỗi nếu bạn quên xử lý một trạng thái.

---

*Tài liệu được tạo tự động từ source code — cập nhật lần cuối: 2026-04-23*
