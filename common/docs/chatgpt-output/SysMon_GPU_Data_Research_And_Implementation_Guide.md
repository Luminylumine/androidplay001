# SysMon GPU 数据获取调研与实现指南

> 适用：Android 10+ / HarmonyOS / EMUI / MIUI / HyperOS  
> GPU：Mali / Adreno / PowerVR  
> 语言：Java  
> 目标：低开销、小包体、稳定不崩、跨厂商 fallback

## 0. 核心结论

Android 没有普通第三方 App 可稳定调用的跨厂商“GPU 使用率 / GPU 当前频率”公共 API。SysMon 应采用 **Metric Source / Provider** 架构，而不是依赖单一路径。

推荐：

```text
GpuMetricResolver
├─ AdrenoKgslSource
├─ MaliDevfreqSource
├─ GenericDevfreqSource
├─ MaliVendorSource
├─ PowerVrSource
├─ DumpsysGpuSource
└─ RenderMetricsSource
```

两个重要纠正：

1. `BatteryManager`、`ActivityManager` 不能提供 GPU usage/frequency。
2. `SurfaceFlinger` FPS / frame latency 不能换算成真实 GPU %。它只能作为独立的 `Render FPS / Jank / Frame Pressure` 指标。

正确优先级：

```text
明确 busy/total counter
→ 明确 utilization 节点
→ devfreq + 已知 vendor load
→ AOSP GPU work accounting
→ frequency-only
→ Render FPS（独立指标）
→ N/A
```

---

# 1. GPU 类型检测

最简单可靠的是 GLES renderer：

```java
String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
```

注意必须在有效 GLES Context 中调用。

```java
public enum GpuVendor {
    ADRENO,
    MALI,
    POWERVR,
    UNKNOWN
}

public static GpuVendor detectGpuVendor(String renderer) {
    if (renderer == null) return GpuVendor.UNKNOWN;

    String r = renderer.toLowerCase(Locale.ROOT);

    if (r.contains("adreno"))
        return GpuVendor.ADRENO;

    if (r.contains("mali"))
        return GpuVendor.MALI;

    if (r.contains("powervr") ||
        r.contains("power vr") ||
        r.contains("rogue"))
        return GpuVendor.POWERVR;

    return GpuVendor.UNKNOWN;
}
```

不要只依赖：

```text
Build.HARDWARE
Build.BOARD
```

这些更适合辅助判断 SoC，而不是 GPU 型号。

---

# 2. 通用 devfreq 探测

不要写死：

```text
/sys/class/devfreq/gpu/cur_freq
```

真实路径可能是：

```text
/sys/class/devfreq/ff9a0000.gpu
/sys/class/devfreq/13000000.mali
/sys/class/devfreq/soc:qcom,kgsl-3d0
```

第一次 capability probe：

```bash
for d in /sys/class/devfreq/*; do
    echo "=== $d ==="
    readlink -f "$d" 2>/dev/null

    for f in         name         cur_freq         available_frequencies         min_freq         max_freq         governor         load         utilization         busy_time         total_time
    do
        printf "%s=" "$f"
        cat "$d/$f" 2>/dev/null || echo X
    done
done
```

GPU candidate 名称建议匹配：

```text
gpu
mali
g3d
kgsl
pvr
powervr
graphics
```

Linux devfreq 内部标准状态本身有：

```text
busy_time
total_time
current_frequency
```

但具体 GPU driver 是否通过 sysfs 将 utilization 暴露给用户空间，不保证。

---

# 3. Mali / Kirin / mali_kbase

Kirin 710 的 Mali-G51 属于 Bifrost，典型驱动为 `mali_kbase` 或 Huawei 修改版。

ARM kbase 的 devfreq 实现内部确实维护：

```text
busy_time
total_time
current_frequency
```

`kbase_devfreq_status()` 的典型逻辑是：

```text
busy_time = time_busy
total_time = time_busy + time_idle
current_frequency = current_nominal_freq
```

因此驱动内部知道 GPU busy/idle，但：

> Huawei 是否通过一个 App/shell 可读的 sysfs 节点导出，是 vendor BSP + SELinux 问题。

