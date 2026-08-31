package com.phone.mirror.data.gallery

import com.phone.mirror.data.remote.files.RemoteFileService
import com.phone.mirror.transport.adb.core.AdbConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * GalleryRepository —— **1:1 移植自 Windows C# GalleryRepository.cs**。
 *
 * 设计模式：
 *  - [loadAsync] 全量加载：从远端 MediaStore 拉全量列表 → Room 缓存 → UI 展示
 *  - [pollNewAsync] 增量轮询：只拉 dateModified 在 lastSync 之后的条目，合并到 Room
 *  - [removeTombstoned]：把远端已不存在但 Room 还在的条目打 tombstone 标记，下次 purge
 *  - Album 构建：按 [GalleryItem.albumKey] 分组，生成 [AlbumSummary]
 *
 * 数据源：
 *  - 远端：shell 执行 `content query --uri content://media/external/images/media ...`（由 RemoteFileService 辅助执行）
 *  - 本地：Room [GalleryDao]
 */
class GalleryRepository(
    private val deviceId: String,
    private val connection: AdbConnection,
    private val remoteFiles: RemoteFileService,
    private val dao: GalleryDao,
) {

    data class AlbumSummary(
        val name: String,
        val itemCount: Int,
        /** 最新一条缩略图 data path，用于 UI 封面 */
        val coverData: String?,
    )

    // —— 内部状态 ——
    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    /** 当前已加载的全部 GalleryItem（不含 tombstone），按 dateModified DESC 排序 */
    val items: Flow<List<GalleryItem>> = _items.asStateFlow()

    private val _albums = MutableStateFlow<List<AlbumSummary>>(emptyList())
    /** 按 albumKey 分组后的相册汇总（每个相册最新条目作为 cover） */
    val albums: Flow<List<AlbumSummary>> = _albums.asStateFlow()

    /** 上次增量同步时间戳（unix seconds） */
    private var lastSyncTs: Long = 0

    // —— 远端 MediaStore 查询 ——

    private suspend fun queryRemoteGallery(): List<GalleryItem> {
        val projection = GalleryRowParser.PROJECTION_FIELDS.joinToString(",")
        val cmd = """content query --uri content://media/external/images/media --projection "$projection" --sort "_ID ASC" """
        // TODO: 扩展到 videos (content://media/external/video/media)
        // TODO: shell 返回的 stdout 逐行喂给 GalleryRowParser.parse
        val output = connection.shell(cmd)
        return output.successOrNull()
            ?.lineSequence()
            ?.mapNotNull { GalleryRowParser.parse(it, deviceId) }
            ?.toList()
            ?: emptyList()
    }

    // —— 公开 API ——

    /** 全量加载（首次进入 Gallery 界面时调用） */
    suspend fun loadAsync() = coroutineScope {
        val remoteList = queryRemoteGallery()

        // 同时做 3 件事：
        //  1) Room upsert
        //  2) Mark tombstones（远端已不存在的本地条目）
        //  3) 从 Room 重新读回权威数据源
        val remoteMediaIds = remoteList.map { it.mediaId }.distinct()
        dao.markTombstones(deviceId, System.currentTimeMillis() / 1000, remoteMediaIds)

        dao.upsertAll(remoteList.map { it.toEntity() })
        dao.purgeTombstones(deviceId)

        val all = dao.loadAll(deviceId).map { it.toDomain() }
        _items.value = all
        _albums.value = buildAlbums(all)
        lastSyncTs = System.currentTimeMillis() / 1000
    }

    /** 增量轮询（后台每 30s 调用一次） */
    suspend fun pollNewAsync(): Result<Int> = runResult {
        val remoteList = queryRemoteGallery()

        // 过滤出上次同步后新增 / 修改的条目
        val updated = remoteList.filter { it.dateModified >= lastSyncTs }
        if (updated.isNotEmpty()) {
            dao.upsertAll(updated.map { it.toEntity() })
        }

        // 更新远端不存在的条目为 tombstone
        val remoteMediaIds = remoteList.map { it.mediaId }
        dao.markTombstones(deviceId, System.currentTimeMillis() / 1000, remoteMediaIds)
        dao.purgeTombstones(deviceId)

        // 重算内存状态
        val all = dao.loadAll(deviceId).map { it.toDomain() }
        _items.value = all
        _albums.value = buildAlbums(all)
        lastSyncTs = System.currentTimeMillis() / 1000

        updated.size
    }

    /** 移除所有 tombstoned 条目 —— 通常在 UI 上主动清理时调用 */
    suspend fun removeTombstoned() {
        dao.purgeTombstones(deviceId)
    }

    /** 清空 Room + 内存缓存（断开设备时调用） */
    suspend fun clear() {
        dao.clearDevice(deviceId)
        _items.value = emptyList()
        _albums.value = emptyList()
    }

    // —— 内部工具 ——

    /** 从 [items] 构建 [AlbumSummary] 列表（按每个 album 的最新条目做 cover 图片） */
    private fun buildAlbums(items: List<GalleryItem>): List<AlbumSummary> {
        val grouped = items.groupBy { it.albumKey }
        return grouped.map { (album, list) ->
            AlbumSummary(
                name = album,
                itemCount = list.size,
                coverData = list.firstOrNull()?.data, // dateModified 已在 groupBy 前排序
            )
        }.sortedByDescending { it.itemCount }
    }

    private fun GalleryItem.toEntity(): GalleryItemEntity = GalleryItemEntity(
        mediaId = mediaId, data = data, size = size, dateModified = dateModified,
        deviceId = deviceId, mimeType = mimeType, width = width, height = height,
        durationMs = durationMs, title = title, albumName = albumName,
        tombstoneTs = tombstoneTs,
    )
}
