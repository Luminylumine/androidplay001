# AdbManager 相册：缩略图性能、缺失与增量删除改造方案

> 项目：`projects/scrcpy_enhance/`  
> 环境：.NET 9 WinForms / Windows 10–11 / 纯 adb / 无 root / 无 APK / 无第三方 NuGet  
> 目标设备：Android 10 / 12 / 16，USB adb 与 TCP adb  
> 调研日期：2026-08-26

---

## 0. 结论先行

现有三个现象的主因判断基本正确，但建议对方案 B、C、E 做几处关键调整。

### 最终建议

1. **A 会话级 TEMP 磁盘缩略图缓存：批准，且应立即实现。**
   - 不要用共享的 `%TEMP%\AdbManager\thumbs\` 直接作为所有实例的工作目录。
   - 推荐 `%TEMP%\AdbManager\thumbs\<session-guid>\`。
   - clean exit 只删除自己的 session 目录；启动时只清理“确认是残留”的旧 session，避免误删另一个仍在运行的 AdbManager 实例。
   - 缓存键应增加 `volume/mediaType/date_modified(or generation_modified)`，不能只靠 `_id + _size`。

2. **B 不建议把 `content://media/.../thumbnails` 全表或 `IN (...)` 查询作为主路径。**
   - `Images.Thumbnails` / `Video.Thumbnails` 从 API 29 起已经 deprecated。
   - Android 10/12/当前 AOSP 仍保留兼容表和 URI，但“接口存在”不等于“每个媒体项都有 legacy 表行”。
   - AOSP 现代 MediaProvider 自己生成的小 JPEG 使用 `Pictures/.thumbnails/<mediaId>.jpg`（图片）和 `Movies/.thumbnails/<mediaId>.jpg`（视频）这一类路径。
   - **更适合纯 adb 的主优化是：会话开始时探测这些目录 → 建已有 thumbnail ID 集合 → 可见区命中后直接批量 `adb pull` 小 JPEG。**
   - OEM 不符合 AOSP 路径时负缓存该能力，再退回 legacy 表/原图。

3. **比“每缩略图一个 adb pull”更重要的优化：ADB 原生支持一次 pull 多个远端文件。**

   ```text
   adb -s <serial> pull <remote1> <remote2> ... <remoteN> <local-staging-dir>
   ```

   当前 ADB 官方语法就是 `pull REMOTE... LOCAL`。
   - 一屏缩略图推荐按 **8–16 张一个 batch**；
   - USB 可同时跑 2 个小图 batch；
   - TCP 默认 1 个 batch；
   - 每个 batch 结束后立即解码/呈现，避免一次等 40 张全部完成才首屏出现。

4. **C：不要简单把所有 Transfer 并发从 2 改成 4。**
   推荐拆成“轻量缩略图”和“重型原图”两个预算：
   - USB：小图 batch 最多 2；原图 fallback 最多 2；全尺寸 GDI+ decode 最多 1–2。
   - TCP：小图 batch 1；原图 fallback 1；decode 1–2。
   - UI 线程只接收已经完成的“小 Bitmap”。

5. **D：`_size` mismatch 不应让缩略图永久死亡。**
   - 对浏览缩略图，`_size` 是 advisory metadata，不应作为强一致性条件。
   - 魔数合法 + 实际能完整 decode → **允许显示**。
   - 记录 warning，并在之后的批量 metadata refresh 中自然纠正。
   - 对“下载/复制原文件”仍可以保持更严格的完整性策略。

6. **E：软件内删除必须改为本地增量删除，不能成功删除一张后 `ReloadAsync()`。**
   - 删除成功/文件已确认不存在后，立即从 `_items` 移除、Dispose 内存 Bitmap、删除该 item 的 session cache、更新 album count、网格局部 invalidate/re-layout。
   - MediaStore 若暂时还有 stale row，维护一个本 session `DeletionTombstone`，**禁止 stale row 在手动刷新时重新冒出来**。
   - 后台只对被删 ID 做定向确认。
   - 手动“刷新”可以仍然全量 query 元数据，但 UI 应做 dictionary diff，而不是清空后重建。

7. **“大量完全不显示”还漏了一个重要原因：GDI+ 编解码格式能力。**
   - `System.Drawing/GDI+` 对 JPEG/PNG/GIF/BMP/TIFF 等传统格式较稳。
   - **HEIC/HEIF、WebP 不能因为“魔数识别成功”就假设 `new Bitmap()` 一定能解。**
   - Mate 30 等设备若存在 HEIC，相册可能出现“文件合法、校验合法、但 GDI+ decode 失败”的占位。
   - Android 侧现成 thumbnail 通常是 JPEG，因此优先拉 Android thumbnail 会同时解决这一类问题。
   - 无第三方解码器约束下，对“Android 没有小图 + 原图是 HEIC/WebP”的情况应明确降级为占位/点击打开，而不是无限重试。

---

# 1. 对三个现象的根因确认

## 1.1 打开慢：当前设计是最昂贵路径

当前每张格子执行：

```text
2–5 MB 原图
→ 新 adb process
→ exec-out cat
→ 全量传输
→ byte[]
→ UI thread GDI+ 全尺寸 decode
→ 绘制
```

假设首屏 30 张，每张平均 3 MB，就是约 90 MB 数据；而实际 UI 格子可能只需要 150×150 或 200×200。