## 3.1 Mali 推荐探测链

```text
/sys/class/devfreq/*
→ vendor GPU sysfs
→ /sys/kernel/gpu/*
→ /sys/kernel/debug/mali*
→ /proc/driver/mali*
→ dumpsys gpu
→ N/A
```

## 3.2 Huawei/Kirin 可探测候选

只允许 probe，不要硬编码保证存在：

```text
/sys/kernel/gpu/
/sys/kernel/gpu/gpu_clock
/sys/kernel/gpu/gpu_min_clock
/sys/kernel/gpu/gpu_max_clock

/sys/devices/platform/*gpu*
/sys/devices/platform/*mali*
/sys/devices/platform/*g3d*

/proc/gpuinfo
/proc/gpu/*
/proc/driver/mali*
```

公开 Kirin BSP/DTS 可以看到 Huawei 对 GPU clock / `g3d` / hw-vote 的私有管理，因此 Kirin 上即便 Mali 支持 devfreq，也不代表标准 `cur_freq` 一定对 shell 暴露。

## 3.3 Mali debugfs

可能：

```text
/sys/kernel/debug/mali0/
/sys/kernel/debug/mali/
/sys/kernel/debug/mali-kbase/
```

可能含：

```text
utilization
mali_trace
job
memory
pm
power
```

但 debugfs 不是稳定生产 ABI，并受：

```text
CONFIG_DEBUG_FS
Mali Kconfig
vendor kernel
SELinux
mount policy
```

影响。

对于你已经确认 shell 对 Huawei 多个 sysfs 节点 `EPERM` 的设备，debugfs 应作为低优先级 probe，而不是主方案。

---

# 4. Qualcomm Adreno / KGSL

Adreno 是最值得优先支持的平台。

常见路径：

```text
/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage
/sys/class/kgsl/kgsl-3d0/gpubusy
/sys/class/kgsl/kgsl-3d0/gpuclk
/sys/class/kgsl/kgsl-3d0/max_gpuclk
/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies

/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq
/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies
```

Qualcomm KGSL 内核源码明确创建了 `gpu_busy_percentage`、`gpubusy`、`gpuclk` 等节点。

## 4.1 `gpu_busy_percentage`

这是最理想的 source。

可能输出：

```text
37 %
```

直接解析成 0–100。

```java
static Double parsePercent(String raw) {
    if (raw == null) return null;

    raw = raw.trim()
             .replace("%", "")
             .trim();

    try {
        double v = Double.parseDouble(raw);
        return v >= 0.0 && v <= 100.0 ? v : null;
    } catch (NumberFormatException e) {
        return null;
    }
}
```

## 4.2 `gpubusy`

常见输出：

```text
busy total
```

例如：

```text
12345 67890
```

一个重要细节：

很多 KGSL 版本输出的是最近一个 driver accounting interval 的：

```text
busy_old
total_old
```

也就是说应直接：

\[
Usage = busy / total 	imes 100
\]

而不是再做两次读取差分。

先观察：

```bash
while true; do
    cat /sys/class/kgsl/kgsl-3d0/gpubusy
    sleep 1
done
```

如果数值不断是不同的小区间值，就用：

```text
busy / total
```

如果确认它们是持续单调增加的累计 counter，才用：

```text
Δbusy / Δtotal
```

解析：

```java
static Double parseBusyTotal(String raw) {
    if (raw == null) return null;

    String[] p = raw.trim().split("\\s+");
    if (p.length < 2) return null;

    try {
        long busy = Long.parseLong(p[0]);
        long total = Long.parseLong(p[1]);

        if (busy < 0 || total <= 0 || busy > total)
            return null;

        return busy * 100.0 / total;

    } catch (NumberFormatException e) {
        return null;
    }
}
```

正式高频采样建议用手写整数 parser 代替 Regex `split()`，降低 GC。

## 4.3 Adreno frequency

推荐：

```text
1. /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq
2. /sys/class/kgsl/kgsl-3d0/gpuclk
3. /sys/class/kgsl/kgsl-3d0/clock_mhz
4. generic devfreq
```

