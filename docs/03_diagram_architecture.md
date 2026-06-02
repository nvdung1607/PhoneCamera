# 🏗️ Architecture Diagram — PhoneCamera

## Kiến trúc tổng thể

Project tuân theo **MVVM (Model-View-ViewModel)** kết hợp **Repository Pattern**, chia thành 3 layer rõ ràng.

---

## Architecture Overview Diagram

```mermaid
graph TB
    subgraph "📱 Presentation Layer (UI)"
        MA["MainActivity\n(Navigation Host)"]
        HS["HomeScreen\n@Composable"]
        SS["StreamerScreen\n@Composable"]
        VS["ViewerScreen\n@Composable"]
    end

    subgraph "🧠 ViewModel Layer"
        HVM["HomeViewModel\n(permission state)"]
        SVM["StreamerViewModel\n(stream control)"]
        VVM["ViewerViewModel\n(player management)"]
    end

    subgraph "🌐 Network Layer"
        NSD1["NsdHelper\n(register service)"]
        NSD2["NsdHelper\n(discover services)"]
        CS["ControlServer\n(TCP port 8081)"]
        CC["CameraControlClient\n(TCP client)"]
    end

    subgraph "💾 Data Layer"
        CR["CameraRepository\n(DataStore persistence)"]
    end

    subgraph "📡 External / Hardware"
        CAM["Camera2 Hardware\n(front/back)"]
        RTSP["RtspServerCamera2\n(RTSP port 8080)"]
        EXO["ExoPlayer\n(RTSP playback)"]
        DS["DataStore\n(disk storage)"]
        MDNS["mDNS / NSD\n(Android OS)"]
    end

    MA --> HS
    MA --> SS
    MA --> VS

    HS <-->|"collectAsStateWithLifecycle"| HVM
    SS <-->|"collectAsStateWithLifecycle"| SVM
    VS <-->|"collectAsStateWithLifecycle"| VVM

    SVM --> CR
    SVM --> NSD1
    SVM --> CS
    SVM --> RTSP
    RTSP --> CAM

    VVM --> CR
    VVM --> NSD2
    VVM --> CC
    VVM --> EXO

    CR --> DS
    NSD1 --> MDNS
    NSD2 --> MDNS

    style MA fill:#4A90D9,color:#fff
    style HS fill:#4A90D9,color:#fff
    style SS fill:#4A90D9,color:#fff
    style VS fill:#4A90D9,color:#fff
    style HVM fill:#7B68EE,color:#fff
    style SVM fill:#7B68EE,color:#fff
    style VVM fill:#7B68EE,color:#fff
    style CR fill:#50C878,color:#fff
    style NSD1 fill:#FF8C00,color:#fff
    style NSD2 fill:#FF8C00,color:#fff
    style CS fill:#FF8C00,color:#fff
    style CC fill:#FF8C00,color:#fff
```

---

## Layer-by-Layer Breakdown

### 1. Presentation Layer (UI)

**Công nghệ**: Jetpack Compose

| Component | File | Vai trò |
|-----------|------|---------|
| `MainActivity` | `MainActivity.kt` | Single Activity — setup `NavHost`, giữ toàn bộ app trong 1 Activity |
| `HomeScreen` | `HomeScreen.kt` | Màn hình chọn vai trò (Streamer / Viewer), xin permissions |
| `StreamerScreen` | `StreamerScreen.kt` | UI full-screen phát camera — camera preview 65%, control panel 35% |
| `ViewerScreen` | `ViewerScreen.kt` | UI xem camera — grid 4 slot, discovery panel |

**Pattern**: Mỗi Screen nhận ViewModel qua `viewModel()` composable, observe state qua `collectAsStateWithLifecycle()`, và gửi event qua ViewModel methods.

```kotlin
// Cách Screen và ViewModel kết nối
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
// UI tự recompose khi uiState thay đổi
```

### 2. ViewModel Layer

**Công nghệ**: AndroidViewModel + StateFlow + Coroutines

| ViewModel | UiState | Vai trò chính |
|-----------|---------|---------------|
| `HomeViewModel` | `HomeUiState` | Track camera/audio permission state |
| `StreamerViewModel` | `StreamerUiState` | Quản lý RTSP server, NSD registration, ControlServer |
| `ViewerViewModel` | `ViewerUiState` | Quản lý 4 ExoPlayer instances, NSD discovery, remote control |

**Pattern**: State được tập trung trong `MutableStateFlow`, chỉ ViewModel mới modify được. UI chỉ đọc `StateFlow` (immutable).

```kotlin
private val _uiState = MutableStateFlow(StreamerUiState())
val uiState: StateFlow<StreamerUiState> = _uiState.asStateFlow() // UI nhận cái này
// Chỉ trong ViewModel mới gọi:
_uiState.update { it.copy(isStreaming = true) }
```

### 3. Data Layer

**Công nghệ**: DataStore, NsdManager (Android), TCP Sockets

| Component | Package | Vai trò |
|-----------|---------|---------|
| `CameraRepository` | `data/` | Single source of truth cho danh sách camera — đọc/ghi DataStore |
| `NsdHelper` | `network/` | Wrapper NsdManager — register service (Streamer) và discover services (Viewer) |
| `ControlServer` | `network/` | TCP server trên port 8081 — nhận text commands từ Viewer |
| `CameraControlClient` | `network/` | TCP client — gửi commands HELLO/BYE/SET_QUALITY/SET_FPS đến Streamer |

---

## Dependency Direction

```mermaid
graph LR
    UI["UI (Screen)"] -->|"depends on"| VM["ViewModel"]
    VM -->|"depends on"| REPO["Repository"]
    VM -->|"depends on"| NSD["NsdHelper"]
    VM -->|"depends on"| NET["Network (TCP)"]
    VM -->|"depends on"| RTSP["RtspServer / ExoPlayer"]
    REPO -->|"depends on"| DS["DataStore"]
    NSD -->|"depends on"| OS["Android NSD OS Service"]

    style UI fill:#4A90D9,color:#fff
    style VM fill:#7B68EE,color:#fff
    style REPO fill:#50C878,color:#fff
    style NSD fill:#50C878,color:#fff
    style NET fill:#50C878,color:#fff
```

> **Dependency Rule**: Các mũi tên chỉ đi một chiều — UI không biết gì về Repository, Repository không biết gì về ViewModel. Đây là Clean Architecture principle.

---

## Tại sao chọn MVVM?

| Vấn đề | Giải pháp MVVM |
|--------|----------------|
| Android Activity bị kill khi xoay màn hình | ViewModel tồn tại qua config changes |
| Logic camera lẫn với UI code | Tách ra ViewModel |
| Test khó khi logic trong Activity | ViewModel có thể test độc lập |
| Multiple observers cùng 1 state | StateFlow broadcast đến nhiều collector |
