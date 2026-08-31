package com.phone.mirror.transport.adb.core

import com.phone.mirror.core.Result

/**
 * ADB 连接接口。负责：
 *  - 握手（CNXN / AUTH）
 *  - 打开多路复用的 stream（open service）
 *  - 直接执行 shell 命令
 */
interface AdbConnection {
    /** 对目标 [service]（如 "shell:", "localabstract:scrcpy_xxx", "sync:"）打开一个 stream */
    suspend fun open(service: String): Result<AdbStream>

    /** 执行 shell 命令，返回 stdout 文本 */
    suspend fun shell(command: String): Result<String>

    /** 关闭整个 ADB 连接 */
    suspend fun close()
}
