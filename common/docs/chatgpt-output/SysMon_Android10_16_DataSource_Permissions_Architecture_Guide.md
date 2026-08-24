# SysMon Android 10–16 数据源、权限与架构落地指南

> 面向：自研 Android 系统监控 App（SysMon）  
> 目标：**安装包小、实现简单、安全不崩、扩展性强、常驻开销低、采样速度快**  
> 兼容范围：Android 10–16（API 29–36）  
> 推荐构建：minSdk 26 / targetSdk 29（可暂时保留）/ compileSdk 尽量跟进 API 36  
> 语言/工具链：纯 Java、aapt2 + javac + d8，可不依赖 Gradle

---

# 0. 总体结论

SysMon 不应该设计成：

```text
无权限模式
→ Shizuku 模式
→ Dhizuku 模式
→ ADB 模式
```

然后整套数据源一起切换。

更合理的架构是：

```text
每个 Metric 独立选择最优 Provider
```

例如：

```text
BATTERY_TEMP
    → BatteryManager / ACTION_BATTERY_CHANGED

CPU_USAGE
    → /proc/stat via Shizuku
    → /proc/stat via embedded ADB
    → vendor bonus direct access
    → N/A

GPU_FREQ
    → devfreq
    → vendor sysfs
    → vendor debugfs/proc
    → N/A
```

即使 Shizuku 已可用，也不要为了“统一 shell 模式”而每秒执行：

```bash
dumpsys battery
```

Framework API 能低成本提供的数据，应始终优先 Framework API。

---

# 1. 几个必须先纠正的架构前提

## 1.1 `/proc/stat` 不能作为普通 App 的标准基线

Android 从较早版本开始已经明显收紧第三方 App 对 procfs 的读取。

因此：

### 普通 `untrusted_app`

可以可靠依赖：

```text
/proc/meminfo
BatteryManager
ACTION_BATTERY_CHANGED
TrafficStats
SystemClock
ConnectivityManager
```

不能可靠依赖：

```text
/proc/stat
/proc/loadavg
/proc/uptime
/proc/net/dev
/proc/net/tcp
其他进程 /proc/<pid>/stat
```

即使某些 Huawei / Xiaomi ROM 上实测能读，也只能视为：

```text
VendorBonusProvider
```

而不能作为 API 29 通用保证。

---

## 1.2 `dumpsys gpu` 并不是 Android 14 才出现

AOSP 的 GPU Service lineage 远早于 Android 14。

历史上：

```text
frameworks/native/services/surfaceflinger/GpuService.cpp
```

后来拆出：

```text
frameworks/native/services/gpuservice/
```

service name 通常为：

```bash
gpu
```

因此可以探测：

```bash
service check gpu
dumpsys gpu
```

但是：

> `dumpsys gpu` 不是统一的“GPU 当前占用百分比 / 当前频率”API。

其主要功能是：

- GPU driver 信息；
- GPU memory accounting；
- GPU stats；
- 新版本中的 GPU work / time-in-frequency-state。

所以不能把它设计成：

```text
GPU utilization 首要来源
```

---

## 1.3 Dhizuku 不能作为 `/proc` / `/sys` 权限 fallback

Dhizuku：

```text
Device Owner / DevicePolicyManager 身份
```

Shizuku：

```text
shell uid 2000
```

两者完全不同。

Device Owner 不代表：

```text
可以任意读取 shell 可读 /proc /sys
```

因此 SysMon 的权限架构应该是：

```text
Framework / direct App API
        ↓
ShellBackend
   ├─ Shizuku
   └─ embedded ADB
        ↓
Optional DeviceOwner capability
```

Dhizuku 只能作为：

```text
某些 Device Owner 专属 Framework API 的附加 Provider
```

而不是主要数据采集后端。

---

# Q1. GPU 占用率 / 频率 / GPU Memory

---

# Q1.1 `dumpsys gpu`

## 明确结论

GPU service 并不是 Android 14 新增。

历史代码路径包括：

```text
frameworks/native/services/surfaceflinger/GpuService.cpp
```

后来迁移到：

```text
frameworks/native/services/gpuservice/GpuService.cpp
```

较新版本可以看到类似：

```bash
dumpsys gpu --gpudriverinfo
dumpsys gpu --gpumem
dumpsys gpu --gpustats
dumpsys gpu --gpuwork
```

其中：

### `--gpudriverinfo`

主要是：

- driver package；
- version；
- driver metadata。

### `--gpustats`

偏 Vulkan / graphics driver 统计。

### `--gpumem`

GPU memory accounting。

### `--gpuwork`

较新 Android 中可能有：

```text
GPU time in frequency state
per UID GPU work
```

本质是工作时间 / 频率驻留统计。

它不是标准输出：

```text
GPU utilization: 63%
GPU frequency: 600MHz
```

因此：

> 对 SysMon 的 GPU 实时占用，不应优先依赖 `dumpsys gpu`。

---

## Huawei 是否一定有

不保证。

建议第一次 capability probe：

```bash
service list | grep -Ei 'gpu|mali|hisi|hisilicon|graphic|dss'

dumpsys -l | grep -Ei 'gpu|mali|hisi|hisilicon|graphic|dss'
```

发现可能服务后：

```bash
dumpsys <service>
dumpsys <service> --help
```

不要硬编码猜测：

```text
hisi_gpu
GPU
hisi_graphic
```

因为没有跨 EMUI / HarmonyOS 版本稳定保证。

---

# Q1.2 Mali Bifrost / kbase 推荐数据源

对于 Mali-G51 / kbase：

```text
devfreq
↓
vendor sysfs
↓
debugfs
↓
vendor procfs
↓
AOSP GPU work accounting
↓
N/A
```

---

## A. `/sys/class/devfreq`

先枚举，而不是猜 GPU 路径：

```bash
find /sys/class/devfreq -maxdepth 2 -type f 2>/dev/null
```

建议逐个：

```bash
for d in /sys/class/devfreq/*; do
    echo "=== $d"
    readlink -f "$d"
    cat "$d/name" 2>/dev/null
    cat "$d/cur_freq" 2>/dev/null
    cat "$d/available_frequencies" 2>/dev/null
    cat "$d/governor" 2>/dev/null
    cat "$d/load" 2>/dev/null
done
```

常见真实节点：

```text
/sys/devices/platform/.../devfreq/<device>/cur_freq
```

而 `/sys/class/devfreq/*` 只是 symlink。

kbase devfreq 驱动内部本身通常维护：

```text
busy_time
total_time
current_frequency
```

但能否通过 sysfs 读到，是 vendor/SELinux 问题。

---

## `load` 量纲不能写死

某些 vendor：

