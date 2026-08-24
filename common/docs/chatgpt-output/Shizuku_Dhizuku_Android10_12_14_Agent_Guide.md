# Shizuku / Dhizuku：Android 10、12、14 使用与授权指南

> 面向自动化 Agent / Android 测试与管理场景  
> 版本基线：2026-08-23

> **使用范围与风险提示：** 本文面向自有设备、企业管理设备或授权测试设备。Dhizuku 的 Device Owner 配置会改变整机管理状态；在已有账号、多用户、工作资料或 OEM 限制的设备上强行处理可能造成数据丢失、无法启动或管理状态异常。生产设备优先使用干净测试机或标准 Android Enterprise 配置流程。

## 文档目标

让 Agent 能够根据 Android 版本、是否 Root、是否可用 ADB、是否已存在 Device Owner，以及目标能力类型，选择 Shizuku 或 Dhizuku，并正确判断授权是否真正有效。

## Agent 读取约定

- 本文中的“授权”必须区分 **后端身份已建立** 与 **客户端已获准使用** 两层。
- 所有高权限动作在执行前必须做 capability check；禁止仅凭版本号或 UI 文案推断权限有效。
- `dpm list-owners` / `dumpsys device_policy` 的 Framework 状态优先于 Dhizuku UI 缓存状态。
- Shizuku 的 `shell` 与 `root` 是不同权限级别，Agent 必须记录服务 UID。
- 遇到 Device Owner 前置条件不满足时，Agent 应停止并报告，不得自动清账号、删用户或清设备。

# 1. 核心结论：两者不是同一种“提权”

Shizuku 和 Dhizuku 都允许普通应用间接访问通常拿不到的系统能力，但二者的权限来源完全不同。

| **项目**        | **权限来源**                 | **典型 UID/身份**           | **能力边界**                                      | **重启后**             | **客户端需再次确认**            |
|-----------------|------------------------------|-----------------------------|---------------------------------------------------|------------------------|---------------------------------|
| Shizuku（ADB）  | adb shell                    | shell / UID 2000            | Shell 能调用的系统 API/服务；不是 root            | 服务停止，需要重新启动 | 是：检查 Binder + Shizuku 授权  |
| Shizuku（Root） | root                         | root / UID 0                | 比 ADB 模式更高；仍受 SELinux/系统实现影响        | 可由 Root 方案自动启动 | 是：检查 Binder + Shizuku 授权  |
| Dhizuku         | Device Owner / Profile Owner | 应用自身 UID + DPM 管理身份 | DevicePolicyManager 管理 API；不是 adb shell/root | Owner 角色通常持久     | 是：初始化 + Dhizuku 客户端授权 |

最重要的判断原则：Shizuku 适合“需要 Shell/Root 身份调用系统服务或系统 API”的应用；Dhizuku 适合“需要设备策略控制器（DPC）能力”的应用。Dhizuku 不能等价替代 Shizuku，也不能因为拥有 Device Owner 就获得 adb shell。

# 2. 授权链路模型

## 2.1 Shizuku

```text
用户 / ADB 或 Root

│

▼

Shizuku Server（shell 或 root 身份）

│ Binder

▼

Shizuku Manager 的客户端授权

│

▼

第三方 App → 系统服务 / UserService
```

Shizuku 的“授权”有两层：第一层是 Shizuku Server 是否已经以 ADB 或 Root 身份运行；第二层是某个第三方应用是否被 Shizuku Manager 允许连接。只完成第二层而 Server 未运行，没有任何效果。

## 2.2 Dhizuku

```text
Android DevicePolicyManager

│

▼

Dhizuku = Device Owner / Profile Owner

│ Binder / Dhizuku API

▼

Dhizuku 客户端授权

│

▼

第三方 App → DevicePolicyManager 能力
```

Dhizuku 的关键前提不是“服务有没有用 adb 启动”，而是 Android Framework 是否已经把 Dhizuku 记录为 Device Owner（或新版本支持的 Profile Owner）。Device Owner 是系统级管理角色，一个设备通常只能有一个 Device Owner。

# 3. Android 10 / 12 / 14 快速矩阵