`gpuclk` / `cur_freq` 常见单位为 Hz，例如：

```text
585000000
```

则：

```java
double mhz = hz / 1_000_000.0;
```

如果明确节点名是：

```text
clock_mhz
```

则返回值已经是 MHz。

---

# 5. 小米为什么仍可能读不到 KGSL

sysfs 文件的 Unix mode 即使是 `0444`，Android 仍可能通过 SELinux 拦住 App。

因此首先用 shell 验证：

```bash
adb shell cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage
adb shell cat /sys/class/kgsl/kgsl-3d0/gpubusy
adb shell cat /sys/class/kgsl/kgsl-3d0/gpuclk
adb shell cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq
```

将问题分成：

```text
A. App 可读
B. App 不可读，但 shell 可读
C. shell 也不可读
D. 节点不存在
```

对应策略：

```text
A → Direct provider
B → Shizuku / embedded ADB provider
C → 继续 vendor/dumpsys probe
D → 继续下一个 source
```

如果小米上 shell 能读 KGSL：

> 这是 Shizuku UserService 最适合解决的场景。

---

# 6. PowerVR

PowerVR 的 Android vendor 接口比 KGSL 更不统一。

推荐：

```text
Generic devfreq
→ platform/vendor PVR nodes
→ optional PVRScope
→ N/A
```

不要假设跨设备一定有：

```text
/sys/class/pvr/*
```

Imagination 提供过 PVRMonitor/PVRScope，可读取 PowerVR hardware counters、clock 等，但它属于 PowerVR 专用 profiling 体系。

如果 SysMon 目标是：

```text
小 APK
纯 Java
跨 GPU
```

第一版不建议引入 PVRScope/native SDK。

---

# 7. `dumpsys gpu`

AOSP 当前 `GpuService` service name 是：

```text
gpu
```

较新版本支持 dump 内容：

```text
--gpudriverinfo
--gpumem
--gpustats
--gpuwork
```

探测：

```bash
service check gpu
dumpsys gpu
dumpsys gpu --gpudriverinfo
dumpsys gpu --gpumem
dumpsys gpu --gpustats
dumpsys gpu --gpuwork
```

AOSP 源码明确允许 shell uid dump。

但：

> `dumpsys gpu` 并不是通用的瞬时 `GPU utilization = 63%` API。

它主要提供：

```text
driver information
GPU memory accounting
GPU stats
GPU work accounting
```

较新的 `--gpuwork` 更接近 GPU work / time-in-frequency-state accounting，而不是传统 1 秒硬件 busy percentage。

HarmonyOS 2 / Android 10 不要假定有 `--gpuwork`。

---

# 8. SurfaceFlinger 不能作为 GPU % fallback

历史命令：

```bash
dumpsys SurfaceFlinger --latency <layer>
```

可以得到：

```text
refresh period
frame timestamps
frame latency
```

较新版本还有：

```text
frame timeline
time stats
```

可以构建：

```text
FPS
Jank
Frame latency
```

但不能推出 GPU %。

错误实现：

```java
gpuUsage = fps / refreshRate * 100;
```

这是错误的。

例如：

```text
30 FPS @ 60Hz
```

GPU 可能是：

```text
20%
40%
80%
100%
```

也可能只是 App 主动锁 30 FPS。

因此建议新增独立指标：

```text
RENDER_FPS
FRAME_JANK
FRAME_LATENCY
```

而 GPU usage 获取失败就显示：

```text
N/A
```

---

# 9. HarmonyOS / EMUI 权限模型

Huawei user build + SELinux enforcing 下，读文件最终受：

```text
Unix mode
+
UID/GID
+
SELinux domain/type
```

共同决定。

即使：

```text
-r--r--r--
```

也可能 EACCES/EPERM。

## 普通 App

GPU Model：

```text
✅ GLES/Vulkan
```

GPU usage：

```text
❌ 无标准 API
⚠ vendor sysfs 恰好开放时作为 bonus
```

GPU frequency：

```text
❌ 无标准 API
⚠ vendor sysfs 恰好开放时作为 bonus
```

## Shizuku ADB mode

