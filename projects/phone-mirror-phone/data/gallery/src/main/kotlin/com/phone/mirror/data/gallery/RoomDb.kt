package com.phone.mirror.data.gallery

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room 数据库：存 GalleryItem 元数据，支持增量同步和 tombstone 逻辑。
 */
@Entity(
    tableName = "gallery_items",
    indices = [
        Index(value = ["deviceId"], name = "idx_device"),
        Index(value = ["deviceId", "albumName"], name = "idx_device_album"),
        Index(value = ["deviceId", "tombstoneTs"], name = "idx_device_tombstone"),
    ],
)
data class GalleryItemEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val mediaId: Long,
    val data: String,
    val size: Long,
    val dateModified: Long,
    val deviceId: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val title: String,
    val albumName: String,
    val tombstoneTs: Long? = null,
) {
    fun toDomain(): GalleryItem = GalleryItem(
        mediaId = mediaId, data = data, size = size, dateModified = dateModified,
        deviceId = deviceId, mimeType = mimeType, width = width, height = height,
        durationMs = durationMs, title = title, albumName = albumName,
        tombstoneTs = tombstoneTs,
    )
}

@Dao
interface GalleryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<GalleryItemEntity>)

    @Query("SELECT * FROM gallery_items WHERE deviceId = :deviceId AND tombstoneTs IS NULL ORDER BY dateModified DESC, mediaId DESC")
    suspend fun loadAll(deviceId: String): List<GalleryItemEntity>

    @Query("SELECT * FROM gallery_items WHERE deviceId = :deviceId AND tombstoneTs IS NULL AND albumName = :album ORDER BY dateModified DESC")
    suspend fun loadByAlbum(deviceId: String, album: String): List<GalleryItemEntity>

    @Query("UPDATE gallery_items SET tombstoneTs = :ts WHERE deviceId = :deviceId AND tombstoneTs IS NULL AND mediaId NOT IN (:activeMediaIds)")
    suspend fun markTombstones(deviceId: String, ts: Long, activeMediaIds: List<Long>)

    @Query("DELETE FROM gallery_items WHERE deviceId = :deviceId AND tombstoneTs IS NOT NULL")
    suspend fun purgeTombstones(deviceId: String)

    @Query("DELETE FROM gallery_items WHERE deviceId = :deviceId")
    suspend fun clearDevice(deviceId: String)
}

@Database(entities = [GalleryItemEntity::class], version = 1, exportSchema = false)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun galleryDao(): GalleryDao

    companion object {
        /** 构建方法 —— 从 Context 调用 */
        fun build(context: android.content.Context): GalleryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                GalleryDatabase::class.java,
                "phone-mirror-gallery.db",
            ).build()
    }
}