| **系统**   | **Shizuku 无 Root 启动** | **是否需电脑**             | **重启后**       | **Dhizuku Device Owner**      | **主要注意点**                                                                                | **推荐策略**                                           |
|------------|--------------------------|----------------------------|------------------|-------------------------------|-----------------------------------------------------------------------------------------------|--------------------------------------------------------|
| Android 10 | USB ADB 执行 start.sh    | 是                         | 必须重启 Shizuku | 支持                          | Device Owner 需干净设备状态；没有 Android 11+ 的无线调试 UI                                   | 长期需要 Shell：考虑 Root；临时使用：PC ADB            |
| Android 12 | 系统“无线调试”配对后启动 | 否（首次可完全手机内完成） | 必须重启 Shizuku | 支持                          | ADB 授权/后台存活；DPC provisioning API 在 Android 12 有代际变化                              | 非 Root 首选无线调试                                   |
| Android 14 | 系统“无线调试”配对后启动 | 否                         | 必须重启 Shizuku | 支持，但更需关注 DPC/OEM 兼容 | Shizuku API 对 target 34 有专门修复；Dhizuku 2.11.x 存在已知 Android 14 device-admin 配置问题 | Shizuku 用近期版本；Dhizuku 用当前稳定版并先验证 Owner |

# 4. Shizuku：三种系统下的使用与授权

## 4.1 共通前提

- 安装官方 Shizuku。第三方 App 也必须显式支持 Shizuku API；安装 Shizuku 本身不会自动让所有 App 获得更高权限。

- ADB 模式下权限上限是 Android 分配给 shell 用户的权限。某个操作即使“需要高权限”，也不代表 adb shell 一定拥有对应权限。

- Root 模式下 Shizuku Server 可运行在 UID 0，但调用仍可能受 SELinux、厂商 Framework 修改、Binder 接口变化等因素影响。

- 客户端每次启动都应把“Server 是否存活”“Shizuku 授权是否授予”“Server 是 shell 还是 root”分开检测。

## 4.2 Android 10：非 Root 只能走电脑 ADB

Android 10 没有 Android 11 引入的系统“无线调试”配对界面。未 Root 设备需要通过 USB ADB（或已有可用的网络 ADB 环境）启动 Shizuku。官方文档给出的 Shizuku v11.2.0+ 启动命令为：

```bash
adb devices

adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

成功后，Shizuku App 会显示服务已运行。设备重启后服务消失，需要再次执行启动步骤。

1. 打开开发者选项与 USB 调试。

2. 连接电脑，执行 adb devices，并在设备上确认 RSA 调试授权。

3. 执行 Shizuku start.sh。

4. 打开需要 Shizuku 的第三方 App；当它调用 Shizuku.requestPermission() 时，在 Shizuku 授权 UI 中允许。

5. Agent 执行真实功能前再次检查 Shizuku Server 和客户端授权状态。

> **Android 10 Agent 结论：** 非 Root 且没有外部 ADB 通道时，不应假设 Shizuku 可以在重启后自行恢复。

## 4.3 Android 12：首选无线调试

Android 12 原生支持 Android 11+ 的“无线调试”机制。Shizuku 可以在设备本机使用配对码与系统 adbd 配对，因此不需要电脑完成日常启动。

1. 开启开发者选项、USB 调试和“无线调试”。

2. 在 Shizuku 中选择无线调试配对。

3. 系统“无线调试”中选择“使用配对码配对设备”。

4. 把系统显示的配对码输入 Shizuku 的配对通知/界面。

5. 返回 Shizuku 执行“启动”。

6. 第三方 App 首次使用时，在 Shizuku 授权界面允许。

配对通常不是每次启动都要重做，但 Shizuku 的 ADB 服务进程在设备重启后仍需再次启动。官方故障排查还建议 Android 11+ 可启用“停用 adb 授权超时功能”，并确保 Shizuku 可后台运行。

## 4.4 Android 14：流程类似 Android 12，但版本兼容更重要

Android 14 的普通用户流程仍是“无线调试配对 → 启动 Shizuku → 客户端授权”。差异主要体现在应用 targetSdk、后台限制与 OEM 改动。Shizuku-API 的 13.1.5 版本记录明确修复了“面向 Android 14 的 App 在非 Provider 进程请求 Binder 时崩溃”的问题，因此 Agent 集成时应避免使用过旧 Shizuku-API。

- 在 Android 14 上优先使用近期 Shizuku 与 Shizuku-API。

- 如果第三方 App 多进程，必须按 Shizuku-API 的多进程方式获取 Binder，而不能假设主进程授权会自动解决所有进程。

- 遇到“授权已允许但功能仍失败”，先判断是不是 adb shell 本身缺少目标权限，而不是立即把问题归为 Shizuku 授权失败。

- OEM ROM（MIUI/HyperOS、ColorOS、Flyme 等）可能附加“安全 USB 调试”“权限监控”等开关，导致 ADB 模式权限弱于 AOSP。

# 5. Shizuku 客户端授权：Agent 应如何判断

对于集成 Shizuku-API 的 App，推荐把状态机做成以下四态，而不是只有“Shizuku 已安装/未安装”。

```text
NO_BINDER → Shizuku Server 未运行或 Binder 尚未收到

