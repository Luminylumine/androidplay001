# Phone Mirror Phone — 完整实施计划

> 分支: feature/phone-mirror-phone  
> 日期: 2026-08-31  
> 技术栈: Kotlin 1.9.24 + Jetpack Compose + AOSP ADB Protocol + 官方 scrcpy-server 4.0 + MediaCodec

## 参考资料
- GPT 调研报告: common/docs/chatgpt-output/Phone_Mirror_Phone_Android_Controller_Research_2026-08-31.md
- Scropy Android (代码矿山): https://github.com/feggaa/scrcpy-android (MIT, 直接引用不依赖)
- scrcpy developer docs: https://github.com/Genymobile/scrcpy/blob/master/doc/develop.md
- scrcpy protocol constants: https://github.com/screen-flow/scrcpy-go/blob/main/consts.go

## 总体交付节奏
- Phase 0→1 可合验: ADB transport + shell, 不关心 screen mirror
- Phase 2→3 可合验: 完整屏幕镜像+控制, 核心 MVP
- Phase 4→5 可合验: 文件/相册, 数据层
- Phase 6→7: Akasha 增强 + 加固, 最后上线前做

## Phase 0 — Transport Spike (ADB Legacy TCP + 基础 AUTH)
### 目标
在 Android App 进程内，完全自研 ADB legacy TCP transport，能连接目标 Android 设备并执行 `shell getprop ro.product.model`。

### 必须完成
- **transport/adb-core**: 
  - AdbPacket.kt: 24-byte header (command/arg0/arg1/data_len/checksum/magic), ADB_CMD enum (CNXN/AUTH/OPEN/OKAY/WRTE/CLSE), computeChecksum()
  - AdbKeyPair.kt: RSA-2048 生成（AndroidKeyStore），Android ADB public key serialization（modulus+exponent 自定义格式）
  - AdbTransport.kt: Transport interface + 基于 Socket 的 TcpTransport 实现
  - AdbConnection.kt: openService() 建立多路复用 stream
  - AdbStream.kt: InputStream/OutputStream 桥接
  
- **transport/adb-wifi/LegacyTcpTransport.kt**:
  - connect(host, port=5555)
  - send CNXN banner ("host::")
  - AUTH handshake: 
    1. CNXN → device 回 AUTH/RSA token
    2. 我们用 RSA 私钥签名 token → AUTH/RSA_SIGNATURE
    3. device 回 AUTH/RSA_PUBLIC_KEY 或 AUTH/DONE
    4. 如果是 PUBLIC_KEY，我们发送自己的 public key → 等 device 确认
    5. 最终 device 发 CNXN 回来，握手完成
  - shell(command) 实现: open("shell:" + command) → 读 WRTE payload

### 验收
App 上点一个 button，Toast 显示目标设备 model。

### 不确定点（需 GPT 确认）
- Android ADB public key serialization 格式：modulus 低字节在前 + exponent 4 字节 + padding + base64？具体参考 AOSP adb_auth_host.cpp

### 参考实现位置
- Scropy Android 的 transport 实现（native C++/Kotlin）
- AOSP: platform_system_core/adb/adb_auth_host.cpp, protocol.h

## Phase 1 — Modern Wireless Debugging + mDNS + Saved Devices
### 目标
完整支持 Android 11+ Wireless Debugging (pairing code + TLS)、mDNS 自动发现、设备持久化。

### 必须完成
- **transport/adb-wifi/TlsWirelessTransport.kt**: TLS over TCP (port=pairing port)
- **transport/adb-wifi/PairingManager.kt**: SPAKE2 + 4-digit code, BoringSSL binding
- **transport/adb-wifi/MdnsDiscovery.kt**: NsdManager 发现 "_adb-tls-connect._tcp." / "_adb-tls-pairing._tcp."
- **data/cache/DeviceStore.kt**: Room entity 存 saved devices (id, host, pairing_port, cert_hash)

### 不确定点（需 GPT 确认）
- SPAKE2 具体握手字节序列 + BoringSSL 如何引用到 Android 项目（maven artifact? NDK build?）
- Android 14+ 的 pairing protocol 变化

## Phase 2 — Mirror MVP
### 目标
屏幕镜像 + 基础控制（tap/swipe/back）。

### 必须完成
- **mirror/scrcpy-protocol/ScrcpyProtocol.kt**: 写入已知常量（从 scrcpy 官文档 + scrcpy-go consts.go）
  ```kotlin
  // ControlMessageType
  INJECT_KEYCODE=0, INJECT_TEXT=1, INJECT_TOUCH_EVENT=2, INJECT_SCROLL_EVENT=3,
  BACK_OR_SCREEN_ON=4, EXPAND_NOTIFICATION_PANEL=5, EXPAND_SETTINGS_PANEL=6,
  COLLAPSE_PANELS=7, GET_CLIPBOARD=8, SET_CLIPBOARD=9, SET_DISPLAY_POWER=10,
  ROTATE_DEVICE=11, UHID_CREATE=12, UHID_INPUT=13, UHID_DESTROY=14,
  OPEN_HARD_KEYBOARD_SETTINGS=15, START_APP=16
  // DeviceMessageType
  DEVICE_CLIPBOARD=0, DEVICE_ACK_CLIPBOARD=1, DEVICE_UHID_OUTPUT=2
  // Codec IDs
  H264=0x68323634, H265=0x68323635
  ```
- **mirror/scrcpy-protocol/ScrcpyCodec.kt**: 
  - ControlMessage 序列化（INJECT_TOUCH_EVENT: 1+1+8+4+4+2+2+2+4+4 = 32 bytes, BE）
  - MediaPacket header: 12 bytes (PTS 61bit + flags 3bit + packet size 32bit)
  - SessionPacket: 12 bytes (flag + clientResized + width + height)
