# 🗺️ Navigation Graph — PhoneCamera

## Navigation Flow Diagram

```mermaid
graph TD
    START(["App Start"]) --> MA["MainActivity\nonCreate()"]

    MA --> NAV["NavHost\nrememberNavController()\nstartDestination = Screen.Home"]

    NAV --> HOME["🏠 HomeScreen\nScreen.Home"]

    HOME -->|"onNavigateToStreamer()\nnavController.navigate(Screen.Streamer)"| STREAMER["📡 StreamerScreen\nScreen.Streamer"]

    HOME -->|"onNavigateToViewer()\nnavController.navigate(Screen.Viewer)"| VIEWER["📺 ViewerScreen\nScreen.Viewer"]

    STREAMER -->|"onBack()\nnavController.popBackStack()"| HOME
    VIEWER -->|"onBack()\nnavController.popBackStack()"| HOME

    style START fill:#333,color:#fff
    style MA fill:#4A90D9,color:#fff
    style NAV fill:#555,color:#fff
    style HOME fill:#50C878,color:#fff
    style STREAMER fill:#E74C3C,color:#fff
    style VIEWER fill:#3498DB,color:#fff
```

---

## Screen Definitions

### `Screen.kt` — Type-safe Navigation Routes

```kotlin
sealed interface Screen {
    @Serializable data object Home : Screen      // route: com.example.phonecamera.navigation.Screen.Home
    @Serializable data object Streamer : Screen  // route: com.example.phonecamera.navigation.Screen.Streamer
    @Serializable data object Viewer : Screen    // route: com.example.phonecamera.navigation.Screen.Viewer
}
```

> **Tại sao dùng `@Serializable` sealed interface thay vì String?**
> Navigation Compose 2.8+ hỗ trợ type-safe navigation. Dùng object thay vì String "home" giúp:
> - Compile-time safety — không typo trong route string
> - Không cần hardcode string constants
> - Arguments truyền qua navigation được type-check tại compile time

---

## Navigation Back Stack Visualization

```mermaid
graph LR
    subgraph "Back Stack khi ở Home"
        BS1["[Home]"]
    end

    subgraph "Back Stack khi ở Streamer"
        BS2["[Home → Streamer]"]
    end

    subgraph "Back Stack khi ở Viewer"
        BS3["[Home → Viewer]"]
    end

    subgraph "Sau popBackStack()"
        BS4["[Home]"]
    end

    BS1 -->|"navigate(Screen.Streamer)"| BS2
    BS1 -->|"navigate(Screen.Viewer)"| BS3
    BS2 -->|"popBackStack()"| BS4
    BS3 -->|"popBackStack()"| BS4
```

---

## Điểm đặc biệt của Navigation trong project này

| Điểm | Chi tiết |
|------|---------|
| **Single Activity** | Toàn bộ app trong 1 `MainActivity` — không có Fragment, chỉ dùng Composable |
| **Type-safe routes** | `Screen` là sealed interface với `@Serializable` — Navigation 2.8+ feature |
| **No arguments** | Các màn hình không cần truyền data qua navigation arguments (data được lấy từ ViewModel/DataStore) |
| **Back handling** | `onBack` lambda được pass vào Screen — `popBackStack()` quay về màn hình trước |
| **Lifecycle aware** | Khi navigate away từ StreamerScreen → `onDispose` được gọi → `releaseCamera()` tự động |

---

## Navigation Events & Side Effects

```mermaid
sequenceDiagram
    participant User
    participant NavController
    participant BackStack
    participant LifecycleOwner

    User->>NavController: navigate(Screen.Streamer)
    NavController->>BackStack: push Screen.Streamer
    NavController->>LifecycleOwner: HomeScreen → STOPPED
    NavController->>LifecycleOwner: StreamerScreen → RESUMED
    Note over LifecycleOwner: StreamerScreen DisposableEffect starts\n→ request landscape orientation\n→ hide system bars

    User->>NavController: popBackStack()
    NavController->>LifecycleOwner: StreamerScreen → DESTROYED
    Note over LifecycleOwner: StreamerScreen onDispose fires\n→ releaseCamera()\n→ restore orientation\n→ show system bars
    NavController->>BackStack: pop Screen.Streamer
    NavController->>LifecycleOwner: HomeScreen → RESUMED
```