所以当前瓶颈不只是“Transfer=2 太低”，而是：

```text
远端传输字节量错误
+ adb process 粒度错误
+ 解码线程错误
+ 缓存生命周期错误
```

单独把并发从 2 改到 4，只会让设备同时拉更多 3 MB 文件，并不能治本。

---

## 1.2 大量缩略图不显示：现有怀疑成立，但还应补充 4 类

### 已知可能性

- `_size` stale 导致误杀；
- TCP/USB 暂时传输失败；
- 只有 cat 一条链路，失败后没有 retry；
- 可视区快速变化导致加载/Prune 竞态。

### 还应补充

#### A. GDI+ 不支持该原图格式

尤其 HEIC/HEIF、WebP、某些厂商特殊格式。“魔数合法”只证明文件类型，不证明 `System.Drawing.Bitmap` 能解码。

#### B. Bitmap 与输入 Stream 生命周期

不要长期把 `new Bitmap(memoryStream)` 得到的 Bitmap 交给 UI，然后立即 Dispose backing stream，再假设 Bitmap 永远完全独立。

最稳妥的缩略图 decode 路径是：

```text
stream
→ Image.FromStream()
→ 创建一个新的目标尺寸 Bitmap
→ Graphics.DrawImage(source → destination)
→ Dispose source + stream
→ 把 destination Bitmap 交给 UI
```

这样 UI 持有的 Bitmap 已经和输入流脱离。

#### C. EXIF orientation

Android 侧 Q 之后系统 thumbnail API 会处理旋转；PC 直接 GDI+ 解原图时不能假设自动处理 EXIF orientation。

原图 fallback 应读取 EXIF Orientation (`0x0112`) 后先 RotateFlip，再生成缓存小图，否则会有横竖方向错误。

#### D. 超大像素图片造成 decode 内存压力

即使源 JPEG 只有 5 MB，解压后：

```text
8000 × 6000 × 4 ≈ 192 MB
```

如果同时 decode 4 张，全尺寸 Bitmap 峰值可非常高。因此“传输并发”和“full-image decode 并发”必须分开限制。

---

# 2. A：会话级 TEMP 缩略图缓存

## 2.1 结论

**方案成立，推荐实现。**

既然用户已经接受“关软件就删”，它是改善回滚动体验最简单、收益最高的一项。

---

## 2.2 推荐目录结构

不要所有实例共用一个平目录：

```text
%TEMP%
└─ AdbManager
   └─ thumbs
      ├─ <session-guid-1>
      │  ├─ session.lock
      │  ├─ device-<hash>
      │  │  ├─ image
      │  │  └─ video
      │  └─ staging
      └─ <session-guid-2>
```

如果用户同时打开两个 AdbManager，启动时无条件清共享 `thumbs` 目录会把另一个实例正在使用的缓存删掉，引入难复现竞态。

---

## 2.3 缓存键

原方案：

```text
sha256(deviceId | _id | kind | _size)
```

不够。

### 推荐

Android 11+ / API 30+ 能查询到 `generation_modified` 时：

```text
SHA256(
    cacheSchemaVersion
  | stableDeviceKey
  | volumeName
  | mediaType
  | mediaId
  | generation_modified
  | _size
)
```

Android 10：

```text
SHA256(
    cacheSchemaVersion
  | stableDeviceKey
  | volumeName
  | mediaType
  | mediaId
  | date_modified
  | _size
  | _data
)
```

说明：

- `cacheSchemaVersion`：如 `thumb-v2`，以后算法变化可自然失效；
- `stableDeviceKey`：优先项目现有稳定设备身份，不要只依赖 TCP 的 `ip:port`；
- `volumeName`：避免多存储卷语义碰撞；
- `mediaType`：image/video；
- `generation_modified`：API 30+ 比 `date_modified` 更适合判断 MediaStore 元数据变化；
- `_data` 只参与 hash，不直接放文件名。

### 为什么 `_size` 不够

图片可能内容变化但大小碰巧相同、原路径被覆盖、MediaStore `_size` 暂时没刷新等。session 很短，风险虽低，但增加 revision 字段成本几乎为零。

---

## 2.4 缓存存什么

建议缓存**可用于网格的本地小图**，而不是原图。

### 来源 1：Android 已有 JPEG thumbnail

```text
Android .thumbnails/*.jpg
→ staging
→ JPEG magic + decode 校验
→ cache final
```

### 来源 2：原图 fallback

```text
原图
→ 后台 GDI+ decode
→ orientation 修正
→ 缩放成 max-edge 约 512 px 的 canonical thumbnail
→ 保存到 session cache
→ 立即释放原始 Bitmap/byte[]
```

推荐 canonical thumbnail max edge：

```text
512 px
```

足够覆盖常见 120–300px 网格；会话缓存不需要按每个 zoom level 存一份。

---

## 2.5 `.partial` / staging

### 单文件生成

```text
<hash>.jpg.partial
→ 写完
→ magic
→ decode
→ File.Move(partial, final)
```

### 多源 `adb pull`

由于 `adb pull remote1 remote2 ... localDir` 由 adb 决定本地文件名，应先进入批次 staging：

```text
session/staging/<batch-guid>/
    173.jpg
    174.jpg
    180.jpg
```