```text
0..100
```

另一些可能：

```text
0..1000
```

因此 capability probe 阶段应该：

1. 连续采样 5–10 次；
2. 验证最大值；
3. 判断输出格式；
4. 缓存解析规则。

不要直接：

```java
usage = value / 100.0;
```

---

# Q1.3 Mali debugfs

可能出现：

```text
/sys/kernel/debug/mali0/
/sys/kernel/debug/mali/
/sys/kernel/debug/mali-kbase/
```

vendor 可能暴露：

```text
utilization
gpuclk
job_count
memory
pm
power
```

但是：

> debugfs 节点不是稳定 ABI。

依赖：

```text
CONFIG_DEBUG_FS
driver config
vendor BSP
SELinux
mount policy
```

普通 App：

```text
基本不可依赖
```

shell：

```text
也经常被 SELinux 拒绝
```

root/userdebug：

```text
成功率最高
```

对于你已经实测：

```text
shell → /sys/class/thermal EPERM
shell → cpufreq 大量 EPERM
```

的 Huawei 设备：

> Mali debugfs 也应默认视为高概率不可用。

---

# Q1.4 `/proc/driver/mali`

可能存在：

```text
/proc/driver/mali
/proc/mali
```

但完全属于：

```text
vendor / driver 私有接口
```

只能 probe。

不能作为 Android 10–16 保证。

---

# Q1.5 Adreno

Qualcomm 常见：

```text
/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage
```

以及：

```text
/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq
/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies
/sys/class/kgsl/kgsl-3d0/devfreq/governor
```

有些版本还存在：

```text
gpubusy
```

如果提供的是累计 busy/total：

```text
util = Δbusy / Δtotal
```

比直接读瞬时百分比更适合监控器。

---

# Q1.6 PowerVR

没有统一 Android ABI。

建议：

1. 枚举 `/sys/class/devfreq/*`；
2. 根据 `name` / symlink target 判断：
   - `gpu`
   - `pvr`
   - `rogue`
3. 再探测 vendor sysfs/debugfs；
4. 失败直接 N/A。

不要维护大量：

```java
if (Build.MANUFACTURER...)
```

机型表。

---

# Q1.7 无 root / shell 时 GPU 能拿什么

## GPU 型号

可通过 OpenGL ES：

```java
glGetString(GL_RENDERER)
glGetString(GL_VENDOR)
glGetString(GL_VERSION)
```

或者 Vulkan physical device enumeration。

例如：

```text
Mali-G51
Adreno (TM) 740
```

---

## 全局 GPU utilization

没有跨厂商公共 Android API。

因此：

```text
N/A
```

是正确结果。

---

## GPU frequency

没有跨厂商公共 API。

只允许 vendor bonus probe。

---

## GPU memory

移动 GPU 多为 UMA：

```text
CPU / GPU 共用系统 DRAM
```

所以 UI 不建议叫：

```text
显存容量
```

更准确：

```text
GPU Memory Usage
GPU Allocated Memory
```

如果：

```bash
dumpsys gpu --gpumem
```

支持，则可显示。

---

## `gfxinfo`

```bash
dumpsys gfxinfo <package>
```

可以获得：

- rendering stats；
- jank；
- frame timing；
- 某些 graphics memory 信息。

不能用来推算：

```text
whole GPU utilization
```

---

# Q1.8 最终 GPU Provider 探测顺序

推荐：

```text
GPU identity
    ↓
enumerate /sys/class/devfreq/*
    │
    ├─ Mali → MaliDevfreqProvider
    ├─ KGSL → AdrenoKgslProvider
    └─ PVR  → PowerVrProvider
    ↓
vendor sysfs
    ↓
KGSL special nodes
    ↓
Mali debugfs / proc
    ↓
dumpsys gpu capabilities
       ├─ gpumem
       ├─ gpuwork
       └─ gpustats
    ↓
N/A
```

---

# Q1.9 Kirin 710 GPU Frequency

最值得先测试：

```bash
for d in /sys/class/devfreq/*; do
    echo "--- $d"
    cat "$d/name" 2>/dev/null
    cat "$d/cur_freq" 2>/dev/null
done
```

然后：

```bash
find /sys \( -iname '*mali*' -o -iname '*gpu*' \) 2>/dev/null
```

如果 shell 下 devfreq 同样：

```text
EPERM
```

则 SysMon 应明确显示：

```text
GPU Frequency: N/A
```

不要为了这个字段尝试 ioctl `/dev/mali0`。

---

# Q2. 电池与功率

---

# Q2.1 power_supply 字段

Linux power_supply 常见：

| 字段 | 单位 / 含义 | 稳定性 |
|---|---:|---|
| `capacity` | % | 高 |
| `voltage_now` | µV | 高 |
| `current_now` | µA | 可选 |
| `current_avg` | µA | 可选 |
| `charge_now` | µAh | 可选 |
| `charge_full` | µAh | 可选 |
| `charge_full_design` | µAh | 可选 |
| `energy_now` | µWh | 可选 |
| `power_now` | µW | 可选 |
| `cycle_count` | 次 | 可选 |
| `temp` | 通常 0.1°C | driver/vendor |
| `health` | enum text | 常见 |
| `status` | enum text | 常见 |
| `type` | Battery/USB/Mains... | 常见 |
| `online` | 0 / 1 | charger |

注意：

标准字段一般为：

```text
voltage_now
power_now
```

而不是泛化：

```text
voltage
power
```

---

# Q2.2 Android 版本不是 power_supply capability 的决定因素

不能简单写：

```text
Android 11 有 current_now
Android 12 才有 charge_full
```

真正链路：

```text
fuel gauge / charger driver
        ↓
Linux power_supply class
        ↓
Health HAL / healthd
        ↓
BatteryService
```

AOSP healthd 自身就是：

```text
节点存在 → 使用
节点不存在 → 忽略
```

因此这里必须 runtime capability probe。

---

# Q2.3 Huawei `current_now`

不要假设一定可读。

探测链：

```text
/sys/class/power_supply/battery/current_now
↓
BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
↓
dumpsys battery
↓
Charge Counter derivative
↓
N/A
```

即使 sysfs 被 Huawei SELinux 屏蔽：

```text
BatteryManager
```

仍可能通过 Health HAL 获得电流。

---

# Q2.4 `dumpsys battery` 字段单位

典型：

```text
AC powered: false
USB powered: true
Wireless powered: false
Max charging current: ...
Max charging voltage: ...
Charge counter: ...
status: ...
health: ...
present: true
level: ...
scale: 100
voltage: ...
temperature: ...
technology: ...
```

推荐按：