DENIED → Server 在运行，但本 App 未获得 Shizuku 授权

GRANTED_SHELL → 已授权，Server UID = shell（通常 2000）

GRANTED_ROOT → 已授权，Server UID = root（0）
```

官方 API 的典型授权流程是：

```kotlin
Shizuku.addBinderReceivedListener(...)

Shizuku.checkSelfPermission()

Shizuku.shouldShowRequestPermissionRationale()

Shizuku.requestPermission(requestCode)

// 收到 OnRequestPermissionResultListener 回调后再继续
```

如果具体功能需要某个系统权限，还应检查 Server 端是否拥有它，而不是仅检查客户端被 Shizuku 授权。官方文档建议使用 Shizuku.getUid()/服务 UID 判断后端身份，并可通过 checkRemotePermission/checkPermission 类接口验证能力。

# 6. Dhizuku：Device Owner 的使用与授权

## 6.1 Dhizuku 真正授予的是什么

Dhizuku 的设计目标是把 Device Owner（以及 2.12.0 起新增的 Profile Owner 使用支持）所拥有的 DevicePolicyManager 能力共享给其他应用。它适合应用管理、用户限制、锁定任务、权限策略等 DPC 场景，不适合替代 shell 命令执行。

截至 2026-08-23，Dhizuku 官方仓库声明支持 Android 8.0～17；最新 release 为 v2.12.0（2026-06-24）。

## 6.2 激活前置条件

Android 官方用于开发/测试的 fully managed device 流程要求：没有其他用户或工作资料、没有账号，然后才能通过 adb shell dpm set-device-owner 设置 Device Owner。Dhizuku 官方也明确建议在“没有任何账号”的设备状态下激活。

- 没有已存在的 Device Owner。一个设备不能同时存在两个 Device Owner。

- 主用户上没有 Google、厂商、邮箱等账号；某些 OEM 还会把隐藏/系统账号纳入检查。

- 没有次级用户、访客、工作资料、应用分身/双开等对应的 Android User/Profile。

- 设备必须实现 android.software.device_admin；部分电视、虚拟环境或精简 ROM 可能不支持。

- 最可预测的做法是使用新初始化或恢复出厂后的测试设备，在添加账号前完成 Device Owner 配置。

> **禁止自动化：** 不要让 Agent 自动删除用户、冻结核心账号组件或清除设备来“凑齐” Device Owner 条件。 这些动作可能导致用户数据删除、系统服务异常或无法正常启动。Agent 应报告前置条件不满足，由人工在测试设备上处理。

## 6.3 标准 ADB 激活与验证

在满足前置条件的测试设备上，Dhizuku 官方教程使用如下 Device Owner 组件：

```bash
adb shell dpm set-device-owner --user 0 com.rosan.dhizuku/.server.DhizukuDAReceiver
```

部分系统也可省略 --user 0；本文保留 user 0 以明确目标主用户。成功后应立即验证 Framework 记录，而不是只看 Dhizuku 页面提示：

```bash
adb shell dpm list-owners

# 期望看到类似：