批次完成后：

```text
逐文件校验
→ Move 到 hash cache final
→ 删除 staging batch dir
```

不要直接 batch pull 到正式 cache 目录。

---

## 2.6 退出清理和崩溃残留

### 正常退出

```text
取消 thumbnail workers
→ 等待/终止自己的 adb children
→ Dispose bitmap cache
→ 删除自己的 session directory
```

清理失败不要阻止应用退出。

### 启动清残留

推荐只删除：

1. `session.lock` 确认不再被其他进程持有；或
2. 目录创建时间明显过期，例如 `>24h`。

如果项目已经有“单实例 mutex”，则可以更积极地清整个 `thumbs` root。

---

# 3. B：Android 侧缩略图的最终策略

## 3.1 legacy `Images.Thumbnails` / `Video.Thumbnails` 的真实定位

官方 API：

- `MediaStore.Images.Thumbnails`：API 29 deprecated；
- `MediaStore.Video.Thumbnails`：API 29 deprecated；
- 官方推荐 app-side `ContentResolver.loadThumbnail()`。

你的约束是“纯 adb，无 APK”，因此不能直接正常调用 app-side `loadThumbnail()`。

AOSP 到 Android 12 以及当前 MediaProvider 代码仍保留 legacy URI/表，但不能推导出“每张图片都存在 legacy row”。

| Android | legacy URI/表存在概率 | 每个媒体都有可用 `_data` 行 | 建议 |
|---|---:|---:|---|
| 10 | 高 | 中等，OEM/缓存状态相关 | 可作为 fallback |
| 12 | 高（AOSP 仍保留） | 低～中 | 不作主路径 |
| 16 | 当前 AOSP 仍有兼容代码 | 低、不应依赖 | 仅 capability fallback |

---

## 3.2 AOSP 现代缩略图文件路径

AOSP MediaProvider 的 Thumbnailer 使用类似：

```text
image → Pictures/.thumbnails/<id>.jpg
video → Movies/.thumbnails/<id>.jpg
```

不同 AOSP 分支对多卷/primary storage 的细节有变化，OEM 也可以修改，因此：

> **这是 capability-probed fast path，不是 Android 公共 API 契约。**

不能无条件硬编码并认为所有设备都成立。

---

## 3.3 推荐 capability probe

相册第一次打开后，后台低优先级执行一次：

```text
adb shell ls -1 <volumeRoot>/Pictures/.thumbnails
adb shell ls -1 <volumeRoot>/Movies/.thumbnails
```

解析仅接受：

```regex
^\d+\.jpg$
```

构建：

```csharp
HashSet<long> imageThumbIds;
HashSet<long> videoThumbIds;
```

### 为什么比逐张 query 更好

一个目录只需一个 adb shell process；即使有几千个 thumbnail，返回的也主要是短文件名文本，通常远小于传一张原图。

### 负缓存

以下情况本 session 关闭该 fast path：

- 目录不存在；
- Permission denied；
- 输出异常；
- OEM 文件名完全不是 `<id>.jpg`；
- 连续命中 ID 但实际 pull 均失败，说明路径规则不适用。

不要因为“当前可见 20 张刚好都没有缩略图”就直接判设备不支持。

---

## 3.4 可见区小图批量拉取

```text
memory hit?
  YES → 立即画
  NO
    ↓
session disk hit?
  YES → decode worker
  NO
    ↓
Android thumbnail id-set hit?
  YES → 加入 SmallThumbBatch
  NO  → fallback
```

批量：

```text
adb -s <serial> pull
    <root>/Pictures/.thumbnails/173.jpg
    <root>/Pictures/.thumbnails/174.jpg
    <root>/Pictures/.thumbnails/180.jpg
    <session-staging-dir>
```

### 推荐 batch size

```text
8–16 files / batch
```

不是 40 张全塞一个 batch。这样既减少 spawn，又能更快开始首屏渐进显示。

### 重要：不要只看整个 adb process exit code

当前 ADB 多源 pull 实现中，某个 source stat/pull 失败会把整体 `success=false`，但会继续处理后面的 source。因此一个 batch 可能：

```text
exit = non-zero
但其中 11/12 个 JPEG 已经成功落盘
```

处理原则：

```text
逐个检查 staging 文件
→ 文件存在 + JPEG magic + decode success 就接收
→ 缺失 item 单独走 fallback
```

---

## 3.5 legacy thumbnail query：怎么用

只作为第二/第三层 fallback。

### 可以一次查全表

```text
content query
--uri content://media/external/images/thumbnails
--projection _id:_data:image_id:kind
```

然后建立 `image_id -> _data`，视频同理。

优点：只 spawn 一次；适合 legacy 表确实有大量数据的 Android/OEM。

缺点：新 Android 表可能很空；`_data` 可能不可读；legacy API 已弃用。

### 可以用 `IN`

`content` CLI 当前实现把 `--where <string>` 直接放入 `ContentResolver.QUERY_ARG_SQL_SELECTION`。MediaProvider 的 selection 是 SQL-style，因此标准条件可用：

```sql
image_id IN (173,174,180)
```

```sql
image_id=173 OR image_id=174
```

```sql
_id>=100 AND _id<=200
```

### 但有一个重要兼容陷阱

AOSP 某些 Android 10/11/12 兼容代码专门识别：

