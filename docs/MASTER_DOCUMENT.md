# 📚 MASTER DOCUMENT — PhoneCamera

> Tài liệu tổng hợp đầy đủ về project. Đọc tài liệu này trước, sau đó xem các file chi tiết tương ứng.

---

## 1. Project Overview

**PhoneCamera** là app Android biến điện thoại thành **IP camera không dây trong mạng LAN**.

- **Streamer phone** → phát camera qua giao thức **RTSP** (port 8080)
- **Viewer phone** → kết nối RTSP stream, xem tối đa 4 camera đồng thời
- **Tự động tìm nhau** qua **mDNS/NSD** — không cần nhập IP thủ công
- **Bảo mật** bằng PIN 4 chữ số tự sinh, lưu persistent qua DataStore
- **Remote control**: Viewer có thể ra lệnh đổi chất lượng/FPS cho Streamer qua TCP

---

## 2. Technology Stack (Tóm tắt)

| Nhóm | Công nghệ | Mục đích |
|------|-----------|---------|
| **UI** | Jetpack Compose + Material3 | Declarative UI — không XML |
| **Navigation** | Navigation Compose 2.8.5 | Type-safe navigation 3 màn hình |
| **Architecture** | MVVM + Repository | Tách UI/logic/data |
| **State management** | StateFlow + Coroutines | Reactive state, lifecycle-aware |
| **Persistence** | DataStore Preferences | Lưu camera list + PIN vào disk |
| **Serialization** | kotlinx.serialization.json | JSON encode/decode CameraConfig |
| **Camera (Streamer)** | pedroSG94/RTSP-Server + RootEncoder | H.264 encoder + RTSP server |
| **Player (Viewer)** | ExoPlayer (media3) | RTSP client + video decode |
| **Discovery** | Android NsdManager (mDNS) | Auto-discover cameras in LAN |
| **Control channel** | Raw TCP + custom text protocol | Remote commands port 8081 |
| **Permissions** | Accompanist Permissions | Runtime permission Compose API |

---

## 3. Architecture (Kiến trúc)

```
┌─────────────────────────────────────────────────────────────────┐
│                    PRESENTATION LAYER                            │
│  MainActivity (NavHost)                                          │
│  ├── HomeScreen (@Composable)                                    │
│  ├── StreamerScreen (@Composable)                                │
│  └── ViewerScreen (@Composable)                                  │
├─────────────────────────────────────────────────────────────────┤
│                    VIEWMODEL LAYER                               │
│  HomeViewModel    StreamerViewModel    ViewerViewModel           │
│  (StateFlow)      (StateFlow)          (StateFlow)               │
├─────────────────────────────────────────────────────────────────┤
│                    DATA LAYER                                    │
│  CameraRepository    NsdHelper    ControlServer/Client           │
│  (DataStore)         (mDNS)       (TCP port 8081)                │
├─────────────────────────────────────────────────────────────────┤
│                    EXTERNAL LAYER                                │
│  RtspServerCamera2   ExoPlayer    DataStore    Android NSD       │
│  (Camera2 + RTSP)    (RTSP play)  (disk)       (OS service)      │
└─────────────────────────────────────────────────────────────────┘
```

**Chi tiết: [03_diagram_architecture.md](03_diagram_architecture.md)**

---

## 4. Module Breakdown (Từng Package)

| Package | Files | Vai trò |
|---------|-------|---------|
| `(root)` | `MainActivity.kt` | Entry point, Navigation setup, NavHost |
| `navigation/` | `Screen.kt` | Định nghĩa 3 route dưới dạng sealed interface |
| `home/` | `HomeScreen.kt`, `HomeViewModel.kt` | Màn hình chọn Streamer/Viewer, permission state |
| `streamer/` | `StreamerScreen.kt`, `StreamerViewModel.kt` | Toàn bộ UI và logic phát RTSP stream |
| `viewer/` | `ViewerScreen.kt`, `ViewerViewModel.kt`, `components/` | Xem camera, manage ExoPlayers, remote control |
| `data/` | `CameraRepository.kt` | Đọc/ghi danh sách camera vào DataStore |
| `network/` | `NsdHelper.kt`, `DiscoveredCamera.kt`, `ControlServer.kt`, `CameraControlClient.kt` | Toàn bộ giao tiếp mạng: mDNS + TCP server/client |
| `ui/theme/` | `Color.kt`, `Theme.kt`, `Type.kt` | Design system: colors, typography |
| `utils/` | `AppLog.kt` | Centralized logging |

---

## 5. Key Components Chi Tiết

### `RtspServerCamera2` (từ thư viện pedroSG94)
- **Là gì**: Server RTSP hoàn chỉnh tích hợp với Camera2 API của Android
- **Pipeline**: Camera2 → H.264/AAC MediaCodec encoder → RTP packetizer → RTSP server
- **Trong code**: Được tạo trong `StreamerViewModel.attachCamera()`, start/stop stream trong `startStream()`/`stopStream()`

### `ExoPlayer` với `RtspMediaSource` (media3)
- **Là gì**: Player của Google, support RTSP qua extension media3-exoplayer-rtsp
- **Trong code**: `ViewerViewModel` quản lý map `activePlayers: Map<Int, ExoPlayer>` — mỗi slot 1 player
- **Buffer config**: `setBufferDurationsMs(2500, 10000, 1000, 1500)` — tối ưu cho low-latency live stream

### `NsdHelper`
- **Là gì**: Wrapper của Android `NsdManager` — Android's mDNS implementation
- **Service type**: `"_rtspguard._tcp."` — custom service type
- **Streamer**: gọi `registerService(8080)` sau khi stream start → broadcast tên thiết bị (Build.MODEL)
- **Viewer**: gọi `discoverServices()` liên tục → callback khi tìm thấy/mất camera

