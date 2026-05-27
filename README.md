# 📷 Phone Camera - Hệ thống Camera Giám sát Nội bộ Không Dây

> **Biến điện thoại Android thành hệ thống camera an ninh nội bộ thông minh** — phát và xem luồng RTSP chất lượng cao qua mạng WiFi nội bộ (LAN) bảo mật bằng mã PIN 4 chữ số, hoạt động không cần kết nối internet hay máy chủ đám mây trung gian.

Ứng dụng được thiết kế tối ưu hóa cho hiệu năng cao, độ trễ cực thấp (~1s) và khả năng tự động khám phá thiết bị (Zero Configuration) trong cùng mạng WiFi. Đây là một dự án hoàn hảo để chứng minh năng lực về lập trình Android hiện đại (Modern Android Development), xử lý Multimedia (Video Streaming), Networking (TCP/IP socket, mDNS) và Quản lý luồng xử lý bất đồng bộ (Kotlin Coroutines & Flow).

---

## 📌 Mục lục

1. [Các Tính Năng Nổi Bật](#-các-tính-năng-nổi-bật)
2. [Kiến Trúc & Công Nghệ](#-kiến-trúc--công-nghệ)
3. [Thiết Kế Luồng & Sơ Đồ Kiến Trúc](#-thiết-kế-luồng--sơ-đồ-kiến-trúc)
4. [Giao Thức Điều Khiển ControlServer (TCP Port 8081) & Xác Thực PIN](#-giao-thức-điều-khuyển-controlserver-tcp-port-8081--xác-thực-pin)
5. [Giải Pháp Tối Ưu Độ Trễ & Xử Lý Frame (Low-Latency Video)](#-giải-pháp-tối-ưu-độ-trễ--xử-lý-frame-low-latency-video)
6. [Cơ Chế Auto-Discovery (mDNS/NSD) & Luồng PIN](#-cơ-chế-auto-discovery-mdnsnsd--luồng-pin)
7. [Quản Lý Vòng Đời & Tránh Rò Rỉ Bộ Nhớ (Memory Leaks)](#-quản-lý-vòng-đời--tránh-rò-rỉ-bộ-nhớ-memory-leaks)
8. [Cấu Trúc Thư Mục Dự Án](#-cấu-trúc-thư-mục-dự-án)
9. [🎓 Bộ Câu Hỏi Phỏng Vấn "Trúng Tủ" (Interview Cheat Sheet)](#-bộ-câu-hỏi-phỏng-vấn-trúng-tủ-interview-cheat-sheet)
10. [Hướng Dẫn Cài Đặt & Chạy Thử](#-hướng-dẫn-cài-đặt--chạy-thử)

---

## ⚡ Các Tính Năng Nổi Bật

| Vai trò | Giao diện | Chức năng chi tiết & Công nghệ cốt lõi |
|---|---|---|
| 📹 **Máy Quay** (Streamer) | Landscape, Immersive | - Phát trực tiếp luồng RTSP (H.264/AAC) sử dụng Camera2 API.<br>- **Bảo mật tối cao:** Tự động sinh mã PIN ngẫu nhiên 4 chữ số khi phát sóng. Chỉ các Viewer nhập đúng PIN mới được phép kết nối xem stream và gửi lệnh TCP.<br>- **Tối ưu phần cứng:** Tự động dùng `CameraManager` kiểm tra camera vật lý của thiết bị. Nút đổi camera trước/sau sẽ bị vô hiệu hóa nếu máy chỉ có 1 camera hoạt động.<br>- Tự động hạ độ sáng màn hình (Auto-dim) về mức tối thiểu sau 30s để tiết kiệm pin.<br>- Hiển thị số lượng và tên thiết bị đang xem stream theo thời gian thực.<br>- Thay đổi nóng camera trước/sau.<br>- Chọn FPS trước khi phát hoặc thay đổi nóng FPS từ xa (`15`, `24`, `30` FPS). |
| 🖥️ **Màn hình Xem** (Viewer) | Grid 2×2, Fullscreen | - Hỗ trợ xem đồng thời tối đa 4 camera theo lưới thời gian thực.<br>- **Xác thực mã PIN thông minh:** Hỗ trợ nhập PIN khi thêm thủ công bằng IP, hoặc khi kết nối nhanh qua tìm kiếm mDNS tự động.<br>- Tự động dò tìm camera đang phát cùng mạng LAN thông qua mDNS (Zero-configuration).<br>- Điều khiển chất lượng luồng phát (Resolution) và tốc độ khung hình (FPS) của camera đích từ xa qua cổng TCP 8081.<br>- Hỗ trợ đo lường FPS thực tế (actual FPS) hiển thị trên từng ô phát.<br>- Chuyển đổi linh hoạt giữa giao thức vận chuyển RTSP (TCP / UDP).<br>- Quản lý lưu trữ thông tin camera lưu động qua Jetpack DataStore. |

---

## 🛠️ Kiến Trúc & Công Nghệ

Dự án áp dụng chặt chẽ các nguyên lý của **Modern Android Development (MAD)**:

### 1. Technology Stack
*   **UI Layer:** Jetpack Compose kết hợp với Material 3, tối ưu thiết kế phản hồi (Responsive Grid), hỗ trợ cả chế độ Portrait và Landscape.
*   **Architecture:** MVVM (Model-View-ViewModel) kết hợp với mô hình dòng dữ liệu một chiều **Unidirectional Data Flow (UDF)**.
*   **Reactive Programming:** Sử dụng Kotlin Coroutines để xử lý tác vụ nền nặng (TCP Server, NSD Discovery) kết hợp với `StateFlow` và `collectAsStateWithLifecycle()` nhằm quan sát trạng thái của ứng dụng an toàn theo vòng đời UI.
*   **Media Streaming:**
    *   *Streamer:* Tích hợp thư viện `RootEncoder` (dựa trên `pedroSG94/RTSP-Server`) thực hiện mã hóa phần cứng phần cứng (H.264/AAC) và cung cấp RTSP Server trực tiếp trên điện thoại. Hỗ trợ xác thực bảo mật tài khoản RTSP credentials (`admin` / `pinCode`).
    *   *Viewer:* Sử dụng `Jetpack Media3 ExoPlayer` với module mở rộng `RtspMediaSource` cấu hình thông tin tài khoản qua URL dạng `rtsp://admin:PIN@host:port/live`.
*   **Data Persistence:** `Jetpack DataStore Preferences` lưu trữ danh sách cấu hình camera dưới định dạng JSON (sử dụng `kotlinx.serialization`) bao gồm cả trường `pinCode` bảo mật.
*   **Network Discovery:** Android `NsdManager` (Network Service Discovery) phục vụ cơ chế mDNS tự khám phá dịch vụ ở port `:8080` (Service type: `_rtspguard._tcp`).

### 2. Design Patterns Áp Dụng
*   **Repository Pattern:** Lớp `CameraRepository` bao bọc lấy Jetpack DataStore để cung cấp luồng dữ liệu (`Flow<List<CameraConfig>>`) giúp giữ code sạch và phân chia tầng lớp dữ liệu rõ ràng.
*   **Sealed Class / Sealed Interface State:** Mô hình hóa các trạng thái bất định như trạng thái Player (`PlayerState: Idle, Loading, Playing, Error`) và các lệnh điều khiển (`ControlServer.Command`).
*   **Observer Pattern:** Giao diện đăng ký nhận các biến đổi dữ liệu thông qua StateFlow của Viewmodel.

---

## 📐 Sơ Đồ Thiết Kế & Kiến Trúc Hệ Thống

### 1. Sơ đồ Use Case (Use Case Diagram)
```mermaid
graph TD
    subgraph Users ["👥 Vai trò Người dùng"]
        SU["📹 Thiết bị Máy Quay (Streamer)"]
        VU["🖥️ Thiết bị Xem (Viewer)"]
    end

    subgraph StreamerUC ["📹 Các Use Cases của Streamer"]
        UC1["Phát trực tiếp RTSP (LIVE)"]
        UC2["Lật Camera trước/sau"]
        UC3["Tự động giảm sáng (Auto-dim)"]
        UC4["Đăng ký dịch vụ mDNS (NSD)"]
        UC_PIN["Tạo PIN xác thực ngẫu nhiên"]
        UC_CAM_CHECK["Kiểm tra phần cứng Camera"]
    end

    subgraph ViewerUC ["🖥️ Các Use Cases của Viewer"]
        UC5["Xem lưới đồng thời (tối đa 4 ô)"]
        UC6["Khám phá camera tự động (mDNS)"]
        UC7["Thêm camera bằng IP thủ công"]
        UC_INPUT_PIN["Nhập PIN xác minh kết nối"]
        UC8["Đổi chất lượng & FPS từ xa (TCP)"]
        UC9["Bật/Tắt âm thanh luồng phát"]
        UC10["Chọn giao thức truyền tải (TCP/UDP)"]
    end

    SU --> UC1
    SU --> UC2
    SU --> UC3
    SU --> UC_PIN
    SU --> UC_CAM_CHECK
    UC1 -->|include| UC4

    VU --> UC5
    VU --> UC6
    VU --> UC7
    VU --> UC_INPUT_PIN
    VU --> UC8
    VU --> UC9
    VU --> UC10

    UC6 -->|interact| UC4
    UC8 -->|send command with PIN| UC1
```

---

### 2. Sơ đồ Lớp (Class Diagram)
```mermaid
classDiagram
    class StreamerViewModel {
        +attachCamera(glView)
        +startStream()
        +stopStream()
        +switchCamera()
        +selectResolution(res)
        +selectFps(fps)
        +releaseCamera()
    }
    
    class ControlServer {
        +port: Int
        -serverJob: Job
        -serverSocket: ServerSocket
        +start(scope, onCommand)
        +stop()
        -parseCommand(line, ip)
    }
    
    class ViewerViewModel {
        +uiState: StateFlow~ViewerUiState~
        +playersState: StateFlow~Map~Int, ExoPlayer~~
        -repository: CameraRepository
        -nsdHelper: NsdHelper
        -controlClient: CameraControlClient
        +startDiscovery()
        +stopDiscovery()
        +addDiscoveredCamera(camera)
        +saveCamera(config)
        +deleteCamera(id)
        +setRemoteQuality(slot, height)
        +setRemoteFps(slot, fps)
        +toggleTcp()
        +toggleAudio(slot)
    }
    
    class CameraControlClient {
        +sendCommand(host, cmd, timeoutMs)
        +sayHello(host, deviceName, pinCode)
        +sayBye(host, deviceName, pinCode)
        +setQuality(host, height, pinCode)
        +setFps(host, fps, pinCode)
    }
    
    class CameraRepository {
        +camerasFlow: Flow~List~CameraConfig~~
        +saveCamera(config)
        +deleteCamera(id)
    }
    
    class CameraConfig {
        +id: Int
        +name: String
        +host: String
        +port: Int
        +pinCode: String
        +isPhoneCamera: Boolean
        +toRtspUrl() String
    }
    
    StreamerViewModel --> ControlServer : sở hữu
    ViewerViewModel --> CameraRepository : sử dụng
    ViewerViewModel --> CameraControlClient : ủy quyền gửi lệnh mạng (SRP)
    CameraRepository --> CameraConfig : lưu trữ dữ liệu
```

---

### 3. Sơ đồ Tuần tự (Sequence Diagrams)

#### A. Luồng Đăng Ký & Phát Camera Bảo Mật (Streamer)
```mermaid
sequenceDiagram
    actor U as Người dùng
    participant SS as StreamerScreen
    participant SVM as StreamerViewModel
    participant CAM as RtspServerCamera2
    participant NSD as NsdHelper

    U->>SS: Mở màn hình Streamer
    SS->>SVM: init()
    SVM->>SVM: checkHardwareCamera() (Sử dụng CameraManager)
    SVM->>SVM: loadLocalIp() (Duyệt NetworkInterface)
    SVM->>SVM: controlServer.start()

    SS->>SVM: attachCamera(openGlView)
    SVM->>CAM: new RtspServerCamera2(glView, port=8080)
    CAM-->>SVM: startPreview()
    SVM-->>SS: isCameraReady = true

    U->>SS: Bấm "BẮT ĐẦU PHÁT"
    SS->>SVM: startStream()
    SVM->>SVM: Sinh PIN ngẫu nhiên (Ví dụ "4587")
    SVM->>CAM: getStreamClient().setAuthorization("admin", "4587")
    SVM->>CAM: prepareVideo(res, fps) + prepareAudio()
    SVM->>CAM: startStream()
    CAM-->>SVM: isStreaming = true
    SVM->>NSD: registerService(8080)
    SVM-->>SS: isStreaming = true, pinCode = "4587"
    SS-->>U: Hiển thị Thẻ PIN bảo mật & Badge LIVE
```

#### B. Luồng Quét Thiết Bị & Xác Thực PIN (Viewer)
```mermaid
sequenceDiagram
    actor U as Người dùng
    participant VS as ViewerScreen
    participant ADD as AddEditCameraDialog
    participant VVM as ViewerViewModel
    participant CCC as CameraControlClient
    participant EXO as ExoPlayer

    U->>VS: Mở màn hình Viewer → quét tự động
    VVM-->>VS: Phát hiện "PhoneCamera_Galaxy"
    U->>VS: Chạm vào camera vừa tìm thấy
    VS->>ADD: Mở Dialog với IP/Port tự điền
    U->>ADD: Nhập mã PIN "4587" hiển thị trên Streamer → Lưu
    ADD->>VVM: saveCamera(CameraConfig với pinCode="4587")
    VVM->>VVM: Lưu vào DataStore Preferences

    VVM->>CCC: sayHello(host, deviceName, "4587")
    CCC-->>VVM: Phản hồi "OK" (PIN chính xác)
    VVM->>EXO: Khởi tạo ExoPlayer(rtsp://admin:4587@host:8080/live)
    EXO-->>VS: Phát trực tiếp thành công
```

#### C. Thay Đổi FPS & Chất Lượng Stream Từ Xa (Remote Control Flow)
```mermaid
sequenceDiagram
    actor U as Người dùng
    participant CC as CameraCell
    participant VVM as ViewerViewModel
    participant CCC as CameraControlClient
    participant TCP as TCP:8081 (ControlServer)
    participant SVM as StreamerViewModel
    participant CAM as RtspServerCamera2

    U->>CC: Chạm vào nút hiển thị FPS → Chọn "24 FPS"
    CC->>VVM: setRemoteFps(slot=0, 24)
    VVM->>VVM: setPlayerState(slot, Loading)

    VVM->>CCC: setFps(host, 24, PIN="4587")
    CCC->>TCP: Lệnh "SET_FPS 24 4587"
    TCP->>TCP: So khớp "4587" == expectedPin
    TCP->>SVM: Kích hoạt Command.SetFps(24)
    SVM->>SVM: changeFpsRemote(24) [Main thread]
    SVM->>CAM: stopStream() → delay(500ms) → prepareVideo(res, 24) → startStream()
    TCP-->>CCC: Phản hồi "OK"
    CCC-->>VVM: Phản hồi "OK"

    Note over VVM: ExoPlayer tự động kết nối lại luồng mới 24fps
    VVM-->>CC: PlayerState.Playing [video chạy ở 24fps]
```

---

## 📡 Giao Thức Điều Khiển ControlServer (TCP Port 8081) & Xác Thực PIN

Để hỗ trợ giao tiếp hai chiều giữa Viewer và Streamer, dự án triển khai một **Custom TCP Server** chạy độc lập trên cổng `8081` tại phía Streamer. 

### 1. Định Dạng Giao Thức Bảo Mật (Text-Based Protocol)
Mỗi phiên kết nối gửi một chuỗi văn bản kết thúc bằng ký tự xuống dòng (`\n`). Để ngăn chặn các kết nối điều khiển trái phép từ các thiết bị khác trong cùng mạng LAN, mọi lệnh điều khiển bắt buộc phải gửi kèm **Mã PIN 4 chữ số** ở cuối:

*   **HELLO `<tên thiết bị> <PIN>`**: Viewer thông báo đã bắt đầu xem thành công.
*   **BYE `<tên thiết bị> <PIN>`**: Viewer thông báo ngừng xem (khi thoát màn hình hoặc xóa camera).
*   **SET_QUALITY `<360|720|1080> <PIN>`**: Viewer yêu cầu thay đổi độ phân giải từ xa.
*   **SET_FPS `<15|24|30> <PIN>`**: Viewer yêu cầu thay đổi tốc độ khung hình từ xa.

### 2. Định Dạng Phản Hồi (Responses)
Server luôn đối chiếu mã PIN nhận được trong gói tin với mã PIN hiện tại của Streamer:
*   Nếu mã PIN đúng: Thực hiện tác vụ và trả về `OK\n`.
*   Nếu mã PIN sai: Trả về `ERROR invalid PIN\n`.
*   Lệnh không hợp lệ: Trả về `ERROR unknown command\n` hoặc `ERROR invalid parameter\n`.

---

## 🎥 Giải Pháp Tối Ưu Độ Trễ & Xử Lý Frame (Low-Latency Video)

Trong các ứng dụng giám sát an ninh, độ trễ truyền dữ liệu video (latency) là yếu tố sống còn. Để đạt độ trễ tiệm cận thời gian thực (~1 giây) qua mạng LAN, dự án triển khai các cấu hình kỹ thuật sau:

### 1. Tối Ưu Bộ Đệm ExoPlayer (Custom LoadControl)
Mặc định, ExoPlayer cấu hình bộ đệm lớn (khoảng 15s đến 50s) nhằm đảm bảo video phát mượt mà qua các mạng internet không ổn định. Chúng tôi tùy chỉnh cấu hình `DefaultLoadControl` trong `CameraCell.kt`:

```kotlin
val loadControl = DefaultLoadControl.Builder()
    // minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs
    .setBufferDurationsMs(1000, 5000, 500, 1000)
    .setPrioritizeTimeOverSizeThresholds(true)
    .build()
```

### 2. Hỗ Trợ Giao Thức Chuyển Chở UDP và TCP
*   **UDP:** Mặc định được bật để đạt tốc độ tối đa và độ trễ thấp nhất. Nếu gặp hiện tượng mất gói tin làm nhiễu hình hoặc vỡ hình, Viewer có thể chuyển đổi thủ công sang chế độ **TCP Interleaved**.

### 3. Tính Toán FPS Thực Tế Bằng Metadata Listener
Mỗi giây, một đồng hồ Coroutine sẽ đọc giá trị `frameCounter`, cập nhật FPS lên UI và reset bộ đếm về 0, cung cấp thông số băng thông trực quan cho lập trình viên.

---

## 🔍 Cơ Chế Auto-Discovery (mDNS/NSD) & Luồng PIN

Ứng dụng không yêu cầu người dùng cấu hình thủ công địa chỉ IP phức tạp nhờ tích hợp giải pháp **Network Service Discovery (NSD)** của Android.

1.  **Đăng ký Dịch vụ (Streamer):**
    Khi Streamer bắt đầu phát sóng luồng RTSP ở port `8080`, hệ thống sử dụng `NsdManager` để đăng ký dịch vụ với định danh:
    *   *Service Name:* `"PhoneCamera_" + DeviceName`
    *   *Service Type:* `"_rtspguard._tcp"`
    *   *Port:* `8080`
2.  **Dò Tìm Dịch vụ (Viewer):**
    Viewer liên tục chạy tiến trình quét mạng (`discoverServices()`) cho loại dịch vụ `_rtspguard._tcp`. Khi phát hiện thấy thiết bị tương thích, `NsdManager` sẽ thực hiện phân giải (Resolve) IP và Port của Streamer, hiển thị thông báo kết nối nhanh (Badge count) cho người dùng.
3.  **Xác minh PIN sau khi tìm thấy:**
    Khi người xem chạm vào thiết bị quét được trong danh sách tự động, ứng dụng sẽ mở dialog pre-fill sẵn Host và Port, đồng thời hiển thị trường nhập mã PIN. Người dùng phải nhập mã PIN hiển thị trên màn hình Streamer để ứng dụng lưu cấu hình và kết nối RTSP thành công.

---

## 💾 Quản Lý Vòng Đời & Tránh Rò Rỉ Bộ Nhớ (Memory Leaks)

Các thư viện Media như ExoPlayer và RtspServerCamera2 có khả năng gây rò rỉ bộ nhớ rất lớn nếu không được giải phóng đúng cách. Ứng dụng xử lý bằng `DisposableEffect` trong Jetpack Compose, lập tức giải phóng tài nguyên trong khối `onDispose` khi UI bị hủy.

---

## 📁 Cấu Trúc Thư Mục Dự Án

```
app/src/main/java/com/example/phonecamera/
├── MainActivity.kt              
├── navigation/
│   └── Screen.kt                
├── data/
│   ├── CameraRepository.kt      
│   ├── CameraConfig.kt          # Thực thể cấu hình camera (id, name, host, port, pinCode, isPhoneCamera)
│   └── nsd/
│       ├── NsdHelper.kt         
│       └── DiscoveredCamera.kt  
├── home/
│   ├── HomeScreen.kt            
│   └── HomeViewModel.kt         
├── streamer/
│   ├── StreamerScreen.kt        # UI Streamer: điều khiển phát, lật camera, thông tin kết nối, PIN card, FPS selector
│   ├── StreamerViewModel.kt     # Quản lý camera phần cứng, điều phối RtspServer và ControlServer, check phần cứng camera
│   └── ControlServer.kt         # TCP Server lắng nghe lệnh chất lượng, FPS từ xa kèm so khớp PIN bảo mật
├── viewer/
│   ├── ViewerScreen.kt          
│   ├── ViewerViewModel.kt       # Quản lý các luồng phát ExoPlayer, gửi lệnh TCP kèm PIN và nhận dạng NSD
│   └── components/
│       ├── CameraCell.kt        # Ô chứa ExoPlayer, xử lý đo FPS thực tế, đổi độ phân giải và FPS từ xa
│       ├── AddEditCameraDialog.kt # Dialog nhập thông tin camera và mã PIN bảo mật 4 chữ số
│       └── DiscoveryBottomSheet.kt
└── utils/
    └── AppLog.kt                
```

---

## 🎓 Bộ Câu Hỏi Phỏng Vấn "Trúng Tủ" (Interview Cheat Sheet)

### 1. Tại sao bạn lại chọn giao thức TCP cho ControlServer thay vì HTTP hay WebSockets?
*   **Trả lời:** 
    > "Trong phạm vi ứng dụng nội bộ (mạng LAN), việc dựng một HTTP Server sẽ tạo thêm nhiều thư viện rác và làm tăng kích thước file APK không cần thiết. WebSockets cũng yêu cầu phải thực hiện bắt tay (handshake) phức tạp. Em đã tự viết một giao thức text-based tối giản trên socket TCP thô. Mỗi khi Viewer muốn gửi lệnh, nó chỉ cần tạo một kết nối ngắn hạn, gửi 1 dòng lệnh chứa mã PIN xác thực rồi đóng kết nối lại. Giải pháp này cực kỳ nhẹ, nhanh và không làm rò rỉ kết nối."

### 2. Làm thế nào bạn giải quyết bài toán bảo mật khi bất kỳ ai trong mạng LAN đều có thể quét thấy và kết nối vào luồng RTSP?
*   **Trả lời:**
    > "Em đã triển khai luồng xác thực hai lớp bằng **mã PIN 4 chữ số**:
    > 1. **Lớp RTSP:** Khi phát, Streamer sinh PIN ngẫu nhiên và đăng ký với RTSP Server qua `setAuthorization`. Viewer phải cấu hình URL dạng `rtsp://admin:PIN@host:port/live`.
    > 2. **Lớp Control TCP:** Mọi lệnh điều khiển tới cổng `8081` bắt buộc phải kèm mã PIN ở cuối lệnh. Máy chủ sẽ so khớp PIN trước khi xử lý, đảm bảo chỉ người có PIN mới được điều khiển chất lượng."

### 3. Bạn đã xử lý Thread Safety như thế nào khi nhận lệnh TCP và cập nhật UI State?
*   **Trả lời:**
### 4. Jetpack Compose recomposition diễn ra liên tục. Làm sao bạn đảm bảo đối tượng ExoPlayer không bị khởi tạo lại vô tội vạ?
*   **Trả lời:**
    > "Em sử dụng hàm `rememberLowLatencyExoPlayer` kết hợp với `DisposableEffect`. ExoPlayer sẽ chỉ được khởi tạo lại khi và chỉ khi các tham số quan trọng thay đổi (địa chỉ `rtspUrl`, chế độ `useTcp`, hoặc một biến đánh dấu lần thử lại `attemptId`).
    > Khi xảy ra quá trình Recomposition thông thường (ví dụ: thay đổi văn bản hiển thị trên UI), các tham số trên không thay đổi, vì thế Compose sẽ tái sử dụng thực thể Player cũ đã được ghi nhớ. Khi Composable bị hủy hoàn toàn hoặc tham số thay đổi, khối `onDispose` sẽ được kích hoạt để giải phóng (`player.release()`) đối tượng cũ nhằm tránh rò rỉ bộ nhớ."

### 5. Tại sao bạn chọn lưu trữ danh sách Camera bằng Jetpack DataStore Preferences thay vì Room Database?
*   **Trả lời:**
    > "Ứng dụng này chỉ quản lý tối đa 4 slot camera với cấu hình dữ liệu dạng phẳng rất đơn giản (không có các mối quan hệ phức tạp như 1-nhiều hay nhiều-nhiều). Việc tích hợp Room Database trong trường hợp này là quá mức cần thiết (overkill), yêu cầu nhiều cấu hình Boilerplate code (Entity, DAO, Database Class, Migrations).
    > Sử dụng Jetpack DataStore Preferences kết hợp thư viện `kotlinx.serialization` giúp em lưu trữ danh sách camera dưới dạng một chuỗi JSON an toàn, đọc ghi bất đồng bộ thông qua Kotlin Flow, vừa gọn nhẹ vừa đảm bảo hiệu năng cao mà không gây nghẽn luồng UI."

---

## 🚀 Hướng Dẫn Cài Đặt & Chạy Thử

### 1. Yêu cầu thiết bị
*   Tối thiểu 2 thiết bị Android chạy hệ điều hành Android 8.0 (API Level 26) trở lên.
*   Cả hai thiết bị cùng kết nối vào **một mạng WiFi chung**.

### 2. Các bước cài đặt
1.  Clone dự án về máy:
    ```bash
    git clone https://github.com/nvdung1607/PhoneCamera.git
    ```
2.  Mở dự án bằng **Android Studio (Ladybug hoặc mới hơn)**.
3.  Kết nối thiết bị Android của bạn và nhấn **Run app** (`Shift + F10`).

### 3. Cách kiểm thử tính năng
1.  **Trên thiết bị làm Máy Quay (Streamer):**
    *   Mở app, chọn **Máy Quay**.
    *   Cấp quyền Camera và Microphone nếu được yêu cầu.
    *   Chọn độ phân giải mong muốn và bấm **BẮT ĐẦU PHÁT**.
2.  **Trên thiết bị làm Màn hình Xem (Viewer):**
    *   Mở app, chọn **Màn hình Xem**.
    *   Bấm vào biểu tượng quét thiết bị ở góc trên bên phải. Hệ thống sẽ tự quét mDNS và hiển thị tên thiết bị Máy Quay.
    *   Chạm vào tên thiết bị Máy Quay để gán vào một trong bốn ô xem của màn hình lưới. Luồng video trực tiếp sẽ hiển thị sau khoảng 1-2 giây.
    *   Bấm vào nút **HD** trên ô camera để đổi độ phân giải từ xa và kiểm tra phản hồi của Streamer.

---

*Cập nhật lần cuối: 2026-05-26*