| 字段 | 单位 |
|---|---|
| Max charging current | µA |
| Max charging voltage | µV |
| Charge counter | µAh |
| voltage | mV |
| temperature | 0.1°C |
| level | `level / scale` |

---

## `Charge counter` 不是 mWh

例如：

```text
Charge counter: 3240000
```

表示约：

```text
3240000 µAh
= 3240 mAh
```

它代表当前剩余电荷估计。

---

# Q2.5 用 Charge Counter 差分估算电流

可以：

\[
I_{\mu A}
=
\frac{\Delta Q_{\mu Ah}\times3600}
{\Delta t_s}
\]

例如：

```text
60 秒内 ΔQ = -833 µAh
```

则：

```text
I ≈ -833 × 3600 / 60
  ≈ -49980 µA
  ≈ -50 mA
```

然后：

\[
P_W
=
\frac{I_{\mu A}\times V_{mV}}
{10^9}
\]

但是不能 1 秒差分。

fuel gauge 通常存在量化台阶：

```text
0
0
0
突然 -5W
0
```

推荐窗口：

```text
30–120 秒
```

UI 应显示：

```text
Average Battery Power
```

而不是：

```text
Instant Power
```

---

# Q2.6 BatteryManager

标准电流：

```java
BatteryManager bm =
    (BatteryManager) context.getSystemService(
        Context.BATTERY_SERVICE);

int currentUa =
    bm.getIntProperty(
        BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
```

单位：

```text
µA
```

一般语义：

```text
positive → charging
negative → discharging
```

需要防御：

```text
Integer.MIN_VALUE
0xffffffff 类异常
明显超出合理范围的 vendor 值
```

---

# Q2.7 ACTION_BATTERY_CHANGED

可获得：

```text
EXTRA_LEVEL
EXTRA_SCALE
EXTRA_STATUS
EXTRA_HEALTH
EXTRA_PLUGGED
EXTRA_VOLTAGE
EXTRA_TEMPERATURE
EXTRA_TECHNOLOGY
```

温度：

```text
EXTRA_TEMPERATURE / 10.0 °C
```

电压：

```text
mV
```

对于 SysMon：

> 电池温度、level、charging state 不需要 shell。

---

# Q2.8 Android 没有标准“输入功率 / 输出功率”API

BatteryManager 不提供：

```text
battery_input_power_w
device_output_power_w
```

通常只能：

\[
P=V\times I
\]

自己推导。

---

# Q2.9 必须区分三种“功率”

## Battery Power

定义：

\[
P_{battery}=V_{battery}\times I_{battery}
\]

推荐 UI 约定：

```text
+ → 电池吸收功率 / charging
- → 电池向系统供电 / discharging
```

例如：

```text
+8.2 W
-3.3 W
```

---

## Charger/Input Power

首先枚举：

```bash
for d in /sys/class/power_supply/*; do
    echo "$d"
    cat "$d/type" 2>/dev/null
    cat "$d/online" 2>/dev/null
done
```

不要假设一定叫：

```text
usb
ac
mains
```

可能是 vendor 名称。

在线节点再尝试：

```text
voltage_now
current_now
power_now
```

注意以下字段：

```text
current_max
voltage_max
input_current_limit
input_power_limit
```

表示的是：

```text
能力 / 上限 / limit
```

不是实时功率。

---

## Whole Device Power

普通 Android App：

> 没有 Android 10–16 跨厂商标准“整机实时功率 W”接口。

---

### 放电时

可以合理显示：

```text
Estimated Device Power
≈ -Battery Power
```

例如：

```text
3.82 V
-850 mA
≈ 3.25 W
```

这实际上很有用。

---

### 充电时

如果同时得到：

```text
P_input
P_battery_charge
```

理论：

\[
P_{system}
\approx
P_{input}-P_{battery}
\]

但会受到：

- charger efficiency；
- PMIC loss；
- cable loss；
- measurement point；
- fuel gauge error；

影响。

因此只能叫：

```text
Estimated System Load
```

不要叫：

```text
Actual Device Power
```

---

# Q2.10 Plug Type

Framework：

```text
BATTERY_PLUGGED_AC
BATTERY_PLUGGED_USB
BATTERY_PLUGGED_WIRELESS
BATTERY_PLUGGED_DOCK
```

用于：

```text
UI 展示
```

而：

```text
/sys/class/power_supply/*/online
```

用于底层 detailed charger provider。

两者不是严格一一映射。

---

# Q3. 普通 App 的 `/proc` / `/sys`

符号：

```text
✅ 可作为标准基线
❌ 不可作为普通 App 基线
⚠ vendor / SELinux dependent，只能 probe
```