```regex
(image_id|video_id) = 单个数字
```

例如：

```sql
image_id=173
```

并可能返回一个 synthetic thumbnail `_data`。

而：

```sql
image_id IN (173,174)
```

**不会命中这个 special-case matcher。**

所以：

- `IN` 查询能查“真实 legacy table rows”；
- 但不能等价替代“逐个 `image_id=<id>` 时 AOSP 可能提供的兼容 synthetic row”。

这也说明不要为追求该兼容分支，在热路径上逐图 spawn `content query`。如需保留，可放到“用户点击重试”或极少数 fallback 项。

---

## 3.6 `--where` 安全拼接

`content` CLI 没有一个好用的 `selectionArgs[]` 命令行接口直接传 `?` 数组。

所以只允许程序生成的整数 ID：

```csharp
long id
```

再拼：

```csharp
var where = $"_id IN ({string.Join(",", ids)})";
```

不要拼文件名、album display name、用户输入或 `_data`。

如果使用 `ProcessStartInfo.ArgumentList`：

```csharp
psi.ArgumentList.Add("--where");
psi.ArgumentList.Add(where);
```

`where` 已经是一个 host argument，不要额外手工包 shell quote。

---

# 4. 推荐的最终“缩略图加载流水线”

```text
                         ┌─────────────────────────┐
                         │ ReportVisibleRange      │
                         └────────────┬────────────┘
                                      │
                         debounce 80–120 ms
                                      │
                                      v
                         visible + prefetch 1–2 rows
                                      │
                         viewport priority queue
                                      │
                                      v
                     ┌───────────────────────────────┐
                     │ 1. Memory Bitmap cache hit?  │
                     └───────────────┬───────────────┘
                                 YES │
                                     ├──> UI paint
                                 NO  │
                                     v
                     ┌───────────────────────────────┐
                     │ 2. Session disk cache hit?   │
                     └───────────────┬───────────────┘
                                 YES │
                                     ├──> decode worker
                                 NO  │
                                     v
              ┌────────────────────────────────────────────┐
              │ 3. Android modern thumb path/id-set hit?  │
              └──────────────────┬─────────────────────────┘
                              YES │
                                  v
                       SmallThumbBatch queue
                       8–16 remote JPEGs
                                  │
                                  v
                   adb pull REMOTE... LOCAL-STAGING
                                  │
                                  v
                       per-file magic/decode check
                                  │
                         ┌────────┴─────────┐
                      success            missing
                         │                  │
                         v                  v
                session disk cache     legacy map hit?
                         │                  │
                         │               yes│
                         │                  v
                         │             adb pull small
                         │                  │
                         │               no │
                         │                  v
                         │          original fallback
                         │                  │
                         │        pull/cat one original
                         │                  │
                         │      background full decode
                         │                  │
                         │      EXIF orientation fix
                         │                  │
                         │      scale to max-edge 512
                         │                  │
                         └──────────┬───────┘
                                    v
                             session disk cache
                                    │
                                    v
                           grid-size Bitmap decode
                                    │
                                    v
                               UI handoff
```

---

# 5. 调度模型：不要只有一个 Transfer Semaphore

## 5.1 推荐三类工作

### 1. Control

小文本命令：`content query`、`ls`、delete verify。

建议：

```text
Concurrency = 1 per device
```

避免多个 MediaProvider query 无意义并发。

### 2. SmallThumbTransfer

主要是几十 KB～几百 KB JPEG，支持 multi-source adb pull。

| 连接 | 并行 batch | 每 batch |
|---|---:|---:|
| USB | 2 | 8–16 |
| TCP | 1 | 8–16 |

不要把“每个文件”算一个 semaphore slot；slot 应代表一个 adb batch process。

### 3. FullTransfer

原图 fallback / 打开 / 下载。

| 连接 | 并发 |
|---|---:|
| USB | 2 |
| TCP | 1 |

如果同时有用户主动“下载/打开”和后台缩略图：

```text
用户主动操作优先级 > 可视缩略图 > prefetch
```

不要让大量后台 thumb 把“用户双击打开照片”排到最后。

---

## 5.2 怎么区分 USB/TCP

简单规则足够：

- adb serial 形似 `host:port` / 无线调试服务形式 → TCP；
- `adb devices -l` 带明确 `usb:` transport 信息 → USB；
- 无法判断时使用保守档 TCP。

不要为了这个功能引入复杂自适应测速，除非后续真机证明必要。

---

# 6. GDI+ decode 移出 UI 线程

## 6.1 结论

**必须做。**

UI 线程只做：

```text
接收已经准备好的 Bitmap
→ 更新 item state
→ Invalidate 对应 rectangle
```

不做：

```text
Image.FromStream
Bitmap decode
EXIF rotation
Graphics.DrawImage resize
磁盘 I/O
```

---

## 6.2 Task.Run 也要限流

不要对 40 张同时直接 `Task.Run(...)`。

推荐独立 `DecodeSemaphore`。

默认先设：

```text
Decode concurrency = 2
```

稳定后再根据真机性能调。

---

## 6.3 Bitmap ownership

推荐明确所有权：

```text
worker:
  source Image
  temp Graphics
  result Bitmap
       |
       └── ownership handoff
              |
              v
UI/item owns result Bitmap
```

