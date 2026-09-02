# androidplay001

> 荧荧（Luminylumine）本人玩手机的记录仓库：围绕自己手上的安卓设备（主力测试机：华为畅享 50Z / EVE-AL00）折腾出来的三个工具，以及它们的公共基础层与开发文档。

## 仓库目的

这里记录荧荧本人玩手机、改手机、给手机造工具的过程与成果。仓库按「公共基础层 + 功能分支」组织：

- `main` 是公共基础层（common/），放所有项目共用的 SDK 资源与开发文档；
- 每个功能项目单独一条 feature 分支，从 main 切出（功能开发依赖 common 层）；
- 以后每折腾出一个新东西，就再加一条分支，结构不变。

## 分支一览

| 分支 | 内容 | 一句话介绍 |
| --- | --- | --- |
| `main` | `common/` 基础层 | 公共 Android SDK（Shizuku / Dhizuku / scrcpy）+ 项目开发文档（`common/docs/`，含 chatgpt 问答记录、trae 规范等） |
| `feature/scrcpy-enhance` | `projects/scrcpy_enhance/` | 基于 scrcpy 的 Windows 投屏/设备管理小工具（.NET 9 WinForms） |
| `feature/akasha-android` | `projects/akasha_android/` | Akasha 安卓 Agent 终端（`com.akasha.app`，原 ClawPhone 更名而来） |
| `feature/device-monitor-tools` | `projects/device_monitor_tools/` | SysMon 安卓运行监视器（`com.sysmon.app`，电池 / GPU / 帧率 / 曲线绘制） |
| `feature/phone-mirror-phone` | `projects/phone_mirror_phone/` | Android 手机投屏与控制实验项目 |
| `voice-io` | `projects/akasha_android/` | Akasha voice-I/O 实验分支 |

本仓库使用长期项目分支，而不是把每个项目都放在默认分支：请先切换到目标分支再构建。各项目分支包含根级许可证和本说明文件；分支之间互相独立。

## 公共依赖（工具链，不入库）

以下工具链体积大且属于本机环境，已被 `.gitignore` 忽略，**clone 后需要在本机自行准备**（放到仓库根目录对应路径下）：

```
tools/
├── jdk17/                              # JDK 17（Android 项目 javac/d8/apksigner 用）
├── android-sdk/
│   ├── build-tools/30.0.3/             # Akasha / SysMon 本地构建
│   ├── build-tools/34.0.0/             # PhoneMirror Android Gradle Plugin 用
│   ├── platforms/android-29/android.jar
│   ├── platforms/android-34/android.jar
│   └── platform-tools/adb.exe          # 仅 scrcpy-enhance 运行时用（可选）
└── scrcpy/scrcpy.exe                   # 仅 scrcpy-enhance 运行时用（可选）
```

- scrcpy-enhance 分支另外需要 **.NET 9.0 SDK**（`dotnet build` 用）。
- 所有构建脚本都使用**相对路径**：从脚本所在位置向上查找 `.git/` 目录定位仓库根，因此 clone 到任意位置、在任意工作目录下执行脚本都可以。
- 公共 SDK jar 在 git 内，位于 `common/sdks/`（Shizuku api/provider/aidl、Dhizuku api），Android 分支的 javac/d8 classpath 直接引用它们，无需额外准备。
- `common/sdks/README.md` 和 `THIRD_PARTY_NOTICES.md` 记录了第三方组件、来源、版本和许可证；不需要构建的 upstream 源码不会随项目分支发布。

## 编译方法

### 1. feature/scrcpy-enhance（Windows）

```powershell
git checkout feature/scrcpy-enhance
dotnet build projects/scrcpy_enhance/AdbManager.csproj -c Release
```

产物：`projects/scrcpy_enhance/bin/Release/net9.0-windows/AdbManager.exe`

### 2. feature/akasha-android（Windows）

```powershell
git checkout feature/akasha-android
powershell -NoProfile -ExecutionPolicy Bypass -File projects/akasha_android/app/build_akasha.ps1
```