| 路径 | API29 | API30 | API31 | API32 | API33 | API34 | API35 | API36 |
|---|---|---|---|---|---|---|---|---|
| `/proc/stat` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `/proc/meminfo` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/proc/loadavg` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `/proc/uptime` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `/proc/net/dev` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `/proc/net/tcp` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `/proc/pressure/*` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| CPU cpufreq sysfs | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ |
| thermal sysfs | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ |
| power_supply sysfs | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ | ⚠ |

Huawei HarmonyOS 2：

```text
以 AOSP Android 10 能力为参考
+ 厂商可额外收紧
```

不能反向假设：

```text
AOSP 不允许 → Huawei 一定不允许
```

或：

```text
Huawei 某台能读 → Android10 全部能读
```

---

# Q3.1 `/proc/meminfo`

这是普通 App 最值得直接读取的 `/proc` 数据源。

用于：

```text
MemTotal
MemAvailable
Cached
Buffers
SwapTotal
SwapFree
```

如果只需要系统内存：

Framework `ActivityManager.MemoryInfo` 也应该作为首选/备用。

---

# Q3.2 `/proc/uptime`

普通 App 没必要读。

直接：

```java
SystemClock.elapsedRealtime()
```

更稳定、更便宜。

---

# Q3.3 `/proc/net/dev`

普通 App不要依赖。

改用：

```java
TrafficStats.getTotalRxBytes()
TrafficStats.getTotalTxBytes()
```

计算：

```text
rxRate = Δrx / Δt
txRate = Δtx / Δt
```

shell 模式再升级到：

```text
/proc/net/dev
```

用于 per-interface。

---

# Q3.4 PSI

路径：

```text
/proc/pressure/cpu
/proc/pressure/memory
/proc/pressure/io
```

4.14 upstream 不具备标准 PSI，但 vendor 可以 backport。

因此：

```text
存在性 probe
```

比：

```text
根据 kernel version 判断
```

更可靠。

---

# Q3.5 cpufreq / thermal / power_supply 为什么只能 ⚠

权限取决于：

```text
kernel node
+ Unix mode bits
+ genfs_contexts
+ AOSP sepolicy
+ vendor sepolicy
+ symlink target label
```

不能只按：

```text
Android 12
Android 13
```

判断。

你的 Huawei 已实测：

```text
shell → cpufreq EPERM
```

那么 untrusted_app 更不能作为可靠来源。

---

# Q3.6 Thermal Framework API

`HardwarePropertiesManager.getDeviceTemperatures()` 很早就有：

```text
CPU
GPU
BATTERY
SKIN
```

但它主要面向：

```text
Device Owner
current VR service
```

普通 App 调用可能：

```text
SecurityException
```

普通 App 从 API 29 可以更稳定得到：

```java
PowerManager.getCurrentThermalStatus()
```

但它给的是：

```text
NONE
LIGHT
MODERATE
SEVERE
CRITICAL
...
```

不是具体：

```text
GPU = 67.2°C
```

---

# Q4. App 内 embedded ADB

这是整个项目里复杂度最高的子模块。

必须独立：

```text
SysMon Core
   │
   └── privilege-adb/
           discovery
           pairing
           TLS
           AUTH
           protocol
           transport
           shell
```

核心数据采集代码不能知道：

```text
CNXN
AUTH
STLS
OPEN
WRTE
OKAY
CLSE
```

---

# Q4.1 Android 11+ Wireless Debugging

架构：

```text
Pairing server
+
TLS connect server
```

是两个不同端口。

端口：

```text
random
```

不要假设固定：

```text
5555
```

也不要写死所谓固定随机端口区间。

---

# Q4.2 Settings key 不应作为核心端口发现

不要依赖：

```text
settings get global adb_wifi
settings get global adb_wifi_pairing
```

得到 `ip:port`。

无线调试的内部状态、property、Framework Settings 并不是为第三方 App 提供的稳定 endpoint discovery ABI。

可能涉及：

```text
adb_wifi_enabled
service.adb.tls.port
persist.adb.tls_server.enable
persist.adb.wifi.guid
```

但不能把这些作为普通 App 通用接口。

---

# Q4.3 mDNS 应该是 Android 11+ 主发现方式

标准服务类型：

```text
_adb._tcp
_adb-tls-pairing._tcp
_adb-tls-connect._tcp
```

其中：

```text
_adb._tcp
```

偏 legacy ADB TCP。

```text
_adb-tls-pairing._tcp
```

是 pairing。

```text
_adb-tls-connect._tcp
```

是 secure wireless connect。

---

# Q4.4 同机发现

同机 App 可以尝试通过 NSD/mDNS 找到 adbd。

但发现结果通常可能是：

```text
192.168.x.x:<port>
```

而不是：

```text
127.0.0.1:<port>
```

因此 endpoint probe 推荐：

```text
mDNS resolved self WLAN IP
↓
same port on 127.0.0.1
```

两个都试。

OEM firewall / bind policy 可能导致其中一个失败。

---

# Q4.5 不建议每次扫 5 万端口

暴力：

```text
5037–55551
```

虽然 loopback 上关闭端口通常立即 RST，但 Java 创建数万 Socket 会产生：

- FD churn；
- exceptions；
- CPU spike；
- power usage；
- OEM security noise。

推荐：

```text
1. cached endpoint
2. legacy 127.0.0.1:5555
3. mDNS connect
4. mDNS pairing
5. previous endpoint retry
6. only manual bounded scan
```

只有用户明确点：

```text
Search ADB Port
```

才做 full scan。

---

## 如果必须扫描

推荐：

```text
32–64 concurrent connection
```

loopback：

```text
20–50ms timeout
```

self WLAN：

```text
50–150ms
```

connect 成功不能直接判定：

```text
这是 adbd
```

必须发送：

```text
CNXN
```

并检查：

```text
STLS / AUTH / CNXN
```

响应。

---

# Q4.6 Android 10

Android 10 没有 Android 11 的标准 Wireless Debugging pairing UI。

常见路径：

```bash
adb tcpip 5555
```

必须之前通过：

```text
USB ADB
root
已有 shell
vendor 功能
```

把 adbd 开到 TCP。

此时：

```text
127.0.0.1:5555
```

如果设备允许 loopback，可直接连接。

Android 10 legacy TCP：

```text
AUTH
```

而不是 Android 11 secure Wi-Fi 的 TLS pairing。

---

# Q4.7 Pairing 与 Connect 必须是两个状态

Android 11+：

```text
Pairing port
    ↓
Pairing protocol
    ↓
Host identity becomes trusted
    ↓
Connect port
    ↓
STLS
    ↓
TLS
    ↓
CNXN
```

不能：

```text
直接连 connect port
→ 顺便 pairing
```

---

# Q4.8 Pairing Code

通常：

```text
6-digit code
```

由设备 Wireless Debugging UI 展示。

用户输入 SysMon。

成功后 host identity 被设备记住。

---

# Q4.9 Secure Wireless 不应重复跑 Legacy AUTH

Legacy：

```text
TCP
↓
AUTH RSA challenge
↓
CNXN
```

Wireless Debugging：

```text
TCP
↓
STLS
↓
TLS
↓
secure authentication
↓
CNXN
```

所以不能写：

```text
STLS
→ TLS
→ 再完整跑一遍 Android10 AUTH
```

---

# Q4.10 RSA key 是一个大坑

RSA-2048 本身可以：

```java
KeyPairGenerator.getInstance("RSA")
```

生成。

但是：

> ADB public key wire format 不是普通 PEM public key。

传统 ADB key 会涉及 Android 特定 RSA public-key struct / encoding。

Wireless pairing 又涉及：

- RSA；
- X.509 certificate；
- pairing crypto；
- shared secret derivation；
- TLS。

因此：

> 不建议自己从零重新实现密码学格式。

你的原方案：

```text
移植 Shizuku / AOSP 已验证代码
```

是正确方向。

---

# Q4.11 Key Persistence

必须跨重启保存 host identity。

否则：

```text
App 重启
→ 新 host key
→ 重新 pairing
```

建议：

```text
ADB private key file
↓
AES-GCM encryption
↓
AES key stored in Android Keystore
```

不要把 private key 明文放：

```text
SharedPreferences
```

也不要 log：

```text
pairing code
private key
TLS secret
```

---

# Q4.12 loopback 与 WLAN self-IP

不要把 ADB backend 设计成：

```java
int port;
```

应该：

```java
final class Endpoint {
    InetAddress address;
    int port;
}
```

因为：

```text
127.0.0.1:<port>
```

和：

```text
192.168.x.x:<port>
```

可能只有其中一个成功。

---

# Q4.13 Shizuku 自启不要硬编码 start.sh

不要长期绑定：

```text
/sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

Shizuku 版本升级会改变 starter / command。

正确设计：

```text
ShizukuStarterStrategy
```

根据：

```text
Manager version
server version
Android version
```

选择对应启动方法。

如果未知：

```text
Unsupported Shizuku starter version
```

而不是尝试旧路径导致奇怪失败。

---

# Q4.14 推荐 ADB 状态机

```text
DISABLED
   ↓
DISCOVERING
   ├─ cached endpoint
   ├─ 5555
   └─ mDNS
   ↓
NEEDS_PAIRING
   ↓
PAIRING
   ↓
PAIRED
   ↓
CONNECTING
   ↓
TLS_HANDSHAKE / AUTH
   ↓
ADB_CONNECTED
   ↓
SHELL_READY
   ↓
SHIZUKU_START(optional)
```

所有：

```text
ECONNREFUSED
TLS failure
pairing rejected
AUTH failure
network changed
wireless debugging disabled
```

都转成：

```text
state transition
```

而不是异常一直冒到 UI。

---

# Q5. Overlay

---

# Q5.1 基础 Window flags

推荐：

```java
TYPE_APPLICATION_OVERLAY
FLAG_NOT_FOCUSABLE
FLAG_LAYOUT_IN_SCREEN
```

用户启用“忽略触摸”才加：

```java
FLAG_NOT_TOUCHABLE
```

不建议默认：

```java
FLAG_LAYOUT_NO_LIMITS
```

因为 200×120dp 小窗通常不需要突破边界。

反而会增加：

- display cutout；
- gesture inset；
- landscape；
- foldable；
- waterfall screen；

兼容问题。

---

# Q5.2 Android 12 overlay touch security

Android 12+ 对不可信 overlay 的 pass-through touch 增加安全限制。

所以：

```text
FLAG_NOT_TOUCHABLE
```

不等于：

```text
下层 App 100% 一定收到所有触摸
```

overlay transparency / obscuring opacity 会影响系统判断。

因此 click-through 必须真机测试。

---

# Q5.3 Immersive 全屏

正常游戏：

```text
hide status bar
hide navigation bar
```

通常不会自动隐藏：

```text
TYPE_APPLICATION_OVERLAY
```

但是 Android 12+ 前台 App 可以请求：

```java
Window.setHideOverlayWindows(true)
```

所以某些：

- 银行；
- 密码输入；
- DRM；
- 安全页面；
- 游戏；

可能隐藏你的 overlay。

UI 文案不要写：

```text
始终显示
```

更准确：

```text
在系统允许第三方悬浮窗时显示
```

---

# Q5.4 `FLAG_SECURE` 不能等同于隐藏其他 overlay

前台 App 的：

```text
FLAG_SECURE
```

主要保护：

```text
自己的窗口内容不被截图 / 投屏
```

并不自动等价：

```text
隐藏所有第三方 overlay
```

隐藏 overlay 是另一套系统机制。

---

# Q5.5 横屏坐标保存

不要保存绝对：

```text
x
y
```

也不建议单纯：

```text
x / screenWidth
y / screenHeight
```

应基于：

```text
可移动区域
```

保存：

\[
nx =
\frac{x}
{usableWidth-overlayWidth}
\]

\[
ny =
\frac{y}
{usableHeight-overlayHeight}
\]

恢复：

\[
newX =
nx \times
(newAvailableW-overlayW)
\]

\[
newY =
ny \times
(newAvailableH-overlayH)
\]

最后 clamp。

这样：

- rotation；
- resolution；
- font size；
- overlay size；

变化时都更稳定。

---

# Q5.6 “截图时自动隐藏 overlay”

关键结论：

> 普通 App 没有 Android 10–16 跨版本可靠的“系统截图发生前通知”。

因此无法保证：

```text
系统截图
→ 截图之前 hide overlay
```

---

## Accessibility 拦 Power + VolumeDown

不可靠。

系统截图 chord 属于 system policy。

不能保证时序：

```text
AccessibilityService 收到按键
↓
hide
↓
WindowManager commit
↓
SystemUI screenshot
```

一定成立。

而且还存在：

- Huawei 指关节截图；
- 三指截图；
- Quick Settings；
- Assistant；
- gesture；
- OEM 截图；

完全不会经过 Power+VolumeDown。

因此：

> 不建议为了截图隐藏引入 AccessibilityService。

---

# Q5.7 Android 14 ScreenCaptureCallback

API 34：

```java
Activity.ScreenCaptureCallback
```

可以检测：

```text
当前 Activity 被截图
```

但 overlay 覆盖在别的游戏上时：

```text
SysMon Activity 不在前台
```

因此不能作为：

```text
全局 screenshot listener
```

---

# Q5.8 MediaStore observer

只能：

```text
截图之后检测
```

还受到：

- scoped storage；
- screenshot folder；
- OEM 实现；
- media insertion delay；

影响。

所以最多做：

```text
检测到截图
→ hide overlay 1–2 秒
```

降低连续截图暴露概率。

不能保护第一张。

---

# Q5.9 推荐 Screenshot 功能

给用户三种：

## Off

普通 overlay。

## Secure Overlay

给 overlay：

```java
FLAG_SECURE
```

让 overlay 内容尽量不出现在截图 / 投屏中。

但 OEM compositor 如何处理该区域不应作跨设备保证。

## SysMon Screenshot

提供 SysMon 自己的截图按钮 / Quick Settings Tile：

```text
hide overlay
↓
等待 WindowManager commit / 1–2 frame
↓
通过 Shizuku/ADB 执行 screenshot
↓
restore overlay
```

这是最确定的办法。

---

# Q5.10 全屏状态检测

不要依赖所谓：

```text
AccessibilityWindowInfo.FLAG_FULLSCREEN
```

来判断 immersive。

Accessibility 并不能可靠告诉你：

```text
当前 App 是否真正 immersive fullscreen
```

---

## shell route

可探测：

```bash
dumpsys window
dumpsys activity activities
```

解析：

```text
topResumedActivity
mCurrentFocus
Insets state
system-bar visibility
```

但这是非稳定文本 API。

因此仅在用户开启：

```text
Hide overlay in fullscreen apps
```

时才启用。

推荐：

```text
foreground package changed
→ immediately re-check

stable state
→ every 2–5s sanity check
```

不要每：

```text
500ms
```

跑 dumpsys。

---

# Q6. Dhizuku

---

# Q6.1 总体结论

对于：

```text
只读系统监控
+
overlay
```

Dhizuku 不应该作为核心依赖。

第一版甚至可以：

```text
完全不集成 Dhizuku
```

---

# Q6.2 可能有价值的能力

`HardwarePropertiesManager`：

```text
getCpuUsages()
getDeviceTemperatures()
```

是 Device Owner 等特殊身份可能使用的 Framework API。

因此理论上：

```text
Dhizuku
→ DeviceOwner identity
→ HardwarePropertiesManager
```

可能提供：

- CPU 使用率；
- CPU/GPU/skin 温度。

但是：

> 是否能经 Dhizuku 正确代理，需要真机验证。

应该设计成：

```text
ExperimentalHardwarePropertiesProvider
```

而不是核心路径。

---

# Q6.3 Ignore Battery Optimizations

不要为了这个引入 Dhizuku。

普通 App 正常：

```text
ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

由用户批准。

不存在一个可以让任意第三方 App 偷偷通过 DPM 完成同样事情的通用设计。

---

# Q6.4 不建议用 Device Owner 控游戏亮度/帧率

为了 SysMon 而用 Device Owner：

```text
强制亮度
限制游戏帧率
kiosk
```

属于：

```text
副作用 > 监控价值
```

不推荐。

---

# Q6.5 Dhizuku 最终策略

第一版：

```text
Settings
└─ Dhizuku Status
```

仅显示：

```text
installed
Device Owner active
```

不调用实际数据 API。

以后如果验证：

```text
HardwarePropertiesManager via Dhizuku
```

确实有价值，再增加：

```text
DhizukuHardwareProvider
```

即可。

---

# Q6.6 Shizuku API

即使：

```text
targetSdk 29
```

也建议使用较新的 Shizuku client API。

不要为了“兼容 target29”故意使用多年旧 API。

---

# Q6.7 手工 aapt2 构建的 Manifest 风险

因为不用 Gradle：

```text
AAR manifest merge
```

不能假设自动完成。

应该打开你实际 vendored 的 Shizuku API AAR/manifest，逐项确认：

```text
provider
uses-permission
meta-data
```

需要什么就显式合并到主 manifest。

对于：

```text
moe.shizuku.manager.permission.API_V23
```

只应该：

```xml
<uses-permission ... />
```

不要自行：

```xml
<permission ... />
```

重新定义 Shizuku 的 permission。

---

# Q7. Kirin 710 / Linux 4.14

---

# Q7.1 CPU cluster

Kirin 710 通常：

```text
cpu0–3 → Cortex-A53
cpu4–7 → Cortex-A73
```

但不要硬编码。

优先：

```text
/sys/devices/system/cpu/cpufreq/policy*/related_cpus
```

或者：

```text
affected_cpus
```

建立：

```java
final class CpuFrequencyDomain {
    int policyId;
    int[] cpus;
}
```

而不是：

```java
cpu0Freq
cpu1Freq
cpu2Freq
...
```

---

# Q7.2 Frequency 通常属于 DVFS Policy

例如：

```text
cpu0
cpu1
cpu2
cpu3
  ↓
