package com.phone.mirror.phone.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phone.mirror.phone.di.AppContainer

/**
 * 远程文件浏览器 —— 列出 /sdcard 下的文件，支持 push/pull。
 *
 * 数据来源：container.remoteFilesService.list(dir)
 */
@Composable
fun FileBrowserScreen(
    container: AppContainer,
    deviceId: String,
) {
    var currentDir by remember { mutableStateOf("/sdcard") }
    // TODO: 接入 RemoteFileService.list() 的 Flow

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("文件 · $deviceId") })

        Text(
            text = "当前目录: $currentDir",
            modifier = Modifier.padding(16.dp),
        )

        // TODO: items(items) { entry → RemoteStat 卡片，点击进入子目录；长按显示 push/pull 菜单 }
        LazyColumn(modifier = Modifier.padding(16.dp)) {
            item { Text("占位实现 —— 等待 RemoteFileService 接入") }
        }
    }
}