- **mirror/scrcpy-session/ScrcpySession.kt**: 
  - push scrcpy-server (adb push via OPEN "sync:" + SEND)
  - start: adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.0 scid=XXXXXX audio=false
  - open video/control socket via localabstract:scrcpy_<scid>
  - video socket: 读 codecId → 等 session packet → 循环 MediaPacket
  - control socket: 双向，写 ControlMessage + 读 DeviceMessage
- **mirror/video-decoder/VideoDecoder.kt**:
  - MediaCodec.createDecoderByType("video/avc") (H.264)
  - configure Surface 输出
  - session packet: 检测分辨率变化 → reconfigure MediaCodec
  - MediaPacket header 解析 (bit 7 = session/media, bit 6 = config packet, bit 5 = key frame)
- **MirrorScreen.kt (Compose)**: AndroidView({ SurfaceView }) + ScrcpySession

### 验收
画面流畅、无 bitmap copy、rotation 后触控不偏移。

### 关键依赖
- 固定使用 scrcpy-server v4.0 (版本必须精确匹配)
- server 作为 app assets 打包，首次运行 adb push 到 /data/local/tmp/
- reverse tunnel vs forward tunnel: 手机间只能用 forward (localabstract → 端口监听)

### 不确定点（需 GPT 确认）
- scrcpy-server push 的 SYNC protocol 精确帧格式（STAT/LIST/SEND/RECV/DATA/DONE）
- scrcpy-server 在不同 Android 版本是否都能 shell 权限正常运行
- reverse tunnel 在设备作为 server 端时是否能被 client 正确连接

## Phase 3 — Control Complete
### 必须完成
- INJECT_KEYCODE: 1+1+4+4+4 = 14 bytes (type+action+keycode+repeat+metastate)
- INJECT_TEXT: 1+4+N (max 300 bytes)
- INJECT_TOUCH_EVENT: 完整实现 (pointerId 8 bytes BE, x/y 4 bytes, screenWidth/Height 2 bytes, pressure u16FP, actionButton/buttons)
- INJECT_SCROLL_EVENT:
- GET_CLIPBOARD / SET_CLIPBOARD: 同步 (maxClipLength = 1<<18 - 14)
- BACK_OR_SCREEN_ON
- SET_DISPLAY_POWER
- ROTATE_DEVICE
- control socket 的 DeviceMessage 接收 (clipboard change → 同步到 controller)
- audio socket 可选 (OPUS codec, AudioTrack)

## Phase 4 — File Transfer
### 必须完成
- **data/remote-files/AdbSync.kt**: 
  - OPEN "sync:"
  - SEND 帧: "SEND"+path+mode+size+time → DATA "DATA"+len+data → "DONE"
  - RECV 帧: "RECV"+path → 返回 DATA/DONE
  - LIST 帧: "LIST"+path → 返回 "DENT"/"DONE"
  - STAT 帧: "STAT"+path → 返回 "STAT"
- **data/remote-files/RemoteFileService.kt**: 流式 push/pull + progress callback + 取消
- **DeviceIoScheduler**: 1:1 port (Metadata=2, Transfer USB=2/TCP=1)
- **FileBrowserScreen.kt**: LazyColumn, pull/push/cancel

## Phase 5 — Gallery
### 必须完成 (大部分已 stub，补全)
- GalleryRepository.LoadAsync + PollNewAsync + RemoveTombstoned
- GalleryRowParser.parse (已 stub，补测试)
- GalleryCache.sniff (已 stub，补所有 7 种格式测试)
- DiskCache.getOrCreateThumbnail (512px JPEG, SHA256 key)
- RoomDb + DAO 完整实现
- **GalleryScreen.kt**: LazyVerticalGrid, thumbnail 加载 + 缓存命中
- DeviceIoScheduler.withThumbBatch 限流

## Phase 6 — Akasha Enhanced
### 可选增强 (基于已有的 feature/akasha-android 分支 IAkashaShell AIDL)
- target 有 Akasha + Dhizuku: 走 RPC 直接执行 shell, MediaProvider DISK mount 绕过分区存储
- target 没装: fallback stock ADB

## Phase 7 — Hardening
- 设备矩阵: Android 10/11/12/13/14/15/16, Xiaomi/Huawei/Samsung/Pixel
- 测试: WiFi 切换、USB 拔插、锁屏、rotation、Surface recreate、thermal throttling
- scrcpy-server 版本 pin 到 release tag, server 二进制 hash 校验
- OAuth/鉴权: 首次连接弹 RSA key 接受提示

## 模块与开发顺序依赖图
```
Phase 0 ──┬── adb-core (协议层, 所有传输的基础)
           ├── adb-wifi legacy TCP (CNXN/AUTH/shell)
           └── adb-usb (后续 Phase 0 补)
              │
Phase 1 ──┬── adb-wifi TLS + mDNS + pairing
           └── data/cache (saved devices Room)
              │
Phase 2 ──┬── mirror/scrcpy-protocol (常量+编解码)
           ├── mirror/scrcpy-session (生命周期)
           ├── mirror/video-decoder (MediaCodec)
           └── app (MirrorScreen Compose)
              │
Phase 3 ──┬── ControlMessage 全部类型实现
           └── DeviceMessage 接收
              │
Phase 4 ── data/remote-files (ADB SYNC) + FileBrowserScreen
              │
Phase 5 ── data/gallery (GalleryRepository + Room + DeviceIoScheduler) + GalleryScreen
              │
Phase 6 ── privilege/shizuku + dhizuku binding
```