same cpufreq policy
```

即使：

```text
cpu0/cpufreq/scaling_cur_freq
cpu1/cpufreq/scaling_cur_freq
```

都存在，也可能实际上指向同一个 policy。

---

# Q7.3 cpufreq EPERM 时

可以尝试：

```text
/proc/cpuinfo
```

ARM part：

```text
Cortex-A53 → 0xD03
Cortex-A73 → 0xD09
```

但是 vendor 也可能裁剪。

最终允许：

```text
CPU topology known
CPU frequency N/A
```

不要因为 frequency 不可读导致 CPU Provider 整体失败。

---

# Q7.4 `dumpsys thermalservice`

在：

```text
/sys/class/thermal → EPERM
```

时非常值得尝试：

```bash
dumpsys thermalservice
```

可能包含：

```text
CPU
GPU
BATTERY
SKIN
cooling devices
thermal status
thresholds
```

Android 10 已经有 ThermalManagerService / Thermal HAL 路线。

但是：

```text
dumpsys 文本输出不是稳定 machine API
```

Parser 必须 best-effort。

---

# Q7.5 `/proc/stat` CPU 公式

典型字段：

```text
cpu user nice system idle iowait irq softirq steal guest guest_nice
```

计算：

```text
Idle =
    idle + iowait

NonIdle =
    user + nice + system +
    irq + softirq + steal

