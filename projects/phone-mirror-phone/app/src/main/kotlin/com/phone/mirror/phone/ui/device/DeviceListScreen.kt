package com.phone.mirror.phone.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phone.mirror.core.DeviceInfo
import com.phone.mirror.phone.di.AppContainer

/**
 * 设备列表 —— 发现附近的 ADB/Wireless Debugging 设备。
 *
 * 数据源：
 *  - mDNS 广播 (container.mdnsDiscovery)
 *  - USB OTG 插入事件 (container.usbDiscovery)
 *  - 手动输入的 IP:Port（TODO）
 */
@Composable
fun DeviceListScreen(
    container: AppContainer,
    onMirrorClick: (String) -> Unit,
    onPairingClick: () -> Unit,
    onGalleryClick: (String) -> Unit,
    onFilesClick: (String) -> Unit,
) {
    // TODO: 接入 container.usbDiscovery.devices + container.mdnsDiscovery.discover()
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Phone Mirror Phone") },
            actions = {
                Button(onClick = onPairingClick) { Text("配对") }
            },
        )

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "未发现任何设备",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "请通过 USB OTG 连接，或点击右上角「配对」按钮",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(device.model, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (device.isUsb) "USB 连接" else "${device.ipAddress}:${device.port}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(modifier = Modifier.padding(top = 8.dp)) {
                                Button(onClick = { onMirrorClick(device.id) }) {
                                    Text("投屏")
                                }
                                Button(onClick = { onGalleryClick(device.id) }, modifier = Modifier.padding(start = 8.dp)) {
                                    Text("相册")
                                }
                                Button(onClick = { onFilesClick(device.id) }, modifier = Modifier.padding(start = 8.dp)) {
                                    Text("文件")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