构建链：`aapt2 compile → aapt2 link → javac（classpath 含 common/sdks）→ d8 → apksigner`。
签名 keystore（`app/akasha.keystore`，alias `akasha`）首次构建自动生成，已被 gitignore 忽略。

产物：`projects/akasha_android/app/build/Akasha-v1.apk`

### 3. feature/device-monitor-tools（Windows）

```powershell
git checkout feature/device-monitor-tools
powershell -NoProfile -ExecutionPolicy Bypass -File projects/device_monitor_tools/app/build_sysmon.ps1
```

构建链与签名方式同上（keystore `app/sysmon.keystore`，alias `sysmon`，自动生成）。

产物：`projects/device_monitor_tools/app/build/SysMon-v1.apk`

### 4. feature/phone-mirror-phone（Windows）

```powershell
git checkout feature/phone-mirror-phone
Copy-Item projects/phone-mirror-phone/local.properties.example projects/phone-mirror-phone/local.properties
# Edit local.properties and set sdk.dir to your Android SDK.
powershell -NoProfile -Command "$env:JAVA_HOME='tools/jdk17'; & 'projects/phone-mirror-phone/gradlew.bat' assembleDebug"
```

`local.properties` is machine-specific and must not be committed.

> `AdbKey.java` 不包含任何预置测试机私钥。首次运行时使用 Android Keystore 为当前安装生成独立的 ADB 身份；构建不需要额外的本地秘密源码。

## 使用方法

### scrcpy_enhance（AdbManager.exe）

1. 双击运行 `AdbManager.exe`；
2. adb / scrcpy 可执行文件按以下顺序解析：仓库内 `tools\android-sdk\platform-tools\adb.exe`、`common\sdks\scrcpy\scrcpy.exe`（或 `tools\scrcpy\scrcpy.exe`），找不到则回退到系统 PATH 里的 `adb` / `scrcpy`；
3. 设备开启 USB 调试（或无线调试）后，在界面中连接设备，即可投屏、查看设备信息、传输文件等。

### akasha（Akasha-v1.apk）

1. `adb install build/Akasha-v1.apk` 安装到手机；
2. 按应用内引导启动 Shizuku 或 Dhizuku 并授权（应用通过二者获取 shell 能力）；
3. 在应用内使用 Agent 终端功能。

### sysmon（SysMon-v1.apk）

1. `adb install build/SysMon-v1.apk` 安装到手机；
2. 按应用内引导启动 Shizuku 或 Dhizuku 并授权；
3. 使用电池 / GPU / 帧率采样、曲线绘制与悬浮窗监视功能（需要悬浮窗、忽略电池优化等权限，按引导授予）。

## 产物与发布约定

- **编译中间文件与编译产物一律不推送**：`build/`、`bin/`、`obj/`、`gen/`、`*.apk`、`*.apk.idsig`、`*.dex`、`*.dll`、`*.exe`、keystore 等全部由 `.gitignore` 过滤，仓库内只保留源码、文档与公共 SDK；
- **编译产物统一发布到 GitHub Releases**：每个项目发布时，把编译好的产物（APK / exe）挂到对应 Release 上，仓库历史中不放任何二进制产物。

## 隐私与敏感内容

- `.gitignore` 对私钥类文件（`adbkey`、`*.p12`、`*.pfx`、`*.keystore`、`*.jks`、测试机 ADB 密钥等）做了精确忽略；
- 本仓库不包含任何 sk- 开头的 API 密钥；文档中出现的 `apiKey` 等均为字段名/占位符。
## Security model

Akasha and SysMon may obtain shell-level privileges through Shizuku or Dhizuku.
These privileges can access device information unavailable to ordinary Android
applications. Only grant these permissions if you understand and trust the
application. Shizuku, Dhizuku, scrcpy, Android, Huawei, and Xiaomi are third-party
projects or trademarks; this repository is not affiliated with or endorsed by them.
