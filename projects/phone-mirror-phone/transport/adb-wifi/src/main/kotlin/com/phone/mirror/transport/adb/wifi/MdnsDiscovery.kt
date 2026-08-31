package com.phone.mirror.transport.adb.wifi

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.phone.mirror.core.DeviceInfo
import kotlinx.coroutines.flow.Flow

/**
 * mDNS 服务发现 —— 自动发现局域网内开启 Wireless Debugging 的设备。
 *
 * Android 11+ Wireless Debugging 会广播 `_adb-tls._tcp.` / `_adb._tcp.` 服务。
 *
 * 在 AndroidManifest.xml 中需要声明：
 * ```xml
 * <uses-permission android:name="android.permission.INTERNET" />
 * <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
 * <uses-permission android:name="android.permission.BLUETOOTH" />
 * ```
 */
interface MdnsDiscovery {

    /**
     * 启动 mDNS 扫描。返回一个 Flow：每当发现新服务或服务下线时发射完整设备列表。
     */
    fun discover(services: List<String> = DEFAULT_SERVICES): Flow<List<DeviceInfo>>

    /** 手动停止扫描 */
    fun stop()

    companion object {
        /** mDNS 服务类型：TLS Wireless Debugging + Legacy TCP ADB */
        val DEFAULT_SERVICES = listOf("_adb-tls._tcp.", "_adb._tcp.")
    }
}