Total =
    Idle + NonIdle
```

两次采样：

\[
CPU =
\frac{\Delta NonIdle}
{\Delta Total}
\]

注意：

```text
guest 已包含在 user
guest_nice 已包含在 nice
```

不能再次加进去。

---

# Q7.6 Top Processes

普通 App：

```text
不能可靠遍历其他 App 的 /proc/<pid>/stat
```

shell backend 可以。

---

## 简单实现

每：

```text
2–5 秒
```

运行：

```bash
top -b -n1
```

不要 500ms 一次。

---

## 更高性能实现

Shizuku UserService 内直接：

```text
enumerate /proc/[0-9]*
↓
read stat / status / cmdline
↓
calculate TopN
↓
single Binder result
```

这比主 App 对每个 PID 做：

```text
remote cat
```

高效很多。

---

# Q7.7 网络接口

`/proc/net/dev` 会包含：

```text
lo
wlan0
rmnet0
rmnet_data0
eth0
tun0
```

不要简单选择：

```text
累计 RX 最大的网卡
```

因为那可能是历史流量。

---

## 推荐识别 active interface

Framework：

```java
ConnectivityManager.getActiveNetwork()
NetworkCapabilities
LinkProperties
LinkProperties.getInterfaceName()
```

例如：

```text
wlan0
rmnet_data0
tun0
```

VPN 时逻辑接口可能为：

```text
tun0
```

因此可以区分：

```text
Logical Traffic
Physical Interface Traffic
```

一般主页使用：

```text
TrafficStats
```

高级详情页使用：

```text
/proc/net/dev
```

---

# Q7.8 `dumpsys netstats`

不是：

```text
/proc/net/dev 的更精准版本
```

区别：

### `/proc/net/dev`

```text
实时 interface byte counter
```

非常适合：

```text
0.5–1s throughput graph
```

### `dumpsys netstats`

偏：

```text
framework accounting
UID bucket
historical traffic
network identity
```

更适合：

```text
某个 App 今天用了多少流量
```

实时监控不要每秒 `dumpsys netstats`。

---

# 8. 最终推荐架构

```text
                     ┌──────────────────┐
                     │    SysMon UI     │
                     │ Activity/Overlay │
                     └────────┬─────────┘
                              │
                         Atomic Snapshot
                              │
                    ┌─────────▼─────────┐
                    │  MetricScheduler  │
                    └─────────┬─────────┘
                              │
                    ┌─────────▼─────────┐
                    │ MetricResolver    │
                    │ capability+cost   │
                    └─────────┬─────────┘
                              │
       ┌───────────┬──────────┼───────────┬───────────┐
       ▼           ▼          ▼           ▼           ▼
 Framework      Direct     ShellFast   ShellCommand  Vendor
 Provider       Provider   Provider    Provider      Provider
                               │
                         ┌─────┴─────┐
                         ▼           ▼
                     Shizuku      Embedded ADB
