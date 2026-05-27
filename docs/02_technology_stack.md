# 🛠️ Technology Stack — PhoneCamera

## Build Configuration

| Thông số | Giá trị |
|----------|---------|
| **compileSdk** | 36 (Android 16) |
| **minSdk** | 26 (Android 8.0 Oreo) |
| **targetSdk** | 36 |
| **Language** | Kotlin |
| **Build System** | Gradle Kotlin DSL (`.kts`) |

---

## Dependencies Chi Tiết

### 🏗️ Core Android

| Thư viện | Version | Mục đích trong project |
|----------|---------|------------------------|
| `androidx.core.ktx` | BOM | Extension functions cho Android APIs. VD: `getSystemService<NsdManager>()` |
| `androidx.lifecycle.runtime.ktx` | BOM | Coroutines + Lifecycle awareness |
| `androidx.activity.compose` | BOM | `setContent {}` và `ComponentActivity` |

### 🎨 Jetpack Compose (UI Framework)

| Thư viện | Mục đích |
|----------|---------|
| `compose.bom` | Bill of Materials — đảm bảo tất cả Compose libraries dùng cùng version |
| `compose.ui` | Core Composable engine, Layout system |
| `compose.ui.graphics` | Canvas, Brush, Color, gradient |
| `compose.ui.tooling.preview` | `@Preview` annotation cho Android Studio |
| `compose.material3` | Component library: Button, Card, Scaffold, SnackBar... |
| `compose.material.icons.extended` | 2400+ icon từ Material Design (Icons.Filled.Camera...) |

> **Tại sao Compose thay vì XML?** Compose là declarative UI — mô tả UI dựa trên state, không cần `findViewById()`, không cần `setText()` thủ công. State thay đổi → UI tự recompose.

### 🧭 Navigation

| Thư viện | Version | Mục đích |
|----------|---------|---------|
| `navigation-compose` | 2.8.5 | Type-safe navigation giữa 3 màn hình dùng `@Serializable` sealed interface |

```kotlin
// Sử dụng type-safe navigation — không dùng String route mà dùng object
composable<Screen.Streamer> { StreamerScreen(...) }
navController.navigate(Screen.Viewer)  // compile-time safe
```

### ⚡ Lifecycle & ViewModel

| Thư viện | Version | Mục đích |
|----------|---------|---------|
| `lifecycle-viewmodel-compose` | 2.8.7 | `viewModel()` composable, ViewModel survive config change |
| `lifecycle-runtime-compose` | 2.8.7 | `collectAsStateWithLifecycle()` — auto pause collecting khi app vào background |

### 🔐 Permissions

| Thư viện | Version | Mục đích |
|----------|---------|---------|
| `accompanist-permissions` | 0.36.0 | Jetpack Compose API cho runtime permissions |

```kotlin
// Accompanist cho phép handle permission trong Composable
val permissionState = rememberPermissionState(Manifest.permission.CAMERA)
```

### 💾 DataStore & Serialization

| Thư viện | Version | Mục đích |
|----------|---------|---------|
| `datastore-preferences` | 1.1.1 | Lưu danh sách camera và PIN vào disk (thay thế SharedPreferences) |
| `kotlinx.serialization.json` | BOM | Serialize `List<CameraConfig>` sang JSON để lưu vào DataStore |

```kotlin
// DataStore + Serialization kết hợp:
@Serializable
data class CameraConfig(val id: Int, val name: String, val host: String, val port: Int)

context.dataStore.edit { prefs ->
    prefs[CAMERAS_KEY] = Json.encodeToString<List<CameraConfig>>(list)
}
```

### 📡 RTSP Streaming (Streamer side)

| Thư viện | Version | Mục đích |
|----------|---------|---------|
| `RTSP-Server:1.4.1` (pedroSG94) | 1.4.1 | **Server RTSP** — Cung cấp `RtspServerCamera2` để phát stream từ camera |
| `RootEncoder:library` (pedroSG94) | 2.7.2 | **Base classes** — `Camera2Base`, `ConnectChecker`, encoder H.264/AAC |

> **Lưu ý quan trọng**: Hai thư viện này từ cùng tác giả nhưng dùng KHÁC interface nên không xung đột. `RTSP-Server` cung cấp `RtspServerCamera2` (subclass), `RootEncoder` cung cấp các class base và encoder.

```kotlin
// Khởi tạo RTSP Server
rtspCamera = RtspServerCamera2(glView, connectChecker, RTSP_PORT).also {
    it.startPreview(facing, res.width, res.height)
}
// Bắt đầu stream
cam.prepareVideo(width, height, fps, bitrate, rotation)
cam.prepareAudio()
cam.startStream()
```

### 📺 ExoPlayer (Viewer side)

| Thư viện | Version | Mục đích |
|----------|---------|---------|
| `media3-exoplayer` | 1.5.0 | Media player core của Google |
| `media3-exoplayer-rtsp` | 1.5.0 | Extension để ExoPlayer hiểu giao thức RTSP |
| `media3-ui` | 1.5.0 | `PlayerView` — UI widget để render video |

```kotlin
// Viewer tạo ExoPlayer với RTSP source
val player = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
val source = RtspMediaSource.Factory()
    .setForceUseRtpTcp(useTcp)
    .createMediaSource(MediaItem.fromUri("rtsp://192.168.1.x:8080"))
player.setMediaSource(source)
player.prepare()
player.playWhenReady = true
```

---

## Architecture Stack (tóm tắt)

```
┌─────────────────────────────────┐
│     Jetpack Compose UI          │  ← StreamerScreen, ViewerScreen, HomeScreen
├─────────────────────────────────┤
│     ViewModel (MVVM)            │  ← StreamerViewModel, ViewerViewModel
├─────────────────────────────────┤
│     Repository Pattern          │  ← CameraRepository (DataStore)
├─────────────────────────────────┤
│     Network Layer               │  ← NsdHelper, CameraControlClient
├─────────────────────────────────┤
│     External Libraries          │  ← RtspServerCamera2, ExoPlayer
└─────────────────────────────────┘
```