只有 UI/item cache 可以 Dispose result；worker 完成后不再碰 result，避免 UI paint 与 background Dispose 同时发生。

---

# 7. 滚动抖动：不要“滚出去就取消一切/立刻丢一切”

## 7.1 建议 item 状态机

```text
Idle
Queued
LoadingRemote
Decoding
Ready
FailedTransient
Unsupported
Deleted
```

每个 item 同时只能存在一个有效 load task。可用：

```csharp
ConcurrentDictionary<ThumbKey, Task<ThumbResult>>
```

做 request coalescing。同一张图滚出再滚入时，如果第一次 load 还在跑，复用原 Task，不要再启一个 adb。

---

## 7.2 viewport generation

每次可见范围大变化：

```text
viewportGeneration++
```

worker 完成时检查：

```text
item 仍存在？
item 没被删除？
当前 generation 是否还需要立即上 UI？
```

如果已经滚远：

- 远端小 JPEG 已经快拉完 → 允许完成并写 session disk cache；
- 不必创建/保留 UI Bitmap；
- 下次滚回来直接 disk hit。

这比强行 cancel 所有任务更省。

---

## 7.3 debounce + prefetch

推荐：

```text
滚动事件 debounce：80–120 ms
prefetch：可视区上下各 1–2 行
```

优先级：

```text
当前可视区中心
> 当前可视区边缘
> 滚动方向前方 prefetch
> 反方向 prefetch
```

无需复杂预测。

---

# 8. D：`_size` mismatch 的最终判定

## 8.1 对缩略图浏览

推荐：

```text
if bytes/path missing:
    retry once
else if magic invalid:
    fail
else if decode fails:
    fail / unsupported codec
else:
    DISPLAY
    if actualLength != MediaStore._size:
        warning only
```

即：**能正确 decode 的实际文件优先于可能陈旧的 MediaStore `_size`。**

---

## 8.2 为什么不值得逐条重新 query `_size`

逐条 `_id=173` query 又会恢复 N 次 adb process 问题。

更便宜：

### 方案 1：不立即核对（推荐）

只记：

```text
MetadataSizeMismatch = true
```

等用户下次手动 refresh 的全量 metadata 自然修正。

### 方案 2：debounced batch reconcile

如果本轮出现多个 mismatch：

```sql
_id IN (173,180,205,...)
```

一次查：

```text
_id:_size:date_modified:generation_modified
```

建议累计到 `>=5` 个 mismatch 或空闲 2 秒才发一个 query。

---

## 8.3 下载/复制不必同等放宽

浏览目标是“能展示”，下载目标是“文件完整”。所以：

- thumbnail display：decode success 可放行；
- download/copy：仍然可以使用更严格 remote stat / expected size / retry。

不要让浏览容错降低文件导出完整性保证。

---

# 9. E：软件内删除的最终增量模型

## 9.1 不再调用 ReloadAsync()

成功删除一个 item 后，全量 rebuild 是错误的 UI 粒度。

---

## 9.2 推荐具体步骤

假设删除 item `173`。

### Step 1：冻结 item

```text
state = Deleting
```

禁用再次删除/打开，可显示轻量 fade/spinner。

### Step 2：执行远端删除

继续使用项目已经验证的：

```text
MediaStore delete
+
rm -f _data fallback
```

但要记住上一轮已经证明：`content` 命令异常不一定通过 exit code 正确表达。因此如果最终走文件系统 fallback，实际文件状态比 `content` exit code 更可信。

### Step 3：确认文件已不存在 / rm 成功

对于普通 `adb shell rm`，让 adb 使用 shell v2 获取远端 command exit status。

如果：

```text
rm -f path → 0
```

可把它作为物理文件已被删除/本来就不存在的成功条件。若返回失败，再额外 `test ! -e path`，不要成功路径固定再多 spawn 一个 verify。

### Step 4：立即本地移除

```text
_items.Remove(item)
thumbnailBitmap.Dispose()
delete session-cache files for ThumbKey
album.Count--
Grid.Remove/Relayout affected range
Invalidate affected region
```

album count 变 0 时可移除空 album node。

### Step 5：写 DeletionTombstone

```csharp
DeletionTombstone {
    deviceKey,
    volume,
    mediaType,
    id,
    originalPath,
    generationModified/dateModified
}
```

生命周期为当前 session。

目的：防止“文件已经 rm 掉，但 MediaStore stale row 还没消失”时下一次 refresh 把坏条目重新加回来。

### Step 6：后台定向确认

2 秒 debounce 后：

单个：

```sql
_id=173
```

批量：

```sql
_id IN (173,174,180)
```

如果 MediaStore 已无 row，tombstone 可以删除；如果 row 仍在，保留 tombstone，不把 item 加回 UI，也不触发全量 reload。

---

# 10. stale MediaStore row 如何展示

## 10.1 软件自己刚删除造成的 stale row

**不要展示。**

程序已经知道用户明确删除过且物理文件不存在，因此 session tombstone 应覆盖 stale provider row。

---

## 10.2 手机外部删除，但 AdbManager 尚不知道

“手机侧外部改动不实时监听，用户点刷新才更新”的产品决策合理。

在用户没刷新前：

- UI 仍可能显示旧 metadata；
- 如果缩略图 session-cache 命中，甚至还能看到旧缩略图。