# User 0: admin=com.rosan.dhizuku/.server.DhizukuDAReceiver,DeviceOwner
```

如果系统不支持 list-owners，可使用 adb shell dumpsys device_policy 检查 Device Owner。

# 7. Dhizuku 在 Android 10 / 12 / 14 的差异

## 7.1 Android 10

- Device Owner / fully managed device 机制已经成熟，ADB 的 dpm set-device-owner 可用于开发和测试配置。

- Android 10 的 DPC provisioning 仍兼容旧式 ACTION_PROVISION_MANAGED_DEVICE 流程；这对 Dhizuku 的“ADB 测试激活”不是必需步骤，但解释了其时代的企业配置模型。

- 激活成功后 Device Owner 角色由系统持久保存，和 Shizuku ADB Server 不同，不需要每次重启重新执行 set-device-owner。

- Dhizuku 客户端 App 仍应在每次进程启动时重新 init 并检查授权，不能只依赖上次运行时状态。

## 7.2 Android 12

- Android 12 继续支持 Device Owner；企业 provisioning API 有变化，旧 ACTION_PROVISION_MANAGED_DEVICE 在 API 31 被弃用，正式 DPC 应实现新的 provisioning mode / policy compliance 流程。

- 对 Dhizuku 的实验室 ADB 激活而言，核心检查仍然是：clean device state + dpm set-device-owner + list-owners 验证。

- 如果打算让 Agent 管理真实企业设备，应优先走 Android Enterprise 的标准 DPC/EMM provisioning，而不是把 ADB 激活当成生产部署方式。

## 7.3 Android 14

Android 14 引入了“无头系统用户（headless system user）”相关的 Device Owner 配置支持。AOSP 明确指出 Android 14 的该模式会给 fully managed / Device Owner 带来特殊兼容性要求，并建议普通手持和平板不要随意使用该配置。

Dhizuku 在 2.11、2.11.1、2.11.2 曾出现与 Android 14 新增 device-admin XML 元素有关的已知问题：在普通非 headless 设备上可能导致 Device Admin 不可见、Bad admin、或“显示已成为 owner 但 App 仍等待激活”。该问题在项目 issue \#277 中被确认，受影响版本被明确列出。因此 Android 14 上不要沿用 2.11.x 的经验判断，优先使用当前稳定版本并以 dpm list-owners 作为最终事实来源。

- “Dhizuku UI 显示成功”不等于 Device Owner 已建立；以 Framework 的 dpm list-owners 为准。

- “Bad admin”不一定是账号问题，也可能是 DeviceAdminReceiver 声明、ROM feature、应用版本或 OEM DevicePolicyManager 修改导致。

- Android 14 OEM 的企业策略实现差异更明显，尤其是 ColorOS/One UI/电视系统/虚拟环境；Agent 应把 ROM 信息纳入错误报告。

# 8. Dhizuku 客户端授权流程

Dhizuku-API 官方示例的核心流程非常直接：先初始化，再检查是否已授权，需要时发起授权请求。

```kotlin
Dhizuku.init(context)

if (!Dhizuku.isPermissionGranted()) {

Dhizuku.requestPermission(listener)

}

// listener 中 grantResult == PackageManager.PERMISSION_GRANTED 后再执行 DPM 能力
```

Agent 应把“Dhizuku Owner 状态”和“当前客户端 Dhizuku 授权”作为两个不同字段。Device Owner 已存在但当前客户端未被 Dhizuku 允许时，第三方 App 仍不能使用共享能力。

# 9. Agent 决策树

```text
目标能力是什么？

│

├─ 需要 shell/root 身份、系统服务、UserService、隐藏/系统 API

│ └─ 选 Shizuku

│ ├─ Root 可用 → Root 模式

│ ├─ Android 10 → 需要外部 ADB 启动

│ └─ Android 12/14 → 无线调试启动

│

└─ 需要 DevicePolicyManager / DPC 管理能力

└─ 选 Dhizuku

├─ 已是 Device/Profile Owner → 检查客户端授权

└─ 未建立 Owner → 检查 clean-device 前置条件；不满足则停止自动化并报告
```

# 10. 推荐给 Agent 的状态模型

建议 Agent 把环境检测结果标准化为以下字段，以避免把“应用安装”“服务启动”“客户端授权”“真实能力”混为一谈。

```yaml
android_api: 29 | 31 | 34

android_version: 10 | 12 | 14

root_available: true/false

adb_available: true/false

wireless_debugging_supported: true/false

shizuku_installed: true/false

shizuku_binder_alive: true/false

shizuku_client_granted: true/false

shizuku_server_uid: 0 | 2000 | other | null

shizuku_capability_check: granted/denied/unknown

dhizuku_installed: true/false

dpm_owner_type: device_owner/profile_owner/none

dpm_owner_component: string | null

dhizuku_init_ok: true/false

dhizuku_client_granted: true/false

oem: string

rom: string

