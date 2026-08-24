# 问题：自研 Android 系统监控 App（SysMon）的数据源与权限架构调研（Android 10–16）

## 背景

我在自研一个 Android 系统运行信息监控 App（下称 SysMon），功能对标 htop/btop：

- 主界面直接显示：CPU 总占用 + 每核占用（8 核，Kirin 710：4×A53 + 4×A73）、GPU 占用、内存、电池（温度/容量/电压/电流/输入功率/输出功率/整机消耗功率）、网络吞吐等。
- 另有一个**悬浮窗**（SYSTEM_ALERT_WINDOW overlay），显示用户可选的指标子集，可配置底色/字色/字号/透明度/忽略点击/全屏与横屏显示策略/截屏时隐藏。
- 权限通道分级 fallback：
  1. **Shizuku（shell uid 2000）**：通过 Shizuku-API 的 `bindUserService` 在我方 Service（运行于 shizuku 特权进程）里 `Runtime.exec` 读 `/proc`、`/sys`、`dumpsys`。
  2. **Dhizuku（Device Owner / DPM）**：已按官方流程激活。
  3. **无权限模式**：普通 untrusted_app 身份直接读 `/proc/stat`、`/proc/meminfo`、`BatteryManager` API 等。
  4. **无线 ADB fallback**：在设备本 App 进程内实现 adb wire protocol 客户端，连接本机 adbd（默认先试 5555），用于读取 shell 可读但 untrusted_app 不可读的数据，以及（可选）通过 `start.sh` 自启 Shizuku。
- 目标兼容 **Android 10 (API 29) 到 Android 16 (API 36)**。App 计划 minSdk 26 / targetSdk 29（纯 Java，无 Gradle，aapt2+javac+d8 手工构建）。
- 主测试机：华为畅享 50Z，**HarmonyOS 2.0（AOSP 10 底座，API 29，SELinux enforcing，无 root，ro.adb.secure=1，user build）**。已实测：shell(uid 2000) 下 `/sys/class/thermal` 整体 EPERM、cpufreq 目录大多 EPERM，但 `/proc/stat`、`/proc/meminfo`、`/proc/cpuinfo`、`dumpsys battery`、`dumpsys display` 均可读。

下面按模块列出需要明确结论的问题。请对每个问题给出：**明确结论 + 依据（AOSP 源码路径/官方文档/已知实现链接）**。若某能力"依赖设备厂商实现、无法一概而论"，请给出"探测顺序"（先探测什么、失败再探测什么）。

---

## Q1. GPU 占用率/频率/显存：Android 10–16 上非 root 与 shell 两级可用的数据源

测试机 GPU 为 **ARM Mali-G51 MP4（Bifrost 架构，kernel 4.14，驱动 mali_kbase）**。已知事实：`/dev/mali0` 在 untrusted_app 可 open 但所有 ioctl 在 shell 域 EPERM；`/sys/class/thermal` shell 不可读；全库未采集过 `dumpsys gpu` 输出。

请回答：

1. **`dumpsys gpu`**：
   - 该服务在 AOSP 哪个版本引入？（我理解 Android 14 之前 AOSP 没有标准 "gpu" service，Android 14/15 有 `GpuService` 相关主线模块？）
   - 它输出哪些字段（占用率？频率？显存？每核？）？输出格式样例？
   - 是否所有 vendor（含华为 HarmonyOS 2 这种 AOSP10 底座）都有？华为自研的 GPU 信息服务是否有对应的 `dumpsys` 服务名（如 `dumpsys hisi_*`、`dumpsys GPU` 等）？
2. **Bifrost/Kbase 驱动的用户态数据源**（按优先级）：
   - `/sys/kernel/debug/mali*/...`、`/proc/driver/mali`：需要哪些权限（root？shell？untrusted_app？）？4.14 老内核 + 华为 BSP 下通常哪些节点存在（如 `utilization`、`job_count`、`gpuclk`、`gpupower`）？
   - `/sys/class/devfreq/...`（GPU 频率，类似 cpufreq）：节点路径模式？shell 可读性？
   - 非 Bifrost 平台（Adreno `/sys/class/kgsl/3d0/{gpu_busy_percentage,devfreq/...}`、PowerVR）的等价节点，用于跨机型兼容的探测清单。