```

---

# 9. 不要定义全局 `PrivilegeMode`

不建议：

```java
enum Mode {
    NORMAL,
    SHIZUKU,
    DHIZUKU,
    ADB
}
```

然后业务层：

```java
if (mode == SHIZUKU) ...
else if ...
```

会迅速恶化。

---

# 10. 定义 Metric

例如：

```text
CPU_TOTAL_USAGE
CPU_CORE_USAGE
CPU_FREQ

MEM_TOTAL
MEM_AVAILABLE

GPU_USAGE
GPU_FREQ
GPU_MEMORY

BATTERY_LEVEL
BATTERY_TEMP
BATTERY_VOLTAGE
BATTERY_CURRENT
BATTERY_POWER
INPUT_POWER

CPU_TEMP
GPU_TEMP
SKIN_TEMP

NET_RX
NET_TX
```

---

# 11. Provider 声明 Capability

例如：

```text
FrameworkBatteryProvider:
    BATTERY_LEVEL
    BATTERY_TEMP
    BATTERY_VOLTAGE
    BATTERY_CURRENT?

ProcStatShellProvider:
    CPU_TOTAL_USAGE
    CPU_CORE_USAGE

MaliDevfreqProvider:
    GPU_FREQ
    GPU_USAGE?

ThermalServiceProvider:
    CPU_TEMP?
    GPU_TEMP?
    SKIN_TEMP?

TrafficStatsProvider:
    NET_RX
    NET_TX
```

`?` 表示：

```text
运行时 probe 决定
```

---

# 12. 每个 Sample 应带 Source / Quality

建议：

```java
final class MetricSample {
    long timestampNs;

    double value;

    MetricUnit unit;

    SourceId source;

    Quality quality;

    long ageMs;

    int flags;
}
```

Quality：

```text
DIRECT_FRAMEWORK
KERNEL_ABI
AOSP_SHELL
VENDOR_SYSFS
DERIVED
ESTIMATED
UNAVAILABLE
```

例如 Diagnostics：

```text
GPU Usage
Source:
  /sys/class/devfreq/ff9a0000.gpu/load

Quality:
  VENDOR_SYSFS
```

这样以后排查用户机型异常非常快。

---

# 13. Capability Probe 只做一次

启动：

```text
probe
↓
build CapabilityGraph
↓
cache
```

不要每一秒：

```java
file.exists()
```

几十遍。

推荐 cache key：

```text
Build.FINGERPRINT
kernel release
boot_id
privilege backend
Shizuku version
```

以下情况 invalidate：

```text
reboot
OS update
Shizuku binder death
permission revoked
ADB reconnect
```

---

# 14. Shizuku UserService：直接读文件，不要 `exec("cat")`

如果 UserService 已在 shell uid：

```text
uid 2000
```

读取：

```text
/proc/stat
/proc/meminfo
/proc/net/dev
/sys/...
```

应该直接：

```java
FileInputStream
BufferedInputStream
```

而不是：

```java
Runtime.exec("cat /proc/stat")
```

原因：

- 少 spawn process；
- 少 context switch；
- 少 shell parser；
- 少 GC；
- 更快；
- 更稳定。

---

# 15. 每个 tick 只做一次 Binder IPC

不要：

```text
App
 ├─ binder read CPU
 ├─ binder read RAM
 ├─ binder read GPU
 ├─ binder read battery
 └─ binder read network
```

推荐：

```java
ShellSnapshot sampleFastMetrics(int mask)
```

UserService 内：

```text
read /proc/stat
read GPU sysfs
read /proc/net/dev
...
```

最后：

```text
single Binder transaction
```

返回所有快速指标。

---

# 16. `dumpsys` 必须分成 Slow Provider

不要每秒：

```text
dumpsys battery
dumpsys thermalservice
dumpsys gpu
dumpsys window
```

推荐：

| Metric | 建议采样周期 |
|---|---:|
| CPU usage | 500ms–1s |
| Network throughput | 500ms–1s |
| GPU usage | 500ms–1s |
| Battery current | 1–2s |
| CPU/GPU frequency | 1–2s |
| Memory | 1–2s |
| Temperature | 2–5s |
| Top processes | 2–5s |
| `dumpsys thermalservice` | 5–10s |
| Battery level | event / 10–30s |
| Charge-counter derivative | 30–120s |
| capability probe | once |

---

# 17. 没有人显示的 Metric 不采集

例如 overlay 当前只显示：

```text
CPU
RAM
Battery
```

则：

```text
GPU Provider
Thermal Provider
Top Process Provider
```

应该暂停。

目标：

```text
0 unnecessary wakeup
```

这比所有 provider 永远定时运行更重要。

---

# 18. UI 与采样解耦

推荐：

```text
sampling:
1 Hz

Activity render:
2–4 Hz max

