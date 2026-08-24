我按你这份 SysMon 调研问题逐项给结论。下面会区分三种性质：**AOSP/Android 可依赖的标准行为**、**Linux 内核 ABI 但 Android 权限不保证**、**厂商私有实现只能运行时探测**。你文档里已经有不少正确方向，但也有几处会直接影响架构的前提需要修正。

先给总判断：**不要把 SysMon 设计成“无权限模式 / Shizuku 模式 / ADB 模式”三个整块模式，而要设计成“每个 Metric 独立选择最优 Provider”**。这会同时解决可扩展性、ROM 差异、性能和包体问题。比如即使 Shizuku 可用，电池温度仍应该走 `ACTION_BATTERY_CHANGED`，不应为了“统一 shell 模式”每秒 `dumpsys battery`。

------

# 一、先给几个会影响架构的关键结论

### 1. `/proc/stat` 不能作为普通 App 的 Android 10–16 基线

这是你当前方案里最重要的修正之一。

Android 从 O 开始已经收紧 `/proc/stat`、`/proc/uptime` 等信息；Android 10 的 sepolicy 以及当前 Android 的 app neverallow 都不允许普通第三方 App 把这些文件作为可靠接口。`/proc/meminfo` 是一个例外，AOSP Android 10 对 appdomain 有明确读权限。([Android Git Repositories](https://android.googlesource.com/platform/system/sepolicy/%2B/refs/heads/android10-qpr3-release/public/app.te))

因此：

- 无权限模式：
    - 内存：可以很好做。
    - 网络：可以用 `TrafficStats`。
    - 电池：可以很好做。
    - CPU 总/每核真实利用率：**标准 Android 无可靠公共来源**。
- Shizuku/ADB shell：
    - `/proc/stat` 是正确的 CPU 来源。

你的 Huawei 如果 `untrusted_app` 实测能打开 `/proc/stat`，只当作一个 `VendorBonusProvider`，不能作为 API 29 基线。

------

### 2. `dumpsys gpu` 远早于 Android 14，但它不是“GPU 百分比接口”

GPU service 的 lineage 实际可以追溯到 Android N。当时 GPU Service 位于 SurfaceFlinger，主要用于 Vulkan 能力查询；2018 年前后又被拆成独立 `gpuservice`。所以“Android 14 才有 gpu service”不成立。AOSP 代码历史能看到早期 `services/surfaceflinger/GpuService.cpp`，以及后来移到 `services/gpuservice` 的提交。([Android Git Repositories](https://android.googlesource.com/platform/frameworks/native/%2B/512f006f46ecdb6e964fea99b7bfdad0fe69a1a0^2..512f006f46ecdb6e964fea99b7bfdad0fe69a1a0/))

当前 AOSP service name 明确是：

```text
gpu
```

当前源码：

```text
frameworks/native/services/gpuservice/GpuService.cpp
```

而且 shell 可以 dump；非 shell 调用需要 `android.permission.DUMP`。([Android Git Repositories](https://android.googlesource.com/platform/frameworks/native/%2B/7fb707802e/services/gpuservice/GpuService.cpp))

但是它提供的是 GPU driver stats、GPU memory accounting、GPU work/time-in-state 等，不是跨厂商统一的：

```text
GPU utilization = 63%
GPU frequency = 600 MHz
```

这种接口。

------

### 3. Dhizuku 不应该成为你的“数据采集权限层”

Dhizuku = Device Owner/DPM 身份代理。

Shizuku = shell uid 2000。

这两个完全不是一类权限。

对于 `/proc`、`/sys`、`debugfs`：

> Dhizuku 不能替代 shell。

所以推荐架构里应当从：

```text
Shizuku
→ Dhizuku
→ untrusted
→ ADB
```

改成：

```text
Framework / direct kernel API
        ↓
shell backend
 ├─ Shizuku
 └─ embedded ADB
        ↓
optional DeviceOwner APIs
```

Dhizuku 是一个**特殊 API Provider**，不是文件权限 fallback。

------

# Q1. GPU：占用率、频率、显存

## Q1.1 `dumpsys gpu`

### 明确结论

**Android N 就已有 GpuService lineage，不是 Android 14 才出现。**

早期：

```text
frameworks/native/services/surfaceflinger/GpuService.cpp
```

后来独立：

```text
frameworks/native/services/gpuservice/
    GpuService.cpp
    gpuservice.rc
    main_gpuservice.cpp
```

2018 年的 AOSP 提交可以直接看到 SurfaceFlinger 内 GPU service 被拆成独立服务。([Android Git Repositories](https://android.googlesource.com/platform/frameworks/native/%2B/512f006f46ecdb6e964fea99b7bfdad0fe69a1a0^2..512f006f46ecdb6e964fea99b7bfdad0fe69a1a0/))

Android 10 因此完全可能存在：

```bash
service check gpu
dumpsys gpu
cmd gpu help
```

但 Huawei 可以修改/移除/裁剪。

### 当前 `dumpsys gpu` 有什么

较新的 AOSP：

```bash
dumpsys gpu --gpudriverinfo
dumpsys gpu --gpumem
dumpsys gpu --gpustats
dumpsys gpu --gpuwork
```

源码明确处理这些选项。([Android Git Repositories](https://android.googlesource.com/platform/frameworks/native/%2B/7fb707802e/services/gpuservice/GpuService.cpp))

其中：

- `gpudriverinfo`
    - driver/package/version 等；
- `gpustats`
    - Vulkan/graphics driver 加载统计；
- `gpumem`
    - GPU memory accounting；
- `gpuwork`
    - GPU work/time-in-frequency-state。

例如当前 `GpuWork` 输出本质类似：

```text
GPU time in frequency state in ms.
uid/freq: 0MHz 50MHz 100MHz ...
1000: ...
10123: ...
```

它通过 GPU work tracepoint+BPF 收集每 UID 的频率驻留时间。([Android Git Repositories](https://android.googlesource.com/platform/frameworks/native/%2B/17b449fc647318859bbdefb87b7a2103ee40faf8/services/gpuservice/gpuwork/GpuWork.cpp))

所以：

> `dumpsys gpu --gpuwork` 有 GPU 工作信息，但仍不能简单理解为瞬时 GPU 负载百分比。

而且这部分 GPU work 是较新的实现；`GpuWork.cpp` 本身 copyright 2022，因此 HarmonyOS 2 / Android 10 不要期待存在。([Android Git Repositories](https://android.googlesource.com/platform/frameworks/native/%2B/17b449fc647318859bbdefb87b7a2103ee40faf8/services/gpuservice/gpuwork/GpuWork.cpp))

### 华为是否必有

不。

AOSP service ≠ vendor 必须完整保留功能。

对于 HarmonyOS 2：

```bash
service list | grep -Ei 'gpu|mali|hisi|hisilicon|graphic|dss'
dumpsys -l | grep -Ei 'gpu|mali|hisi|graphic|dss'
```

发现服务后：

```bash
dumpsys <service>
dumpsys <service> --help
```

不要在代码里硬写：

```text
hisi_gpu
GPU
hisi_graphics
```

因为没有 Huawei 跨版本保证。

------

# Q1.2 Mali Bifrost / kbase

## 推荐的数据源优先级

对 Mali：

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

注意，我反而**不推荐把 `dumpsys gpu` 放在 GPU utilization 探测第一位**，因为它的语义不是硬件瞬时利用率。

------

## A. `/sys/class/devfreq`

首先枚举，而不是猜路径：

```bash
find /sys/class/devfreq -maxdepth 2 -type f 2>/dev/null
```

然后对每个：

```bash
readlink -f /sys/class/devfreq/*
cat /sys/class/devfreq/*/name
cat /sys/class/devfreq/*/cur_freq
cat /sys/class/devfreq/*/available_frequencies
cat /sys/class/devfreq/*/governor
cat /sys/class/devfreq/*/load
```

常见实际位置是：

```text
/sys/devices/platform/.../devfreq/<device>/cur_freq
```

`/sys/class/devfreq` 只是 symlink。

ARM kbase 本身支持 Linux devfreq；驱动内部 `kbase_devfreq_status()` 有：

```text
busy_time
total_time
current_frequency
```

这意味着驱动本身通常知道 GPU busy 与频率，只是**是否通过 sysfs 暴露给你完全是另一回事**。([Android Git Repositories](https://android.googlesource.com/kernel/google-modules/gpu/%2B/20fff721667a227b3d6decf9dbc3798476390302/mali_kbase/backend/gpu/mali_kbase_devfreq.c))

### 解析

如果有：

```text
load
```

要确认量纲。有些是：

```text
0..100
```

有些 vendor 可能：

```text
0..1000
```

不要直接除 100。

Probe 阶段读 5～10 次，并验证范围。

------

## B. Mali debugfs

可能存在：

```text
/sys/kernel/debug/mali0/
/sys/kernel/debug/mali/
/sys/kernel/debug/mali-kbase/
```

vendor 可能提供：

```text
utilization
gpuclk
job_count
memory
pm
power
```

但**节点名称完全不是 ABI**。

debugfs 本来就面向 debug，不应该依赖其稳定性；kbase 的很多 debugfs 功能还依赖 `CONFIG_DEBUG_FS`/driver Kconfig。([Android Git Repositories](https://android.googlesource.com/kernel/google-modules/gpu/%2B/35feb9b795cf1cd0d9a0a2edb6ade3c83040f48b/mali_kbase/mali_kbase_defs.h))

普通 App：

```text
基本可以视作不可用
```

shell：

```text
也经常被 SELinux 拦
```

root/userdebug：

```text
最有机会。
```

你这台 Huawei：

> `/sys/class/thermal` 连 shell 都 EPERM，那么 debugfs Mali 节点我会默认判断“高概率 shell 也拿不到”。

------

## C. `/proc/driver/mali`

属于 vendor/driver 私有 ABI。

可能有：

```text
/proc/driver/mali
/proc/mali
```

但不存在任何 Android 10–16 保证。

只 probe，不硬编码依赖。

------

# Q1.3 Adreno / PowerVR

## Adreno

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

有些驱动还有：

```text
gpubusy
```

如果是累计 busy/total：

```text
util =
    Δbusy /
    Δtotal
```

这比读一个已经平均过的百分比更适合作 SysMon。

------

## PowerVR

没有 Android 统一接口。

建议：

```text
devfreq device name contains:
gpu
pvr
rogue
```

然后 vendor sysfs/debugfs。

找不到直接 N/A。

不要维护一大坨：

```java
if (manufacturer.equals(...))
```

机型表。

------

# Q1.4 无 root/shell 能拿什么 GPU

### GPU 型号

可以。

OpenGL ES：

```java
glGetString(GL_RENDERER)
glGetString(GL_VENDOR)
glGetString(GL_VERSION)
```

Vulkan 也可以枚举 physical device。

例如：

```text
Mali-G51
Adreno (TM) 740
```

------

### 全局 GPU 占用率

**没有标准公共 API。**

------

### GPU 频率

**没有标准公共 API。**

------

### GPU 显存

这里需要明确 Android 手机的概念。

大量 mobile GPU 是 UMA：

```text
GPU 和 CPU 共用 DRAM
```

不存在 PC 那种：

```text
8 GB VRAM
```

所以建议 UI 不叫：

> 显存容量

改为：

> GPU memory usage / GPU 分配内存

`dumpsys gpu --gpumem` 在支持设备上就是这种 accounting。

------

### `gfxinfo`

```bash
dumpsys gfxinfo <package>
```

测的是：

- frame rendering；
- jank；
- UI pipeline timing；
- graphics memory 部分统计。

不是：

```text
whole GPU utilization
```

不要用它估算全局 GPU load。

------

# Q1.5 最终 GPU 探测链

我推荐：

```text
Probe GPU identity
    ↓
enumerate /sys/class/devfreq/*
    │
    ├─ Mali → MaliDevfreqProvider
    ├─ kgsl → AdrenoProvider
    └─ pvr → PowerVRProvider
    ↓
vendor sysfs nodes
    ↓
KGSL specific nodes
    ↓
Mali debugfs/proc vendor nodes
    ↓
dumpsys gpu capabilities
       ├─ gpumem
       ├─ gpuwork
       └─ gpustats
    ↓
N/A
```

而不是：

```text
dumpsys gpu → debugfs
```

------

## Kirin 710 GPU frequency

**没有可以承诺存在的非 root 通用路径。**

最值得试：

```bash
for d in /sys/class/devfreq/*; do
    echo "=== $d"
    cat "$d/name" 2>/dev/null
    cat "$d/cur_freq" 2>/dev/null
done
```

再：

```bash
find /sys -iname '*mali*' -o -iname '*gpu*' 2>/dev/null
```

如果 shell 下 devfreq 也 EPERM：

> SysMon 应当显示 GPU frequency = N/A。

不要为了这个字段去 ioctl `/dev/mali0`。你的实测已经说明 SELinux/driver ioctl path 不允许 shell，它不是一个值得继续押注的方向。

------

# Q2. 电池和功率

这里建议 SysMon 做得比大多数监控软件更严谨，因为“充电功率”“电池功率”“整机功耗”很容易被混在一起。

------

# Q2.1 power_supply 字段

Linux power_supply ABI 典型单位：

| 字段                 | 单位/意义          | 稳定程度 |
| -------------------- | ------------------ | -------- |
| `capacity`           | %                  | 高       |
| `voltage_now`        | µV                 | 高       |
| `current_now`        | µA                 | 可选     |
| `current_avg`        | µA                 | 可选     |
| `charge_now`         | µAh                | 可选     |
| `charge_full`        | µAh                | 可选     |
| `charge_full_design` | µAh                | 可选     |
| `energy_now`         | µWh                | 可选     |
| `power_now`          | µW                 | 可选     |
| `cycle_count`        | 次                 | 可选     |
| `temp`               | 通常 0.1°C         | driver   |
| `health`             | enum text          | 常见     |
| `status`             | enum text          | 常见     |
| `type`               | Battery/USB/Mains… | 常见     |
| `online`             | 0/1                | charger  |

Linux power_supply ABI 对 `current_now`、`voltage_now`、`online` 等定义了语义和量纲。([Google Code](https://code.googlesource.com/linux/torvalds/linux/%2B/4a22709e21c2b1bedf90f68c823daf65d8e6b491/Documentation/ABI/testing/sysfs-class-power))

### 一个重要原则

这些字段不是：

```text
Android 10 有
Android 11 新增
Android 12 必须
```

这种关系。

本质是：

```text
fuel gauge / charger driver
      ↓
power_supply class
      ↓
Health HAL / healthd
      ↓
BatteryService
```

Android 10 的 healthd `BatteryMonitor.cpp` 本身就是检测哪个节点存在、可读，就使用哪个，例如：

```text
capacity
voltage_now
current_now
current_avg
charge_counter
charge_full
cycle_count
temp
technology
```

因此这是**driver capability matrix，而不是 Android API matrix**。([Android Git Repositories](https://android.googlesource.com/platform/system/core/%2B/refs/heads/android10-qpr2-s2-release/healthd/BatteryMonitor.cpp))

------

# Q2.2 Huawei `current_now`

不能假定 Huawei 一定有。

如果：

```bash
cat /sys/class/power_supply/battery/current_now
```

失败，那么下一步不是立即放弃：

```text
BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
↓
dumpsys battery
↓
charge counter derivative
↓
N/A
```

因为 HAL 可能仍然有私有途径获得当前电流。

------

# Q2.3 `dumpsys battery` 单位

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

单位：

| 项                   | 单位           |
| -------------------- | -------------- |
| Max charging current | µA             |
| Max charging voltage | µV             |
| Charge counter       | µAh            |
| voltage              | mV             |
| temperature          | 0.1 °C         |
| level                | % if scale=100 |

AOSP `BatteryService` 内部字段名称本身就标明了这些单位。([Android Git Repositories](https://android.googlesource.com/platform/frameworks/base/%2B/master/services/core/java/com/android/server/BatteryService.java))

### Charge counter 不是 mWh

比如：

```text
Charge counter: 3240000
```

通常意味着：

```text
3,240,000 µAh
= 3240 mAh
```

它表示剩余电荷量估计。

------

## 可以差分算电流吗？

可以，但只适合较长窗口：

[
I_{\mu A} =
\frac{\Delta Q_{\mu Ah} \times 3600}
{\Delta t_s}
]

比如：

```text
60s:
ΔQ = -833 µAh
```

则：

```text
I ≈ -833 × 3600 / 60
  ≈ -49,980 µA
  ≈ -50 mA
```

然后：

[
P_W =
\frac{I_{\mu A}\times V_{mV}}
{10^9}
]

但是：

> 不要每 1 秒差分 Charge counter。

fuel gauge quantization 会让结果跳成：

```text
0 W
0 W
-5.8 W
0 W
```

推荐：

```text
30–120 秒窗口
```

并显示成：

> Battery average power

而不是瞬时功率。

------

# Q2.4 BatteryManager

你的问题中有个字段名需要修正：

> Android 没有标准的 `EXTRA_BATTERY_CURRENT_NOW`。

电流正确方式：

```java
BatteryManager bm =
    (BatteryManager) context.getSystemService(
        Context.BATTERY_SERVICE);

int current =
    bm.getIntProperty(
        BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
```

单位：

```text
µA
```

标准定义：

```text
positive = charging
negative = discharging
```

`BATTERY_PROPERTY_CURRENT_NOW`、`CURRENT_AVERAGE`、`CHARGE_COUNTER`、`ENERGY_COUNTER` 从 API 21 就存在。([Android Developers](https://developer.android.com/reference/android/os/BatteryManager))

注意处理：

```text
Integer.MIN_VALUE
```

或明显异常值为 unsupported。

------

## Sticky battery Intent

```java
IntentFilter(Intent.ACTION_BATTERY_CHANGED)
```

可以拿：

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
value / 10.0 °C
```

电压：

```text
mV
```

Android 14/API34 又增加了 cycle count、charging status 相关公开信息。([Android Developers](https://developer.android.com/reference/kotlin/android/os/BatteryManager))

截至 API36：

> 仍不要依赖一个“标准 BatteryManager 瞬时电池温度 getter”替代 `ACTION_BATTERY_CHANGED`。

------

# Q2.5 输入/输出/整机功率一定要拆开

我建议你的 UI 明确分三个 Metric。

### Battery power

[
P_{battery}=V_{battery}\times I_{battery}
]

定义：

```text
+ = 电池吸收能量
- = 电池向系统供能
```

例如：

```text
+8.2 W   battery charging
-3.3 W   battery discharging
```

------

### Charger/Input power

寻找实际在线的 power supply：

```bash
for d in /sys/class/power_supply/*; do
    echo "$d"
    cat "$d/type" 2>/dev/null
    cat "$d/online" 2>/dev/null
done
```

不要假设名字一定：

```text
usb
ac
mains
```

Huawei/QCOM/MTK 可能叫别的。

然后尝试：

```text
voltage_now
current_now
power_now
```

如果：

```text
power_now
```

存在，Linux ABI 通常以 µW 表示。

注意：

```text
current_max
voltage_max
input_current_limit
input_power_limit
```

不是实际输入功率。

它们是：

> limit/capability。

------

### Whole-device power

非 root 环境：

> **没有 Android 10–16 跨厂商可靠公共瞬时“整机 W”接口。**

`PowerStats HAL`、rail energy、batterystats energy consumers 是系统功耗建模/统计基础设施，不是给普通 App 的通用实时 wattmeter。

所以产品语义推荐：

#### 放电状态

此时：

```text
Device draw ≈ -BatteryPower
```

其实相当不错。

因为绝大多数系统能量来自电池。

比如：

```text
Battery:
3.82V
-850mA

≈ 3.25W device battery-side power
```

这是非常有价值的指标。

#### 充电状态

如果同时拿到：

```text
P_usb_input
P_battery_charge
```

理论：

[
P_{device}\approx
P_{input}-P_{battery}
]

但还需要考虑：

```text
PMIC/charger conversion loss
cable measurement location
battery fuel gauge error
```

所以显示：

> Estimated system load

而不是：

> Device actual power

------

# Q2.6 Plug type

`BATTERY_PLUGGED_*`：

```text
AC
USB
WIRELESS
DOCK
```

是 framework 归一化结果。

它不等于：

```text
BATTERY_PLUGGED_USB
    ↕
/sys/class/power_supply/usb
```

一一对应。

正确做法：

- UI 插头类型 → BatteryManager；
- 详细 charger 节点 → sysfs provider。

------

# Q3. `/proc` / `/sys` Android 10–16

符号：

```text
✅ = 可以作为 AOSP 普通 App 基线
❌ = 不可作为普通 App 基线
⚠ = vendor/SELinux label dependent，只能 probe
```

| 路径               | 29   | 30   | 31   | 32   | 33   | 34   | 35   | 36   |
| ------------------ | ---- | ---- | ---- | ---- | ---- | ---- | ---- | ---- |
| `/proc/stat`       | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    |
| `/proc/meminfo`    | ✅    | ✅    | ✅    | ✅    | ✅    | ✅    | ✅    | ✅    |
| `/proc/loadavg`    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    |
| `/proc/uptime`     | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    |
| `/proc/net/dev`    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    |
| `/proc/net/tcp`    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    |
| `/proc/pressure/*` | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    | ❌    |
| CPU cpufreq sysfs  | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    |
| thermal zone sysfs | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    |
| power_supply sysfs | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    | ⚠    |

Android 10 AOSP 明确给 appdomain `proc_meminfo` 的读取权限，因此 `/proc/meminfo` 是这里最可信的直接 `/proc` 数据源。([Android Git Repositories](https://android.googlesource.com/platform/system/sepolicy/%2B/refs/heads/android10-qpr3-release/public/app.te))

而 `/proc/stat`、uptime 等自 Android O 就已经被限制，当前 Android 的 policy 仍保留这类限制。旧 targetSdk 也不会神奇恢复这些权限。([Android Git Repositories](https://android.googlesource.com/platform/system/sepolicy/%2B/android16-release/private/app_neverallows.te))

------

## `/proc/net/dev`

普通 App 不要依赖。

无权限网络吞吐改成：

```java
TrafficStats.getTotalRxBytes()
TrafficStats.getTotalTxBytes()
```

或者：

```text
getUidRxBytes()
getUidTxBytes()
```

你的 SysMon 做系统总体网络速度：

```text
ΔTotalRxBytes / Δt
ΔTotalTxBytes / Δt
```

就够了。

shell 时才升级到：

```text
/proc/net/dev
```

------

## `/proc/uptime`

无权限模式根本没必要读。

直接：

```java
SystemClock.elapsedRealtime()
```

这比解析 proc 更稳。

------

## PSI

路径：

```text
/proc/pressure/cpu
/proc/pressure/memory
/proc/pressure/io
```

Linux upstream PSI 是较新内核能力；4.14 upstream 没有，OEM 可以 backport，所以：

```text
存在性 probe > kernel version 判断
```

普通 App不作为数据源。

shell 下：

```bash
test -r /proc/pressure/cpu
```

再启用。

------

# Q3. cpufreq / thermal / power_supply 为什么我写 ⚠ 而不是统一 ❌

因为 `/sys` 权限不是简单按：

```text
Android 12
Android 13
```

决定的。

真正决定：

```text
kernel node
+
mode bits
+
genfs_contexts
+
vendor sepolicy
+
symlink target label
```

AOSP 历史上对一部分：

```text
sysfs_devices_system_cpu
```

有广泛读取规则，但 cpufreq driver/vendor 可能落到其他 SELinux type。

所以：

> 编写通用 SysMon 时，这些只能作为 runtime bonus source。

你的 Huawei 已经提供了一个很好的反例：

```text
shell uid 2000
→ cpufreq EPERM
```

那么 untrusted_app 就不用再期待。

------

# Q3. thermal API 的一个纠正

你提到：

> Android 14 `getDeviceTemperatures()` 走 thermal HAL。

这里需要拆开两套东西。

`HardwarePropertiesManager.getDeviceTemperatures()`：

```text
API 24
```

就有了，而且提供：

```text
CPU
GPU
BATTERY
SKIN
```

温度。

但是：

> Device Owner 或 current VR service 才能调用。

普通 App 会：

```text
SecurityException
```

官方文档明确如此。([Android Developers](https://developer.android.com/reference/android/os/HardwarePropertiesManager))

Android 10/API29 开始普通 App 可以使用的是：

```java
PowerManager.getCurrentThermalStatus()
```

以及 listener。

这是：

```text
NONE
LIGHT
MODERATE
SEVERE
CRITICAL...
```

热状态，而不是：

```text
GPU = 69.2°C
```

------

# Q4. App 内 ADB client

这是整个项目里我认为**工程复杂度最高，同时也最值得独立封装**的一部分。

我强烈建议：

```text
SysMon core
    │
    └── privilege-adb/
           discovery
           pairing
           tls
           protocol
           transport
           shell
```

核心采样代码永远不要知道：

```text
CNXN
AUTH
STLS
OPEN
WRTE
OKAY
CLSE
```

这些细节。

------

# Q4.1 Android 11+ 无线 ADB

官方架构：

```text
pairing server
+
TLS connect server
```

是两个不同端口。

连接端口：

> random port

而不是 5555。

官方文档**没有承诺随机端口范围**。([Android Git Repositories](https://android.googlesource.com/platform/packages/modules/adb/%2B/HEAD/docs/dev/adb_wifi.md))

因此：

> 不要硬编码“30000–49999 就是无线 ADB”。

------

## 你猜测的 Settings key 不正确

不要依赖：

```text
adb_wifi
adb_wifi_pairing
```

保存：

```text
IP:PORT
```

Android Framework 确实有 wireless debugging enabled 状态，例如 `adb_wifi_enabled`，但真正 connect server port 是 adbd 设置的：

```text
service.adb.tls.port
```

另外相关内部 property：

```text
persist.adb.tls_server.enable
persist.adb.wifi.guid
```

AOSP ADB Wi-Fi 文档明确描述了这些。([Android Git Repositories](https://android.googlesource.com/platform/packages/modules/adb/%2B/HEAD/docs/dev/adb_wifi.md))

所以：

```text
settings get global adb_wifi
```

不是可靠端口发现方案。

------

# Q4.2 mDNS 是正确主方案

官方服务：

```text
_adb._tcp
_adb-tls-pairing._tcp
_adb-tls-connect._tcp
```

其中：

```text
_adb._tcp
```

是 legacy TCP。

```text
_adb-tls-pairing._tcp
```

是 pairing。

```text
_adb-tls-connect._tcp
```

是安全连接服务器。([Android Git Repositories](https://android.googlesource.com/platform/packages/modules/adb/%2B/HEAD/docs/dev/adb_wifi.md))

典型官方示例：

```text
192.168.86.38:33861 pairing
192.168.86.38:33015 connect
```

------

## 同机 App 能不能发现自己 adbd

理论和实践上：

> 可以尝试，而且这是正确方向。

但不要假设：

```text
127.0.0.1 mDNS
```

mDNS 本身是在 Wi-Fi/multicast interface 工作：

```text
224.0.0.251:5353
```

你的 App 做 NSD/mDNS discovery 得到的通常是：

```text
当前 wlan IP + port
```

不一定是：

```text
127.0.0.1
```

因此我的连接顺序是：

```text
resolved WLAN self IP
↓
127.0.0.1 same port
```

两个都试。

Huawei/OEM 防火墙差异会导致其中之一失败。

------

# Q4.3 不建议每次扫描 5 万端口

扫描：

```text
5037–55551
```

虽然 loopback closed port 通常立即 RST，理论很快，但 Java 创建数万：

```text
Socket
FileDescriptor
exception
```

本身非常浪费。

还可能造成：

- FD churn；
- CPU spike；
- 电池 spike；
- log 噪声；
- OEM 网络安全策略异常。

正确顺序：

```text
1. cached endpoint
2. 127.0.0.1:5555
3. mDNS _adb-tls-connect
4. mDNS _adb-tls-pairing
5. cached previous TLS port nearby probes
6. bounded concurrent scan
```

只有用户主动点：

> “查找无线调试端口”

时才执行第 6。

------

## 如果必须扫

不要串行：

```java
for (int p=1; p<65536; p++)
```

建议：

```text
32–64 concurrent connects
```

loopback timeout：

```text
20–50ms
```

self-WLAN：

```text
50–150ms
```

TCP connect 成功后不能认定 adbd：

```text
send CNXN
↓
expect A_STLS / A_AUTH / CNXN
```

否则你会误认本地 HTTP/server。

------

# Q4.4 Android 10

Android 10 没有 Android 11 那套 Wireless Debugging pairing UI。

标准路径：

```bash
adb tcpip 5555
```

需要之前通过：

```text
USB ADB
root
existing shell
vendor facility
```

启动 TCP adbd。

之后：

```text
127.0.0.1:5555
```

如果设备允许 loopback，可以自连。

Android 10 legacy TCP：

```text
A_AUTH
```

不是 Android 11 secure Wi-Fi 的 TLS transport。AOSP 文档也明确把 legacy TCP 与 TLS server 区分开。([Android Git Repositories](https://android.googlesource.com/platform/packages/modules/adb/%2B/HEAD/docs/dev/adb_wifi.md))

------

# Q4.5 Pairing 与连接必须分离

Android 11+：

```text
Pairing port
       ↓
pairing protocol
       ↓
host public key becomes trusted
       ↓
Connect port
       ↓
STLS
       ↓
TLS authentication
       ↓
ADB CNXN
```

未配对客户端不能在 connect port 顺带完成 pairing。

官方定义：

> host public key 位于设备 `/data/misc/adb/adb_keys` 或 `/adb_keys` 时即认为 paired。([Android Git Repositories](https://android.googlesource.com/platform/packages/modules/adb/%2B/HEAD/docs/dev/adb_wifi.md))

pairing code：

```text
6 digit
```

由设备 UI 展示。

用户将其输入你的 SysMon。

------

# Q4.6 无线 ADB 不应该再走传统 AUTH 流程

这是实现时非常容易写错的一点。

legacy：

```text
TCP
↓
A_AUTH
↓
RSA challenge
↓
CNXN
```

secure wireless：

```text
TCP
↓
A_STLS
↓
TLS
↓
secure authentication
↓
CNXN
```

官方文档明确：

> legacy server greeting 是 `A_AUTH`；wireless debugging TLS server 是 `A_STLS`。([Android Git Repositories](https://android.googlesource.com/platform/packages/modules/adb/%2B/HEAD/docs/dev/adb_wifi.md))

所以不要：

```text
STLS
→ TLS
→ 再照 Android10 AUTH 代码跑一次
```

------

# Q4.7 RSA key 格式是一个大坑

RSA-2048：

```java
KeyPairGenerator.getInstance("RSA")
```

当然可以生成。

但是：

> ADB public key wire format 不是“普通 PEM public key”。

传统 ADB public key大致是：

```text
base64(Android RSAPublicKey struct) comment
```

而不是：

```text
-----BEGIN PUBLIC KEY-----
...
```

无线 pairing 又要：

- RSA-2048；
- X.509 certificate；
- pairing protocol crypto；
- shared secret derivation。

所以：

> 不建议自己重新实现 crypto 格式。

最合理做法就是你原计划：

> **直接移植 Shizuku/AOSP 已验证实现。**

不要“理解协议后简化一版”。

------

## key persistence

私钥必须跨进程、跨 App 重启持久保存，否则：

```text
每次 SysMon 重启
→ 新 Host identity
→ 再 pairing
```

建议：

```text
app private file
↓
AES-GCM encryption
↓
AES key in Android Keystore
```

而不是把 ADB private key：

```text
明文 SharedPreferences
```

保存。

同时绝对不要 log：

```text
pairing secret
private key
TLS material
```

------

# Q4.8 loopback vs wlan0

从身份认证角度：

```text
同一个 host key
```

即可。

但是 networking 行为不同：

```text
127.0.0.1
```

可能：

- listener 没 bind loopback；
- OEM firewall 特殊处理。

而：

```text
192.168.x.x → 本机
```

更接近 adbd 官方 Wi-Fi 使用场景。

所以 Provider 要接受：

```java
Endpoint {
    InetAddress address;
    int port;
}
```

而不要设计成：

```java
int adbPort;
```

------

# Q4.9 自启 Shizuku：千万别硬编码旧 start.sh

你的：

```text
/sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

已经不能作为长期接口。

截至现在最新正式 release 是 Shizuku **13.6.0**；release notes 明确：

- 支持 Android 16 QPR1；
- 更新 start command；
- starter 可以拷到例如 `/data/local/tmp/shizuku` 的可执行位置；
- Android 13+ trusted WLAN 可无 root auto start。([GitHub](https://github.com/RikkaApps/Shizuku/releases))

所以设计：

```text
ShizukuStarterStrategy
```

而不是：

```java
Runtime.exec("sh /sdcard/.../start.sh")
```

建议：

```text
Manager version
↓
choose known starter strategy
↓
if unsupported
→ tell user "Shizuku startup command changed"
```

------

# Q4.10 推荐完整 ADB state machine

这是我建议你真正实现的结构：

```text
DISABLED
  ↓
DISCOVERING
  ├─ legacy 5555
  ├─ cached endpoint
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

出现：

```text
ECONNREFUSED
TLS cert mismatch
pairing rejected
AUTH rejected
network changed
wireless debugging disabled
```

都回状态机，而不是 throw 到 UI。

------

# Q5. Overlay

## 基础 LayoutParams

推荐：

```java
TYPE_APPLICATION_OVERLAY
FLAG_NOT_FOCUSABLE
FLAG_LAYOUT_IN_SCREEN
```

只有用户选择“忽略触摸”时再：

```java
FLAG_NOT_TOUCHABLE
```

不建议默认：

```text
FLAG_LAYOUT_NO_LIMITS
```

因为你的 200×120dp 小窗没必要突破系统边界，反而会增加：

- cutout；
- gesture inset；
- rotation；
- waterfall；
- foldable；

的布局问题。

`TYPE_APPLICATION_OVERLAY` 从 API26 起就是第三方 overlay 类型。([Android Developers](https://developer.android.com/reference/android/view/WindowManager.LayoutParams))

------

# Android 12 的触摸安全变化

Android 12 对不可信 overlay 的 pass-through touch 做了额外安全限制。

所以：

```text
NOT_TOUCHABLE
```

不意味着：

> 下层任何 App 在任何透明度下都一定会收到点击。

overlay obscuring opacity 会参与系统判断。([Android Developers](https://developer.android.com/reference/android/view/WindowManager.LayoutParams))

因此：

> click-through 是需要真机测试的行为，不是单靠 flag 可以跨 10–16 保证。

------

# 全屏 App 能不能压掉 SysMon overlay

普通 immersive：

```text
hide status/navigation bars
```

通常不会自动隐藏 `TYPE_APPLICATION_OVERLAY`。

但是 Android 12+ 前台 App 可以通过：

```java
Window.setHideOverlayWindows(true)
```

请求系统隐藏其他 App overlay。

这是安全功能。([Android Developers](https://developer.android.com/about/versions/12/features?hl=zh-CN))

所以你的产品文案不要写：

> “强制在所有游戏上显示”。

应该写：

> “在允许第三方悬浮窗的全屏应用中显示”。

银行、密码、安全界面、部分游戏可能压掉 overlay。

------

# `FLAG_SECURE` 的前台 App 会不会把你的 overlay 隐掉

不能把这两件事混淆：

```text
FLAG_SECURE
```

主要控制其窗口内容能否被截图/投屏。

隐藏第三方 overlay 的正式机制是：

```text
HIDE_OVERLAY_WINDOWS /
setHideOverlayWindows()
```

不是 `FLAG_SECURE` 本身。

------

# 横屏位置保存

不要保存：

```text
absolute x/y
```

也不要简单：

```text
x / screenWidth
```

推荐保存相对于**可移动范围**：

[
nx=
\frac{x}
{usableWidth-overlayWidth}
]

[
ny=
\frac{y}
{usableHeight-overlayHeight}
]

rotation 后：

```text
newX =
 nx × (newAvailableW - overlayW)

newY =
 ny × (newAvailableH - overlayH)
```

然后：

```text
clamp
```

这样字体变化/overlay size 变化也不容易跑出屏幕。

------

# Q5.2 截图隐藏：这里必须改变产品预期

你的目标：

> “系统截图时 SysMon overlay 不进入截图，但底下的 App 正常出现在那个区域。”

普通 App **没有一个跨 Android 10–16 的可靠公开 API**做到这一点。

这是关键结论。

------

## Accessibility 拦 Power+VolumeDown 不可靠

系统截图 chord 是 WindowManager policy / PhoneWindowManager 层处理。

Accessibility 的 key filtering 不能给你保证：

```text
AccessibilityService
收到按键
→ hide overlay
→ WindowManager commit
→ SystemUI screenshot
```

一定按这个顺序执行。

而且：

- Power key 特殊；
- 三指截图；
- Huawei 指关节截图；
- Quick Settings；
- gesture screenshot；

完全可能不走这一组合。

所以：

> 不建议为了“截图隐藏”引入 AccessibilityService。

成本远大于收益。

------

## Android 14 Screenshot API

API34 有：

```java
Activity.ScreenCaptureCallback
```

但是：

> 它只监测**你的 Activity 被截图**。

你的 overlay 在别的游戏上时：

```text
SysMon Activity 不可见
```

它不能作为 global screenshot listener。

官方还明确说明 Android 14 该 API 对特定硬件按键截图有效，而不检测 ADB 等截图。([Android Developers](https://developer.android.com/about/versions/14/features/screenshot-detection?authuser=19&hl=en))

------

## `ACTION_SCREENSHOT`

不要做核心依赖。

不存在一个：

```text
API29–36
普通应用
全局可靠
截图之前触发
```

的 screenshot broadcast。

OEM 私有广播即使某设备存在，也只能当 bonus。

------

## MediaStore observer

属于：

```text
after screenshot
```

不是 before。

而且：

- scoped storage；
- Photos permissions；
- OEM screenshot storage；
- delayed insert；
- edit/share screenshot；

都会影响。

它最多实现：

> 检测到截图后隐藏 1～2 秒，减少连续截图泄露。

无法保护第一张。

也不要承诺：

```text
几十毫秒
```

实际延迟没有这样的 API 保证，可能到数百毫秒甚至更多。

------

# 真正推荐的 Screenshot 模式

给用户三个选项即可。

### Off

正常 overlay。

### Secure overlay

给你的 overlay 本身：

```text
FLAG_SECURE
```

优点：

```text
自己的内容不容易进入截图/投屏
```

但 OEM compositor 对那个区域怎么表现可能不同，不应承诺一定“透明看到下面内容”。

### SysMon Screenshot

提供：

```text
Quick Settings tile
或 SysMon 按钮
```

流程：

```text
hide overlay
↓
等待 WindowManager commit / 1–2 frame
↓
Shizuku/ADB 执行 screenshot
↓
restore overlay
```

这是唯一可以真正做得确定的方案。

例如：

```text
hide
~50–100 ms
screencap
restore
```

这里的等待不是截图检测，是你控制截图流程，因此可靠很多。

------

# Q5.3 Fullscreen detection

你问题里：

```text
AccessibilityWindowInfo.getFlags()
FLAG_FULLSCREEN
isImmersive
```

这条路线有概念混淆。

`FLAG_FULLSCREEN`：

```text
WindowManager.LayoutParams
```

不是 `AccessibilityWindowInfo` 给你暴露的可靠 flag。

Accessibility 可以看到：

- window bounds；
- focused；
- active；
- PiP 等。

但无法可靠问：

> “当前前台 App 是否 immersive fullscreen？”

------

## shell 方法

可以：

```bash
dumpsys window
dumpsys activity activities
```

解析：

```text
topResumedActivity
mCurrentFocus
Insets
system bar visibility
```

但是 dumpsys 文本不是稳定 API。

建议：

```text
只在 "Hide in fullscreen" 功能开启时启用
```

而且不要：

```text
500ms dumpsys一次
```

推荐：

```text
前台 package 改变
↓
重新判断

稳定时 2–5s sanity check
```

然后缓存：

```text
package -> fullscreen behavior
```

------

# Q6. Dhizuku

## 结论

对于你的 SysMon：

> **不应该作为核心依赖。**

如果目标是：

```text
小
简单
稳定
高扩展
```

甚至可以第一版完全不集成 Dhizuku。

------

# Dhizuku 唯一有意思的监控能力

前面提到：

```java
HardwarePropertiesManager.getCpuUsages()
HardwarePropertiesManager.getDeviceTemperatures()
```

Device Owner 是允许调用的身份。

官方文档明确限制为：

```text
Device Owner / current VR service
```

否则 `SecurityException`。([Android Developers](https://developer.android.com/reference/android/os/HardwarePropertiesManager))

因此理论上：

```text
Dhizuku DO identity
→ HardwarePropertiesManager
→ CPU usage/raw temperature
```

可能有价值。

但这需要验证 Dhizuku 对这项 Binder/API 的代理方式是否保留 DO caller identity。

所以我会把它放：

```text
ExperimentalHardwarePropertiesProvider
```

而不是：

```text
core dependency
```

------

# “忽略电池优化”

没有你说的：

```java
DevicePolicyManager.setIgnoreBatteryOptimizations()
```

这种通用 DPM API。

正常 App：

```text
ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

由用户明确允许。

不要为这个目的接 Dhizuku。

------

# `setMinimumScreenBrightness` / `setMaximumCapturedFrameRate`

不要基于这些名称设计功能。

它们不是你可以依赖的通用 `DevicePolicyManager` 监控 API。

尤其：

> “为了监控稳定，使用 Device Owner 强制锁亮度 / 限游戏帧率”

是非常不合适的架构。

Device Owner 是整机策略控制能力，副作用明显大于 SysMon 的需求。

------

# Q6.1 最终 Dhizuku 建议

第一版：

```text
Settings
  Dhizuku status:
      Installed
      DO active
```

然后：

```text
不调用任何 Dhizuku API。
```

以后真机证实：

```text
HardwarePropertiesManager via Dhizuku
```

特别有价值，再加入一个：

```text
DhizukuHardwareProvider
```

即可。

这会明显减少代码量和故障面。

------

# Q6.2 Shizuku API

你 targetSdk=29 不受：

> “target Android 14 导致 binder compatibility crash”

那个问题的直接影响。

但没理由去使用老 Client API。

我的建议：

```text
Shizuku API 13.1.5
```

或者与你确认兼容的当前 client library。

Manager 则使用当前 13.6.0；它已经明确支持 Android 16 QPR1。([GitHub](https://github.com/RikkaApps/Shizuku/releases))

------

## 手工 aapt2 build 一个特别容易漏的东西

因为你：

```text
不用 Gradle
```

所以 library manifest merge 不会替你完成所有事情。

建议直接打开你 vendored 的：

```text
Shizuku API AAR
```

把该版本 manifest 中需要的：

```text
provider
uses-permission
meta-data
```

逐项复制到主 Manifest。

尤其：

```text
moe.shizuku.manager.permission.API_V23
```

是 Shizuku 权限名，但**不要自己声明 `<permission>`**。

Shizuku 官方也明确要求第三方不要自行声明它的 `moe.shizuku.manager.permission.*` permission。([GitHub](https://github.com/rikkaapps/shizuku))

最安全的原则是：

> 复制你实际 vendored API 版本里的 manifest，不要抄网上某篇 2022 教程。

------

# Q7. Kirin 710

## Q7.1 cluster mapping

通常 Kirin 710 可以理解为：

```text
cpu0–3  little A53
cpu4–7  big A73
```

但 SysMon **不要硬编码**。

正确探测：

```text
/sys/devices/system/cpu/cpufreq/policy0/related_cpus
/sys/devices/system/cpu/cpufreq/policy4/related_cpus
```

或：

```text
affected_cpus
```

以及：

```text
cpuN/cpufreq → policyX
```

建立：

```java
CpuFrequencyDomain {
    int policyId;
    int[] CPUs;
}
```

而不是：

```java
CPU0.frequency
CPU1.frequency
...
```

------

## 为什么

手机 SoC 的 DVFS 通常是：

```text
cluster/policy domain
```

例如：

```text
cpu0
cpu1
cpu2
cpu3
    ↓
同一个 cpufreq policy
```

因此你看到四个：

```text
scaling_cur_freq
```

有可能只是四个 symlink，实际上同一 frequency domain。

------

## 如果 cpufreq 全 EPERM

可以尝试：

```bash
cat /proc/cpuinfo
```

ARM MIDR part：

```text
A53 = 0xD03
A73 = 0xD09
```

但 Huawei 可能不给完整字段。

最终允许：

```text
CPU topology known
CPU frequency N/A
```

不要为了一个频率字段让整个 CPU Provider fail。

------

# Q7.2 `dumpsys thermalservice`

这是我非常推荐你在 Huawei 上马上测试的东西：

```bash
dumpsys thermalservice
```

Android 10 已经有 `ThermalManagerService` / Thermal HAL 2.0 路线，framework thermal service 本来就是为了绕过“应用自己抓各种 thermal_zone”这种方式。([Android Git Repositories](https://android.googlesource.com/platform/frameworks/base/%2B/refs/heads/android10-release/services/core/java/com/android/server/power/ThermalManagerService.java))

根据 Android/HAL/vendor 版本，它可能输出：

```text
Temperature
  CPU
  GPU
  BATTERY
  SKIN

cooling devices
thermal status
thresholds
```

因此你 Huawei：

```text
/sys/class/thermal → EPERM
```

时，非常适合作：

```text
dumpsys thermalservice
```

fallback。

注意：

> 输出格式不是稳定 machine API。

所以 parser 要：

```text
best-effort
```

而不是严格固定行号。

------

# Q7.3 `/proc/stat`

字段 Linux ABI 很稳定：

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

[
CPU =
\frac{\Delta NonIdle}
{\Delta Total}
]

注意：

> `guest` 已经包含在 user，`guest_nice` 包含在 nice。

不要重复加，否则 CPU 会算高。

------

# Q7.4 top processes

普通 App：

> 不应该依赖遍历 `/proc/<pid>/stat` 读取其他 App。

Android 的 proc privacy/SELinux 会把你挡住。

shell：

```text
可以做。
```

但不要：

```bash
top -b -n1
```

每 500ms spawn 一次。

这是非常浪费的。

推荐：

```text
Top process feature enabled?
        ↓ yes
sample every 2–5s
```

有两个实现选择：

### 简单版

```bash
top -b -n1 ...
```

解析。

### 高性能版

Shizuku UserService 内：

```text
遍历 /proc/[0-9]*
↓
读取 stat/status/cmdline
↓
直接返回 TopN
```

因为这整个操作发生在 uid2000 进程。

这样：

```text
一次 Binder transaction
```

就把 TopN 返回 App。

这比主 App 远程：

```text
cat /proc/100/stat
cat /proc/101/stat
cat ...
```

高效得多。

------

# Q7.5 网络接口

`/proc/net/dev` 会包含：

```text
lo
```

例如：

```text
lo
wlan0
rmnet0
rmnet_data0
eth0
tun0
```

不要：

> 选 RX bytes 最大的接口。

这会选到历史累计最大的旧接口。

------

## 正确方法

Framework：

```java
ConnectivityManager.getActiveNetwork()
getNetworkCapabilities()
getLinkProperties()
```

然后：

```java
LinkProperties.getInterfaceName()
```

得到 active interface。

注意 VPN：

```text
tun0
```

可能是逻辑流量接口。

所以最好给网络 Provider 两个概念：

```text
Logical Traffic
Physical Interface Traffic
```

一般用户 UI：

```text
TrafficStats total RX/TX
```

高级页：

```text
per interface
```

------

# `dumpsys netstats` 是否更准

它不是：

> `/proc/net/dev` 的“更精确版本”。

用途不同。

`/proc/net/dev`：

```text
NIC/kernel interface byte counters
```

非常适合：

```text
1s 实时速度图
```

`netstats`：

```text
framework accounting
UID buckets
network identities
historical accounting
```

更适合：

> “某 App 今天用了多少流量”。

而且 dumpsys 重很多。

所以实时图：

```text
/proc/net/dev
```

优于：

```text
dumpsys netstats
```

------

# 最重要的部分：SysMon 最终架构

我建议做成下面这种结构。

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
 Framework     AppDirect   ShellFast   ShellCommand  Vendor
 Provider      Provider    Provider    Provider      Provider
                               │
                         ┌─────┴─────┐
                         ▼           ▼
                     Shizuku      ADB Shell
```

------

# 1. 不要定义 `PrivilegeMode`

不要核心代码写：

```java
enum Mode {
    NORMAL,
    SHIZUKU,
    DHIZUKU,
    ADB
}
```

这会很快变成：

```java
if SHIZUKU...
else if ADB...
else if...
```

地狱。

------

## 定义 Metric

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

------

## 每个 provider 宣布自己能提供什么

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

问号：

```text
probe 后确定。
```

------

# 2. 每个 sample 携带来源与质量

建议：

```java
MetricSample {
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

这会让以后调试非常舒服。

例如用户报告：

> 我的 GPU 功率乱跳。

diagnostics 页面直接：

```text
GPU Usage
Source: mali-devfreq:/sys/class/devfreq/ff9a0000.gpu/load
Quality: VENDOR_SYSFS
```

你马上知道问题在哪。

------

# 3. capability probe 只做一次

启动后：

```text
Probe
↓
CapabilityGraph
↓
Cache
```

不要每秒：

```java
new File(path).exists()
```

几十遍。

缓存 key 推荐：

```text
Build.FINGERPRINT
kernel release
boot_id
privilege backend
Shizuku version
```

以下事件再 invalidate：

```text
reboot
OS update
Shizuku binder death
privilege granted/revoked
ADB reconnect
```

------

# 4. Shizuku UserService 必须负责“批量读取”

你的原描述：

> UserService 里面 Runtime.exec 读 `/proc`、`/sys`

这里建议改。

**文件绝对不要用 `Runtime.exec("cat ...")`。**

UserService 已经：

```text
uid = shell
```

那么直接：

```java
FileInputStream
BufferedInputStream
```

读：

```text
/proc/stat
/proc/meminfo
/proc/net/dev
/sys/...
```

即可。

------

## 每 tick 一个 Binder transaction

不要：

```text
App
 ├ binder read /proc/stat
 ├ binder read meminfo
 ├ binder read gpu
 ├ binder read battery
 └ binder read network
```

做成：

```java
ShellSnapshot sampleFastMetrics(int mask)
```

一次进入 UserService：

```text
read proc/stat
read sysfs GPU
read net/dev
...
```

然后一次返回。

这对：

```text
速度
CPU overhead
Binder overhead
电池
代码简单度
```

都有明显好处。

------

# 5. `dumpsys` 单独作为慢 Provider

绝对不要每秒：

```text
dumpsys battery
dumpsys thermalservice
dumpsys gpu
dumpsys window
```

分频：

| 数据                      | 推荐频率       |
| ------------------------- | -------------- |
| CPU usage                 | 500ms–1s       |
| network                   | 500ms–1s       |
| GPU util                  | 500ms–1s       |
| battery current           | 1–2s           |
| CPU/GPU freq              | 1–2s           |
| temperature               | 2–5s           |
| memory                    | 1–2s           |
| battery level             | 10–30s / event |
| charge counter derivative | 30–120s        |
| thermalservice dumpsys    | 5–10s          |
| top processes             | 2–5s           |
| capability probe          | once           |

更重要的是：

> 没人显示这个 Metric 时不采。

例如 overlay 当前只选：

```text
CPU
RAM
battery
```

那 GPU/thermal Provider：

```text
0 wakeups
```

------

# 6. UI 与采样分离

推荐：

```text
Sampling:
1 Hz

Main UI:
2–4 Hz max render

Overlay:
1 Hz default
```

采样线程更新：

```java
AtomicReference<SystemSnapshot>
```

Activity 和 Overlay 只读取最新 immutable snapshot。

这样根本不需要：

- RxJava；
- Kotlin coroutine；
- LiveData；
- Flow；
- DI framework。

完全符合你：

> Java + 小 APK。

------

# 7. Parser 不要 Regex 化

对于：

```text
/proc/stat
/proc/meminfo
/proc/net/dev
```

不要：

```java
line.split("\\s+")
```

每秒大量创建对象。

也不要：

```java
Scanner
```

建议一个小型：

```text
AsciiParser
```

按 byte/char 扫数字。

例如：

```java
static long parseLong(
    byte[] data,
    int start,
    int end)
```

这样：

```text
GC ≈ 极低
```

对长期 overlay 很有意义。

------

# 8. Error model

每个 Provider 的错误要分类：

```text
UNSUPPORTED
PERMISSION_DENIED
TRANSIENT_IO
BACKEND_DEAD
PARSE_CHANGED
INVALID_VALUE
```

策略：

### ENOENT

```text
UNSUPPORTED
```

本次 boot 不再试。

### EACCES / EPERM

```text
PERMISSION_DENIED
```

当前 backend demote。

不要每秒再试一次。

### Shizuku Binder death

```text
invalidate shell capabilities
↓
fallback Framework
```

UI 不崩。

### Parse changed

记录一次：

```text
source/parser/version
```

然后 N/A。

不要：

```text
NumberFormatException
→ foreground service crash
```

------

# GPU / 温度 / Battery 最终字段矩阵

## 无权限

| Metric                              | 无权限             |
| ----------------------------------- | ------------------ |
| GPU model                           | ✅ GLES/Vulkan      |
| GPU utilization                     | ❌ N/A              |
| GPU frequency                       | ❌/⚠ vendor bonus   |
| GPU memory                          | ❌                  |
| CPU raw temperature                 | ❌                  |
| GPU raw temperature                 | ❌                  |
| battery temperature                 | ✅                  |
| thermal status                      | ✅ API29+           |
| battery level                       | ✅                  |
| battery voltage                     | ✅                  |
| battery current                     | ✅/⚠ BatteryManager |
| battery power                       | ✅/⚠ V×I            |
| charger input power                 | ❌                  |
| actual whole-device power           | ❌                  |
| discharge battery-side device power | ✅/⚠                |

------

## shell/Shizuku/ADB

| Metric             | Shell                     |
| ------------------ | ------------------------- |
| GPU model          | ✅                         |
| GPU utilization    | ⚠ vendor/devfreq/KGSL     |
| GPU frequency      | ⚠ devfreq/vendor          |
| GPU memory         | ⚠ `dumpsys gpu --gpumem`  |
| CPU raw temp       | ⚠ `thermalservice`/vendor |
| GPU raw temp       | ⚠ `thermalservice`/vendor |
| battery temp       | ✅                         |
| battery voltage    | ✅                         |
| battery current    | ✅/⚠                       |
| battery power      | ✅/⚠                       |
| input power        | ⚠ power_supply            |
| whole device power | ⚠ estimate                |
| CPU utilization    | ✅ `/proc/stat`            |
| per-core CPU       | ✅ `/proc/stat`            |
| network interface  | ✅ `/proc/net/dev`         |

------

# 对 Huawei HarmonyOS 2，我建议你现在马上运行的一组 capability test

这一组命令获得的信息比继续猜 Huawei BSP 有价值：

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

**建议你以后把这组能力探测直接变成 SysMon 的 `Diagnostics → Export capability report`。**用户遇到异常让他导出一份，你就不用再猜厂商 ROM。

------

# 最终推荐的开发优先级

如果以你要求的：

> **程序小 + 实现简单 + 安全不崩 + 扩展性强 + 工作占用低**

作为最高准则，我会按这个顺序开发：

**第一阶段只完成 `Metric / Provider / Snapshot / Scheduler` 骨架**，无权限数据做 BatteryManager、TrafficStats、meminfo、SystemClock；Shizuku UserService 一次调用批量读取 `/proc/stat`、`/proc/meminfo`、`/proc/net/dev`。这时候 CPU、RAM、网络、电池和 overlay 已经能非常稳定地工作。

**第二阶段加 hardware capability probe**，实现 `DevfreqGpuProvider`、`AdrenoKgslProvider`、`ThermalServiceProvider`、`PowerSupplyProvider`。所有 vendor 数据源都是插件式 Provider，找不到就 N/A，不影响核心功能。

**第三阶段再做 embedded ADB。**它应该完全实现成一个可替换的 `ShellBackend`，对上层暴露的接口与 Shizuku 一致：

```java
interface ShellBackend {
    FastSnapshot sampleFast(int mask);
    CommandResult exec(String command, long timeout);
    boolean isAlive();
}
```

这样 ADB 无论 pairing、TLS、端口发现多复杂，都不会污染监控逻辑。

**Dhizuku 放最后，甚至不实现。**除非你实测 `HardwarePropertiesManager` 通过 Dhizuku 能稳定得到 Huawei/其他设备 raw thermal/CPU 信息，否则它对 SysMon 不值得增加一整套依赖。

**Overlay 的“截图隐藏”不要依赖 Accessibility。**第一版实现普通 overlay + optional `FLAG_SECURE`；如果你确实需要“截图里完全没有监控层”，提供 SysMon 自己的截图入口，执行 `hide → frame commit → screenshot → restore`。这是可预测、维护成本最低的方案。

还有一个工程层面的建议：**你的 `targetSdk 29` 可以作为兼容策略暂时保留，但编译 SDK 应尽量用 API 36。**不要让整个架构依赖“target 29 能绕过现代 Android 限制”；SELinux、内核接口、HIDE_OVERLAY_WINDOWS、无线 ADB 等大量限制根本不由 targetSdk 决定。Shizuku 当前 13.6.0 也已经明确包含 Android 16 QPR1 支持以及新的启动命令，因此尤其不要继续绑定旧 `start.sh` 路径。([GitHub](https://github.com/RikkaApps/Shizuku/releases))

如果下一步进入实现，我建议直接从 **`MetricProvider + CapabilityGraph + Shizuku 批量采样协议`** 开始，这个骨架一旦定对，后面 Mali/Adreno/Huawei/ADB 都只是增加 Provider，而不会反复重构主程序。