package com.phone.mirror.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.phone.mirror.transport.adb.core.AdbConnectionImpl
import com.phone.mirror.transport.adb.core.AdbKeyPair
import com.phone.mirror.transport.adb.wifi.LegacyTcpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Phase 0 验收入口 —— 只做一件事：
 *   用户输入 target 的 IP:PORT (如 192.168.1.100:5555) → 点按钮
 *   → 显示 target `ro.product.model`，证明 ADB protocol 自实现通了。
 *
 * 成功标准: 界面 status 变成 `>>> [target model name]` 后 `DONE ✓`。
 *
 * target 侧准备:
 *   adb tcpip 5555
 *   adb connect <phone-ip>:5555
 *   adb -s <phone-ip>:5555 shell settings put global adb_enabled 1  (确认)
 * 或直接在 target 上 `setprop service.adb.tcp.port 5555 && stop adbd && start adbd`
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhoneMirrorTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Phase0TestScreen()
                }
            }
        }
    }
}

@Composable
private fun Phase0TestScreen() {
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("192.168.1.100") }
    var port by remember { mutableStateOf("5555") }
    var status by remember { mutableStateOf("未连接") }
    var running by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Phone Mirror Phone — Phase 0", style = MaterialTheme.typography.titleLarge)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("Target IP") },
                modifier = Modifier.weight(1f), singleLine = true,
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("Port") },
                modifier = Modifier.weight(0.5f), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        Text("状态: $status", style = MaterialTheme.typography.bodyMedium)

        Button(
            enabled = !running,
            onClick = {
                if (running) return@Button
                running = true
                status = "连接中..."
                runJob = scope.launch(Dispatchers.IO) {
                    runPhase0Test(host, port.toIntOrNull() ?: 5555) { status = it }
                }.also { j -> j.invokeOnCompletion { running = false } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "测试中..." else "连接 & 读取 model") }

        Button(
            enabled = running,
            onClick = { runJob?.cancel() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("取消") }
    }
}

private suspend fun runPhase0Test(
    host: String,
    port: Int,
    onStatus: (String) -> Unit,
) {
    fun log(msg: String) {
        onStatus(msg)
        android.util.Log.i("Phase0", msg)
    }

    try {
        log("[1/4] 生成 RSA-2048 密钥对...")
        val keyPair = AdbKeyPair.generate("phone-mirror-phone@test")
        log("    publicKeyBase64=${keyPair.publicKeyBase64.length} chars")

        log("[2/4] TCP 连接 $host:$port ...")
        val transport = LegacyTcpTransport(host, port)
        transport.connect().onFailure { t -> throw IllegalStateException("TCP fail: ${t.message}") }
        log("    TCP OK")

        log("[3/4] ADB CNXN/AUTH 握手...")
        val conn = AdbConnectionImpl(transport, keyPair)
        val banner = conn.connect().errorOrThrow()
        log("    peer banner: $banner")

        log("[4/4] shell getprop ro.product.model ...")
        val model = conn.shell("getprop ro.product.model").errorOrThrow().trim()
        val brand = conn.shell("getprop ro.product.brand").successOrNull()?.trim() ?: "?"
        val sdk = conn.shell("getprop ro.build.version.sdk").successOrNull()?.trim() ?: "?"
        log("    >>> $brand $model (SDK $sdk)")

        conn.close()
        log("DONE ✓ — Phase 0 验收通过")
    } catch (ce: CancellationException) {
        log("已取消")
    } catch (t: Throwable) {
        log("FAIL ✗ ${t.javaClass.simpleName}: ${t.message}")
        android.util.Log.e("Phase0", "test failed", t)
    }
}
