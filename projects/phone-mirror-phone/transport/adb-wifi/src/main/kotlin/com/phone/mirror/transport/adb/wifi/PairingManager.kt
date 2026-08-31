package com.phone.mirror.transport.adb.wifi

import com.phone.mirror.core.Result

/**
 * Wireless Debugging 配对管理器。
 *
 * Android 11+ Wireless Debugging 需要先配对：
 *  1. 手机显示配对 IP:port + 6 位配对码
 *  2. 客户端发起 pairing 握手（UDP + AES）
 *  3. 成功后获得 certificate，可用于后续 [TlsWirelessTransport]
 */
interface PairingManager {

    /**
     * 执行配对流程。
     * @param pairingAddress 手机显示的配对地址（`ip:port`）
     * @param pairingCode    手机显示的 6 位数字配对码
     * @param deviceName     本端显示在手机上的名字
     * @return 成功返回 Certificate 字符串；失败返回 Result.Failure
     */
    suspend fun pair(
        pairingAddress: String,
        pairingCode: String,
        deviceName: String = "phone-mirror-phone",
    ): Result<String>

    /** 校验某个证书是否已配对过（可用于跳过重复配对） */
    suspend fun isAlreadyPaired(host: String, port: Int): Boolean

    /** 清除所有配对缓存 */
    suspend fun clearAllPairings()
}
