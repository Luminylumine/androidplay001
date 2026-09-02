package com.phone.mirror.transport.adb.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.phone.mirror.core.Result
import com.phone.mirror.core.runCatchingResult
import com.phone.mirror.transport.adb.core.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 Android UsbManager 的 USB OTG ADB 传输。
 * Phase 2+ 用。当前 stub。
 */
class UsbAdbTransport(
    private val usbDevice: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val adbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) : AdbTransport {

    @Volatile
    private var open = false

    override val isConnected: Boolean get() = open

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            if (!connection.claimInterface(adbInterface, /*force=*/ false)) {
                error("claimInterface failed — 设备可能被其他应用占用")
            }
            open = true
            Result.success(Unit)
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
            error("bulkTransfer incomplete: $written / ${data.size}")
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        try {
            connection.releaseInterface(adbInterface)
        } catch (_: Throwable) { /* 忽略 */ }
        connection.close()
        open = false
    }

    companion object {
        fun isAdbInterface(iface: UsbInterface): Boolean =
            iface.interfaceClass == 0xFF &&
                iface.interfaceSubclass == 0x42 &&
                iface.interfaceProtocol == 0x01
    }
}
