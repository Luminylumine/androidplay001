package com.phone.mirror.privilege.shizuku

import android.content.Context
import com.phone.mirror.core.Result

/**
 * Shizuku 绑定封装。
 *
 * Shizuku 提供了 adb 用户级 shell 的直接调用通道，让我们可以不通过 `Runtime.exec("su")`
 * 也能执行 shell 命令。这是可选增强路径 —— 基础功能不依赖 Shizuku，但启用它可以：
 *  - 降低对 USB 调试授权的依赖（使用 WiFi ADB + Shizuku）
 *  - 在某些 OEM ROM 上规避 SELinux 限制
 *
 * 注意：Shizuku API 库本身（rikka.shizuku:api:13.1.5）应该由 app 模块的 DI 容器统一注入；
 * 本模块只负责抽象 "是否绑定成功" 与 "run shell command" 的高层行为。
 */
interface ShizukuAccess {

    /** 初始化绑定流程 */
    suspend fun bind(context: Context): Result<Unit>

    /** 是否已绑定（shizuku service 在线） */
    val isBound: Boolean

    /**
     * 执行 shell 命令，返回 stdout 文本。
     * @param allowRoot 是否允许通过 shizuku 请求 root（依赖 shizuku 服务启动方式）
     */
    suspend fun exec(command: String, allowRoot: Boolean = false): Result<String>

    /** 主动解绑 */
    suspend fun unbind()
}
