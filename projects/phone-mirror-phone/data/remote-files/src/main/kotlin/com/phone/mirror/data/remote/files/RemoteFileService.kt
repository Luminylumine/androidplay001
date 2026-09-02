package com.phone.mirror.data.remote.files

import com.phone.mirror.core.Result
import kotlinx.coroutines.flow.Flow

/**
 * 远程文件服务接口 —— 基于 ADB SYNC 协议实现。
 *
 * 能力：
 *  - stat 单文件元数据（size / mtime / mode / type）
 *  - list 目录下条目（支持批量流式输出）
 *  - push 本地文件到远端 / 流式写入
 *  - pull 远端文件到本地 / 流式读取
 */
interface RemoteFileService {

    /** 远端文件元数据 */
    data class RemoteStat(
        val mode: Int,
        val size: Long,
        val mtime: Long,
        val path: String,
    ) {
        val isDirectory: Boolean get() = (mode and AdbSync.Mode.S_IFMT) == AdbSync.Mode.S_IFDIR
    }

    /** 查询远端 [path] 的状态 */
    suspend fun stat(path: String): Result<RemoteStat>

    /** 列出 [dirPath] 下所有条目，Flow 每条发射一个 */
    suspend fun list(dirPath: String): Flow<RemoteStat>

    /**
     * 流式 push：从 [source] 逐块推送字节到 [remotePath]。
     * @param mode ADB SYNC 文件模式（0644 默认）
     */
    suspend fun push(
        remotePath: String,
        source: Flow<ByteArray>,
        totalSize: Long,
        mode: Int = 0b110_100_100,
    ): Result<Unit>

    /**
     * 流式 pull：从 [remotePath] 拉取文件，逐块发射。
     */
    suspend fun pull(remotePath: String): Flow<ByteArray>

    /** 删除远端文件 */
    suspend fun delete(remotePath: String): Result<Unit>

    /** 创建远端目录 */
    suspend fun mkdir(dirPath: String): Result<Unit>
}