3. **无任何 root/shell 时**，untrusted_app 能拿到的 GPU 信息有哪些？（例如 `dumpsys SurfaceFlinger` 的 GPU 时间？`gfxinfo`？`Debug` API？还是基本拿不到占用率，只能显示 "N/A"？）
4. **推荐的数据源探测顺序**（shell 通道下的 fallback 链），例如：`dumpsys gpu` → `kgsl` → `devfreq` → `debugfs` → `N/A`。请给出每一步的具体命令/文件与解析要点。
5. GPU 频率在 Huawei Kirin 710 上有没有任何非 root 可读来源？

## Q2. 电池与功率：数据源矩阵（API 29–36，AOSP + 华为）

1. **`/sys/class/power_supply/battery/` 字段矩阵**：
   - `capacity`、`voltage`、`current_now`、`current_avg`、`charge_now`、`charge_full`、`temp`、`power`、`energy_now`、`cycle_count`、`health`、`status`、`type` —— 各自在 AOSP 哪个版本成为常见？哪些是 vendor 可选（不保证存在）？
   - **shell(uid 2000) 与 untrusted_app 分别对哪些文件可读**（AOSP 默认 SELinux 策略；`/sys` 节点一般 0444 但 SELinux 标签可能挡 untrusted_app）？
   - 华为 HarmonyOS 2 / EMUI 系上，`current_now`（电流，µA）是否可读？我见过很多华为机屏蔽 `current_now`，若屏蔽，shell 下还有什么替代（`dumpsys battery` 的 `Charge counter: 324000` 是什么含义/单位？能否差分出功率）？
2. **`dumpsys battery`**：各字段（status/health/present/level/scale/voltage/temperature/Charge counter/Max charging current/Max charging voltage/AC-USB-Wireless powered）单位与含义；`Charge counter` 是 µAh 累计还是 mWh？差分数值能否换算充电/放电功率（除以时间 × 电压）？精度如何？
3. **BatteryManager API（无权限模式）**：
   - `EXTRA_BATTERY_CURRENT_NOW` / `EXTRA_BATTERY_CURRENT_NOW` 相关字段在哪些 ROM 上真的有值（多数为空？华为呢）？
   - 温度（0.1°C）、电压（mV）、level、health、technology、charging 各 API 的可用性与版本变化（API 29→36 有无新增/弃用，如 `getBatteryTemperature`？Android 15 的 `BatteryManager` 新 API？）
   - 有没有 API 可以直接拿"电池输入功率/输出功率"（mW）？（我理解没有标准 API，只能 µA×mV 自算。）
4. **"手机消耗功率"（整机功耗）与"电池输入/输出功率"的区分**：
   - 电池侧：`current_now × voltage`（充电为正/放电为负的符号约定）。
   - USB/AC 输入侧：读 `/sys/class/power_supply/usb/`（或 `ac`/`mains`）的 `current_now`/`voltage`/`power`？Android 10–16 上这些节点存在性与 shell 可读性？
   - 整机消耗（battery + CPU + GPU + 射频…）：非 root 下有没有任何可靠来源（`/sys/class/hwmon`？`power` HAL？`dumpsys batterystats` 的预估？）？如果拿不到真实整机功耗，请明确说"只能给电池侧功率 + 基于 CPU 负载的估算"，并给一个合理的估算公式建议。
5. 充电状态/插头类型（USB/AC/无线）：`BatteryManager.BATTERY_PLUGGED_*` 与 `/sys/class/power_supply/*/online` 的对应关系。

## Q3. 普通 App（untrusted_app）可读的 /proc 与 /sys 文件：API 29→36 逐版本确认

请在 AOSP 10/11/12/13/14/15/16 上确认以下文件对 **untrusted_app**（普通第三方 App 进程，无任何特殊权限）的可读性（SELinux + 内核层），并指出**从哪个版本开始被收紧**：

