可以把两个问题合起来看。你现在遇到的 `file_search` “设置里已经给了存储权限，但运行时仍然 Permission denied”，**很可能正好暴露了你这个 Agent 架构里一个很关键的边界问题：Android 权限不是“整个 Agent 共享”的，而是跟执行代码的 UID / 进程身份绑定的。**

你提供的环境是 Android 10 / API 29、无 root，Shizuku server 已确认运行在 `shell(UID 2000)`，Dhizuku 是 Device Owner；你的问题文档也明确给出了 App/Dhizuku/Shizuku 的 UID 关系。 另外，你的系统采集已经实测：这台华为设备上的 `adb shell` 对 `/storage/emulated/0` 存在访问拒绝，因此这里不是纯理论问题。

## 先解决 `file_search` 无权限

你现在很可能有类似这样的执行链：

```text
Agent App
UID 10201
│
│ 用户在设置里授予
│ READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE
│
└── Shizuku.bindUserService()
        │
        ▼
   FileSearchService
   UID 2000(shell)
        │
        ▼
   /storage/emulated/0/xxx
        │
        └── Permission denied
```

这里最大的误区是：

```text
“我的 App 有存储权限”
          ≠
“Shizuku UserService 有这个权限”
```

Shizuku 官方明确说明：ADB 模式下 `UserService` 运行身份就是 **UID 2000 (`shell`)**，而不是你的 APK UID；并且 UserService 也不是正常 Android Application Process，很多依赖普通应用 `Context` 的 API 都不能按普通 App 的方式工作。([GitHub](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md?plain=1))

因此：

```text
Settings
  ↓
授予 com.your.agent 存储权限
  ↓
UID 10201 获得权限

但：

Shizuku UserService
  ↓
UID 2000
  ↓
不会继承 UID 10201 的 runtime permission
```

这就是第一层问题。

### 还有第二层：Android 10 Scoped Storage

即使你的 `file_search` **完全运行在 UID 10201 的普通 App 进程里**，Android 10 + `targetSdkVersion 29` 仍然可能出现：

```text
设置：
“存储权限：允许”

代码：
File("/storage/emulated/0/...").listFiles()

结果：
null / EACCES / Permission denied
```

