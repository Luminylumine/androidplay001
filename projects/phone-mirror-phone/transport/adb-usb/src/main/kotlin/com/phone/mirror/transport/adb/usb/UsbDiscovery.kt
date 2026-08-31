package com.phone.mirror.transport.adb.usb

import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.phone.mirror.core.DeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * USB 设备发现：通过 [UsbManager] 列出所有已连接的 ADB 兼容 USB 设备。
 *
 * 在 AndroidManifest.xml 中需要：
 * ```xml
 * <uses-feature android:name="android.hardware.usb.host" />
 * <uses-permission android:name="android.permission.USB_PERMISSION" />
 * ```
 */
class UsbDiscovery(private val context: Context) {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<UsbDevice>>(emptyList())
    /** 所有已连接且接口 class=FF subclass=42 protocol=01 的 USB 设备 */
    val devices: Flow<List<UsbDevice>> = _devices.asStateFlow()

    /** 扫描当前 USB 设备列表（通常在 Activity onResume / USB attach broadcast 时调用） */
    fun scan() {
        val list = usbManager.deviceList.values.filter { device ->
            // 至少有一个 interface 满足 ADB USB 接口特征
            (0 until device.interfaceCount).any { idx ->
                val iface = device.getInterface(idx)
                iface.interfaceClass == 0xFF &&
                    iface.interfaceSubclass == 0x42 &&
                    iface.interfaceProtocol == 0x01
            }
        }
        _devices.value = list
    }

    /** 请求 USB 权限 —— 需配合 PendingIntent 广播接收 */
    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** 将 [UsbDevice] 转换为 [DeviceInfo]（基础字段，无握手信息） */
    fun toDeviceInfo(device: UsbDevice): DeviceInfo = DeviceInfo(
        id = "usb-${device.deviceId}",
        model = device.productName ?: device.deviceName,
        isUsb = true,
        ipAddress = null,
        port = 0,
    )
}