1. `/proc/stat`（全核/全局 CPU jiffies）
2. `/proc/meminfo`、`/proc/loadavg`、`/proc/uptime`
3. `/proc/net/dev`（网卡吞吐）、`/proc/net/tcp`（端口统计，需要吗？只问可读性）
4. `/proc/pressure/{cpu,memo,io}`（PSI，内核 4.20+ 才有，4.14 内核无）
5. `/sys/devices/system/cpu/cpu[N]/cpufreq/scaling_cur_freq`、`cpuinfo_max_freq`、`scaling_governor`（API 33+ 是否有变化？华为 4.14 内核上 shell 已确认 EPERM，untrusted_app 是否更差？）
6. `/sys/class/thermal/thermal_zone[N]/{temp,type}`（AOSP 默认 SELinux 标签？API 29 上 untrusted_app 可读吗？API 30+ 有无收紧？Android 14 的 `getDeviceTemperatures()` 走的是 thermal HAL，与 /sys 无关——确认）
7. `/sys/class/power_supply/*`（见 Q2.1，请一并给 untrusted_app 视角）
8. Android 15/16 是否有新的 procfs/sysfs 限制（例如针对 `/proc/self/`、`hiddenpid`、"restricted" 挂载选项的变化）影响以上文件？

**期望输出**：一张矩阵表（行=文件，列=API 29/30/31/32/33/34/35/36，值=untrusted_app 可读性 + 备注），并标注"华为 HarmonyOS 2（AOSP10 底座）上以 AOSP 10 列为准 + 厂商可能额外收紧"。

## Q4. 设备内自建 adb 客户端：端口发现、配对、授权（Android 10–16）

我计划把 Shizuku 源码里的 `AdbClient`/`AdbPairingClient`（Kotlin，含 CNXN/STLS TLS 握手、AUTH RSA 签名、AdbMessage CRC32 协议）移植成 Java，在**手机本 App 进程内**连接**本机 adbd**（loopback `127.0.0.1`）。

1. **端口发现**（核心难点）：
   - Android 11+ 的"无线调试"：连接端口与配对端口都是**随机端口（范围？）**，adbd 监听 `0.0.0.0` 还是仅本机？端口保存在哪些 `settings` 键（我理解 `adb_wifi` / `adb_wifi_pairing`，请确认键名与值格式 `ip:port`）？这些键**shell 可读吗**（`settings get global adb_wifi`）？untrusted_app 可读吗（Settings.Global 的 protected 列表）？
   - **mDNS**：adbd 是否发布 `_adb-tls-connect._tcp` / `_adb-tls-pairing._tcp` 服务？在**同一台设备 loopback** 上，App 的 mDNS 监听能否收到本机 adbd 的通告（multicast 224.0.0.251 在 lo/wlan0 上的行为）？Shizuku 的 `AdbMdns.kt` 实际是怎么用的（只在局域网用，还是也用于本机）？
   - **端口扫描**：在 `127.0.0.1` 上扫描 5037–55551（约 5 万端口）找 adbd，用短超时 TCP connect（RST 即排除），loopback 下实际耗时量级？有没有更快的定向方式（先试 5555 → `adb_wifi` 键 → mDNS → 全段扫描）？
   - Android 10（无"无线调试"UI）：只有 legacy `adb tcpip 5555`（需电脑/USB 触发过一次）或本机已有 adbd 监听场景，请确认。
2. **连接与授权**：
   - 通过**连接端口**（非配对端口）连接时，是否要求主机已事先配对？未配对时服务端行为（拒绝？还是也走配对握手）？
   - 配对流程：`AdbPairingClient` 连**配对端口**，协议 banner 是 `pair-v1.0`？配对码（6 位数字）在谁这边显示、如何校验？配对成功后凭证存哪（服务端 `/data/misc/adb/` 哪里），**是否持久**（重启后是否还要重新配对）？
   - **RSA 主机密钥**：无线调试走 TLS（STLS），TLS 会话内还做传统 `AUTH` 公钥授权吗？`ro.adb.secure=1` 时，新公钥首次连接会触发设备端授权通知（用户点"允许"一次后持久到 `adb_keys`）——对无线连接是否同样成立？我的 App 作为 adb client 需要**自己生成并持久保存 RSA-2048 密钥对**（adbkey 格式：PKCS#8 私钥 PEM + 公钥 PEM 带指纹注释），`java.security.KeyPairGenerator` 生成 + 手工 PEM 编码是否可行、有无坑（adbd 对公钥格式/长度的要求）？
   - loopback 连接（127.0.0.1）与走 wlan0 连自己 IP，对 adbd 而言有差别吗（来源地址是否影响授权/限流）？
   - Android 10 的 adbd 是**非 TLS**（A_VERSION 0x01000000）；Android 11+ 是 TLS（`STLS`）。`AdbClient` 的 `handleSTLS` 在**本机自连**场景是否需要特殊处理（证书校验方向）？Shizuku 源码里 STLS 是怎么校验对端证书的（`checkAdbdCertificate`？）？