用户点击打开时远端文件不存在，不要只弹错误后永久留坏 tile。

推荐：

```text
Read fails with ENOENT / remote not found
→ 确认这是“文件不存在”而不是 TCP 断连
→ 静默移除该 item
→ 更新 album count
→ 加 session tombstone
→ 可选 toast：“照片已在手机上不存在”
```

以下不能静默当成删除：device offline、TCP timeout、permission denied、adb server error。只有明确 ENOENT/no such file/test ! -e 才惰性移除。

---

# 11. 手动刷新：全量 query 也不等于全量 UI 重建

即使 Refresh 仍执行完整：

```text
content query images
content query video
```

也不要：

```text
_items.Clear()
Dispose all bitmaps
Rebuild all rows
```

推荐：

```text
oldByKey = current items
newByKey = query result

removed = old - new
added   = new - old
updated = intersection where metadata fingerprint changed
same    = intersection unchanged
```

处理：

```text
same:
  保留 Bitmap / disk cache / item object

updated:
  metadata 原位更新
  fingerprint 变化时 invalidate thumbnail

removed:
  Dispose + remove

added:
  insert + placeholder
```

这样即使元数据刷新仍是全量，用户也不会看到整页闪烁和所有缩略图重拉。

---

# 12. where 子句能力边界

## 12.1 能力结论

AOSP `content` CLI 文档把 `--where` 定义为 SQL-style where clause；当前实现将其放进 `QUERY_ARG_SQL_SELECTION` 后调用 provider。

对标准 MediaProvider，常用条件可用：

```sql
_id=173
```

```sql
_id IN (173,174,180)
```

```sql
_id=173 OR _id=174
```

```sql
bucket_id=123 AND _id IN (...)
```

```sql
_id>=100 AND _id<=200
```

---

## 12.2 不要依赖的东西

不要使用：

- `GROUP BY` 注入；
- `LIMIT` 注入；
- projection 里塞 SQL expression；
- 用户文本直拼 SQL；
- OEM provider 私有扩展。

Android 新版 MediaProvider 对过去 abusive query 写法限制越来越多。这里只需要标准 selection。

---

## 12.3 ID batch 大小

推荐通用 helper：

```text
chunk size = 100
```

远低于 Windows command line 长度风险，也足够一屏/批量删除。不要一次拼几千个 ID。

---

# 13. adb 进程 spawn：是否值得长驻 shell

## 13.1 不建议为了二进制缩略图引入长驻 shell

长驻 `adb shell` 看起来能省 spawn，但马上要解决：

- 每条 command 的边界；
- stdout/stderr framing；
- 二进制文件边界；
- 路径 shell escaping；
- cancellation；
- remote shell 死亡；
- TCP reconnect；
- 半包；
- 前一个命令输出污染下一个命令；
- shutdown。

最终等于自己造一个 RPC 协议。对于“程序小 + 实现简单 + 安全不崩溃”的优先级，不划算。

---

## 13.2 更好的减 spawn 方法

### 文本

尽量批量：

```text
一次全表 metadata query
一次 ls thumbnail directory
一次 IN query 核对多个 ID
```

### 文件

利用 ADB 已有的 SYNC 协议：

```text
adb pull REMOTE... LOCAL
```

比 `persistent shell + cat + 自定义长度 framing` 简单可靠得多。

---

## 13.3 原图 fallback

原图通常单个几 MB，真正耗时主要是字节传输和 decode。因此原图仍“一次一个 adb pull”可以接受；不值得为了少量 fallback 原图实现复杂 multiplexer。

批量 pull 的最大收益在“小 JPEG thumbnail”。

---

# 14. 首次打开的渐进体验

推荐相册打开过程：

```text
T0:
  metadata query 完成
  立即建立所有 tile placeholder

T0 +:
  当前可视区 8–16 张第一批 thumbnail
  → batch pull
  → decode
  → 第一批出现

随后:
  当前可视区剩余
  → 第二批

随后:
  滚动方向 prefetch 1–2 行

最后:
  低优先级处理可见区里只能 original fallback 的 item
```

不要等“当前一屏 30 张都处理完成”以后再统一刷新 UI；每个 batch / decode 完成后局部 invalidate。

---

# 15. TCP 断连与缓存一致性

## 15.1 断线不要清缓存

session disk cache 表示“该 session 曾经成功拿到过这张缩略图”。TCP 临时 disconnect 不应删除它。

断线时可以：

- 继续显示已经缓存的缩略图；
- 状态栏显示 device disconnected；
- 禁止 delete/download 等远端动作；
- 新缩略图请求进入 `FailedTransient/WaitingForDevice`。

---

## 15.2 重连后

如果仍是同一个 stable device identity，保留 cache，但 remote metadata snapshot 应视为可能 stale。

### 简单方案（推荐）

```text
重连成功
→ 不自动全量刷新
→ 用户点击 Refresh 时 diff refresh
```

### 稍积极方案

```text
重连成功后 idle 1–2 秒
→ 全量 metadata query
→ dictionary diff
```

不要清空 UI。

---

## 15.3 防止 IP:port 被另一台设备复用

TCP cache key 不要只用 `192.168.1.5:5555`。

如果项目已有稳定设备 identity，直接使用。否则至少组合：

