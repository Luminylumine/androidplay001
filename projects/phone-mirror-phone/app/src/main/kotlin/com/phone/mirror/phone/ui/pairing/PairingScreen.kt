package com.phone.mirror.phone.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.phone.mirror.phone.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Wireless Debugging 配对界面 —— 用户输入手机显示的 "ip:port" 和 6 位数字配对码。
 */
@Composable
fun PairingScreen(
    container: AppContainer,
) {
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pairingMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopAppBar(title = { Text("Wireless Debugging 配对") })

        Text(
            text = "请在手机的「开发者选项 → Wireless Debugging → 使用配对码配对设备」获取配对地址和 6 位码",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("配对地址 (例: 192.168.1.23:37921)") },
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
            label = { Text("配对码 (6 位数字)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )

        Button(
            onClick = {
                scope.launch {
                    pairingMessage = "配对中…"
                    // TODO: container.pairingManager.pair(address, code) 成功后跳转设备列表
                    pairingMessage = "占位：等待 PairingManager 实现"
                }
            },
            enabled = address.contains(':') && code.length == 6,
        ) {
            Text("开始配对")
        }

        if (pairingMessage.isNotBlank()) {
            Text(pairingMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