Shizuku UserService 在 ADB mode 运行：

```text
uid 2000
shell
```

因此：

> shell 能做什么，它就能做什么。

如果：

```bash
adb shell cat PATH
```

也返回：

```text
Permission denied
```

那么 Shizuku ADB mode 通常也不能突破该 SELinux 限制。

## Dhizuku

Dhizuku 是：

```text
Device Owner sharing
```

不是：

```text
shell uid 2000
```

所以：

> Dhizuku 基本不能解决 GPU sysfs 读取权限问题。

Device Owner 的 `HardwarePropertiesManager` 可以用于 GPU 温度等特殊硬件属性，但并没有 GPU utilization / frequency API。

---

# 10. 不要把权限设计成全局 Mode

不推荐：

```text
NORMAL
SHIZUKU
DHIZUKU
ADB
```

然后整个 GPU 模块随 Mode 切换。

推荐：

```text
DirectFileBackend
ShellBackend
DeviceOwnerBackend
```

其中：

```text
ShellBackend
├─ Shizuku
└─ Embedded ADB
```

每个 Source 自己声明需要哪个 backend。

---

# 11. 推荐数据模型

GPU 各字段应独立 fallback。

例如：

```text
usage → KGSL
frequency → devfreq
memory → dumpsys gpu
model → GLES
```

不要要求一个 Provider 必须同时返回所有 GPU 字段。

```java
public final class GpuSnapshot {
    public String model;

    public Double usagePercent;
    public Long frequencyHz;
    public Long memoryBytes;

    public String usageSource;
    public String frequencySource;
    public String memorySource;
}
```

这样允许：

```text
GPU Model = Mali-G51
GPU Usage = N/A
GPU Frequency = 600 MHz
GPU Memory = N/A
```

---

# 12. Safe File Read

普通 App 和 Shizuku UserService 都可以使用直接文件读取。

```java
public final class SafeFileReader {

    private SafeFileReader() {}

    public static String readText(String path) {
        File file = new File(path);

        if (!file.isFile())
            return null;

        try (FileInputStream in =
                     new FileInputStream(file)) {

            byte[] buf = new byte[256];
            int count = in.read(buf);

            if (count <= 0)
                return null;

            return new String(
                    buf,
                    0,
                    count,
                    StandardCharsets.US_ASCII)
                    .trim();

        } catch (IOException |
                 SecurityException e) {
            return null;
        }
    }
}
```

---

# 13. Shizuku UserService 不要执行 `cat`

如果 UserService 已经以 shell uid 运行：

不推荐：

```java
Runtime.exec("cat /sys/...");
```

直接：

```java
FileInputStream
```

读取即可。

优点：

```text
少一次 shell process
少 fork/exec
少 stdout parser
更快
更省电
更稳定
```

---

# 14. Provider Probe 只做一次

错误：

```text
每 500ms：
  path A exists?
  path B exists?
  path C exists?
```

正确：

```text
启动 / backend 改变
↓
probe once
↓
选择 source
↓
cache path + parser
↓
只采选中的节点
```

以下事件再重新 probe：

```text
reboot
ROM update
Shizuku binder death
ADB reconnect
连续采样失败
```

---

# 15. Probe Result 不要只有 boolean

推荐：

```java
public enum ProbeResult {
    AVAILABLE,
    NOT_FOUND,
    PERMISSION_DENIED,
    INVALID_FORMAT,
    UNSUPPORTED
}
```

Diagnostics 例如：

```text
/sys/class/kgsl/kgsl-3d0/gpubusy
Backend: APP
Result: PERMISSION_DENIED

/sys/class/kgsl/kgsl-3d0/gpubusy
Backend: SHIZUKU_SHELL
Result: AVAILABLE
Sample: 12947 38820
```

---

# 16. 推荐 Resolver

GPU Usage 和 Frequency 最好分别 resolve。

## Usage

```text
Adreno:
gpu_busy_percentage
→ gpubusy
→ known devfreq utilization
→ dumpsys gpu work
→ N/A

Mali:
known devfreq utilization/load
→ vendor Mali sysfs
→ debugfs if permitted
→ GPU work
→ N/A

PowerVR:
known generic devfreq utilization
→ vendor PVR
→ optional PVRScope
→ N/A
```