```text
adb transport serial
+ ro.serialno/ro.boot.serialno（如果可读）
+ build fingerprint
```

再 hash。

---

# 16. Android 11+ `generation_modified`：可选增强

API 30 起有：

```text
generation_added
generation_modified
```

官方定义它们为单调增长的 MediaStore generation，更适合判断 metadata 是否变化，比 `date_modified` 稳健。

所以 Android 12/16 projection 可考虑增加：

```text
generation_modified
```

用途：

1. session thumbnail cache fingerprint；
2. Refresh diff 判断“同一个 `_id` 是否真的变化”。

Android 10 不存在该列，因此必须按 API capability 加 projection，不能三档硬查同一列。

暂时不建议用它实现“完全增量外部同步”，因为 generation 适合发现 added/modified，但 deleted row 已经不存在，单靠 `generation_modified > x` 无法发现所有删除；完整 generation 同步还涉及 MediaStore overall version。当前需求没必要把系统做复杂。

---

# 17. 建议实现优先级

## Phase 1：立刻做，低风险高收益

1. session TEMP thumbnail cache；
2. decode/resize 移出 UI thread；
3. `_size mismatch` 从 fatal 降为 warning；
4. retry 1 次；
5. item load 状态机 + request dedupe；
6. 删除成功后本地 Remove，不再 `ReloadAsync()`；
7. Refresh 改成 full-query + diff apply。

仅做这一阶段就会明显改善体验。

---

## Phase 2：缩略图带宽优化

1. 探测 `Pictures/.thumbnails` / `Movies/.thumbnails`；
2. 建 remote thumbnail ID map；
3. multi-source `adb pull`；
4. 8–16 一个 batch；
5. Android JPEG thumbnail → session disk cache；
6. legacy table 一次全表 query 仅作 fallback。

这是解决“首次打开还慢”的主阶段。

---

## Phase 3：细节稳健性

1. EXIF orientation；
2. `generation_modified`；
3. tombstone；
4. mismatch batch reconcile；
5. connection-type concurrency；
6. HEIC/WebP 明确 unsupported/fallback UX；
7. 用户点击单项 retry。

---

# 18. 最终参数建议

```text
VisibleRangeDebounceMs       = 100
PrefetchRows                 = 2

ThumbPullBatchSize           = 12

UsbThumbBatchConcurrency     = 2
TcpThumbBatchConcurrency     = 1

UsbFullTransferConcurrency   = 2
TcpFullTransferConcurrency   = 1

DecodeConcurrency            = 2

RetryCount                   = 1
RetryDelayMs                 = 250

CanonicalThumbMaxEdgePx      = 512

DeleteReconcileDelayMs       = 2000
WhereIdChunkSize             = 100

StaleSessionCleanupAge       = 24h
```

这些值的核心是：简单、有界、不会把 USB/TCP/内存压爆、容易真机调参。

---

# 19. 最终决策表

| 场景 | 首选 | 回退 | 是否重新拉原图 |
|---|---|---|---|
| 内存 Bitmap 命中 | 直接画 | - | 否 |
| session disk thumb 命中 | 后台 decode | - | 否 |
| AOSP/OEM modern thumbnail 命中 | multi-source `adb pull` 小 JPEG | legacy thumb | 否 |
| legacy `_data` 命中 | `adb pull` 小图 | 原图 | 否 |
| 无任何小图 | pull/cat 原图 → 后台缩 512px → disk cache | 占位 | **仅一次/session fingerprint** |
| `_size` mismatch，但 magic+decode 成功 | 显示 + warning | 后台批量 metadata reconcile | 否 |
| 临时 adb/TCP error | retry 1 次 | FailedTransient / 点击重试 | 视情况 |
| HEIC/WebP 原图且无 Android JPEG thumb | 占位/可打开 | 可选 Windows codec 扩展方案 | 不无限重试 |
| 软件内删除 | 本地 Remove + tombstone | 延迟 `_id IN (...)` 核对 | 不 reload |
| 手机外部删除但 UI 尚未知 | 保留 snapshot | 打开时 ENOENT 惰性移除 / 用户 Refresh | 不 reload |
| 用户 Refresh | 全量 metadata query + diff apply | - | 仅 metadata fingerprint 变化项失效 |

---

# 20. 推荐的数据结构

```csharp
sealed class GalleryItem
{
    public long Id;
    public string VolumeName;
    public GalleryMediaType MediaType;

    public string? DataPath;
    public long MediaStoreSize;
    public long DateModified;
    public long? GenerationModified;

    public ThumbLoadState ThumbState;
    public Bitmap? VisibleBitmap;

    public string MetadataFingerprint;
}
```

```csharp
sealed record ThumbCacheKey(
    string DeviceKey,
    string Volume,
    GalleryMediaType MediaType,
    long MediaId,
    long Revision,
    long Size,
    int CacheSchemaVersion);
```

```csharp
sealed record DeletionTombstone(
    string DeviceKey,
    string Volume,
    GalleryMediaType MediaType,
    long MediaId,
    string? OriginalPath,
    long Revision);
```

```text
DeviceThumbnailCapabilities:
    ModernImageThumbPathSupported
    ModernVideoThumbPathSupported
    ImageThumbIds
    VideoThumbIds
    LegacyImageThumbnailTableUsable
    LegacyVideoThumbnailTableUsable
    ContentReadNegativeCached
```