### `ControlServer` + `CameraControlClient`
- **Là gì**: Custom TCP protocol (text-based) để Viewer điều khiển Streamer
- **Protocol**: Mỗi lệnh là 1 dòng text, response là "OK" hoặc "ERROR ..."
- **Commands**: `HELLO name pin`, `BYE name pin`, `SET_QUALITY 720 pin`, `SET_FPS 30 pin`
- **Authentication**: PIN được kiểm tra trong mỗi command

---

## 6. Network Communication

### Ports

| Port | Giao thức | Chiều | Mục đích |
|------|-----------|-------|---------|
| **8080** | RTSP (TCP control + UDP/TCP data) | Viewer → Streamer | Video stream |
| **8081** | TCP text | Viewer → Streamer | Control commands |
| **5353** | UDP Multicast | Broadcast | mDNS discovery |

### RTSP URL Format
```
rtsp://admin:PIN@host:8080
```

### Control Protocol
```
HELLO {deviceName} {pin}     → Response: "OK" | "ERROR invalid PIN"
BYE {deviceName} {pin}       → Response: "OK"
SET_QUALITY {heightP} {pin}  → Response: "OK" | "ERROR ..."
SET_FPS {fps} {pin}          → Response: "OK" | "ERROR ..."
```

---

## 7. How to Read the Code (Thứ tự đọc gợi ý)

```
1. Screen.kt              → Hiểu 3 màn hình của app
2. MainActivity.kt        → Hiểu cách Navigation setup
3. HomeScreen.kt          → Xem cách Compose screen đơn giản
4. HomeViewModel.kt       → Xem ViewModel đơn giản nhất
5. CameraRepository.kt    → Hiểu DataStore + Serialization
6. DiscoveredCamera.kt    → Data model đơn giản
7. NsdHelper.kt           → Hiểu NSD/mDNS
8. ControlServer.kt       → Hiểu TCP server
9. CameraControlClient.kt → Hiểu TCP client
10. StreamerViewModel.kt  → Core logic phát stream (phức tạp nhất Streamer)
11. StreamerScreen.kt     → UI phức tạp của Streamer
12. ViewerViewModel.kt    → Core logic xem (phức tạp nhất Viewer)
13. ViewerScreen.kt       → UI grid 4 slot
```

---

## 8. Glossary (Từ điển thuật ngữ)

| Thuật ngữ | Giải thích |
|-----------|-----------|
| **RTSP** | Real Time Streaming Protocol — giao thức điều khiển stream video (RFC 2326) |
| **RTP** | Real-time Transport Protocol — giao thức truyền data video/audio thực tế |
| **mDNS** | Multicast DNS — DNS không cần server, hoạt động trong LAN (RFC 6762) |
| **NSD** | Network Service Discovery — Android API bao bọc mDNS |
| **DNS-SD** | DNS Service Discovery — cơ chế dùng DNS để announce services (RFC 6763) |
| **H.264** | Video codec nén (cũng gọi là AVC) — được dùng để encode camera frames |
| **AAC** | Audio codec — encode microphone audio |
| **MediaCodec** | Android hardware encoder/decoder API — được pedroSG94 dùng bên dưới |
| **DataStore** | Library của Google thay SharedPreferences — coroutine-based, type-safe |
| **StateFlow** | Kotlin coroutines hot flow — phát current state đến tất cả collectors |
| **Recomposition** | Jetpack Compose re-render UI khi state thay đổi |
| **ViewModel** | Architecture component — survive config changes (rotation), outlive Activity |
| **MVVM** | Model-View-ViewModel — tách UI (View) khỏi logic (ViewModel) khỏi data (Model) |
| **OpenGlView** | Surface dùng OpenGL ES để render camera preview (từ RootEncoder) |
| **ConnectChecker** | Interface của RootEncoder — callback khi viewer kết nối/ngắt kết nối |
| **sealed interface/class** | Kotlin type — tập hợp đóng các subtype, dùng cho exhaustive when |
| **collectAsStateWithLifecycle** | Extension Compose — collect Flow và auto-stop khi app vào background |
| **DefaultLoadControl** | ExoPlayer buffer control — config thời gian buffer trước khi play |

---

## 9. Liên kết tài liệu

| File | Nội dung |
|------|---------|
| [01_project_overview.md](01_project_overview.md) | Tổng quan, tính năng, cấu trúc thư mục |
| [02_technology_stack.md](02_technology_stack.md) | Chi tiết từng dependency |
| [03_diagram_architecture.md](03_diagram_architecture.md) | Kiến trúc MVVM, layer diagram |
| [04_diagram_class_uml.md](04_diagram_class_uml.md) | Class diagram UML đầy đủ |
| [05_diagram_sequence.md](05_diagram_sequence.md) | 5 Sequence diagrams (app launch, stream, viewer, remote control, cleanup) |
| [06_diagram_data_flow.md](06_diagram_data_flow.md) | Data flow: camera frames, control commands, DataStore |
| [07_diagram_state.md](07_diagram_state.md) | 5 State diagrams (Streamer, Player/slot, NSD, Navigation, ControlServer) |
| [08_diagram_component.md](08_diagram_component.md) | Component interaction giữa 2 điện thoại, protocol stack |
| [09_diagram_navigation.md](09_diagram_navigation.md) | Navigation graph, back stack, lifecycle effects |
| [10_code_trace_user_journey.md](10_code_trace_user_journey.md) | Full call trace Streamer & Viewer journey |
