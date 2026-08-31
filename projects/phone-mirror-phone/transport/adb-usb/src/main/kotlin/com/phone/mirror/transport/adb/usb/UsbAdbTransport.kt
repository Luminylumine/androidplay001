package com.phone.mirror.transport.adb.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.phone.mirror.core.Result
import com.phone.mirror.transport.adb.core.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 Android UsbManager 的 USB OTG ADB 传输。
 *
 * ADB USB 设备的识别：
 *  - Interface.Class = 0xFF
 *  - Interface.Subclass = 0x42
 *  - Interface.Protocol = 0x01
 *
 * 端点：
 *  - EP 0: control (setup 用于 ADB SETUP 命令)
 *  - Bulk EP IN:   读取手机 → 主机数据
 *  - Bulk EP OUT:  写入主机 → 手机数据
 */
class UsbAdbTransport(
    private val usbDevice: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val adbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) : AdbTransport {

    override val isConnected: Boolean get() = connection.isOpen

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runResult {
            if (!connection.claimInterface(adbInterface, /*force=*/ false)) {
                error("claimInterface failed — 设备可能被其他应用占用")
            }
        }
    }

    override suspend fun readBytes(len: Int): ByteArray = withContext(Dispatchers.IO) {
        val buf = ByteArray(len)
        val read = connection.bulkTransfer(inEndpoint, buf, len, /*timeout=*/ 5000)
        if (read <= 0) ByteArray(0) else buf.copyOf(read)
    }

    override suspend fun writeBytes(data: ByteArray) = withContext(Dispatchers.IO) {
        val written = connection.bulkTransfer(outEndpoint, data, data.size, /*timeout=*/ 5000)
        if (written != data.size) {
            // USB 写入可能被拆分；如需保证完整写入需循环，当前占位假设一次成功
            error("bulkTransfer incomplete: $written / ${data.size}")
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        try {
            connection.releaseInterface(adbInterface)
        } catch (_: Throwable) { /* 忽略 */ }
        connection.close()
    }

    companion object {
        /** 判断 [iface] 是否是 ADB USB 接口 (class=FF, subclass=42, protocol=01) */
        fun isAdbInterface(iface: UsbInterface): Boolean =
            iface.interfaceClass == 0xFF &&
                iface.interfaceSubclass == 0x42 &&
                iface.interfaceProtocol == 0x01
    }
}
