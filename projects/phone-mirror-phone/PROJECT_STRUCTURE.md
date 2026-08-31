# phone-mirror-phone

Android 手机到手机 ADB 控制器应用。一台 Android 设备通过 ADB over WiFi 或 USB OTG 控制另一台 Android 设备。

## 模块依赖图

```
app
├── transport:adb-wifi  → transport:adb-core → core
├── transport:adb-usb   → transport:adb-core → core
├── mirror:scrcpy-session → mirror:scrcpy-protocol → transport:adb-core → core
│                          mirror:scrcpy-protocol → core
├── mirror:video-decoder → mirror:scrcpy-protocol
├── data:remote-files → transport:adb-core → core
├── data:gallery → transport:adb-core → core
│                  room
├── data:cache → core
├── privilege:shizuku → core
└── privilege:dhizuku → core
```

## 模块说明

| 模块 | 类型 | 说明 |
|------|------|------|
| `:core` | pure Kotlin JVM | 纯数据模型、调度器、Result<T> 包装。无 Android 依赖。 |
| `:transport:adb-core` | android-library | ADB 协议抽象层：AdbTransport、AdbPacket、AdbConnection、AdbStream、AdbKeyPair 接口 |
| `:transport:adb-wifi` | android-library | TCP Legacy 传输、TLS Wireless 传输、Pairing 配对、mDNS 发现 |
| `:transport:adb-usb` | android-library | UsbManager 驱动的 USB OTG ADB 传输 |
| `:mirror:scrcpy-protocol` | android-library | scrcpy 协议常量、控制包类型、编解码 |
| `:mirror:scrcpy-session` | android-library | scrcpy 会话生命周期：连接 → 推送 scrcpy-server → 启动 → 视频/控制流 |
| `:mirror:video-decoder` | android-library | MediaCodec H.264 解码 → Surface 输出 |
| `:data:remote-files` | android-library | 远程文件服务 + ADB SYNC 协议实现 |
| `:data:gallery` | android-library | GalleryRepository、GalleryRowParser、Magic-number 嗅探、Room 缓存 |
| `:data:cache` | android-library | 磁盘缓存、512px JPEG 缩略图存储 |
| `:privilege:shizuku` | android-library | Shizuku 绑定封装（可选增强路径） |
| `:privilege:dhizuku` | android-library | DHizuku 绑定封装（可选增强路径） |
| `:app` | android-application | 主应用入口 + Compose UI + NavHost + DI 容器 |

## Windows C# 设计模式移植表

| Windows C# 源 | Android Kotlin 目标模块 | 说明 |
|---------------|------------------------|------|
| `DeviceIoScheduler.cs` | `:core` DeviceIoScheduler | SemaphoreSlim → Semaphore；Metadata=2, Transfer(USB=2/TCP=1), ThumbBatch(USB=2/TCP=1) |
| `DeviceCapability.cs` | `:core` DeviceCapability | enum of ADB 能力位 |
| `DeviceInfo.cs` | `:core` DeviceInfo | 数据类 1:1 移植 |
| `AdbTransport.cs` | `:transport:adb-core` | 接口抽象 |
| `AdbPacket.cs` | `:transport:adb-core` | 数据包结构 |
| `GalleryRepository.cs` | `:data:gallery` | LoadAsync / PollNewAsync 增量轮询 / RemoveTombstoned / Album 构建 |
| `GalleryItem.cs` | `:data:gallery` | 完全相同字段 + SortKey / AlbumKey |
| `GalleryRowParser.cs` | `:data:gallery` | **关键移植**："下一列名=" 边界分割算法，处理值中的逗号和等号，`_data` 末列 trick |
| `GalleryCache.cs` | `:data:gallery` | Magic-number 嗅探表（JPEG FFD8FF, PNG 89504E47..., WebP RIFF+WEBP, HEIC/AVIF ISO-BMFF brand） |
| `AdbSync.cs` | `:data:remote-files` | SYNC 协议帧 |
| `ScrcpyProtocol.cs` | `:mirror:scrcpy-protocol` | 常量 + 控制包类型 |
| `ScrcpySession.cs` | `:mirror:scrcpy-session` | 会话状态机 |
| `DiskCache.cs` | `:data:cache` | 缓存 key = deviceId + mediaId + size + dateModified → SHA256 hex |

## 技术栈

- Kotlin 1.9.24
- Jetpack Compose (Material3)
- Android Gradle Plugin 8.5.0
- Kotlin Coroutines 1.8.1
- Room 2.6.1
- Navigation Compose 2.7.7
- Lifecycle 2.8.0

## 构建

```bash
# 需要先将 Android gradle wrapper 生成到本目录（Android Studio 首次打开会自动生成）
# 需要配置 local.properties 指向 SDK
./gradlew assembleDebug
```

## 注意

- `gradle/wrapper/` 未提交二进制文件 — 首次在 Android Studio 打开项目时自动生成
- 所有版本号集中在 `gradle/libs.versions.toml` 中管理
- 根模块禁用了传递 R 类 (`android.nonTransitiveRClass=true`)
