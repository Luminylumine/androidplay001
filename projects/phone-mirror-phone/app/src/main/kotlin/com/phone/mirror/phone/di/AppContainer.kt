package com.phone.mirror.phone.di

import android.content.Context
import com.phone.mirror.data.cache.DiskCache
import com.phone.mirror.data.gallery.GalleryDatabase
import com.phone.mirror.transport.adb.wifi.MdnsDiscovery
import com.phone.mirror.transport.adb.wifi.PairingManager
import com.phone.mirror.transport.adb.usb.UsbDiscovery

/**
 * 简易服务定位器（Service Locator）容器。
 *
 * 未引入 Hilt/Koin 以保持 0 依赖（方便后续替换或保留最小化注入）。
 * 每个服务都有 lazy 初始化，避免 AppApplication.onCreate 时阻塞。
 *
 * 用法：
 * ```kotlin
 * val container = (activity.application as AppApplication).container
 * val repo = container.galleryRepositoryFactory(...)
 * ```
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    // —— 数据层 ——

    /** 磁盘缓存（会话级） */
    val diskCache: DiskCache by lazy { DiskCache(appContext) }

    /** Room 数据库（单例） */
    val galleryDb: GalleryDatabase by lazy { GalleryDatabase.build(appContext) }

    // —— 发现层 ——

    /** mDNS 发现（Wireless Debugging 设备广播） */
    val mdnsDiscovery: MdnsDiscovery by lazy {
        // TODO: 注入 NsdManager，返回实现 MdnsDiscovery 的实例
        throw UnsupportedOperationException("MdnsDiscovery 尚未实现")
    }

    /** USB 设备发现 */
    val usbDiscovery: UsbDiscovery by lazy { UsbDiscovery(appContext) }

    /** Wireless Debugging 配对管理器 */
    val pairingManager: PairingManager by lazy {
        // TODO: 注入 PairingManager 实现
        throw UnsupportedOperationException("PairingManager 尚未实现")
    }

    // —— 工厂方法 ——

    // 某些服务依赖"具体目标设备"，故提供 factory 而不是单例
    // 工厂方法会在后续 transport / mirror / data 模块齐备后补全
}