3. **用本机 adb 通道自启 Shizuku**：连接成功后执行 `shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`（或新版等效路径），在 Android 10（USB 触发过 tcpip 5555）与 Android 11+（无线调试已配对）两条路径上是否都可行？start.sh 路径在 Shizuku 13.x/14.x（2025/2026 版本）是否变化？
4. 有没有已知的"App 内 adb client 连本机 adbd"的先例项目（除 Shizuku 外，如 termux-adb、其他 Shizuku 替代品）？它们的端口发现做法？

## Q5. 悬浮窗（SYSTEM_ALERT_WINDOW overlay）：Android 10–16 的行为与"截屏隐藏"

1. **窗口类型与 flags 组合**：显示一个"指标状态"小窗（约 200×120dp，等宽字体文本），要求：可拖动、默认不挡手势（可选项）、在**全屏视频/横屏游戏**里也可见（或按用户选项隐藏）。
   - `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE` + `FLAG_NOT_TOUCHABLE`（可选）+ `FLAG_LAYOUT_IN_SCREEN` + `FLAG_LAYOUT_NO_LIMITS` 是否是正确组合？各 flag 在 API 29→36 的行为有无变化？
   - 全屏 immersive（游戏 `hideSystemBars`）里 overlay 会不会被隐藏？`FLAG_SECURE` 的 App 里 overlay 是否自动不可见（截屏也截不到 overlay，这是系统行为？）？
   - 横屏时 overlay 位置如何保持（保存归一化坐标 x/W, y/H 重算？还是保存绝对坐标 + rotation 事件重放）？
2. **截屏时隐藏悬浮窗**（"截屏忽略悬浮窗"）：
   - 目标：用户截屏时，悬浮窗不出现在截屏图里。
   - **事前拦截**是否可能？方案 a) 无障碍服务监听 `KEYCODE_VOLUME_DOWN`+`POWER` 组合（`FLAG_REQUEST_FILTER_KEY_EVENTS`），在系统执行截屏前 hide()——无障碍服务收到的 KeyEvent 时序是否早于系统截屏动作？（请确认输入事件分发顺序：AccessibilityService 是在 InputDispatcher 里哪个阶段收到事件？系统截屏快捷键在哪一层消费？）方案 b) 监听无障碍 `onWindowContentChanged`/`TYPE_WINDOW_STATE_CHANGED` 检测截屏预览窗？
   - **事后检测**：`Intent.ACTION_SCREENSHOT`（`android.intent.action.SCREENSHOT`，旧）与 `android.media.action.SCREENSHOT_TAKEN` 哪个可用？是否 protected（普通 App 能否注册接收）？`MediaStore` ContentObserver 监听 `Pictures/Screenshots`（scoped storage 下 App 能看到系统截屏的 URI 插入吗，需要什么权限）？哪种方式延迟最小、在 API 29→36 都可用？
   - 如果只能事后检测，产品语义变成"检测到截屏后隐藏 N 秒（覆盖用户连拍）"——请评估这个语义在 API 29→36 上的可行性与延迟量级（通常几十 ms？）。
3. **前台 App 全屏/沉浸状态检测**（用于"全屏应用时隐藏"选项）：
   - 无障碍服务 `getWindows()` 返回的 `AccessibilityWindowInfo.getFlags()` 中 `FLAG_FULLSCREEN`（0x00000400）+ API 30+ 的 `isImmersive`？能否可靠判断"当前前台是全屏游戏/视频"？
   - 无无障碍权限时，shell 通道 `dumpsys window` / `dumpsys activity activities` 解析 `mImmersive`/`topResumedActivity` 的可行性与开销（每 2s 一次）？
   - 横屏检测：`Configuration.orientation` 最简单——确认无坑。