last_error: string | null
```

执行动作前的硬性规则：只要某项能力属于系统管理或高权限操作，Agent 必须先做 capability check，再执行真实动作；不能仅凭 Android 版本或“授权弹窗点过允许”推断能力存在。

# 11. 常见失败与判定

| **现象**                                      | **更可能原因**                     | **Agent 应检查**                                    | **处理原则**                                      |
|-----------------------------------------------|------------------------------------|-----------------------------------------------------|---------------------------------------------------|
| Shizuku App 显示未运行                        | 服务未启动/被杀                    | Binder、Shizuku 页面、ADB/无线调试状态              | 重新启动 Shizuku；Android 10 需要外部 ADB         |
| Shizuku 已授权但系统调用失败                  | shell 本身无权限/OEM 限制/API 变化 | server UID、目标 permission、异常类型               | 不要重复授权；改做能力检查                        |
| Android 14 Shizuku 客户端崩溃/取不到 Binder   | 旧 Shizuku-API、多进程处理错误     | API 版本、targetSdk、进程模型                       | 升级 API 并按官方多进程方式处理                   |
| Dhizuku set-device-owner 报 existing accounts | 主用户仍有账号                     | dumpsys account、系统账号列表                       | 在测试机人工清理；Agent 不自动破坏账号体系        |
| Dhizuku 报 existing users                     | 存在次用户/访客/工作资料/双开空间  | pm list users / 系统设置                            | 人工删除非必要 profile；注意数据损失              |
| Dhizuku 报 device owner already set           | 已有 DPC                           | dpm list-owners                                     | 只能保留/迁移/移除既有 Owner；不能并存两个 DO     |
| Dhizuku 报 Bad admin                          | Receiver/版本/OEM/feature 问题     | app 版本、Android 14 已知问题、device_admin feature | 不要当成普通授权失败；换当前稳定版/换测试设备验证 |
| Dhizuku UI 与系统 Owner 状态不一致            | 应用状态缓存或版本兼容问题         | dpm list-owners                                     | Framework 状态优先                                |

# 12. 安全边界与自动化限制

- Shizuku 客户端授权相当于允许第三方 App 借用 Shizuku Server 的高权限能力。只给可信、来源可验证的应用授权。

- Dhizuku Device Owner 能执行影响整机策略的管理操作。Agent 不应在未知设备上自动设为 Device Owner。

- 不要通过自动删除用户、清空账号、冻结核心系统组件、修改 APK 签名等方式绕过 Device Owner 前置条件。

- 生产环境的企业设备优先采用 Android Enterprise/EMM 的标准 provisioning；ADB set-device-owner 更适合开发和受控实验室测试。

- 撤销 Device Owner 的方法受应用声明、Android 版本和 OEM 实现影响。执行前必须确认存在受支持的撤销/迁移路径和设备备份。

# 13. 三个版本的推荐配置总结

| **系统**   | **临时 Shell 能力**                | **长期 Device Policy 能力**        | **Agent 默认选择**                                              |
|------------|------------------------------------|------------------------------------|-----------------------------------------------------------------|
| Android 10 | Shizuku + USB/外部 ADB；重启后重做 | Dhizuku Device Owner（干净测试机） | 先问目标是 Shell 还是 DPM；无外部 ADB 时不要假设 Shizuku 可恢复 |
| Android 12 | Shizuku + 无线调试                 | Dhizuku Device Owner               | Shizuku 非 Root 最方便；DPM 需求才用 Dhizuku                    |
| Android 14 | 近期 Shizuku + 无线调试 + 新版 API | 当前稳定 Dhizuku + Framework 验证  | 更严格做版本/OEM/Owner 状态检查                                 |

# 14. 参考资料

1. Shizuku 官方用户手册：启动方式与 Android 11+ 无线调试：[https://shizuku.rikka.app/guide/setup/](https://shizuku.rikka.app/guide/setup/)

2. Shizuku 官方中文用户手册源码：[https://github.com/RikkaApps/websites/blob/master/shizuku/zh-hans/guide/setup.md](https://github.com/RikkaApps/websites/blob/master/shizuku/zh-hans/guide/setup.md)

3. Shizuku-API 官方开发者指南：[https://github.com/RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)

4. Dhizuku 官方仓库：[https://github.com/iamr0s/Dhizuku](https://github.com/iamr0s/Dhizuku)

5. Dhizuku-API 官方仓库：[https://github.com/iamr0s/Dhizuku-API](https://github.com/iamr0s/Dhizuku-API)

6. Dhizuku 官方激活教程 / Discussion \#19：[https://github.com/iamr0s/Dhizuku/discussions/19](https://github.com/iamr0s/Dhizuku/discussions/19)

7. Dhizuku Releases（v2.12.0 为 2026-06-24 的最新稳定版）：[https://github.com/iamr0s/Dhizuku/releases](https://github.com/iamr0s/Dhizuku/releases)

8. Dhizuku Android 14 / 2.11.x device-admin 已知问题 \#277：[https://github.com/iamr0s/Dhizuku/issues/277](https://github.com/iamr0s/Dhizuku/issues/277)

9. Android Enterprise Developer Guide：ADB 设置 fully managed Device Owner 的前置条件：[https://developer.android.com/work/guide](https://developer.android.com/work/guide)

10. Android DevicePolicyManager API Reference：[https://developer.android.com/reference/android/app/admin/DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)

11. Android 12 Enterprise Changes：[https://developer.android.com/work/versions/android-12](https://developer.android.com/work/versions/android-12)

12. AOSP Device Management：Android 14 headless system user 与 Device Owner：[https://source.android.com/docs/devices/admin/implement](https://source.android.com/docs/devices/admin/implement)
