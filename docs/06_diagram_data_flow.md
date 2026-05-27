# 🔀 Data Flow Diagram — PhoneCamera

## Tổng quan Data Flow

```mermaid
flowchart TD
    subgraph INPUT["📥 INPUTS"]
        CAM_HW["Camera Hardware\n(frames)"]
        MIC["Microphone\n(audio)"]
        USER_S["User Actions\n(Streamer)"]
        USER_V["User Actions\n(Viewer)"]
        LAN["LAN Network\n(mDNS packets)"]
    end

    subgraph STREAMER["📱 STREAMER PHONE"]
        direction TB
        SUI["StreamerScreen\n(UI)"]
        SVM2["StreamerViewModel\n(state + logic)"]
        RTSP_ENC["RtspServerCamera2\n(H.264 encoder + RTSP server)"]
        NSD_REG["NsdHelper\nregisterService()"]
        CTRL_SRV["ControlServer\n(port 8081)"]
        REPO_S["CameraRepository\n(DataStore)"]
    end

    subgraph NETWORK["🌐 LAN NETWORK"]
        RTSP_PKT["RTSP/RTP Video Stream\nport 8080 (UDP/TCP)"]
        MDNS_PKT["mDNS Packets\n_rtspguard._tcp"]
        CTRL_PKT["Control TCP\nport 8081\n(HELLO/BYE/SET_QUALITY/SET_FPS)"]
    end

    subgraph VIEWER["📺 VIEWER PHONE"]
        direction TB
        VUI["ViewerScreen\n(UI — 4 slots)"]
        VVM2["ViewerViewModel\n(state + logic)"]
        EXO["ExoPlayer\n(RTSP decoder)"]
        NSD_DIS["NsdHelper\ndiscoverServices()"]
        CTRL_CLI["CameraControlClient\n(TCP client)"]
        REPO_V["CameraRepository\n(DataStore)"]
    end

    subgraph OUTPUT["📤 OUTPUTS"]
        SCREEN_S["Streamer Screen\n(preview + controls)"]
        SCREEN_V["Viewer Screen\n(4 video slots)"]
        DISK["Disk Storage\n(DataStore)"]
    end

    %% Streamer data flow
    CAM_HW --> RTSP_ENC
    MIC --> RTSP_ENC
    USER_S --> SUI
    SUI --> SVM2
    SVM2 --> RTSP_ENC
    SVM2 --> NSD_REG
    SVM2 --> CTRL_SRV
    SVM2 --> REPO_S
    REPO_S --> DISK
    RTSP_ENC --> RTSP_PKT
    NSD_REG --> MDNS_PKT
    RTSP_ENC --> SCREEN_S

    %% Viewer data flow
    LAN --> NSD_DIS
    NSD_DIS --> VVM2
    USER_V --> VUI
    VUI --> VVM2
    VVM2 --> CTRL_CLI
    VVM2 --> EXO
    VVM2 --> REPO_V
    REPO_V --> DISK
    CTRL_CLI --> CTRL_PKT

    %% Network to Viewer
    RTSP_PKT --> EXO
    MDNS_PKT --> NSD_DIS
    CTRL_PKT --> CTRL_SRV

    %% Outputs
    EXO --> SCREEN_V
    VVM2 --> SCREEN_V

    style INPUT fill:#1a1a2e,color:#eee
    style STREAMER fill:#16213e,color:#eee
    style NETWORK fill:#0f3460,color:#eee
    style VIEWER fill:#16213e,color:#eee
    style OUTPUT fill:#1a1a2e,color:#eee
```

---

## Data Flow Chi Tiết: Camera Frames

```mermaid
flowchart LR
    subgraph "Camera Hardware"
        SENSOR["Image Sensor\n(raw frames)"]
    end

    subgraph "RtspServerCamera2 (pedroSG94)"
        CAP["Camera2 Capture Session"]
        ENC_V["H.264 Encoder\n(MediaCodec)"]
        ENC_A["AAC Encoder\n(MediaCodec)"]
        RTSP_SRV["RTSP Server\n(RFC 2326)"]
        RTP["RTP Packetizer"]
    end

    subgraph "Network"
        UDP["UDP datagrams\n(default)"]
        TCP2["TCP stream\n(optional)"]
    end

    subgraph "ExoPlayer (Viewer)"
        RTSP_CLI["RTSP Client\n(DESCRIBE/SETUP/PLAY)"]
        RTP_RCV["RTP Receiver"]
        DEC_V["H.264 Decoder"]
        DEC_A["AAC Decoder"]
        RENDER["VideoFrameProcessor\n(OpenGL Surface)"]
    end

    SENSOR --> CAP
    CAP --> ENC_V
    CAP --> ENC_A
    ENC_V --> RTP
    ENC_A --> RTP
    RTP --> RTSP_SRV
    RTSP_SRV --> UDP
    RTSP_SRV --> TCP2
    UDP --> RTP_RCV
    TCP2 --> RTP_RCV
    RTP_RCV --> RTSP_CLI
    RTSP_CLI --> DEC_V
    RTSP_CLI --> DEC_A
    DEC_V --> RENDER
    DEC_A --> RENDER
```

---

## Data Flow Chi Tiết: Control Commands

```mermaid
flowchart LR
    subgraph "Viewer Phone"
        VUSER["User taps\n'720p' button"]
        VVMF["ViewerViewModel\nsetRemoteQuality(720)"]
        CCLIENT["CameraControlClient\nsendCommand()"]
        SOCK_C["TCP Socket\nclient"]
    end

    subgraph "TCP Connection (port 8081)"
        RAW["Raw text:\n'SET_QUALITY 720 1234\\n'"]
    end

    subgraph "Streamer Phone"
        SOCK_S["TCP Socket\nserver"]
        CSRV["ControlServer\nparseCommand()"]
        SVMF["StreamerViewModel\nonCommand(SetQuality)"]
        RESTART["Restart stream\nat new resolution"]
    end

    VUSER --> VVMF
    VVMF --> CCLIENT
    CCLIENT --> SOCK_C
    SOCK_C --> RAW
    RAW --> SOCK_S
    SOCK_S --> CSRV
    CSRV --> SVMF
    SVMF --> RESTART
    RESTART --"'OK'"--> RAW
    RAW --"'OK'"--> CCLIENT
    CCLIENT --> VVMF
    VVMF --"delay 2s then reconnect"--> VVMF
```

---

## Data Flow: DataStore Persistence

```mermaid
flowchart TD
    subgraph "App Start"
        INIT["ViewerViewModel.init()"]
        FLOW["camerasFlow.collect()"]
    end

    subgraph "DataStore (disk)"
        DS["rtsp_guard_prefs\nkey: cameras_json\nvalue: JSON string"]
    end

    subgraph "JSON"
        JSON["[{id:0, name:'Pixel7',\nhost:'192.168.1.5',\nport:8080, isPhoneCamera:true}]"]
    end

    subgraph "In-Memory State"
        SLOTS["List<CameraConfig?>\n[cam0, cam1, null, null]"]
        PLAYER["ExoPlayer instances\nmap[0]=player0\nmap[1]=player1"]
    end

    INIT --> FLOW
    FLOW --> DS
    DS --> JSON
    JSON --> SLOTS
    SLOTS --> PLAYER

    subgraph "On Save Camera"
        SAVE["repository.saveCamera(config)"]
        ENCODE["Json.encodeToString(list)"]
        WRITE["dataStore.edit { prefs[key] = json }"]
    end

    SAVE --> ENCODE
    ENCODE --> WRITE
    WRITE --> DS
```
