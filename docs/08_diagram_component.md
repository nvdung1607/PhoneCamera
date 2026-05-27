# 🧩 Component Diagram — PhoneCamera

## Tổng quan Component Interaction (2 điện thoại)

```mermaid
graph TB
    subgraph PHONE_S["📱 Streamer Phone"]
        subgraph APP_S["PhoneCamera App (Streamer mode)"]
            SS_UI["StreamerScreen\n(Jetpack Compose)"]
            SVM_C["StreamerViewModel\n(MVVM)"]
            RTSP_S["RtspServerCamera2\n(RTSP Server :8080)"]
            NSD_R["NsdHelper\n(register _rtspguard._tcp)"]
            CTRL_S["ControlServer\n(TCP :8081)"]
            REPO_S2["CameraRepository\n(DataStore)"]
        end

        subgraph HW_S["Android Hardware"]
            CAM2["Camera2 API\n(front/back camera)"]
            MIC2["Microphone"]
        end

        subgraph OS_S["Android OS Services"]
            NSD_OS_S["NsdManager\n(mDNS/Bonjour)"]
            CAM_OS["CameraManager"]
        end
    end

    subgraph LAN["🌐 Local Area Network (WiFi)"]
        RTSP_NET["RTSP/RTP\nport 8080\n(H.264 video stream)"]
        CTRL_NET["TCP Control\nport 8081\n(text commands)"]
        MDNS_NET["mDNS Multicast\n224.0.0.251:5353\n(_rtspguard._tcp)"]
    end

    subgraph PHONE_V["📺 Viewer Phone"]
        subgraph APP_V["PhoneCamera App (Viewer mode)"]
            VS_UI["ViewerScreen\n(Jetpack Compose)"]
            VVM_C["ViewerViewModel\n(MVVM)"]
            EXO_P["ExoPlayer (×4 slots)\n(RTSP Player)"]
            NSD_D["NsdHelper\n(discover services)"]
            CTRL_C["CameraControlClient\n(TCP Client)"]
            REPO_V2["CameraRepository\n(DataStore)"]
        end

        subgraph OS_V["Android OS Services"]
            NSD_OS_V["NsdManager\n(mDNS/Bonjour)"]
        end
    end

    %% Streamer internal
    SS_UI <--> SVM_C
    SVM_C --> RTSP_S
    SVM_C --> NSD_R
    SVM_C --> CTRL_S
    SVM_C --> REPO_S2
    RTSP_S --> CAM2
    RTSP_S --> MIC2
    NSD_R --> NSD_OS_S
    CAM2 --> CAM_OS

    %% Viewer internal
    VS_UI <--> VVM_C
    VVM_C --> EXO_P
    VVM_C --> NSD_D
    VVM_C --> CTRL_C
    VVM_C --> REPO_V2
    NSD_D --> NSD_OS_V

    %% Network connections
    RTSP_S -->|"H.264 stream"| RTSP_NET
    RTSP_NET -->|"decode"| EXO_P
    NSD_OS_S -->|"mDNS announce"| MDNS_NET
    MDNS_NET -->|"service found"| NSD_OS_V
    CTRL_C -->|"HELLO/SET_QUALITY/BYE"| CTRL_NET
    CTRL_NET -->|"commands"| CTRL_S
```

---

## Component: Network Protocol Stack

```mermaid
graph TB
    subgraph "Application Layer"
        RTSP_PROTO["RTSP Protocol\n(RFC 2326)\nDESCRIBE / SETUP / PLAY / TEARDOWN"]
        CTRL_PROTO["Custom Text Protocol\nHELLO name pin\nSET_QUALITY 720 pin\nBYE name pin\nSET_FPS 30 pin"]
        MDNS_PROTO["DNS-SD / mDNS\n(RFC 6762 + 6763)\n_rtspguard._tcp\nService Type"]
    end

    subgraph "Transport Layer"
        TCP1["TCP\n(RTSP control channel)"]
        UDP1["UDP/RTP\n(video data — default)"]
        TCP2["TCP/RTP\n(video data — optional toggle)"]
        TCP3["TCP\n(Control commands port 8081)"]
        UDP2["UDP Multicast\n(mDNS port 5353)"]
    end

    subgraph "Network Layer"
        IPV4["IPv4\n(LAN only)"]
        MULTI["224.0.0.251\n(mDNS multicast group)"]
    end

    RTSP_PROTO --> TCP1
    RTSP_PROTO --> UDP1
    RTSP_PROTO --> TCP2
    CTRL_PROTO --> TCP3
    MDNS_PROTO --> UDP2

    TCP1 --> IPV4
    UDP1 --> IPV4
    TCP2 --> IPV4
    TCP3 --> IPV4
    UDP2 --> MULTI
    MULTI --> IPV4
```