## Q6. Dhizuku 对"监控/悬浮窗"类 App 的实际价值 + Shizuku API 版本约束

1. 对 SysMon 这类**只读监控 + overlay** 的 App，Dhizuku（Device Owner / DPM）有没有实际用武之地？候选：
   - DPM 能否帮第三方 App **忽略电池优化**（`setIgnoreBatteryOptimizations` 是 DPM API 吗？还是只能走 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 用户弹窗）？
   - `lockTaskMode` / kiosk、`setMinimumScreenBrightness`（锁定最高亮度防止游戏降亮干扰监控？）、`setMaximumCapturedFrameRate`（限制前台帧率，游戏兼容选项？）——这些 DPM 能力在"非设备管理场景的普通用户设备"上使用是否有副作用（Device Owner 被 Dhizuku 占用后，第三方 App 经 Dhizuku 代理调用 DPM 是否会被系统拒绝，因为 DPM 很多 API 只有 Device Owner 自己能调）？
   - 结论：SysMon 是否值得集成 Dhizuku？若值得，集成哪些具体能力；若不值得，明确说"仅保留状态显示，不集成"。
2. **Shizuku-API 版本**：App minSdk 26 / **targetSdk 29**，但要跑在 Android 14/15/16 设备上：
   - 最低可用的 Shizuku-API 版本？（已知 13.1.5 修复了 target 34 App 的 binder 崩溃，我们 target 29 是否不受该问题影响？是否仍建议用最新版 13.1.5/14.x？）
   - `ShizukuProvider` 在 Android 14+ 是否仍必须声明？`moe.shizuku.manager.permission.API_V23` 声明是否仍需要？
   - Shizuku server 13.x/14.x 在 Android 15/16 上的 ADB 模式（无线调试）启动流程是否与 Android 12/14 一致？

## Q7. 小问题合集（测试机 Kirin 710 / 4.14 内核）

1. Kirin 710（4×A53 + 4×A73，big.LITTLE 双 cluster）：`/sys/devices/system/cpu/cpu[N]/cpufreq/` 的 cluster 映射（cpu0-3 小核一组、cpu4-7 大核一组？还是相反）？`scaling_cur_freq` 每个 cpu 独立还是 cluster 共享？在 shell EPERM 的情况下，**untrusted_app 能否读 `scaling_cur_freq`**（AOSP10 上 /sys cpufreq 节点 SELinux 标签是什么）？
2. 华为机 `dumpsys thermalservice` 的输出格式（shell 可读？含哪些传感器/温度值/级别）？是否可作为"无 /sys/class/thermal 权限时的温度替代"？
3. `/proc/stat` 的 `cpu` 行字段在 Android 10→16 内核上是否稳定（user nice system idle iowait irq softirq steal guest guest_nice）？`/proc/[pid]/stat` 是否**不**用于每进程监控（htop 风格我们只做系统级 + top 进程可选，top 进程需要 shell 的 `top -b -n1` 或 `/proc/[pid]/stat` 遍历——untrusted_app 遍历 /proc 读其他进程 stat 是否可行？SELinux 对 /proc/[pid]/stat 的 untrusted_app 可读性）？
4. 网络吞吐：`/proc/net/dev` 的 bytes 计数在 4.14 内核上是否包含 lo？如何识别主网卡（wlan0 vs rmnet0 vs eth0，按接口枚举 + 选 rx 最大？）？shell 下 `dumpsys netstats` 是否更准（流量统计含 TCP 层）？

## 期望输出格式

- 按 Q1–Q7 逐条回答，每条给出**明确结论 + 依据链接/源码路径**。
- 涉及"厂商实现差异"的，给出**探测顺序建议**（命令/文件 + 解析要点 + 失败 fallback）。
- 最后给一节"对 SysMon 的落地建议"：
  - GPU/温度/电池功率三个模块在【无权限】【shell 通道】两种模式下的**字段可用性矩阵**（哪些字段能显示、哪些显示 N/A）。
  - 无线 ADB fallback 的**推荐实现顺序**（端口发现 → 握手 → 授权 → 数据通道/自启 Shizuku）。
  - 悬浮窗"截屏隐藏"的**推荐实现**（事前/事后/两者结合）。