## Frequency

```text
Adreno:
kgsl devfreq/cur_freq
→ gpuclk
→ generic devfreq
→ N/A

Mali:
GPU devfreq cur_freq
→ vendor GPU/G3D clock
→ N/A

PowerVR:
generic devfreq cur_freq
→ vendor node
→ N/A
```

---

# 17. 采样频率

| Metric | 推荐周期 |
|---|---:|
| GPU utilization | 500 ms–1 s |
| GPU frequency | 500 ms–1 s |
| GPU memory | 2–5 s |
| GPU model | once |
| dumpsys GPU static info | once |
| GPU work | 2–5 s |
| SurfaceFlinger FPS | 1 s |

不要：

```text
每 500ms dumpsys gpu
```

只有当前 UI/Overlay 正在显示 GPU 指标时才启动 GPU sampler。

---

# 18. Huawei Enjoy 50Z 的实际建议

已知条件：

```text
Kirin 710
Mali-G51
kernel 4.14
HarmonyOS 2 / Android 10 base
SELinux enforcing
shell 对 thermal/cpufreq 已存在 EPERM
```

最短验证顺序：

```bash
adb shell '
for d in /sys/class/devfreq/*; do
  echo ===$d
  readlink -f $d 2>/dev/null
  cat $d/name 2>/dev/null
  cat $d/cur_freq 2>/dev/null
  cat $d/load 2>/dev/null
  cat $d/utilization 2>/dev/null
done
'
```

然后：

```bash
adb shell dumpsys gpu
```

再：

```bash
adb shell '
find /sys -maxdepth 5   \( -iname "*gpu*" -o -iname "*mali*" -o -iname "*g3d*" \)   2>/dev/null | head -300
'
```

如果 shell 对可疑 GPU 节点都没有读取权限：

> 不建议继续投入 `/dev/mali0 ioctl` 绕过。GPU usage/frequency 显示 `N/A` 是正确产品行为。

---

# 19. Xiaomi / Adreno 的实际建议

先直接测试：

```bash
adb shell cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage
adb shell cat /sys/class/kgsl/kgsl-3d0/gpubusy
adb shell cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq
adb shell cat /sys/class/kgsl/kgsl-3d0/gpuclk
```

如果其中 shell 可读：

```text
App 不可读
Shell 可读
```

就优先使用：

```text
Shizuku UserService + FileInputStream
```

这是目前最有希望快速解决 Xiaomi GPU 显示问题的路径。

---

# 20. 推荐 GPU Diagnostics

建议加入：

```text
Settings
→ Diagnostics
→ GPU Probe
→ Export Report
```

报告：

```text
Device
------
Manufacturer
Model
SDK
Fingerprint
Kernel
SELinux

GPU Identity
------------
GL_VENDOR
GL_RENDERER
GL_VERSION

Backends
--------
Direct App
Shizuku available
Shizuku UID
ADB available

Adreno
------
gpu_busy_percentage
gpubusy
gpuclk
devfreq/cur_freq

Devfreq
-------
node
canonical path
name
cur_freq
load
utilization
busy_time
total_time

Vendor Paths
------------
...

GpuService
----------
service check gpu
dumpsys gpu
gpumem
gpuwork

Final Selection
---------------
Usage source
Frequency source
Memory source
```

这个功能比继续维护“某型号应该有某路径”的机型表更有长期价值。

---

# 21. 推荐代码目录

```text
gpu/
├─ GpuVendor.java
├─ GpuSnapshot.java
├─ GpuResolver.java
├─ GpuSampler.java
│
├─ io/
│  ├─ SafeFileReader.java
│  ├─ DirectFileBackend.java
│  └─ ShellFileBackend.java
│
├─ adreno/
│  ├─ KgslBusyPercentSource.java
│  ├─ KgslBusyTotalSource.java
│  └─ KgslFrequencySource.java
│
├─ mali/
│  ├─ MaliDevfreqSource.java
│  └─ MaliVendorSource.java
│
├─ powervr/
│  └─ PowerVrDevfreqSource.java
│
├─ generic/
│  └─ GenericDevfreqScanner.java
│
└─ aosp/
   ├─ DumpsysGpuSource.java
   └─ RenderMetricsSource.java
```