---

## Component: RtspServerCamera2 Internal Pipeline

```mermaid
graph LR
    subgraph "Camera2 API"
        CS2["CaptureSession"]
        IMG["ImageReader\n(YUV_420_888)"]
    end

    subgraph "Encoder Pipeline (RootEncoder)"
        VE["Video Encoder\nMediaCodec H.264"]
        AE["Audio Encoder\nMediaCodec AAC"]
        MUX["RTP Muxer"]
    end

    subgraph "RTSP Server"
        SESSION["Session Manager\n(per connected viewer)"]
        INTERLEAVE["Interleaved TCP\nor UDP RTP"]
    end

    subgraph "OpenGlView (Preview)"
        GL["OpenGL ES\n(SurfaceTexture)"]
        PREVIEW["Screen Preview\n(real-time)"]
    end

    CS2 --> IMG
    CS2 --> GL
    GL --> PREVIEW
    IMG --> VE
    VE --> MUX
    AE --> MUX
    MUX --> SESSION
    SESSION --> INTERLEAVE
```

---

## Component: Authentication Flow

```mermaid
graph TD
    subgraph "Streamer"
        PIN_GEN["Generate PIN\n(random 4-digit)\non startStream()"]
        RTSP_AUTH["RTSP Authorization\n'admin' : PIN\nvia cam.getStreamClient()\n.setAuthorization()"]
        CTRL_AUTH["ControlServer\nvalidate cmd.pin\n== expected PIN"]
        SAVE_PIN["CameraRepository\nsaveStreamerPin(pin)\n(DataStore persist)"]
    end

    subgraph "Viewer"
        ENTER_PIN["User enters PIN\nor auto-filled\nfrom saved config"]
        RTSP_URL["RTSP URL:\nrtsp://admin:PIN@host:8080"]
        CTRL_CMD["Control command:\nHELLO name PIN"]
    end

    PIN_GEN --> RTSP_AUTH
    PIN_GEN --> SAVE_PIN
    ENTER_PIN --> RTSP_URL
    ENTER_PIN --> CTRL_CMD
    RTSP_URL -->|"RTSP DESCRIBE"| RTSP_AUTH
    CTRL_CMD -->|"TCP :8081"| CTRL_AUTH
```

---

## Manifest & Permissions Breakdown

```mermaid
graph LR
    subgraph "Permissions"
        P1["INTERNET\n→ RTSP streaming qua TCP/UDP"]
        P2["ACCESS_NETWORK_STATE\n→ Check network availability"]
        P3["ACCESS_WIFI_STATE\n→ Get local IP (NetworkInterface)"]
        P4["CHANGE_WIFI_MULTICAST_STATE\n→ Nhận gói mDNS multicast\n(Viewer NSD discovery)"]
        P5["CAMERA\n→ CameraX / Camera2 access"]
        P6["RECORD_AUDIO\n→ Microphone encoding (AAC)"]
    end

    subgraph "Features (not required)"
        F1["android.hardware.camera\nrequired=false\n→ Tablets without camera still install"]
        F2["android.hardware.camera.autofocus\nrequired=false"]
    end

    subgraph "Activity Config"
        A1["configChanges: orientation|screenSize\n→ Không recreate Activity khi xoay\nViewModel giữ nguyên state"]
        A2["allowBackup=true"]
        A3["usesCleartextTraffic=true\n→ Cho phép HTTP/RTSP (không phải HTTPS)\n(LAN only, không qua internet)"]
    end
```
