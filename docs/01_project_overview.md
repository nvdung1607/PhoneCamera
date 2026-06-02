# 📱 Project Overview — PhoneCamera

## Mục đích ứng dụng

**PhoneCamera** là ứng dụng Android biến điện thoại thành **camera IP không dây**. Một điện thoại phát stream video qua giao thức **RTSP**, các điện thoại khác kết nối vào để xem — tất cả hoạt động trong cùng mạng LAN (WiFi nội bộ), không cần server bên ngoài.

## Tính năng chính

| Tính năng | Mô tả |
|-----------|-------|
| **Streamer mode** | Điện thoại A phát camera (front/back) qua RTSP port 8080 |
| **Viewer mode** | Điện thoại B xem tối đa 4 camera đồng thời (grid layout) |
| **Auto-discovery** | Tự động tìm camera trong LAN qua NSD/mDNS (không cần nhập IP thủ công) |
| **Remote control** | Viewer có thể ra lệnh đổi độ phân giải/FPS từ xa cho Streamer |
| **PIN Authentication** | Stream được bảo vệ bằng mã PIN 4 chữ số tự sinh |
| **Multi-resolution** | Hỗ trợ 360p / 720p / 1080p, FPS: 15 / 24 / 30 |
| **Auto-reconnect** | Viewer tự động thử lại khi mất kết nối (max 5 lần) |
| **Persistent config** | Lưu danh sách camera vào DataStore (tồn tại sau khi tắt app) |

## Target Users

- Developer cần camera ngoài để test ứng dụng
- Người dùng muốn dùng điện thoại cũ làm camera giám sát nội bộ
- Homelab / DIY security camera

## Cấu trúc thư mục

```
phoneCamera/
├── app/
│   ├── build.gradle.kts          ← Dependencies & build config
│   └── src/main/
│       ├── AndroidManifest.xml   ← Permissions & Activity khai báo
│       └── java/com/example/phonecamera/
│           ├── MainActivity.kt           ← Entry point, Navigation setup
│           ├── navigation/
│           │   └── Screen.kt             ← Định nghĩa 3 màn hình (sealed interface)
│           ├── home/
│           │   ├── HomeScreen.kt         ← Màn hình chọn vai trò
│           │   └── HomeViewModel.kt      ← Quản lý permission state
│           ├── streamer/
│           │   ├── StreamerScreen.kt     ← UI phát camera
│           │   └── StreamerViewModel.kt  ← Logic phát RTSP + NSD register
│           ├── viewer/
│           │   ├── ViewerScreen.kt       ← UI xem camera (grid 4 slot)
│           │   ├── ViewerViewModel.kt    ← Logic kết nối RTSP + NSD discover
│           │   └── components/           ← Các Composable con
│           ├── data/
│           │   └── CameraRepository.kt   ← Đọc/ghi config camera từ DataStore
│           ├── network/                  ← Toàn bộ giao tiếp mạng tập trung tại đây
│           │   ├── NsdHelper.kt          ← Wrapper NsdManager (register/discover)
│           │   ├── DiscoveredCamera.kt   ← Data model camera tìm qua NSD
│           │   ├── ControlServer.kt      ← TCP server nhận lệnh từ Viewer
│           │   └── CameraControlClient.kt ← TCP client gửi lệnh đến Streamer
│           ├── ui/theme/                 ← Design system (Color, Type, Theme)
│           └── utils/
│               └── AppLog.kt             ← Logging utility
```

## Ports được dùng

| Port | Giao thức | Mục đích |
|------|-----------|----------|
| **8080** | RTSP (TCP/UDP) | Video stream từ Streamer |
| **8081** | TCP text-based | Control commands (HELLO/BYE/SET_QUALITY/SET_FPS) |
