// ============================================================================
// BiliLite — Android 数据层骨架
// 纯本地 Room: UP主/视频/观看记录/队列。专注密码用 keystore(生产)或本地(骨架)。
// 真实 B 站登录/API 需另行接入(见 README),这里只定义本地持久结构。
// ============================================================================
package com.bililite.data

import androidx.room.*

@Entity(tableName = "upmain")
data class Up(
    @PrimaryKey val id: String,      // B站 mid
    val name: String,
    val fans: Long = 0,              // 搜索时显示,防选错
    val face: String = "",           // UP 头像 url
    val tags: List<String> = emptyList(), // 自定义分类标签
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "video")
data class Video(
    @PrimaryKey val id: Long,        // B站 aid(稳定唯一)
    val bvid: String = "",
    val cid: Long = 0,
    val upId: String,
    val title: String,
    val durationSec: Int,
    val pages: Int = 1,
    val series: String = "",         // 分P系列名(空=单P)
    val pic: String = "",            // 封面图 url
    val playCount: Long = 0,         // 播放量(用于排序)
    val pubdate: Long = 0,           // 发布时间(Unix 秒)
    val favorite: Boolean = false,   // 是否收藏
    val tags: List<String> = emptyList()
)

@Entity(tableName = "watch")
data class Watch(
    @PrimaryKey val videoId: Long,
    val mode: String = "deep",       // quick / deep
    var progress: Int = 0,           // 0-100
    var summary: String = "",        // 精读总结
    var learned: Boolean = false,    // 已完成
    val startedAt: Long = System.currentTimeMillis(),
    var secs: Long = 0
)

@Entity(tableName = "queue_item")
data class QueueItem(
    @PrimaryKey val videoId: Long,
    val status: String = "todo",     // todo / done
    val addedAt: Long = System.currentTimeMillis()
)

// 专注密码(6位): 生产应存 Android Keystore / 单向 hash
object FocusLock {
    fun hash(pin: String): String = Integer.toUnsignedString(pin.hashCode())
    fun verify(stored: String, pin: String): Boolean = stored == hash(pin)
}


class Converters {
    @TypeConverter
    fun fromList(v: List<String>?): String =
        if (v.isNullOrEmpty()) "" else org.json.JSONArray(v).toString()

    @TypeConverter
    fun toList(v: String): List<String> {
        if (v.isBlank()) return emptyList()
        val a = org.json.JSONArray(v)
        return (0 until a.length()).map { a.getString(it) }
    }
}


@TypeConverters(Converters::class)
@Database(
    entities = [Up::class, Video::class, Watch::class, QueueItem::class], version = 6, exportSchema = false)
abstract class BiliDb : RoomDatabase() {
    abstract fun upDao(): UpDao
    abstract fun videoDao(): VideoDao
    abstract fun watchDao(): WatchDao
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile private var I: BiliDb? = null
        fun get(c: android.content.Context): BiliDb = I ?: synchronized(this) {
            I ?: Room.databaseBuilder(c.applicationContext, BiliDb::class.java, "bililite.db")
                .fallbackToDestructiveMigration()
                .build().also { I = it }
        }
    }
}

@Dao interface UpDao {
    @Query("SELECT * FROM upmain") suspend fun all(): List<Up>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(u: Up)
    @Query("DELETE FROM upmain WHERE id=:id") suspend fun delete(id: String)
}
@Dao interface VideoDao {
    @Query("SELECT * FROM video WHERE upId IN (:ups)") suspend fun byUps(ups: List<String>): List<Video>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(v: List<Video>)
    @Query("DELETE FROM video WHERE upId=:upId") suspend fun deleteByUp(upId: String)
    @Query("UPDATE video SET favorite=:fav WHERE id=:id") suspend fun setFavorite(id: Long, fav: Boolean)
    @Query("SELECT * FROM video WHERE favorite=1 ORDER BY id DESC") suspend fun favorites(): List<Video>
}
@Dao interface WatchDao {
    @Query("SELECT * FROM watch WHERE videoId=:id") suspend fun get(id: Long): Watch?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(w: Watch)
    @Query("SELECT * FROM watch") suspend fun all(): List<Watch>
    @Query("SELECT * FROM watch ORDER BY startedAt DESC") suspend fun history(): List<Watch>
    @Query("DELETE FROM watch") suspend fun clear()
}
@Dao interface QueueDao {
    @Query("SELECT * FROM queue_item ORDER BY addedAt") suspend fun all(): List<QueueItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(q: QueueItem)
    @Query("DELETE FROM queue_item WHERE videoId=:id") suspend fun delete(id: Long)
}
