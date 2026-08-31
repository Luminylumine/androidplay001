package com.phone.mirror.privilege.dhizuku

import android.content.Context
import com.phone.mirror.core.Result

/**
 * DHizuku 绑定封装。
 *
 * DHizuku 是 Shizuku 的 DHCP/无需手动启动守护进程的分支版本，
 * 适合嵌入 ROM 或系统应用场景。接口上与 [com.phone.mirror.privilege.shizuku.ShizukuAccess] 保持一致，
 * 上层只需把实现注入不同即可。
 */
interface DhizukuAccess {

    /** 初始化绑定流程 */
    suspend fun bind(context: Context): Result<Unit>

    /** 是否已绑定 */
    val isBound: Boolean

    /** 执行 shell 命令 */
    suspend fun exec(command: String, allowRoot: Boolean = false): Result<String>

    /** 主动解绑 */
    suspend fun unbind()
}