不需要：

```text
RxJava
DI framework
JNI
Coroutine
```

纯 Java 足够完成第一版。

---

# 22. 推荐开发顺序

## Phase 1

先完成：

```text
GPU Identity
Adreno KGSL
Generic devfreq
```

这是投入产出最高的一步。

## Phase 2

加入：

```text
Mali devfreq dynamic discovery
Huawei GPU Diagnostics
```

不要写 Huawei 型号硬编码表。

## Phase 3

加入：

```text
dumpsys gpu
gpumem
gpuwork
```

作为较新 Android 的增强。

## Phase 4

加入：

```text
SurfaceFlinger FPS / Jank
```

但放到：

```text
Render Metrics
```

而不是 GPU usage fallback。

---

# 23. 最终决策树

```text
Detect GPU Vendor
        │
        ├─ Adreno
        │    ↓
        │  KGSL direct
        │    ↓ fail
        │  KGSL shell
        │    ↓ fail
        │  generic devfreq
        │    ↓
        │  dumpsys GPU accounting
        │
        ├─ Mali
        │    ↓
        │  GPU devfreq direct
        │    ↓ fail
        │  GPU devfreq shell
        │    ↓
        │  vendor mali/g3d path
        │    ↓
        │  dumpsys GPU accounting
        │
        └─ PowerVR
             ↓
           generic devfreq
             ↓
           vendor PVR
             ↓
           optional PVRScope

Usage unavailable
→ N/A

Frequency unavailable
→ N/A

Render FPS
→ 独立指标
```

---

# 24. 参考资料

## AOSP GpuService

https://android.googlesource.com/platform/frameworks/native/+/master/services/gpuservice/GpuService.cpp

可确认：

```text
SERVICE_NAME = "gpu"
shell uid 可 dump
--gpustats
--gpudriverinfo
--gpumem
--gpuwork
```

## AOSP GPU Work

https://android.googlesource.com/platform/frameworks/native/+/17b449fc647318859bbdefb87b7a2103ee40faf8/services/gpuservice/gpuwork/GpuWork.cpp

## ARM Mali kbase devfreq

https://android.googlesource.com/kernel/google-modules/gpu/+/20fff721667a227b3d6decf9dbc3798476390302/mali_kbase/backend/gpu/mali_kbase_devfreq.c

## Mali devfreq Kconfig

https://android.googlesource.com/kernel/google-modules/gpu/+/cfb55729953d62d99f66b0adc59963b189e9394b/mali_kbase/Kconfig

## Qualcomm KGSL

https://android.googlesource.com/kernel/msm/+/b12578d7731ada3b0477db98b1257e4fa537b97b/drivers/gpu/msm/kgsl_pwrctrl.c

## Qualcomm `gpubusy`

https://android.googlesource.com/kernel/msm/+/015df42bb044519d5a4a51fe20827a38ff636fae/drivers/gpu/msm/kgsl_pwrctrl.c

## AOSP CPUInfo diagnostic 中的 KGSL 路径

https://android.googlesource.com/platform/external/cpuinfo/+/refs/heads/android-s-beta-4/scripts/android-device-dump.py

## Linux devfreq

https://github.com/torvalds/linux/blob/master/include/linux/devfreq.h

## SurfaceFlinger Android 16

https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/surfaceflinger/SurfaceFlinger.cpp

## SurfaceFlinger `--latency` 历史实现

https://android.googlesource.com/platform/frameworks/native/+/82d7ab6%5E%21/

## HardwarePropertiesManager

https://developer.android.com/reference/android/os/HardwarePropertiesManager

## Shizuku API

https://github.com/RikkaApps/Shizuku-API

## Dhizuku API

https://github.com/iamr0s/Dhizuku-API

## PowerVR PVRMonitor / PVRScope

https://github.com/powervr-graphics/PVRMonitor
