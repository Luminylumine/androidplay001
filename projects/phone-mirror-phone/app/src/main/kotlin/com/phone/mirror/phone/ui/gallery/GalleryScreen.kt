package com.phone.mirror.phone.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phone.mirror.data.gallery.GalleryItem
import com.phone.mirror.data.gallery.GalleryRepository
import com.phone.mirror.phone.di.AppContainer

/**
 * 相册网格 —— 使用 LazyVerticalGrid + Room Flow 数据源。
 *
 * 初始加载调用 GalleryRepository.loadAsync()，后台轮询调用 pollNewAsync()。
 */
@Composable
fun GalleryScreen(
    container: AppContainer,
    deviceId: String,
) {
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }

    // TODO: 接入 container.galleryRepositoryFactory(deviceId) 返回的 GalleryRepository
    // 并 collect galleryRepository.items Flow

    LaunchedEffect(deviceId) {
        // TODO: repository.loadAsync() → items = repository.items.first()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("相册 · $deviceId") })

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在加载相册…")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.mediaId }) { item ->
                    // TODO: 从 DiskCache 读 512px 缩略图 → AsyncImage / painterResource
                    Text(
                        text = item.title.ifBlank { item.data.substringAfterLast('/') },
                    )
                }
            }
        }
    }
}