---

# 21. 对题目 1–6 的直接回答

## 1. A 缓存实现要点与边界

**成立。**

调整：

- 每 session 独立目录；
- key 增加 volume + media type + revision；
- API 30+ 优先 `generation_modified`；
- clean exit 删自己的 session；
- 启动只删确认 stale 的旧 session；
- cache 只存小图，不长期存完整原图；
- staging → validate → atomic move。

## 2. B 批量 thumbnail 查询与 Android 10/12/16

`--where` 对标准 MediaProvider 的 `IN / OR / AND / 比较 / 括号` 都属于普通 SQL selection，可用；但 CLI 没有正常 selectionArgs 数组接口，因此只拼程序验证过的 numeric ID。

最优形态：

```text
AOSP modern .thumbnails 目录一次探测
→ multi-source adb pull 可见小图
→ legacy table 一次全表 query
→ 极少数单 ID legacy compatibility probe
→ original fallback
```

不是“每个可见 item 一次 content query”，也不是把 legacy table 当唯一主映射。

## 3. `_size` mismatch 但内容合法

**缩略图浏览应放行。** 条件：`magic valid + decode success`。`_size` mismatch 仅 warning。

更便宜核对：不立即核对，或多个 mismatch 聚合成一次 `_id IN (...)` query。

## 4. C 并发与 adb spawn

USB/TCP 应分档。但比“4 个单文件 adb”更好的优化是 `adb pull REMOTE... LOCAL` 对小图批量传输。

**不推荐长驻 shell 传二进制**，因为 framing/cancellation/reconnect/quoting 复杂度不符合本项目优先级。

## 5. E 定向核对与 stale MediaStore

`_id IN (...)` 可用于批量定向确认。

软件自己删除：本地立即移除 + tombstone，MediaStore stale row 不得重新展示。

手机外部删除：直到 Refresh 前允许 snapshot 继续存在；但用户点击打开并明确 ENOENT 时应惰性静默移除，而不是留永久坏 tile。

## 6. 漏掉的关键点

最重要的补充：

1. **ADB multi-source pull**；
2. **GDI+ HEIC/WebP 不保证可解**；
3. **EXIF orientation**；
4. **full-image decode 内存峰值**；
5. **viewport request dedupe / generation**；
6. **Refresh 全量 query 也应该 diff-apply**；
7. **TCP 断线保留 session cache，不要当内容失效**；
8. API 30+ 可用 `generation_modified` 改善 cache invalidation；
9. legacy `image_id=<single>` 的兼容 synthetic 行与 `IN (...)` 行为并不完全等价。

---

# 22. 参考资料

1. Android Developers — `MediaStore.Images.Thumbnails`，API 29 起 deprecated  
   https://developer.android.com/reference/android/provider/MediaStore.Images.Thumbnails

2. Android Developers — `MediaStore.Video.Thumbnails`，API 29 起 deprecated  
   https://developer.android.com/reference/android/provider/MediaStore.Video.Thumbnails

3. Android Developers — `MediaStore.MediaColumns.GENERATION_MODIFIED`  
   https://developer.android.com/reference/android/provider/MediaStore.MediaColumns

4. AOSP `Content.java` — `content query --where` 被放入 `QUERY_ARG_SQL_SELECTION`；`content` provider 异常捕获/打印行为  
   https://android.googlesource.com/platform/frameworks/base/+/ea9ef78/cmds/content/src/com/android/commands/content/Content.java

5. AOSP MediaProvider Android 12 — legacy thumbnail compatibility 与现代 Thumbnailer  
   https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/android12-release/src/com/android/providers/media/MediaProvider.java

6. AOSP MediaProvider current/master — `thumbnails` 表兼容代码、Thumbnailer、`Pictures/.thumbnails` / `Movies/.thumbnails` 路径逻辑  
   https://android.googlesource.com/platform/packages/providers/MediaProvider/+/master/src/com/android/providers/media/MediaProvider.java

7. AOSP MediaProvider Android 10 — thumbnail compatibility / `ensureThumbnail`  
   https://android.googlesource.com/platform/packages/providers/MediaProvider/+/android-10.0.0_r11/src/com/android/providers/media/MediaProvider.java

8. ADB 官方 man page — `pull REMOTE... LOCAL`  
   https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md

9. ADB `file_sync_client.cpp` — multi-source pull 遇单个 source 失败后继续处理其他 source  
   https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/android15-qpr2-s8-release/client/file_sync_client.cpp

---

# 23. 最后建议

如果以：

```text
程序小
+ 实现简单
+ 安全不崩溃
+ 扩展性强
```

为最高优先级，建议保持下面这个边界：

```text
MediaStore = metadata snapshot
Android existing JPEG thumbnail = 最优数据源
session TEMP = 会话缓存
原图 = 最后 fallback
Refresh = metadata full query + UI diff
软件内 delete = local incremental mutation + tombstone
```

本轮性能收益最大的三个改动，按优先级是：

```text
1. session disk cache + background resize
2. Android .thumbnails + multi-source adb pull
3. 删除/Refresh 从 rebuild 改为 diff
```

不推荐为了消除最后几十毫秒 adb spawn 而引入 persistent-shell binary protocol；复杂度和故障面会远大于收益。