因为 API 29 开始，target 29 的 App **默认启用 Scoped Storage**。Android 官方甚至专门说明：即使 scoped storage 正在生效，运行时权限对话框仍可能向用户显示成“广泛访问外部存储”，所以“设置页面显示允许”并不能证明你有传统 raw filesystem 访问能力。([Android Developers](https://developer.android.com/training/data-storage))

你当前 target 29，在 Android 10 上如果确实需要传统文件路径模式，可以暂时这样：

```xml
<manifest ...>

    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE" />

    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="29" />

    <application
        android:requestLegacyExternalStorage="true"
        ...>

    </application>

</manifest>
```

Android 官方确认：target ≤ 29 的 App 在 Android 10 上，可以用：

```xml
android:requestLegacyExternalStorage="true"
```

暂时退出 scoped storage。([Android Developers](https://developer.android.com/training/data-storage/use-cases?hl=zh-CN))

但这应该只作为你 **Android 10 兼容路径**，不要把手机版 Agent 的长期架构建立在它上面，因为 Android 11+ 会忽略这个方案。

你可以马上在真正执行 `file_search` 的位置打印：

```kotlin
Log.i(
    "FileSearch",
    """
    pid=${android.os.Process.myPid()}
    uid=${android.os.Process.myUid()}
    readPermission=${
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }
    legacy=${
        if (Build.VERSION.SDK_INT == 29)
            Environment.isExternalStorageLegacy()
        else
            "N/A"
    }
    """.trimIndent()
)
```

判断非常直接：

| 输出                                    | 说明                                     |
| --------------------------------------- | ---------------------------------------- |
| `uid=10201, read=GRANTED, legacy=false` | Scoped Storage 问题                      |
| `uid=10201, read=GRANTED, legacy=true`  | 再检查具体 pathname / 华为策略           |
| `uid=2000`                              | 你的 file_search 实际跑进 Shizuku 了     |
| `uid=10203`                             | 实际跑进 Dhizuku 身份了                  |
| `uid=10201, read=DENIED`                | 普通 runtime permission 本身没有正确授予 |

我建议你的 Agent **第一步就把 UID 输出加入每一个 Tool 调用日志**。

例如：

```text
tool=file_search
pid=18344
uid=10201
executor=APP
storage_mode=SAF
```

以及：

```text
tool=shell
pid=19442
uid=2000
executor=SHIZUKU
```

以后权限问题会非常容易定位。

------

# 你的手机版 AI Agent 最好不要“所有 Tool 都丢给 Shizuku”

这是你架构上最值得现在就调整的一点。

不要设计成：

```text
Agent
 ↓
Shizuku
 ↓
所有东西
 ├── file_search
 ├── read_file
 ├── shell
 ├── package_manager
 ├── settings
 └── ...
```

更合理的是：

```text
                     Agent Runtime
                    UID 10201
                        │
       ┌────────────────┼────────────────┐
       │                │                │
       ▼                ▼                ▼
 StorageBroker     ShizukuBroker     DhizukuBroker
 UID 10201          UID 2000          Device Owner
       │                │                │
       │                │                │
 file_search          pm/am           DPM
 read_file            dumpsys         policy
 write_file           settings        runtime grant
 SAF/MediaStore        cmd             package policy
 app storage           shell
```

换句话说：

**Shizuku 的权限更高，不等于 Shizuku 在每个维度上都比你的 App 更有权限。**

Android 权限体系不是：

```text
root > shell > app
```

这么简单。

实际上是：

```text
Linux UID
+
Android permissions
+
AppOps
+
SELinux domain
+
FUSE/storage policy
+
URI grants
+
DevicePolicy authority
```

共同决定的。

你的机器已经恰好证明了这一点：

```text
shell UID 2000

可以：
pm
am
dumpsys
很多系统 Binder

却可能：
不能正常遍历 /storage/emulated/0
```

而你的普通 App：

```text
UID 10201

不能：
执行 shell 特权 PackageManager 操作

但是：
用户可以通过 SAF 明确授权它访问某个文件夹
```

所以对 Agent 来说，应该选择**最适合该 Tool 的执行身份**，而不是永远选择“看起来权限最高”的那个身份。

------

# file_search 推荐方案

如果这个 Agent 是你长期准备适配 Android 10/12/14 的，我建议把文件访问抽象成一个独立 `StorageBroker`：

```text
LLM
 ↓
file_search(query, workspace)
 ↓
StorageBroker
 ↓
┌──────────────────────────────┐
│ App private                  │
│ SAF DocumentTree             │
│ MediaStore                   │
│ Legacy storage (A10 only)    │
└──────────────────────────────┘
```

让用户第一次选择工作目录：

```kotlin
Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
```

用户例如授权：

```text
Internal storage/Documents/AgentWorkspace
```

然后：

```kotlin
contentResolver.takePersistableUriPermission(
    uri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
)
```

之后你的 Agent 保存的是：

```text
content://com.android.externalstorage.documents/tree/primary%3ADocuments...
```

而不是依赖：

```text
/storage/emulated/0/Documents/...
```

于是 Android 10、12、14 都可以沿用同一模型。

`file_search`：

```text
DocumentTree
 ↓
ContentResolver
 ↓
enumerate
 ↓
index
 ↓
search
```

而不是：

```text
find /storage/emulated/0
```

------

## 如果 Shizuku Tool 必须处理用户选中的文件

也不要把 pathname 传过去。

推荐：

```text
App UID 10201
     │
ContentResolver.openFileDescriptor()
     │
     ▼
ParcelFileDescriptor
     │ Binder
     ▼
Shizuku UserService
UID 2000
```

也就是：

```kotlin
val pfd = contentResolver.openFileDescriptor(uri, "r")
```

然后通过你自己的 AIDL 把：

```text
ParcelFileDescriptor
```

交给后台服务。

这样权限检查发生在：

```text
UID 10201
+
用户给予的 URI grant
```

文件已经成功打开以后，服务处理的是一个 **FD capability**，而不是再次尝试：

```text
UID2000 → pathname → FUSE permission check
```

这对 Agent 的设计非常干净。

------

# Q1：Dhizuku 到底是不是 shell？

**不是。这个结论可以明确。**

Dhizuku 官方对自己的定义就是：

> sharing DeviceOwner permissions with other applications

也就是共享 **Device Owner / DevicePolicyManager 权限能力**，不是提供 shell UID。([GitHub](https://github.com/iamr0s/Dhizuku))

你现在设备状态：

```text
Dhizuku
UID 10203
Device Owner
```

Device Owner 的意义是：

```text
UID 10203
+
DevicePolicyManager 特权
```

不是：

```text
UID → 2000
```

更不是：

```text
UID → 0
```

### `Dhizuku.newProcess()`

源码调用链是：

```text
Dhizuku.newProcess()
 ↓
IDhizuku.remoteProcess()
 ↓
DhizukuService.remoteProcess()
 ↓
DhizukuProcess.remoteProcess()
```

官方源码中 `remoteProcess()` 只是由 Dhizuku 服务进程创建 remote process，没有任何切换到 `shell(2000)` 或 `root(0)` 的路径。([GitHub](https://github.com/iamr0s/Dhizuku-API/blob/main/dhizuku-server_api/src/main/java/com/rosan/dhizuku/server_api/DhizukuService.java))

因此它继承 Dhizuku 服务进程的 Linux 身份。

在你这台机器上预期：

```text
uid=10203
```

而不是：

```text
uid=2000(shell)
```

你可以直接实测锁死这个结论：

```java
RemoteProcess p = Dhizuku.newProcess(
    new String[]{"/system/bin/id"},
    null,
    null
);
```

读取 stdout。

预期：

```text
uid=10203(...)
```

------

# Dhizuku `bindUserService()` 也是同理

Dhizuku 源码会：

```java
createPackageContext(
    component.getPackageName(),
    CONTEXT_INCLUDE_CODE | CONTEXT_IGNORE_SECURITY
)
```

然后加载第三方 App 的 class：

```java
packageContext
    .getClassLoader()
    .loadClass(...)
```

再实例化你的 Service。([GitHub](https://github.com/iamr0s/Dhizuku-API/blob/main/dhizuku-server_api/src/main/java/com/rosan/dhizuku/server_api/UserService.java))

这意味着本质是：

```text
加载“你的代码”
进入“Dhizuku 管理的服务进程”
```

而不是：

```text
把你的进程变成 adb shell
```

所以可以把两种 UserService 对比：

```text
Shizuku UserService
        │
        └── Shizuku backend identity
            ADB → UID 2000
            ROOT → UID 0

Dhizuku UserService
        │
        └── Dhizuku server identity
            Device Owner app UID
```

Shizuku 官方明确承诺前一种 UID 语义。([GitHub](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md?plain=1))

------

# Dhizuku delegated scopes 是什么？

这里也需要纠正一个容易出现的理解：

它不是：

```text
“把 system service Binder 权限随便代理给 App”
```

而是：

```text
DevicePolicyManager
        ↓
Android 官方定义的若干 delegated scope
        ↓
允许某个包调用特定 DPM API
```

例如：

```text
DELEGATION_PERMISSION_GRANT
```

Android 官方明确规定它可以访问：

```text
setPermissionPolicy()
getPermissionGrantState()
setPermissionGrantState()
```

([Android Developers](https://developer.android.com/reference/android/app/admin/DevicePolicyManager))

所以你的想法：

> Dhizuku 能不能给我的 Agent 授予危险 runtime permission？

**可以，这正是 Dhizuku 的合理用途之一。**

例如 Device Owner / delegate 可以：

```java
dpm.setPermissionGrantState(
    admin,
    "com.your.agent",
    Manifest.permission.READ_EXTERNAL_STORAGE,
    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
)
```

官方文档明确规定 Device Owner、Profile Owner 或获得 `DELEGATION_PERMISSION_GRANT` 的 delegate 可以这么做。([Android Developers](https://developer.android.com/reference/android/app/admin/DevicePolicyManager))

但是非常重要：

```text
DPM grantRuntimePermission
          ↓
只是 runtime permission = granted

不是：
关闭 Scoped Storage
绕过 FUSE
绕过 SELinux
获得 shell UID
获得 root
获得 MANAGE_EXTERNAL_STORAGE
```

因此你当前 `file_search` 问题，即便 Dhizuku 成功把：

```text
READ_EXTERNAL_STORAGE = GRANTED
```

写上去了，也依然可能失败。

这与你现在看到的现象完全一致。

------

# Q2：Android 10 无 root 的 Shizuku 长期运行

你文档里的基本判断是正确的。

Shizuku 官方 API README 至今仍明确写着：

```text
非 root：
每次开机后必须通过 ADB 手动启动

Android 11 以前：
需要电脑运行 adb

Android 11+：
可以利用系统无线调试
```

([GitHub](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md?plain=1))

Android 10 官方 Wi-Fi ADB 流程仍然是：

```bash
adb devices

adb tcpip 5555

adb connect PHONE_IP:5555
```

而第一步需要已有 USB ADB transport。([Android Developers](https://developer.android.google.cn/tools/adb?hl=zh-cn))

所以 A10 上不存在通用的：

```text
手机刚重启
 ↓
没有 root
 ↓
没有电脑/外部 adb host
 ↓
普通 APK 自己启动 adbd shell session
```

这种启动链。

Android 安全模型就是为了阻止它。

------

# Termux 本机 `adb connect 127.0.0.1:5555` 可以吗？

**可以作为“保活/恢复手段”，但不能作为冷启动 bootstrap。**

区别很关键：

### 手机还没重启

你之前已经执行过：

```bash
adb tcpip 5555
```

adbd 还在监听。

那么 Termux：

```bash
adb connect 127.0.0.1:5555
```

在实现和 OEM 允许的前提下可以作为一个本地 ADB host。

于是：

```text
Shizuku server crash
       ↓
adbd:5555 仍然存在
       ↓
Termux adb
       ↓
adb shell ...
       ↓
重新启动 Shizuku
```

这个可以做 watchdog。

### 手机完整重启

通常：

```text
reboot
 ↓
adbd 恢复默认 USB 模式
 ↓
tcp:5555 不再监听
 ↓
Termux adb connect 127.0.0.1:5555
 ↓
Connection refused
```

Termux 无法自己执行：

```bash
adb tcpip 5555
```

因为这条命令本身就需要已经存在的 ADB 控制通道。

这就是 bootstrap paradox。

------

# ADB RSA 密钥也需要澄清

`/data/misc/adb/adb_keys` **不是 host 的私钥库**。

AOSP 说明：

```text
/data/misc/adb/adb_keys
```

存的是设备端允许的 **public keys**。

真正的 ADB host 持有 private key，用私钥签名设备发出的 challenge；如果设备不认识该 key，host 可以发送 public key，由 Android framework 弹出 RSA 授权确认。([Android Goole Source](https://android.googlesource.com/platform/system/core/%2B/f4ed516643ee8ed3a59ad1a8048f7ce5f47f93fb))

所以：

```text
/data/misc/adb/adb_keys
不能拿来给 Termux “当 adb 私钥用”
```

Termux adb 应当有自己的：

```text
private adb key
```

然后设备授权其对应公钥。

或者你非常清楚密钥管理风险的情况下，让本机 adb client 使用一个**已经被设备授权过的对应 private key**。

------

# Shizuku `start.sh` 路径

截至当前官方 Shizuku 文档，对 **v11.2.0+** 仍然给出：

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

([GitHub](https://github.com/RikkaApps/websites/blob/master/shizuku/zh-hans/guide/setup.md))

所以你当前：

> `/sdcard/Android/data/moe.shizuku.privileged.api/` 看不到，但 shizuku_server 正在运行

**不能直接推导为“现在新版路径换了”。**

特别是你这台 HarmonyOS 2 / Android 10 设备自身已经记录了 shell 对 external storage 的异常访问限制。

建议真正验证：

```bash
pm path moe.shizuku.privileged.api

ls -ld /sdcard
ls -ld /sdcard/Android
ls -ld /sdcard/Android/data

sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

最后一条比 `ls` 更有判别力：

```text
No such file or directory
```

和：

```text
Permission denied
```

含义完全不同。

------

# Q3：Shizuku API 13.1.5 + targetSdk 29

这个组合本身没有问题。

你的逻辑：

```text
pingBinder()
 ↓
checkSelfPermission()
 ↓
requestPermission(1001)
 ↓
用户允许
 ↓
bindUserService()
```

方向正确。

不过我建议改成**事件驱动状态机**，不要把 `pingBinder()` 当作初始化机制。

官方提供：

```java
Shizuku.addBinderReceivedListener(...)
Shizuku.addBinderDeadListener(...)
Shizuku.addRequestPermissionResultListener(...)
```

并且明确要求只在 Binder alive 时调用 Shizuku API。([GitHub](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md?plain=1))

你可以建成：

```text
SHIZUKU_UNAVAILABLE
       │
binder received
       ▼
CHECK_PERMISSION
       │
       ├── denied
       │      ↓
       │ REQUEST_PERMISSION
       │
       └── granted
              ↓
        CHECK_BACKEND_UID
              ↓
     Shizuku.getUid() == 2000
              ↓
        BIND_USER_SERVICE
              ↓
          SHELL_READY
```

Binder dead：

```text
SHELL_READY
   ↓
binderDead
   ↓
SHIZUKU_UNAVAILABLE
```

还建议明确检查：

```kotlin
val backendUid = Shizuku.getUid()

when (backendUid) {
    0 -> ROOT
    2000 -> ADB_SHELL
    else -> UNKNOWN
}
```

官方定义就是：

```text
root → 0
ADB → 2000
```

([GitHub](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md?plain=1))

A10 上 `requestPermission()` 本身正常工作。它是 **Shizuku Manager 自己管理的授权**，不是 Android 的 `READ_EXTERNAL_STORAGE` runtime permission。

这两个“授权”建议在你的 Agent UI 上也完全分开：

```text
Agent permissions

Storage workspace
✓ Documents/Agent

Shizuku
✓ shell UID 2000

Dhizuku
✓ Device Owner policy access
```

不要都显示成一个模糊的：

```text
“高级权限：已授权”
```

否则后面调试会非常痛苦。

------

# 我建议你最终定成这种 Agent 权限模型

```text
                    ┌─────────────────────┐
                    │     AI Agent        │
                    │     UID 10201       │
                    └─────────┬───────────┘
                              │
        ┌─────────────────────┼────────────────────┐
        │                     │                    │
        ▼                     ▼                    ▼
┌───────────────┐    ┌────────────────┐    ┌────────────────┐
│ StorageBroker │    │ ShizukuBroker  │    │ DhizukuBroker  │
│ UID 10201     │    │ UID 2000       │    │ Device Owner   │
├───────────────┤    ├────────────────┤    ├────────────────┤
│ file_search   │    │ shell          │    │ DPM            │
│ read_file     │    │ pm             │    │ package policy │
│ write_file    │    │ am             │    │ permission     │
│ SAF           │    │ cmd            │    │ app suspend    │
│ MediaStore    │    │ dumpsys        │    │ restrictions   │
└───────┬───────┘    └───────┬────────┘    └────────────────┘
        │                     │
        │ PFD / bytes         │
        └─────────► Binder ◄──┘
```

其中最重要的一条规则可以直接写进 Agent 的 system prompt/tool policy：

> **文件 Tool 默认在 App UID 下执行；系统管理 Tool 默认通过 Shizuku；DevicePolicy Tool 默认通过 Dhizuku。不得因为 Shizuku 具有 shell 身份而自动将普通文件操作迁移到 Shizuku UserService。**

对于你当前这个 `file_search Permission denied`，我会优先按这个顺序排查：**先打印实际 UID → 如果是 2000 就把 file_search 移回 App 进程；如果是 10201，再检查 `Environment.isExternalStorageLegacy()`；A10 临时需要 raw path 时开启 `requestLegacyExternalStorage=true`；长期则把 Agent workspace 改为 SAF DocumentTree。**

这基本同时解决你现在的 bug，也避免以后升级 Android 12/14 时再重做整个文件 Tool 层。