Overlay:
1 Hz default
```

采样线程：

```java
AtomicReference<SystemSnapshot>
```

Activity / Overlay：

```text
read latest immutable snapshot
```

完全不需要引入：

```text
RxJava
Coroutine
Flow
LiveData
DI Framework
```

非常适合纯 Java 小 APK。

---

# 19. Parser 不要用高分配实现

对：

```text
/proc/stat
/proc/meminfo
/proc/net/dev
```

不要每秒大量：

```java
String.split("\\s+")
Scanner
Pattern / Matcher
```

建议：

```text
small AsciiParser
```

直接扫描：

```text
byte[]
```

然后 parse long。

长期运行时：

```text
GC pressure 显著更低
```

---

# 20. Error Model

Provider 应统一：

```text
UNSUPPORTED
PERMISSION_DENIED
TRANSIENT_IO
BACKEND_DEAD
PARSE_CHANGED
INVALID_VALUE
```

---

## ENOENT

```text
UNSUPPORTED
```

本次 boot 不再重试。

---

## EACCES / EPERM

```text
PERMISSION_DENIED
```

当前 Provider demote。

不要每秒重试。

---

## Shizuku Binder Death

```text
invalidate shell capabilities
↓
fallback Framework/ADB
```

UI 不崩。

---

## Parse Changed

记录：

```text
source
parser version
raw sample summary
```

然后：

```text
N/A
```

不要让：

```text
NumberFormatException
```

杀掉 foreground service。

---

# 21. GPU / 温度 / 电池功率可用性矩阵

## 无权限

| Metric | 可用性 |
|---|---|
| GPU model | ✅ GLES/Vulkan |
| GPU utilization | ❌ N/A |
| GPU frequency | ❌ / ⚠ vendor bonus |
| GPU memory | ❌ |
| CPU raw temperature | ❌ |
| GPU raw temperature | ❌ |
| Battery temperature | ✅ |
| Thermal status | ✅ API29+ |
| Battery level | ✅ |
| Battery voltage | ✅ |
| Battery current | ✅ / ⚠ |
| Battery power | ✅ / ⚠ `V×I` |
| Charger input power | ❌ |
| Actual whole-device power | ❌ |
| Battery-side discharge power | ✅ / ⚠ |

---

## Shell：Shizuku / ADB

| Metric | 可用性 |
|---|---|
| GPU model | ✅ |
| GPU utilization | ⚠ devfreq/KGSL/vendor |
| GPU frequency | ⚠ devfreq/vendor |
| GPU memory | ⚠ `dumpsys gpu --gpumem` |
| CPU raw temperature | ⚠ thermalservice/vendor |
| GPU raw temperature | ⚠ thermalservice/vendor |
| Battery temperature | ✅ |
| Battery voltage | ✅ |
| Battery current | ✅ / ⚠ |
| Battery power | ✅ / ⚠ |
| Input power | ⚠ power_supply |
| Whole-device power | ⚠ estimated |
| CPU utilization | ✅ `/proc/stat` |
| Per-core CPU | ✅ `/proc/stat` |
| Network per-interface | ✅ `/proc/net/dev` |

---

# 22. Huawei HarmonyOS 2 推荐 Capability Test

建议在 shell 下执行：

```bash
echo === SERVICES ===
service list | grep -Ei 'gpu|thermal|power|hisi|dss|graphic'

echo === GPU SERVICE ===
dumpsys gpu 2>&1 | head -100

echo === GPU DEVFREQ ===
for d in /sys/class/devfreq/*; do
    echo "--- $d"
    readlink -f "$d"
    cat "$d/name" 2>/dev/null
    cat "$d/cur_freq" 2>/dev/null
    cat "$d/load" 2>/dev/null
    cat "$d/governor" 2>/dev/null
done

echo === THERMAL ===
dumpsys thermalservice 2>&1 | head -200

echo === POWER ===
for d in /sys/class/power_supply/*; do
    echo "--- $d"
    for f in type online status health capacity \
             voltage_now current_now current_avg \
             charge_now charge_counter charge_full \
             energy_now power_now temp cycle_count; do
        printf "%s=" "$f"
        cat "$d/$f" 2>/dev/null || echo X
    done
done

echo === CPUFREQ ===
for d in /sys/devices/system/cpu/cpufreq/policy*; do
    echo "--- $d"
    cat "$d/related_cpus" 2>/dev/null
    cat "$d/scaling_cur_freq" 2>/dev/null
    cat "$d/cpuinfo_max_freq" 2>/dev/null
done
```

---

# 23. 建议增加 Diagnostics → Export Capability Report

以后遇到用户 ROM 问题，不再猜设备。

建议报告：

```text
Build.FINGERPRINT
SDK_INT
kernel release
SELinux status
Shizuku availability/version
ADB backend status

/proc/stat
/proc/meminfo
/proc/net/dev

cpufreq policies

devfreq devices

power_supply nodes

dumpsys gpu capability

thermalservice capability
```

每个 source 输出：

```text
EXISTS
READABLE
PERMISSION_DENIED
PARSE_OK
VALUE_RANGE
```

这样可以快速适配：

```text
Huawei
Xiaomi
Samsung
Qualcomm
MediaTek
Exynos
Tensor
```

---

# 24. 推荐开发阶段

## Phase 1：核心骨架

完成：

```text
Metric
Provider
Snapshot
Scheduler
Resolver
```

实现：

```text
BatteryManager
TrafficStats
/proc/meminfo
SystemClock
```

Shizuku UserService：

```text
/proc/stat
/proc/meminfo
/proc/net/dev
```

一次 Binder 批量返回。

这一阶段已经可以稳定提供：

```text
CPU
RAM
network
battery
overlay
```

---

## Phase 2：Hardware Providers

增加：

```text
DevfreqGpuProvider
AdrenoKgslProvider
ThermalServiceProvider
PowerSupplyProvider
```

所有硬件来源：

```text
probe
→ available
→ use

probe fail
→ N/A
```

绝不影响核心功能。

---

## Phase 3：Embedded ADB

ADB 对上层只暴露：

```java
interface ShellBackend {
    FastSnapshot sampleFast(int mask);

    CommandResult exec(
        String command,
        long timeoutMs
    );

    boolean isAlive();
}
```

Shizuku 和 ADB 都实现：

```text
ShellBackend
```

业务代码不区分 transport。

---

## Phase 4：Dhizuku

最后考虑。

只有当真机证明：

```text
HardwarePropertiesManager via DeviceOwner
```

明显补足数据源时再加入。

否则：

```text
不实现
```

反而是最合理设计。

---

# 25. Overlay Screenshot 最终推荐

第一版：

```text
TYPE_APPLICATION_OVERLAY
FLAG_NOT_FOCUSABLE
optional FLAG_NOT_TOUCHABLE
```

截图功能：

```text
normal
optional FLAG_SECURE
SysMon-controlled screenshot
```

不要为了“预判系统截图”引入 Accessibility。

---

# 26. 最终架构原则

整个 SysMon 应坚持以下原则：

```text
Framework API first
↓
direct low-cost file read
↓
shell fast provider
↓
vendor provider
↓
dumpsys slow provider
↓
derived / estimated
↓
N/A
```

而不是：

```text
只要 Shizuku 可用
→ 所有数据都 shell command
```

---

# 27. 最终目标

一台设备最终可能形成：

```text
CPU_USAGE
  → /proc/stat via Shizuku

CPU_FREQ
  → N/A

GPU_USAGE
  → Mali devfreq

GPU_FREQ
  → Mali devfreq

GPU_TEMP
  → dumpsys thermalservice

BATTERY_TEMP
  → ACTION_BATTERY_CHANGED

BATTERY_CURRENT
  → BatteryManager

BATTERY_POWER
  → derived V×I

NETWORK
  → TrafficStats

TOP_PROCESS
  → Shizuku /proc scan
```

这是正常而且理想的结果。

**权限 backend 和 metric data source 不需要一一绑定。**

这也是保证：

- ROM 差异下不崩；
- 某个节点失效不拖垮整个 App；
- Android 10–16 易扩展；
- APK 小；
- runtime overhead 低；

的关键。
