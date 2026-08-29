package com.bililite.ui

import com.bililite.app.BILICARD
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bililite.core.BiliApi
import com.bililite.core.LoginSession
import com.bililite.plugin.PluginRuntime
import com.bililite.core.C
import com.bililite.core.BiliTheme
import com.bililite.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- ViewModel ----------
class BiliViewModel(private val db: BiliDb, private val ctx: Context) : ViewModel() {
    val api = BiliApi(ctx).apply {
        assignCookie(LoginSession.cookieString(ctx))
        dedeUserId = LoginSession.dedeUserId(ctx)
    }
    // v0.4.1 离线缓存状态(必须在 init 块之前声明,否则 loadCached() 访问未初始化委托导致闪退)
    data class CachedVideo(val bvid: String, val cid: Long, val title: String, val partTitle: String, val path: String, val size: Long)
    // v0.4.19: 缓存任务(队列)。status: 排队/下载中/完成/失败;progress 0..100;qn 目标清晰度
    data class CacheTask(
        val bvid: String, val cid: Long, val title: String, val partTitle: String, val qn: Int = 64,
        var status: String = "排队中", var progress: Int = 0, var done: Long = 0, var total: Long = 0
    )
    var cachedVideos by mutableStateOf<List<CachedVideo>>(emptyList()); private set
    var cacheTasks by mutableStateOf<List<CacheTask>>(emptyList()); private set
    var caching by mutableStateOf(false); private set
    var cacheMsg by mutableStateOf(""); private set
    private var cacheQueueRunner: kotlinx.coroutines.Job? = null
    var ups by mutableStateOf<List<Up>>(emptyList()); private set
    var vids by mutableStateOf<List<Video>>(emptyList()); private set
    var feedVids by mutableStateOf<List<Video>>(emptyList()); private set  // 首页稳定随机顺序
    var favs by mutableStateOf<List<Video>>(emptyList()); private set        // 收藏列表
    var history by mutableStateOf<List<Watch>>(emptyList()); private set      // 播放历史
    var syncing by mutableStateOf(false); private set
    // 首页 UI 状态(提升到 VM,退出播放后保留排序/搜索/筛选)
    var homeSortMode by mutableStateOf(0)                 // 0 综合 1 播放量 2 按时间
    var homeKeyword by mutableStateOf("")                 // 已提交搜索词
    var homeBvidMode by mutableStateOf(false)             // 是否在 bvid 结果页
    var homeFilterMids by mutableStateOf<Set<String>>(emptySet())  // UP 筛选
    var watchFilter by mutableStateOf(0)                  // v0.3.1: 0 全部 1 未看 2 已看
    var msg by mutableStateOf(""); private set
    var uname by mutableStateOf(LoginSession.uname(ctx)); private set
    var face by mutableStateOf(LoginSession.face(ctx)); private set
    var sign by mutableStateOf(LoginSession.sign(ctx)); private set

    /** 拉取真实用户资料(头像/用户名/签名),失败时用缓存 */
    fun loadProfile() {
        if (uname.isNotBlank()) return  // 已有缓存,不再重复请求
        viewModelScope.launch {
            try {
                val nav = withContext(Dispatchers.IO) { api.nav().optJSONObject("data") }
                val n = nav?.optString("uname") ?: ""
                if (n.isNotEmpty()) {
                    val f = nav.optString("face", "")
                    val s = nav.optString("sign", "")
                    LoginSession.setProfile(ctx, n, f, s)
                    uname = n; face = f; sign = s
                }
            } catch (_: Exception) { /* 无网时保持缓存 */ }
        }
    }

    init {
        viewModelScope.launch {
            try { reload() } catch (_: Exception) { /* 数据库异常不崩主界面 */ }
            // 启动自动检查 UP 更新
            if (ups.isNotEmpty()) checkUpdates()
        }
        loadProfile()
        loadCached()
    }

    /** v0.4.6: 登出→重登后重置登录态并重载数据(避免复用旧账号的 cookie 与列表) */
    fun rebind() {
        api.assignCookie(LoginSession.cookieString(ctx))
        api.dedeUserId = LoginSession.dedeUserId(ctx)
        uname = LoginSession.uname(ctx)
        face = LoginSession.face(ctx)
        sign = LoginSession.sign(ctx)
        ups = emptyList(); vids = emptyList(); feedVids = emptyList()
        favs = emptyList(); history = emptyList(); bookmarks = emptyList()
        watchedIds = emptySet()
        lastFeedIds = emptySet()
        viewModelScope.launch {
            try { reload() } catch (_: Exception) {}
            loadProfile()
        }
    }

    suspend fun reload() {
        val u = withContext(Dispatchers.IO) { db.upDao().all() }
        ups = u
        val v = if (u.isEmpty()) emptyList()
                else withContext(Dispatchers.IO) { db.videoDao().byUps(u.map { it.id }) }
        vids = v
        // 稳定随机顺序:仅当视频集合真正变化时才重新打乱(切 tab/回首页/重载不重排,
        // 这是"退出播放回到原位置"的前提)
        val newIds = v.map { it.id }.toHashSet()
        if (newIds != lastFeedIds) {
            feedVids = v.shuffled()
            lastFeedIds = newIds
        }
        // 同步收藏列表
        favs = withContext(Dispatchers.IO) { db.videoDao().favorites() }
        // 同步书签列表(持久化,App 启动即加载)
        bookmarks = withContext(Dispatchers.IO) { db.bookmarkDao().all() }
        // v0.3.1: 同步已看标记
        refreshWatched()
    }

    /** 上次 feed 的视频 id 集合(用于判断"视频集合是否真正变化") */
    private var lastFeedIds: Set<Long> = emptySet()

    /** 懒解析分P的 cid(播放前用),失败返回 0。 */
    suspend fun resolveCid(bvid: String): Long {
        val arr = withContext(Dispatchers.IO) { api.pagelist(bvid) }
        val first = arr.optJSONObject(0)
        return first?.optLong("cid", 0L) ?: 0L
    }

    /** 从真实 API 拉取 UP 的全部视频并入库(参考 space/wbi/arc/search,自动翻页)。带重试应对偶发风控。
     *  v0.4.6: try/finally 复位 syncing,避免 reload 抛异常导致永久卡"同步中"。 */
    fun syncUp(mid: String, name: String) {
        if (syncing) return
        syncing = true; msg = "同步 $name …"
        viewModelScope.launch {
            try {
                var lastErr = ""
                var all = emptyList<Video>()
                var ok = false
                for (attempt in 1..3) {
                    try {
                        all = withContext(Dispatchers.IO) { fetchAllVideos(mid) }
                        ok = true; break
                    } catch (e: Exception) {
                        lastErr = e.message ?: "网络错误"
                        // 412/风控偶发:短暂等待后重试
                        if (attempt < 3) kotlinx.coroutines.delay(1200L * attempt)
                    }
                }
                if (!ok) { msg = "同步失败: $lastErr"; return@launch }
                if (all.isEmpty()) {
                    msg = "该 UP 暂无公开视频"
                } else {
                    val merged = withContext(Dispatchers.IO) { mergeFavorites(all) }
                    withContext(Dispatchers.IO) { db.videoDao().upsertAll(merged) }
                    reload()
                    msg = "已同步 ${all.size} 个视频"
                }
            } catch (e: Exception) {
                com.bililite.core.BiliLog.e("Sync", "同步 UP $mid 失败: ${e.message}", e)
                msg = "同步失败: ${e.message ?: "未知错误"}"
            } finally {
                syncing = false
            }
        }
    }

    var checking by mutableStateOf(false); private set
    var newCount by mutableStateOf(0); private set

    /** 启动时静默检查所有已添加 UP 是否有新视频并入库。 */
    fun checkUpdates() {
        if (checking) return
        checking = true
        viewModelScope.launch {
            try {
                val us = ups
                var added = 0
                for (u in us) {
                    // v0.3.1 性能优化:启动检查只取每个 UP 最新 5 页(150 条),
                    // 足以覆盖新视频,避免全量 60 页同步导致启动后长时间卡顿
                    val fresh = withContext(Dispatchers.IO) { fetchAllVideos(u.id, maxPages = 5) }
                    if (fresh.isNotEmpty()) {
                        val existing = withContext(Dispatchers.IO) { db.videoDao().byUps(listOf(u.id)) }
                        val knownIds = existing.map { it.id }.toSet()
                        val newOnes = fresh.filter { it.id !in knownIds }
                        added += newOnes.size
                        val merged = withContext(Dispatchers.IO) { mergeFavorites(fresh) }
                        withContext(Dispatchers.IO) { db.videoDao().upsertAll(merged) }
                    }
                }
                newCount = added
                reload()
            } catch (_: Exception) { /* 静默失败 */ }
            finally { checking = false }
        }
    }

    /** 入库前保留已有的收藏标记(避免 REPLACE 把 favorite 重置为 false)。 */
    private suspend fun mergeFavorites(fresh: List<Video>): List<Video> {
        val favIds = db.videoDao().favoriteIds().toSet()
        if (favIds.isEmpty()) return fresh
        return fresh.map { if (it.id in favIds) it.copy(favorite = true) else it }
    }
    private suspend fun fetchAllVideos(mid: String, maxPages: Int = 60): List<Video> {
        val out = ArrayList<Video>()
        var pn = 1
        var useWbi = false      // 默认走 arc/list(稳定)
        var fallbackTried = false
        while (pn <= maxPages) {  // 安全上限
            val j = try {
                if (useWbi) api.userVideos(mid.toLongOrNull() ?: 0L, pn)
                else api.userVideosNoWbi(mid.toLongOrNull() ?: 0L, pn)
            } catch (e: Exception) {
                if (!useWbi && !fallbackTried) {
                    useWbi = true; fallbackTried = true
                    continue
                }
                throw e
            }
            val code = j.optInt("code", 0)
            if (code != 0) {
                // arc/list 偶发业务错误:回退 arc/search
                if (!useWbi && !fallbackTried) { useWbi = true; fallbackTried = true; continue }
                throw Exception(j.optString("message", "code=$code"))
            }
            val data = j.optJSONObject("data")
            // arc/search → data.list.vlist; arc/list → data.archives(平铺数组) 或 data.vlist
            val list = data?.optJSONObject("list")?.optJSONArray("vlist")
                ?: data?.optJSONArray("archives")
                ?: data?.optJSONArray("vlist")
            val total = data?.optJSONObject("page")?.optInt("count", 0)
                ?: data?.optInt("count", 0)
                ?: data?.optInt("total_count", 0) ?: 0
            if (list == null || list.length() == 0) break
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val bvid = o.optString("bvid", "")
                val aid = o.optLong("aid", bvid.hashCode().toLong())
                val title = o.optString("title", "").replace("<em class=\"keyword\">", "").replace("</em>", "")
                // duration 可能是秒数(number)或 mm:ss 字符串
                val dur = if (o.has("duration")) o.optInt("duration", 0)
                          else lengthToSec(o.optString("length", ""))
                // 分P数: videos(number) 或 pages(数组)
                val pages = if (o.has("videos")) o.optInt("videos", 1)
                            else o.optJSONArray("pages")?.length() ?: 1
                out.add(Video(
                    id = aid, bvid = bvid, upId = mid, title = title,
                    durationSec = dur, pages = pages,
                    pic = normalizeCover(o.optString("pic", o.optString("cover", ""))),
                    playCount = o.optJSONObject("stat")?.optLong("view", 0L)
                        ?: o.optLong("play", 0L),
                    pubdate = o.optLong("pubdate", 0L)
                ))
            }
            if (out.size >= total || list.length() < 30) break
            pn++
        }
        return out
    }

    fun addUpAndSync(up: Up) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { db.upDao().upsert(up) }
            } catch (e: Exception) {
                msg = "添加失败: ${e.message ?: "数据库错误"}"
                return@launch
            }
            syncUp(up.id, up.name)
        }
    }

    // ---------- 搜索真实 UP(带粉丝数,防选错) ----------
    private var _search = mutableStateOf<List<Up>>(emptyList())
    var searchResults: List<Up> get() = _search.value; set(v) { _search.value = v }
    var searching by mutableStateOf(false); private set

    // ---------- 按 bvid 搜索(唯一视频码) ----------
    private var _bvidResult = mutableStateOf<Video?>(null)
    var bvidResult: Video? get() = _bvidResult.value; set(v) { _bvidResult.value = v }

    /** 通过 bvid(形如 BV1xx 或其中的唯一段)查找视频。 */
    fun searchByBvid(input: String) {
        val bvid = normalizeBvid(input.trim())
        if (bvid.isEmpty()) { msg = "请输入正确的视频码(如 BV 开头)"; return }
        searching = true; msg = "查找视频…"
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { api.videoView(bvid) }.optJSONObject("data")
                if (data == null) {
                    msg = "未找到该视频"; _bvidResult.value = null
                } else {
                    val owner = data.optJSONObject("owner")
                    val v = Video(
                        id = data.optLong("aid", 0L),
                        bvid = data.optString("bvid", bvid),
                        cid = data.optLong("cid", 0L),
                        upId = owner?.optString("mid", "") ?: "",
                        title = data.optString("title", ""),
                        durationSec = data.optInt("duration", 0),
                        pages = data.optJSONArray("pages")?.length() ?: 1,
                        pic = normalizeCover(data.optString("pic", ""))
                    )
                    _bvidResult.value = v
                    msg = "找到: ${v.title}"
                }
            } catch (e: Exception) {
                msg = "查找失败: ${e.message ?: "网络错误"}"
            } finally { searching = false }
        }
    }

    fun searchUps(keyword: String) {
        val kw = keyword.trim(); if (kw.isEmpty()) return
        searching = true; msg = "搜索…"
        viewModelScope.launch {
            try {
                val arr = withContext(Dispatchers.IO) { api.searchUser(kw) }
                val list = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i); if (o == null) null else {
                        Up(id = o.optString("mid", ""),
                           name = o.optString("uname", "?").replace("<em class=\"keyword\">", "").replace("</em>", ""),
                           fans = o.optLong("fans", 0L),
                           face = normalizeCover(o.optString("upic", o.optString("face", ""))))
                    }
                }
                searchResults = list
                msg = if (list.isEmpty()) "无结果" else "找到 ${list.size} 个 UP"
            } catch (e: Exception) {
                msg = "搜索失败: ${e.message ?: "网络错误"}"
            } finally { searching = false }
        }
    }

    fun removeUp(mid: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.upDao().delete(mid)
                db.videoDao().deleteByUp(mid)
            }
            reload()
        }
    }

    /** 切换收藏状态(不触发 feed 重排) */
    fun toggleFavorite(video: Video, category: String = "") {
        val newFav = !video.favorite
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.videoDao().setFavorite(video.id, newFav)
                if (newFav && category.isNotEmpty()) db.videoDao().setFavCategory(video.id, category)
            }
            // 原位更新 vids / feedVids 的 favorite 标记,不 shuffle
            val upd: (Video) -> Video = { if (it.id == video.id) it.copy(favorite = newFav, favCategory = if (newFav && category.isNotEmpty()) category else if (newFav) it.favCategory else "") else it }
            vids = vids.map(upd)
            feedVids = feedVids.map(upd)
            // 更新收藏列表
            favs = if (newFav) (favs + video.copy(favorite = true, favCategory = category.ifEmpty { video.favCategory })).distinctBy { it.id }
                    else favs.filter { it.id != video.id }
        }
    }

    /** 修改已收藏视频的分类 */
    fun setFavCategory(video: Video, category: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.videoDao().setFavCategory(video.id, category) }
            val upd: (Video) -> Video = { if (it.id == video.id) it.copy(favCategory = category) else it }
            vids = vids.map(upd)
            feedVids = feedVids.map(upd)
            favs = favs.map(upd)
        }
    }

    /** 已使用的收藏分类(去重排序,""归为"未分类") */
    val favCategories: List<String>
        get() = favs.map { it.favCategory.ifBlank { "未分类" } }.distinct().sorted()

    /** 加载收藏列表 */
    fun loadFavorites() {
        viewModelScope.launch {
            favs = withContext(Dispatchers.IO) { db.videoDao().favorites() }
        }
    }

    // ---------- UP 分组 ----------
    /** 已设置的 UP 分组名(去重排序) */
    val upGroups: List<String>
        get() = ups.mapNotNull { it.grp.takeIf { g -> g.isNotBlank() } }.distinct().sorted()

    /** 设置/清除某 UP 的分组 */
    fun setUpGroup(up: Up, group: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.upDao().upsert(up.copy(grp = group.trim())) }
            reload()
        }
    }

    // ---------- 播放历史 / 断点续播 ----------
    /** 上次进度落库时间(节流:高频进度回调只在间隔≥4s 时才写库,避免 IO 风暴导致全局卡顿) */
    private var lastProgressWriteAt = 0L

    /** 记录播放进度(secs=已播秒数,durSec=总时长秒数,cid=当前分P)。
     *  v0.3 性能修复:高频调用时①节流写库 ②不再每次全量刷新 history 列表
     *  (原实现每 500ms 读一次全表并替换 state,是"极其卡顿"的主因)。
     *  v0.3.1: 播放≥90% 自动标记"已看"。 */
    fun recordProgress(videoId: Long, secs: Long, durSec: Int, cid: Long = 0) {
        val now = System.currentTimeMillis()
        if (now - lastProgressWriteAt < 4000) return
        lastProgressWriteAt = now
        viewModelScope.launch {
            val progress = if (durSec > 0) ((secs * 100) / durSec).toInt().coerceIn(0, 100) else 0
            val learned = progress >= 90
            // v0.4.6: 保留首次观看时间(原来每次覆盖 startedAt,历史排序漂移)
            val startedAt = withContext(Dispatchers.IO) { db.watchDao().get(videoId)?.startedAt }
                ?: System.currentTimeMillis()
            val w = Watch(videoId = videoId, cid = cid, progress = progress, secs = secs,
                learned = learned, startedAt = startedAt)
            withContext(Dispatchers.IO) { db.watchDao().upsert(w) }
            if (learned) refreshWatched()
        }
    }

    /** 退出播放/切后台时强制落库(不受节流限制)。 */
    fun flushProgress(videoId: Long, secs: Long, durSec: Int, cid: Long = 0) {
        lastProgressWriteAt = 0
        viewModelScope.launch {
            val progress = if (durSec > 0) ((secs * 100) / durSec).toInt().coerceIn(0, 100) else 0
            val startedAt = withContext(Dispatchers.IO) { db.watchDao().get(videoId)?.startedAt }
                ?: System.currentTimeMillis()
            val w = Watch(videoId = videoId, cid = cid, progress = progress, secs = secs,
                learned = progress >= 90, startedAt = startedAt)
            withContext(Dispatchers.IO) { db.watchDao().upsert(w) }
            // 历史列表此时刷新一次即可
            history = withContext(Dispatchers.IO) { db.watchDao().history() }
            refreshWatched()
        }
    }

    /** 已看视频 id 集合(v0.3.1,用于列表标记与筛选) */
    var watchedIds by mutableStateOf<Set<Long>>(emptySet()); private set

    private suspend fun refreshWatched() {
        val ids = withContext(Dispatchers.IO) { db.watchDao().learnedIds() }
        if (ids.toSet() != watchedIds) watchedIds = ids.toSet()
    }

    /** 手动标记已看/未看 */
    fun setWatched(videoId: Long, watched: Boolean) {
        viewModelScope.launch {
            val w = withContext(Dispatchers.IO) { db.watchDao().get(videoId) }
                ?: Watch(videoId = videoId, progress = if (watched) 100 else 0)
            withContext(Dispatchers.IO) {
                db.watchDao().upsert(w.copy(learned = watched, progress = if (watched) 100 else w.progress))
            }
            refreshWatched()
        }
    }

    /** 取某视频上次进度(秒),无记录返回 0 */
    suspend fun lastPosition(videoId: Long): Long =
        withContext(Dispatchers.IO) { db.watchDao().get(videoId)?.secs ?: 0L }

    /** v0.4.15: 上次播放的分P cid(用于重新进入时恢复上次的分P,而非回到第一P) */
    suspend fun lastWatchCid(videoId: Long): Long =
        withContext(Dispatchers.IO) { db.watchDao().get(videoId)?.cid ?: 0L }

    /** 取某视频上次观看记录(含 cid + secs),无记录返回 null */
    suspend fun lastWatch(videoId: Long): Watch? =
        withContext(Dispatchers.IO) { db.watchDao().get(videoId) }

    /** 加载历史列表 */
    fun loadHistory() {
        viewModelScope.launch {
            history = withContext(Dispatchers.IO) { db.watchDao().history() }
        }
    }

    /** 清空历史 */
    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.watchDao().clear() }
            history = emptyList()
        }
    }

    // ---------- 视频书签 ----------
    var bookmarks by mutableStateOf<List<Bookmark>>(emptyList()); private set

    /** 加载全部书签 */
    fun loadBookmarks() {
        viewModelScope.launch {
            bookmarks = withContext(Dispatchers.IO) { db.bookmarkDao().all() }
        }
    }

    /** 打书签(返回新书签 id) */
    fun addBookmark(bvid: String, videoTitle: String, cid: Long, pageIndex: Int, pageTitle: String, timeSec: Long) {
        viewModelScope.launch {
            val b = Bookmark(bvid = bvid, videoTitle = videoTitle, cid = cid,
                pageIndex = pageIndex, pageTitle = pageTitle, timeSec = timeSec)
            withContext(Dispatchers.IO) { db.bookmarkDao().upsert(b) }
            bookmarks = withContext(Dispatchers.IO) { db.bookmarkDao().all() }
        }
    }

    /** 重命名书签 */
    fun renameBookmark(id: Long, note: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.bookmarkDao().rename(id, note) }
            bookmarks = withContext(Dispatchers.IO) { db.bookmarkDao().all() }
        }
    }

    /** 删除书签 */
    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.bookmarkDao().delete(id) }
            bookmarks = withContext(Dispatchers.IO) { db.bookmarkDao().all() }
        }
    }

    // ---------- UP 主合集(seasons/series) ----------
    /** 合集条目:type=season(合集) / series(系列) */
    data class SeasonInfo(val seasonId: Long, val title: String, val total: Int, val type: String = "season")

    var seasons by mutableStateOf<List<SeasonInfo>>(emptyList()); private set
    var seasonsLoading by mutableStateOf(false); private set
    var seasonsOwnerMid: String = ""; private set
    var seasonsOwnerName: String = ""; private set

    /** 拉取某 UP 的合集+系列列表 */
    fun loadSeasons(mid: String, upName: String) {
        seasonsOwnerMid = mid; seasonsOwnerName = upName
        seasons = emptyList(); seasonsLoading = true
        viewModelScope.launch {
            try {
                val j = withContext(Dispatchers.IO) { api.seasonsSeriesList(mid.toLongOrNull() ?: 0L) }
                if (j.optInt("code", -1) != 0) throw Exception(j.optString("message", "无数据"))
                // v0.4.4 修复:正确结构是 data.items_lists.{seasons_list,series_list},
                // 每个元素带 meta{season_id/series_id,name,total}。之前误解析为 seasons_lists[].seasons[] 导致"暂无合集"。
                val items = j.optJSONObject("data")?.optJSONObject("items_lists")
                    ?: throw Exception("无数据")
                val out = ArrayList<SeasonInfo>()
                val seasonsList = items.optJSONArray("seasons_list")
                if (seasonsList != null) for (i in 0 until seasonsList.length()) {
                    val meta = seasonsList.optJSONObject(i)?.optJSONObject("meta") ?: continue
                    val id = meta.optLong("season_id", 0L)
                    if (id != 0L) out.add(SeasonInfo(id, meta.optString("name", "未命名合集"), meta.optInt("total", 0), "season"))
                }
                val seriesList = items.optJSONArray("series_list")
                if (seriesList != null) for (i in 0 until seriesList.length()) {
                    val meta = seriesList.optJSONObject(i)?.optJSONObject("meta") ?: continue
                    val id = meta.optLong("series_id", 0L)
                    if (id != 0L) out.add(SeasonInfo(id, meta.optString("name", "未命名系列"), meta.optInt("total", 0), "series"))
                }
                seasons = out
            } catch (e: Exception) {
                msg = "合集加载失败: ${e.message ?: "网络错误"}"
            } finally { seasonsLoading = false }
        }
    }

    var seasonVideos by mutableStateOf<List<Video>>(emptyList()); private set
    var seasonVideosLoading by mutableStateOf(false); private set
    var seasonTitle by mutableStateOf(""); private set

    /** 拉取某合集/系列的视频列表(自动翻页,作为连播列表)。type=season|series */
    fun loadSeasonVideos(seasonId: Long, title: String, type: String = "season", onDone: () -> Unit = {}) {
        seasonTitle = title; seasonVideos = emptyList(); seasonVideosLoading = true
        viewModelScope.launch {
            try {
                val out = ArrayList<Video>()
                var pn = 1
                while (pn <= 20) {  // 安全上限
                    // v0.4.4:系列走 /x/series/archives,合集走 seasons_archives_list(之前系列也用 season_id,导致系列为空)
                    val j = withContext(Dispatchers.IO) {
                        if (type == "series") api.seriesArchivesList(seasonId, pn)
                        else api.seasonArchivesList(seasonId, pn)
                    }
                    if (j.optInt("code", -1) != 0) throw Exception(j.optString("message", "加载失败"))
                    val d = j.optJSONObject("data") ?: break
                    val arr = d.optJSONArray("archives") ?: break
                    if (arr.length() == 0) break
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val bvid = o.optString("bvid", "")
                        if (bvid.isEmpty()) continue
                        out.add(Video(
                            id = o.optLong("aid", bvid.hashCode().toLong()),
                            bvid = bvid, upId = seasonsOwnerMid,
                            title = o.optString("title", ""),
                            durationSec = o.optInt("duration", 0),
                            pic = normalizeCover(o.optString("pic", "")),
                            pubdate = o.optLong("pubdate", 0L)))
                    }
                    val page = d.optJSONObject("page")
                    val total = page?.optInt("total", 0) ?: 0
                    val size = page?.optInt("page_size", page?.optInt("size", 30) ?: 30) ?: 30
                    if (total > 0 && out.size >= total) break
                    if (arr.length() < size) break
                    pn++
                }
                seasonVideos = out
            } catch (e: Exception) {
                msg = "合集视频加载失败: ${e.message ?: "网络错误"}"
            } finally { seasonVideosLoading = false; onDone() }
        }
    }

    // ---------- 云端收藏夹(B 站账号) ----------
    data class FavFolder(val id: Long, val title: String, val count: Int)

    var favFolders by mutableStateOf<List<FavFolder>>(emptyList()); private set
    var favFoldersLoading by mutableStateOf(false); private set

    private val favPrefs get() = ctx.getSharedPreferences("bililite_bili_fav", Context.MODE_PRIVATE)

    /** 读收藏夹列表的本地缓存(上次同步成功的),避免每次打开都请求导致风控。 */
    private fun loadFavFoldersCache(): List<FavFolder> {
        val s = favPrefs.getString("folders", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                FavFolder(o.optLong("id", 0L), o.optString("title", "未命名"), o.optInt("count", 0))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveFavFoldersCache(list: List<FavFolder>) {
        val arr = org.json.JSONArray()
        list.forEach { f ->
            arr.put(org.json.JSONObject().apply {
                put("id", f.id); put("title", f.title); put("count", f.count)
            })
        }
        favPrefs.edit().putString("folders", arr.toString()).apply()
    }

    fun loadFavFolders() {
        favFoldersLoading = true
        // 先显示本地缓存(若有),再后台刷新,避免风控时看不到任何内容
        val cached = loadFavFoldersCache()
        if (cached.isNotEmpty()) favFolders = cached
        viewModelScope.launch {
            try {
                val j = withContext(Dispatchers.IO) { api.favFolders() }
                if (j.optInt("code", -1) != 0) throw Exception(j.optString("message", "请先登录"))
                val arr = j.optJSONObject("data")?.optJSONArray("list")
                    ?: throw Exception("未获取到收藏夹")
                val list = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    FavFolder(o.optLong("id", 0L), o.optString("title", "未命名"), o.optInt("media_count", 0))
                }
                favFolders = list
                saveFavFoldersCache(list)   // 同步成功 → 刷新缓存
            } catch (e: Exception) {
                // 风控/网络失败:保留缓存(若缓存为空才提示)
                if (favFolders.isEmpty()) msg = "收藏夹加载失败: ${e.message ?: "网络错误"}"
            } finally { favFoldersLoading = false }
        }
    }

    var favFolderVideos by mutableStateOf<List<Video>>(emptyList()); private set
    var favFolderVideosLoading by mutableStateOf(false); private set
    var favFolderTitle by mutableStateOf(""); private set

    /** 读某收藏夹视频的本地缓存(上次同步成功),风控时兜底显示 */
    private fun loadFavVideosCache(mediaId: Long): List<Video> {
        val s = favPrefs.getString("videos_$mediaId", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Video(
                    id = o.optLong("id", 0L),
                    bvid = o.optString("bvid", ""),
                    upId = o.optString("upId", ""),
                    title = o.optString("title", ""),
                    durationSec = o.optInt("durationSec", 0),
                    pic = o.optString("pic", ""),
                    pubdate = o.optLong("pubdate", 0L))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveFavVideosCache(mediaId: Long, list: List<Video>) {
        val arr = org.json.JSONArray()
        list.forEach { v ->
            arr.put(org.json.JSONObject().apply {
                put("id", v.id); put("bvid", v.bvid); put("upId", v.upId)
                put("title", v.title); put("durationSec", v.durationSec)
                put("pic", v.pic); put("pubdate", v.pubdate)
            })
        }
        favPrefs.edit().putString("videos_$mediaId", arr.toString()).apply()
    }

    /** 拉取某云端收藏夹的视频(自动翻页)。
     *  v0.4.19 修复:参考 fetchAllVideos 的翻页方式——用收藏夹总数 media_count 作为终止条件,
     *  而非仅依赖 has_more(某些接口/账号下 has_more 不可靠导致只拉一页)。
     *  v0.4.20 持久化:先显示本地缓存,再后台刷新;风控/失败时保留缓存兜底。 */
    fun loadFavFolderVideos(mediaId: Long, title: String, total: Int = 0) {
        favFolderTitle = title; favFolderVideosLoading = true
        val cached = loadFavVideosCache(mediaId)
        if (cached.isNotEmpty()) favFolderVideos = cached   // 先显示缓存
        else favFolderVideos = emptyList()
        viewModelScope.launch {
            try {
                val out = ArrayList<Video>()
                var pn = 1
                var totalCount = total  // media_count(已知总数),0=未知
                while (pn <= 500) {
                    val j = withContext(Dispatchers.IO) { api.favResourceList(mediaId, pn) }
                    if (j.optInt("code", -1) != 0) throw Exception(j.optString("message", "加载失败"))
                    val d = j.optJSONObject("data") ?: break
                    val arr = d.optJSONArray("medias") ?: break
                    if (arr.length() == 0) break
                    // 若调用时未传总数,从本页 data 里就地读取 media_count 兜底
                    if (totalCount <= 0) {
                        val mc = d.optJSONObject("info")?.optInt("media_count", 0) ?: 0
                        if (mc > 0) totalCount = mc
                    }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val bvid = o.optString("bvid", "")
                        if (bvid.isEmpty()) continue   // 失效视频(bvid 空)
                        out.add(Video(
                            id = o.optLong("id", bvid.hashCode().toLong()),
                            bvid = bvid,
                            upId = o.optJSONObject("upper")?.optString("mid", "") ?: "",
                            title = o.optString("title", ""),
                            durationSec = o.optInt("duration", 0),
                            pic = normalizeCover(o.optString("cover", "")),
                            pubdate = o.optLong("pubtime", 0L)))
                    }
                    // 终止条件(修复 600 只加载 39 的 bug):
                    // ① 已取满总数(media_count)→ 停(最可靠,不依赖 has_more)
                    if (totalCount > 0 && out.size >= totalCount) break
                    // ② has_more=false → 停(字段在 data 顶层)
                    val hasNext = d.optBoolean("has_more", false)
                    // ③ 若本页原始条数为 0 才停(去掉 arr.length()<20 的误伤判断——
                    //    失效视频被过滤后本页可能 <20 但仍有下一页)
                    if (!hasNext) break
                    pn++
                }
                favFolderVideos = out
                saveFavVideosCache(mediaId, out)   // 同步成功 → 刷新缓存
            } catch (e: Exception) {
                // 风控/网络失败:保留已显示的缓存(仅当无缓存才提示)
                if (favFolderVideos.isEmpty()) msg = "收藏夹内容加载失败: ${e.message ?: "网络错误"}"
            } finally { favFolderVideosLoading = false }
        }
    }

    // ---------- 离线缓存(v0.4.1,刚需) ----------
    private val cachePrefs get() = ctx.getSharedPreferences("bililite_cache", Context.MODE_PRIVATE)

    fun loadCached() {
        val s = cachePrefs.getString("list", "[]") ?: "[]"
        cachedVideos = try {
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CachedVideo(o.optString("bvid"), o.optLong("cid"), o.optString("title"),
                    o.optString("partTitle", ""), o.optString("path"), o.optLong("size"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveCached(list: List<CachedVideo>) {
        val arr = org.json.JSONArray()
        list.forEach { c ->
            arr.put(org.json.JSONObject().apply {
                put("bvid", c.bvid); put("cid", c.cid); put("title", c.title)
                put("partTitle", c.partTitle); put("path", c.path); put("size", c.size)
            })
        }
        cachePrefs.edit().putString("list", arr.toString()).apply()
        cachedVideos = list
    }

    /** 当前视频(分P)是否有缓存,有则返回本地文件路径。cid=0(未知)时按 bvid 匹配单P缓存 */
    fun cachedPath(bvid: String, cid: Long): String? =
        cachedVideos.firstOrNull { it.bvid == bvid && it.cid == cid }
            ?.path?.takeIf { java.io.File(it).exists() }

    /** 缓存当前视频(v0.4.19 队列化:加入队列,逐个下载,每个任务带独立进度)。qn 为目标清晰度。
     *  v0.4.20: 按 bvid+cid 精确去重,让合集中每一P都能独立缓存。 */
    fun cacheVideo(v: Video, cid: Long, qn: Int = 64, partTitle: String = "") {
        // 已缓存(同 bvid 且同 cid)则提示
        if (cachedVideos.any { it.bvid == v.bvid && it.cid == cid }) {
            android.widget.Toast.makeText(ctx, "该分P已缓存", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 已在队列中(同 bvid 且同 cid)则提示
        if (cacheTasks.any { it.bvid == v.bvid && it.cid == cid }) {
            android.widget.Toast.makeText(ctx, "该分P已在缓存队列中", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val task = CacheTask(v.bvid, cid, v.title, partTitle, qn)
        cacheTasks = cacheTasks + task
        cacheMsg = "已加入缓存队列: ${v.title.take(14)}"
        // v0.4.19: 点击缓存给明确反馈(Toast 弹窗)
        android.widget.Toast.makeText(ctx, "已开始缓存: ${v.title.take(14)}", android.widget.Toast.LENGTH_SHORT).show()
        // 让队列继续跑(若有正在运行的任务,新任务会被下一个 while 循环接住)
        if (cacheQueueRunner?.isActive == true) return
        cacheQueueRunner = viewModelScope.launch { processCacheQueue() }
    }

    /** 队列串行下载(队首下载完再下一个),每个任务实时更新 progress/status */
    private suspend fun processCacheQueue() {
        caching = true
        try {
            while (cacheTasks.any { it.status == "排队中" }) {
                val idx = cacheTasks.indexOfFirst { it.status == "排队中" }
                if (idx < 0) break
                val t = cacheTasks[idx]
                updateTask(t.bvid, t.cid) { it.status = "下载中" }
                try {
                    downloadOne(t)
                } catch (e: Exception) {
                    updateTask(t.bvid, t.cid) { it.status = "失败"; it.progress = 0 }
                }
            }
        } finally { caching = false }
    }

    // v0.4.20: 队列按 bvid+cid 精确匹配,让合集每一P独立缓存、互不干扰
    private fun updateTask(bvid: String, cid: Long, mutate: (CacheTask) -> Unit) {
        cacheTasks = cacheTasks.map { task ->
            if (task.bvid == bvid && task.cid == cid) task.copy().also(mutate) else task
        }
    }

    /** 下载单个任务,下载完成写入 cachedVideos(按 bvid+cid 隔离) */
    private suspend fun downloadOne(t: CacheTask) {
        var realCid = t.cid
        var partTitle = t.partTitle
        if (realCid == 0L) {
            realCid = withContext(Dispatchers.IO) {
                api.pagelist(t.bvid).optJSONObject(0)?.optLong("cid", 0L) ?: 0L
            }
            if (realCid == 0L) { updateTask(t.bvid, t.cid) { it.status = "失败" }; return }
        }
        // 分P标题为空时,用 pagelist 查对应 cid 的 part(用于离线缓存列表显示"第N集"等)
        if (partTitle.isBlank()) {
            partTitle = try {
                val arr = withContext(Dispatchers.IO) { api.pagelist(t.bvid) }
                (0 until arr.length()).firstOrNull { arr.optJSONObject(it)?.optLong("cid", 0L) == realCid }
                    ?.let { arr.optJSONObject(it)?.optString("part", "") } ?: ""
            } catch (_: Exception) { "" }
        }
        var stream = withContext(Dispatchers.IO) { api.playStream(t.bvid, realCid, t.qn) }
        var qn = t.qn
        while (stream.audioUrl.isNotEmpty() && qn > 16) {
            qn = if (qn == 64) 32 else 16
            stream = withContext(Dispatchers.IO) { api.playStream(t.bvid, realCid, qn) }
        }
        val u = stream.videoUrl
        if (u.isEmpty()) { updateTask(t.bvid, realCid) { it.status = "失败" }; return }
        if (stream.audioUrl.isNotEmpty()) { updateTask(t.bvid, realCid) { it.status = "失败" }; return }
        val dir = java.io.File(ctx.filesDir, "cache_videos"); dir.mkdirs()
        val f = java.io.File(dir, "${safeBvid(t.bvid)}_${realCid}.mp4")
        val ok = withContext(Dispatchers.IO) {
            api.downloadToFileProgress(u, f) { done, total ->
                updateTask(t.bvid, realCid) { it2 ->
                    it2.done = done; it2.total = total
                    it2.progress = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                }
                // 同步更新播放器顶部提示,让下载进度可见
                val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                cacheMsg = "缓存中 $pct% · ${t.title.take(12)}"
            }
        }
        if (ok) {
            val cur = cachedVideos.toMutableList()
            cur.removeAll { it.bvid == t.bvid && it.cid == realCid }
            cur.add(CachedVideo(t.bvid, realCid, t.title, partTitle, f.absolutePath, f.length()))
            saveCached(cur)
            cacheMsg = "缓存完成 · ${t.title.take(12)}"
        } else {
            f.delete()
            updateTask(t.bvid, realCid) { it.status = "失败"; it.progress = 0 }
            cacheMsg = "缓存失败 · ${t.title.take(12)}"
        }
        // v0.4.20: 完成/失败的任务按 bvid+cid 从队列移除(不影响其他分P)
        cacheTasks = cacheTasks.filter { !(it.bvid == t.bvid && it.cid == realCid) }
        // 结果提示 2.5 秒后自动清除
        kotlinx.coroutines.delay(2500)
        if (cacheMsg.startsWith("缓存完成") || cacheMsg.startsWith("缓存失败")) cacheMsg = ""
    }

    /** 删除单个缓存(v0.4.6: 磁盘 IO 移出主线程) */
    fun deleteCache(path: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { java.io.File(path).delete() }
            saveCached(cachedVideos.filter { it.path != path })
        }
    }

    // ---------- v0.4.4: 多端同步(导出/导入 JSON) ----------

    private fun upToJson(u: Up) = org.json.JSONObject().apply {
        put("id", u.id); put("name", u.name); put("fans", u.fans); put("face", u.face)
        put("tags", org.json.JSONArray(u.tags)); put("grp", u.grp); put("addedAt", u.addedAt)
    }
    private fun videoToJson(v: Video) = org.json.JSONObject().apply {
        put("id", v.id); put("bvid", v.bvid); put("cid", v.cid); put("upId", v.upId)
        put("title", v.title); put("durationSec", v.durationSec); put("pages", v.pages)
        put("series", v.series); put("pic", v.pic); put("playCount", v.playCount)
        put("pubdate", v.pubdate); put("favorite", v.favorite)
        put("favCategory", v.favCategory); put("tags", org.json.JSONArray(v.tags))
    }
    private fun watchToJson(w: Watch) = org.json.JSONObject().apply {
        put("videoId", w.videoId); put("cid", w.cid); put("mode", w.mode)
        put("progress", w.progress); put("summary", w.summary); put("learned", w.learned)
        put("startedAt", w.startedAt); put("secs", w.secs)
    }
    private fun bookmarkToJson(b: Bookmark) = org.json.JSONObject().apply {
        put("id", b.id); put("bvid", b.bvid); put("videoTitle", b.videoTitle); put("cid", b.cid)
        put("pageIndex", b.pageIndex); put("pageTitle", b.pageTitle); put("timeSec", b.timeSec)
        put("note", b.note); put("createdAt", b.createdAt)
    }
    private fun queueToJson(q: QueueItem) = org.json.JSONObject().apply {
        put("videoId", q.videoId); put("status", q.status); put("addedAt", q.addedAt)
    }

    /** 导出全部本地数据(UP/全部视频/收藏标记/已看/书签/队列/缓存元数据/设置)为 JSON 字符串。
     *  v0.4.5 修复:之前只导出收藏,不导出视频库——导入到新设备后首页空无一物,用户以为无效。
     *  现在导出全部视频(含 favorite 标记),导入后首页/收藏/历史完整恢复。 */
    suspend fun exportAllData(): String = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val upsAll = db.upDao().all()
        val allVideos = if (upsAll.isEmpty()) emptyList() else db.videoDao().byUps(upsAll.map { it.id })
        val favVideos = db.videoDao().favorites()
        val watches = db.watchDao().all()
        val bookmarks = db.bookmarkDao().all()
        val queue = db.queueDao().all()
        val prefs = ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE)
        com.bililite.core.BiliLog.i("Export", "开始导出: UP ${upsAll.size} / 视频 ${allVideos.size} / 收藏 ${favVideos.size} / 已看 ${watches.size} / 书签 ${bookmarks.size} / 队列 ${queue.size}")
        val json = org.json.JSONObject().apply {
            put("app", "BiliLite"); put("version", 2)
            put("exportedAt", System.currentTimeMillis())
            put("ups", org.json.JSONArray().apply { upsAll.forEach { put(upToJson(it)) } })
            put("videos", org.json.JSONArray().apply { allVideos.forEach { put(videoToJson(it)) } })
            put("favs", org.json.JSONArray().apply { favVideos.forEach { put(videoToJson(it)) } })
            put("watch", org.json.JSONArray().apply { watches.forEach { put(watchToJson(it)) } })
            put("bookmarks", org.json.JSONArray().apply { bookmarks.forEach { put(bookmarkToJson(it)) } })
            put("queue", org.json.JSONArray().apply { queue.forEach { put(queueToJson(it)) } })
            put("cached", org.json.JSONArray().apply {
                cachedVideos.forEach { put(org.json.JSONObject().apply {
                    put("bvid", it.bvid); put("cid", it.cid); put("title", it.title); put("size", it.size)
                }) }
            })
            put("settings", org.json.JSONObject().apply {
                put("speed", prefs.getFloat("speed", 1.0f))
                put("auto_next", prefs.getBoolean("auto_next", true))
                put("quality", prefs.getInt("quality", 64))
                // v0.4.9: 字幕设置与深色模式随备份导出
                put("sub_size", prefs.getInt("sub_size", 1))
                put("sub_pos", prefs.getInt("sub_pos", 0))
                put("dark", prefs.getBoolean("dark", false))
            })
        }.toString()
        com.bililite.core.BiliLog.i("Export", "导出完成: ${json.length} 字符,耗时 ${System.currentTimeMillis() - t0}ms")
        json
    }

    /** 导入 JSON 数据(合并式:UP/视频/收藏/已看/书签/队列 upsert,设置覆盖)。返回结果提示。
     *  v0.4.5 修复:①导入全部视频(旧备份只有 favs 的仍兼容);②导入后重建首页 feed/收藏/历史;
     *  ③全程日志。 */
    suspend fun importAllData(json: String): String = withContext(Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()
            com.bililite.core.BiliLog.i("Import", "开始导入,文件大小 ${json.length} 字符")
            val root = org.json.JSONObject(json)
            if (root.optString("app") != "BiliLite") return@withContext "不是有效的 BiliLite 备份文件"
            var nUp = 0; var nVideo = 0; var nFav = 0; var nWatch = 0; var nBookmark = 0; var nQueue = 0
            // UP
            val upsArr = root.optJSONArray("ups")
            if (upsArr != null) for (i in 0 until upsArr.length()) {
                val o = upsArr.optJSONObject(i) ?: continue
                val tags = o.optJSONArray("tags")?.let { (0 until it.length()).map { k -> it.optString(k) } } ?: emptyList()
                db.upDao().upsert(Up(
                    id = o.optString("id"), name = o.optString("name"),
                    fans = o.optLong("fans", 0), face = o.optString("face"),
                    tags = tags, grp = o.optString("grp"), addedAt = o.optLong("addedAt", System.currentTimeMillis())))
                nUp++
            }
            // 全部视频(v0.4.5 新增:导入后首页直接可见)。旧备份无 videos 数组则退回 favs。
            val videosArr = root.optJSONArray("videos") ?: root.optJSONArray("favs")
            if (videosArr != null) for (i in 0 until videosArr.length()) {
                val o = videosArr.optJSONObject(i) ?: continue
                val tags = o.optJSONArray("tags")?.let { (0 until it.length()).map { k -> it.optString(k) } } ?: emptyList()
                val fav = o.optBoolean("favorite", true)
                db.videoDao().upsertAll(listOf(Video(
                    id = o.optLong("id"), bvid = o.optString("bvid"), cid = o.optLong("cid", 0),
                    upId = o.optString("upId"), title = o.optString("title"),
                    durationSec = o.optInt("durationSec", 0), pages = o.optInt("pages", 1),
                    series = o.optString("series"), pic = o.optString("pic"),
                    playCount = o.optLong("playCount", 0), pubdate = o.optLong("pubdate", 0),
                    favorite = fav, favCategory = o.optString("favCategory"), tags = tags)))
                nVideo++
                if (fav) nFav++
            }
            // 已看记录
            val watchArr = root.optJSONArray("watch")
            if (watchArr != null) for (i in 0 until watchArr.length()) {
                val o = watchArr.optJSONObject(i) ?: continue
                db.watchDao().upsert(Watch(
                    videoId = o.optLong("videoId"), cid = o.optLong("cid", 0),
                    mode = o.optString("mode", "deep"), progress = o.optInt("progress", 0),
                    summary = o.optString("summary"), learned = o.optBoolean("learned", false),
                    startedAt = o.optLong("startedAt", System.currentTimeMillis()),
                    secs = o.optLong("secs", 0)))
                nWatch++
            }
            // 书签
            val bmArr = root.optJSONArray("bookmarks")
            if (bmArr != null) for (i in 0 until bmArr.length()) {
                val o = bmArr.optJSONObject(i) ?: continue
                db.bookmarkDao().upsert(Bookmark(
                    id = o.optLong("id", 0), bvid = o.optString("bvid"),
                    videoTitle = o.optString("videoTitle"), cid = o.optLong("cid", 0),
                    pageIndex = o.optInt("pageIndex", 0), pageTitle = o.optString("pageTitle"),
                    timeSec = o.optLong("timeSec", 0), note = o.optString("note"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())))
                nBookmark++
            }
            // 队列
            val queueArr = root.optJSONArray("queue")
            if (queueArr != null) for (i in 0 until queueArr.length()) {
                val o = queueArr.optJSONObject(i) ?: continue
                db.queueDao().upsert(QueueItem(
                    videoId = o.optLong("videoId"), status = o.optString("status", "todo"),
                    addedAt = o.optLong("addedAt", System.currentTimeMillis())))
                nQueue++
            }
            // 缓存元数据(仅当本地文件仍存在时有效)
            val cachedArr = root.optJSONArray("cached")
            if (cachedArr != null) {
                val cur = cachedVideos.toMutableList()
                for (i in 0 until cachedArr.length()) {
                    val o = cachedArr.optJSONObject(i) ?: continue
                    // v0.4.17: 清洗 bvid 防路径遍历(外部 JSON 可带 ../ 指向任意本地文件)
                    val bvid = o.optString("bvid"); val cid = o.optLong("cid", 0)
                    val f = java.io.File(ctx.filesDir, "cache_videos/${safeBvid(bvid)}_${cid}.mp4")
                    if (f.exists()) {
                        cur.removeAll { it.bvid == bvid && it.cid == cid }
                        cur.add(CachedVideo(bvid, cid, o.optString("title"), o.optString("partTitle", ""), f.absolutePath, f.length()))
                    }
                }
                saveCached(cur)
            }
            // 设置
            val st = root.optJSONObject("settings")
            if (st != null) {
                ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).edit()
                    .putFloat("speed", st.optDouble("speed", 1.0).toFloat())
                    .putBoolean("auto_next", st.optBoolean("auto_next", true))
                    .putInt("quality", st.optInt("quality", 64))
                    // v0.4.9: 字幕设置与深色模式
                    .putInt("sub_size", st.optInt("sub_size", 1))
                    .putInt("sub_pos", st.optInt("sub_pos", 0))
                    .putBoolean("dark", st.optBoolean("dark", false))
                    .apply()
                // 深色模式立即生效
                BiliTheme.applyDark(st.optBoolean("dark", false))
            }
            reload()
            // v0.4.5: 导入后同步刷新历史(之前 history 不刷新,历史页看不到导入的记录)
            history = db.watchDao().history()
            com.bililite.core.BiliLog.i("Import", "导入完成: UP $nUp / 视频 $nVideo / 收藏 $nFav / 已看 $nWatch / 书签 $nBookmark / 队列 $nQueue,耗时 ${System.currentTimeMillis() - t0}ms")
            "导入完成: UP $nUp / 视频 $nVideo / 收藏 $nFav / 已看 $nWatch / 书签 $nBookmark / 队列 $nQueue"
        } catch (e: Exception) {
            com.bililite.core.BiliLog.e("Import", "导入失败: ${e.message}", e)
            "导入失败: ${e.message ?: "文件格式错误"}"
        }
    }
}

private fun lengthToSec(len: String): Int {
    val parts = len.split(":")
    return if (parts.size == 2) (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
           else parts.firstOrNull()?.toIntOrNull() ?: 0
}

/** 封面 URL 规范化为 https(Android 禁明文 http),并去掉可能的 @ 缩放后缀。 */
private fun normalizeCover(url: String): String {
    if (url.isEmpty()) return ""
    var u = url.trim()
    if (u.startsWith("//")) u = "https:" + u
    else if (u.startsWith("http://")) u = "https://" + u.substringAfter("http://")
    // 去掉 @xxxw_xxxh 缩放后缀以保兼容(可选保留清晰度)
    u = u.substringBefore("@")
    return u
}

/**
 * 生成 B 站 CDN 缩略图 URL(按目标尺寸缩放并转 webp,大幅减小下载体积,修复首页封面加载慢)。
 * B 站 hdslb CDN 支持在图片 URL 后追加 @宽w_高h_清晰度c.webp 动态缩放。
 */
private fun coverThumb(url: String, w: Int, h: Int): String {
    val base = normalizeCover(url)
    if (base.isEmpty()) return ""
    return "$base@${w}w_${h}h_1c.webp"
}

/** 规范化 bvid:接受 BV1xx 或用户只粘了后半段(如 Xa35Sjj),补全成 BV 开头 */
private fun normalizeBvid(input: String): String {
    var s = input.trim()
    if (s.isEmpty()) return ""
    // 去掉可能误带的前缀
    s = s.substringAfterLast("/")
    s = s.substringBefore("?")
    s = s.trim()
    if (s.startsWith("BV")) return s
    // 若只有后半段,尝试补 BV1 前缀(B站 bvid 常见为 BV1 + 9位)
    if (s.length in 6..11 && s.all { it.isLetterOrDigit() }) return "BV1$s"
    return s
}

class BiliVMFactory(private val c: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        BiliViewModel(BiliDb.get(c), c.applicationContext) as T
}

// ---------- 三个 Tab 屏幕 ----------
/** 播放请求:目标视频 + 连播列表(播放完自动播下一个) + 本地缓存路径(离线播放) */
data class PlayReq(val video: Video, val playlist: List<Video> = emptyList(), val localPath: String? = null)

@Composable
fun HomeScreen(vm: BiliViewModel,
               onPlay: (Video, List<Video>) -> Unit = { _, _ -> },
               isTablet: Boolean = false,
               listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
               gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()) {
    var input by remember { mutableStateOf("") }        // 输入框内容(仅临时)
    var showUpPicker by remember { mutableStateOf(false) }
    // v0.4.15: 筛选区(排序/UP/分组/已看)默认收起,点击"筛选"展开,减少首页顶部占用
    var filtersExpanded by remember { mutableStateOf(false) }

    // 判断是否是 bvid 查找 / 关键词搜索
    fun doSearch() {
        val q = input.trim()
        if (q.isEmpty()) return
        // 仅当明确以 BV 开头才走 bvid 查找,其余一律按关键词搜索
        if (q.startsWith("BV", ignoreCase = true)) {
            vm.homeBvidMode = true
            vm.searchByBvid(q)
        } else {
            vm.homeBvidMode = false
            vm.homeKeyword = q
        }
    }

    // UP id -> 名字 映射
    val upName = remember(vm.ups) { vm.ups.associate { it.id to it.name } }
    // UP id -> 分组 映射
    val upGroup = remember(vm.ups) { vm.ups.associate { it.id to it.grp } }

    // v0.3 性能修复:过滤+排序只在相关输入变化时重算(原实现每次重组都全量排序,
    // 搜索框每敲一个字都卡)
    val feed = remember(vm.feedVids, vm.homeFilterMids, vm.homeKeyword, vm.homeSortMode, vm.watchFilter, vm.watchedIds) {
        var base = vm.feedVids
        if (vm.homeFilterMids.isNotEmpty()) base = base.filter { it.upId in vm.homeFilterMids }
        if (vm.homeKeyword.isNotBlank()) {
            val words = vm.homeKeyword.split(Regex("\\s+")).filter { it.isNotBlank() }
            base = base.filter { v -> words.any { w -> v.title.contains(w, ignoreCase = true) } }
        }
        // v0.3.1: 已看/未看筛选
        if (vm.watchFilter == 1) base = base.filter { it.id !in vm.watchedIds }      // 未看
        else if (vm.watchFilter == 2) base = base.filter { it.id in vm.watchedIds }   // 已看
        when (vm.homeSortMode) {
            1 -> base.sortedByDescending { it.playCount }   // 播放量
            2 -> base.sortedByDescending { it.pubdate }     // 按时间(新→旧)
            else -> base                                    // 综合(保持原随机顺序)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 返回手势:若有搜索/排序/筛选,先清空回到"综合",否则交给上层
        BackHandler(enabled = vm.homeSortMode != 0 || vm.homeKeyword.isNotBlank()
            || vm.homeFilterMids.isNotEmpty() || vm.homeBvidMode) {
            vm.homeSortMode = 0
            vm.homeKeyword = ""
            vm.homeBvidMode = false
            vm.homeFilterMids = emptySet()
            vm.bvidResult = null
        }

        // 顶部搜索栏(高度减小 + 圆角)
        Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp, 16.dp, 0.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextField(value = input, onValueChange = { input = it },
                placeholder = { Text("搜索已添加 UP 的视频", color = C.t2, fontSize = 13.sp) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = C.soft,
                    unfocusedContainerColor = C.soft,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = C.t1),
                modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { doSearch() }))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { doSearch() },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.block),
                modifier = Modifier.height(46.dp)) {
                Text("搜索", color = Color.White)
            }
            // v0.4.15: 筛选区收起/展开按钮(默认收起)
            TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                Text(if (filtersExpanded) "收起 ▴" else "筛选 ▾", color = C.t2, fontSize = 13.sp)
            }
        }

        // 排序 + UP 筛选 + 已看/未看 行(v0.4.15 收起;v0.4.19 合并到同一行,可横向滚动)
        if (filtersExpanded) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // 综合 / 播放量 / 按时间
            listOf("综合", "播放量", "按时间").forEachIndexed { i, label ->
                Surface(
                    color = if (vm.homeSortMode == i) C.block else C.card,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    border = if (vm.homeSortMode == i) null else androidx.compose.foundation.BorderStroke(1.dp, C.line),
                    modifier = Modifier.clickable { vm.homeSortMode = i }
                ) {
                    Text(label, fontSize = 12.sp,
                        color = if (vm.homeSortMode == i) C.onBlock else C.t1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            // UP 筛选
            Surface(
                color = if (vm.homeFilterMids.isNotEmpty()) C.block else C.card,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                border = if (vm.homeFilterMids.isNotEmpty()) null else androidx.compose.foundation.BorderStroke(1.dp, C.line),
                modifier = Modifier.clickable { showUpPicker = true }
            ) {
                Text(
                    if (vm.homeFilterMids.isEmpty()) "全部UP主"
                    else if (vm.homeFilterMids.size == 1) upName[vm.homeFilterMids.first()] ?: "已选1个"
                    else "已选${vm.homeFilterMids.size}个UP",
                    fontSize = 12.sp,
                    color = if (vm.homeFilterMids.isNotEmpty()) C.onBlock else C.t1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
            // 已看/未看筛选(与排序同行)
            listOf("全部" to 0, "未看" to 1, "已看" to 2).forEach { (label, mode) ->
                val on = vm.watchFilter == mode
                Surface(
                    color = if (on) C.block else C.card,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, C.line),
                    modifier = Modifier.clickable { vm.watchFilter = mode }
                ) {
                    Text(label, fontSize = 12.sp,
                        color = if (on) C.onBlock else C.t2,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }

        // v0.3 新增:UP 分组快速筛选行(在"管理UP主→分组"中设置)
        val groups = vm.upGroups
        if (groups.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.take(6).forEach { g ->
                    val gUps = vm.ups.filter { it.grp == g }.map { it.id }.toSet()
                    val on = vm.homeFilterMids == gUps
                    Surface(
                        color = if (on) C.block else C.card,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, C.line),
                        modifier = Modifier.clickable {
                            vm.homeFilterMids = if (on) emptySet() else gUps
                        }
                    ) {
                        Text("#$g", fontSize = 12.sp,
                            color = if (on) C.onBlock else C.t2,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }

        } // v0.4.15: filtersExpanded 闭合

        // UP 选择弹层
        if (showUpPicker) {
            AlertDialog(
                onDismissRequest = { showUpPicker = false },
                confirmButton = { TextButton(onClick = { showUpPicker = false }) { Text("确定", color = C.t1) } },
                dismissButton = { TextButton(onClick = { vm.homeFilterMids = emptySet() }) { Text("清除", color = C.t2) } },
                title = { Text("选择UP主(可多选)", color = C.t1, fontSize = 16.sp) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(vm.ups, key = { it.id }) { u ->
                            val on = u.id in vm.homeFilterMids
                            Surface(
                                color = if (on) C.block else C.card,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                    .clickable {
                                        vm.homeFilterMids = if (on) vm.homeFilterMids - u.id else vm.homeFilterMids + u.id
                                    }
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text((if (u.grp.isNotBlank()) "[${u.grp}] " else "") + u.name,
                                        color = if (on) C.onBlock else C.t1,
                                        fontSize = 14.sp, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                },
                containerColor = C.card
            )
        }

        if (vm.ups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("尚未添加 UP\n去「我的」→「管理UP主」添加", color = C.t2,
                    fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        if (vm.newCount > 0 && !vm.checking) {
            Text("已自动更新 ${vm.newCount} 个新视频", color = C.t2, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (vm.msg.isNotEmpty() && vm.syncing) {
            Text(vm.msg, color = C.t2, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
        }

        // bvid 查找结果(单条)
        if (vm.homeBvidMode) {
            val r = vm.bvidResult
            if (vm.searching) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.t1)
                }
            } else if (r != null) {
                Column(Modifier.padding(16.dp)) {
                    VideoRow(r, upName[r.upId] ?: "", onClick = { onPlay(r, listOf(r)) }, watched = r.id in vm.watchedIds)
                    Spacer(Modifier.height(8.dp))
                    Text("输入「BV 开头的视频码」可直接查找视频", color = C.t2, fontSize = 11.sp)
                }
            } else if (vm.msg.isNotEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(vm.msg, color = C.t2, fontSize = 13.sp)
                }
            }
            return@Column
        }

        if (feed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (vm.homeKeyword.isNotBlank()) "没有匹配的视频" else "暂无视频", color = C.t2)
            }
            return@Column
        }
        if (isTablet) {
            // 平板:多列网格(自适应列宽,横屏更多列)
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 220.dp),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                gridItems(feed, key = { it.id }) { v -> VideoCardVertical(v, upName[v.upId] ?: "",
                    onClick = { onPlay(v, feed) }, onToggleFav = { vm.toggleFavorite(v) }, watched = v.id in vm.watchedIds) }
            }
        } else {
            // 手机:列表(v0.3:listState 由外部持有,播放返回后保持滚动位置;key 稳定 item 身份)
            LazyColumn(state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                items(feed, key = { it.id }) { v -> VideoRow(v, upName[v.upId] ?: "", onClick = { onPlay(v, feed) },
                    onToggleFav = { vm.toggleFavorite(v) }, watched = v.id in vm.watchedIds) }
            }
        }
    }
}

/** 平板竖版卡片:上封面,下标题/UP/信息 */
@Composable
private fun VideoCardVertical(v: Video, upName: String, onClick: () -> Unit, onToggleFav: () -> Unit, watched: Boolean = false) {
    Card(colors = CardDefaults.cardColors(containerColor = BILICARD), onClick = onClick,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            if (v.pic.isNotEmpty()) {
                AsyncImage(model = coverThumb(v.pic, 672, 378), contentDescription = v.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(C.line))
            }
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (watched) {
                        Surface(color = C.watchedBg, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
                            Text("已看", color = C.watchedFg, fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(v.title, color = C.t1, fontSize = 14.sp, maxLines = 2,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onToggleFav, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(if (v.favorite) "★" else "☆",
                            color = if (v.favorite) Color(0xFFFFB300) else C.t2,
                            fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text((if (upName.isNotEmpty()) "$upName" else "") +
                     (if (v.playCount > 0) " · ${fmtPlay(v.playCount)}播放" else ""),
                     color = C.t2, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** 单条横版大卡:左侧封面,右侧标题/UP/信息 */
@Composable
fun VideoRow(v: Video, upName: String = "", onClick: () -> Unit = {}, onToggleFav: (() -> Unit)? = null, watched: Boolean = false) {
    Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
        onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 封面(16:9 缩略)
            if (v.pic.isNotEmpty()) {
                AsyncImage(model = coverThumb(v.pic, 320, 180), contentDescription = v.title,
                    modifier = Modifier.width(120.dp).height(68.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(C.line))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // v0.3.1: 已看标记
                    if (watched) {
                        Surface(color = C.watchedBg, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
                            Text("已看", color = C.watchedFg, fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(v.title, color = C.t1, fontSize = 14.sp, maxLines = 2,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Text((if (upName.isNotEmpty()) "$upName · " else "") +
                     "${v.durationSec / 60}:${(v.durationSec % 60).toString().padStart(2, '0')}" +
                     (if (v.playCount > 0) " · ${fmtPlay(v.playCount)}播放" else ""),
                     color = C.t2, fontSize = 11.sp)
            }
            if (onToggleFav != null) {
                TextButton(onClick = onToggleFav) {
                    Text(if (v.favorite) "★" else "☆",
                        color = if (v.favorite) Color(0xFFFFB300) else C.t2,
                        fontSize = 20.sp)
                }
            }
        }
    }
}

/** UP 头像(圆形,无头像用正色占位 + 首字) */
@Composable
private fun UpAvatar(face: String, name: String, size: androidx.compose.ui.unit.Dp) {
    if (face.isNotEmpty()) {
        AsyncImage(model = coverThumb(face, 96, 96), contentDescription = name,
            modifier = Modifier.size(size).clip(CircleShape).background(C.line))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(C.line),
            contentAlignment = Alignment.Center) {
            Text(name.take(1), color = C.t2, fontSize = (size.value * 0.4f).sp)
        }
    }
}

private fun fmtPlay(c: Long): String = when {
    c >= 100000000 -> "%.1f亿".format(c / 100000000.0)
    c >= 10000 -> "%.1f万".format(c / 10000.0)
    else -> c.toString()
}

@Composable
fun QueueScreen(vm: BiliViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("待学习队列(骨架) · 精读需写总结", color = C.t2, fontSize = 12.sp)
        Text("已学习队列 · 0", color = C.t2, fontSize = 12.sp)
    }
}

@Composable
fun ReportScreen(vm: BiliViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("学习报告 · 本周", style = MaterialTheme.typography.titleMedium, color = C.t1)
        Text("学习时长 0 分钟 · 已看 0 · 精读 0 · 平均完成度 0%", color = C.t2, fontSize = 13.sp)
    }
}


@Composable
fun ProfileScreen(vm: BiliViewModel, onLoggedOut: () -> Unit = {},
                  onPlay: (Video, List<Video>) -> Unit = { _, _ -> },
                  onPlayWithCid: (Video, Long, Long, List<Video>) -> Unit = { _, _, _, _ -> },
                  onPlayBookmark: (Video, Long, Long, List<Video>) -> Unit = { _, _, _, _ -> },
                  onPlayCache: (Video, String) -> Unit = { _, _ -> }) {
    val ctx = LocalContextSafe()
    var showManage by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showFavs by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showSeasons by remember { mutableStateOf(false) }      // v0.3: UP 合集浏览
    var showCloudFav by remember { mutableStateOf(false) }     // v0.3: 云端收藏夹
    var showCache by remember { mutableStateOf(false) }        // v0.4.1: 离线缓存
    var showSync by remember { mutableStateOf(false) }         // v0.4.4: 数据同步/日志
    var showPlugins by remember { mutableStateOf(false) }      // 插件系统:插件管理

    if (showSync) {
        SyncScreen(vm, onBack = { showSync = false })
        return
    }
    if (showPlugins) {
        com.bililite.ui.PluginScreen(onBack = { showPlugins = false })
        return
    }

    if (showManage) {
        ManageUPScreen(vm, onBack = { showManage = false })
        return
    }
    if (showAccount) {
        AccountScreen(vm, onBack = { showAccount = false }, onLoggedOut = onLoggedOut)
        return
    }
    if (showFavs) {
        FavoritesScreen(vm, onBack = { showFavs = false }, onPlay = onPlay)
        return
    }
    if (showHistory) {
        HistoryScreen(vm, onBack = { showHistory = false }, onPlay = onPlay, onPlayWithCid = onPlayWithCid)
        return
    }
    if (showBookmarks) {
        BookmarkListScreen(vm, onBack = { showBookmarks = false }, onPlay = onPlay, onPlayBookmark = onPlayBookmark)
        return
    }
    if (showSeasons) {
        SeasonBrowserScreen(vm, onBack = { showSeasons = false }, onPlay = onPlay)
        return
    }
    if (showCloudFav) {
        CloudFavScreen(vm, onBack = { showCloudFav = false }, onPlay = onPlay)
        return
    }
    if (showCache) {
        CacheScreen(vm, onBack = { showCache = false }, onPlayCache = onPlayCache)
        return
    }
    if (showDisclaimer) {
        DisclaimerScreen(onBack = { showDisclaimer = false })
        return
    }

    // v0.4.4: 平板适配——列表限宽居中,避免按钮在宽屏上拉满
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 600.dp).padding(16.dp)) {
        // ---- 用户资料头:头像 + 用户名 + 签名 ----
        Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = vm.face.takeIf { it.isNotEmpty() }?.let { coverThumb(it, 128, 128) },
                    contentDescription = "头像",
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(C.line)
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.uname.ifBlank { "B站用户" }, color = C.t1,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 18.sp)
                    if (vm.sign.isNotBlank())
                        Text(vm.sign, color = C.t2, fontSize = 12.sp, maxLines = 2)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- v0.4.9: 按钮分组卡片化(学习数据 / B站内容 / 设置),两列网格不再一长串 ----
        // (lambda 形式以便在 @Composable 上下文内调用 Composable 组件)
        val groupCard: @Composable (String, List<Triple<String, () -> Unit, Boolean>>) -> Unit = { title, items ->
            Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(title, color = C.t2, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    items.chunked(2).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { (label, action, disabled) ->
                                OutlinedButton(onClick = action, enabled = !disabled,
                                    modifier = Modifier.weight(1f)) {
                                    Text(label, color = if (disabled) C.t2.copy(alpha = 0.5f) else C.t1, fontSize = 13.sp)
                                }
                            }
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        groupCard("学习数据", listOf(
            Triple("收藏", { vm.loadFavorites(); showFavs = true }, false),
            Triple("历史记录", { vm.loadHistory(); showHistory = true }, false),
            Triple("视频书签", { vm.loadBookmarks(); showBookmarks = true }, false),
            Triple("离线缓存", { vm.loadCached(); showCache = true }, false)))

        val cloudFavDisabled = com.bililite.plugin.FeatureGate.isDisabled("cloudFav")
        groupCard("B站内容", listOf(
            Triple(if (cloudFavDisabled) "B站收藏 (已禁用)" else "B站收藏", { vm.loadFavFolders(); showCloudFav = true },
                cloudFavDisabled),
            Triple("UP主合集", { showSeasons = true }, false),
            Triple("管理UP主", { showManage = true }, false)))

        groupCard("设置", listOf(
            // v0.4.19: 按钮文字随当前主题动态切换(当前深色→点后变浅色并显示"浅色模式")
            Triple((if (BiliTheme.dark) "浅色模式" else "深色模式"), {
                BiliTheme.toggle(ctx)
            }, false),
            Triple("插件", { showPlugins = true }, false),
            Triple("账号管理", { showAccount = true }, false)))

        // v0.4.20 插件系统:渲染插件通过 ui.registerMenu 注册的自定义入口
        val pluginMenus = com.bililite.plugin.PluginMenus.menus
        if (pluginMenus.isNotEmpty()) {
            groupCard("插件功能", pluginMenus.map { m ->
                Triple(m.label, {
                    PluginRuntime.uiHandler?.post { try { m.handler.call() } catch (_: Exception) {} }
                }, false)
            })
        }

        // 底部占满,QQ 群号贴最下方(还原为单一群号,16sp 加粗)
        Spacer(Modifier.weight(1f))
        Text("QQ交流群：811598424", color = C.t1, fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
    }
    }
}

/** 收藏列表(v0.3:按分类筛选 + 连播上下文) */
@Composable
private fun FavoritesScreen(vm: BiliViewModel, onBack: () -> Unit, onPlay: (Video, List<Video>) -> Unit) {
    val upName = remember(vm.ups) { vm.ups.associate { it.id to it.name } }
    var curCat by remember { mutableStateOf("全部") }
    var editCat by remember { mutableStateOf<Video?>(null) }
    var catInput by remember { mutableStateOf("") }
    // v0.4.9: 嵌套分类二级筛选状态(函数顶层,对话框也可访问)
    var curTop by remember { mutableStateOf("全部") }
    var curSub by remember(curTop) { mutableStateOf("") }
    BackHandler { if (editCat != null) editCat = null else onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("收藏", style = MaterialTheme.typography.titleMedium, color = C.t1)
            Spacer(Modifier.weight(1f))
            Text("${vm.favs.size}", color = C.t2, fontSize = 12.sp)
        }
        // v0.4.9: 多级嵌套分类(分类名用 父/子 路径),两级筛选:父类行 → 子类行
        val cats = vm.favCategories
        // 父类集合:未分类→"未分类";"数学"→"数学";"数学/微积分"→"数学"
        val topCats = cats.map { it.substringBefore("/", it).ifBlank { "未分类" } }.distinct()
        // 当前父类下的子类(含"该父类全部"选项)
        val subCats = remember(cats, curTop) {
            cats.mapNotNull { c ->
                val top = c.substringBefore("/", c).ifBlank { "未分类" }
                if (curTop == "全部") null
                else if (top == curTop) c.substringAfter("/", "").ifBlank { null } else null
            }.distinct().filter { it.isNotEmpty() }
        }
        if (cats.size > 1 || (cats.size == 1 && cats[0] != "未分类")) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("全部") + topCats).forEach { c ->
                    val on = curTop == c
                    Surface(
                        color = if (on) C.block else C.card,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, C.line),
                        modifier = Modifier.clickable { curTop = c }
                    ) {
                        Text(c, fontSize = 12.sp,
                            color = if (on) C.onBlock else C.t2,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
        // 子类筛选行(选中父类且有子类时显示)
        if (curTop != "全部" && subCats.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("全部") + subCats).forEach { c ->
                    // v0.4.9: "全部"用空串表示(避免与真实子类名冲突)
                    val on = curSub == c
                    Surface(
                        color = if (on) C.block else C.card,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, C.line),
                        modifier = Modifier.clickable { curSub = if (c == "全部") "" else c }
                    ) {
                        Text(c, fontSize = 12.sp,
                            color = if (on) C.onBlock else C.t2,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.favs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无收藏\n在首页点视频右侧的 ☆ 收藏", color = C.t2,
                    fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        // v0.4.9: 两级筛选匹配(父/子 路径)
        val shown = remember(vm.favs, curTop, curSub) {
            vm.favs.filter { v ->
                val cat = v.favCategory.ifBlank { "未分类" }
                val top = cat.substringBefore("/", cat)
                val sub = if (cat.contains("/")) cat.substringAfter("/") else ""
                when {
                    curTop == "全部" -> true
                    sub.isEmpty() -> top == curTop        // 无子类时按父类匹配
                    curSub.isEmpty() -> top == curTop     // 选中父类未选子类 → 该父类全部
                    else -> top == curTop && sub == curSub
                }
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该分类暂无收藏", color = C.t2, fontSize = 13.sp)
            }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { v ->
                Column {
                    VideoRow(v, upName[v.upId] ?: "", onClick = { onPlay(v, shown) },
                        onToggleFav = { vm.toggleFavorite(v) })
                    // v0.3: 分类标签 + 改分类入口
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("分类:${if (v.favCategory.isBlank()) "未分类" else v.favCategory}",
                            color = C.t2, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editCat = v; catInput = v.favCategory }) {
                            Text("改分类", color = C.t1, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    // 改分类对话框
    if (editCat != null) {
        val existing = vm.favCategories.filter { it != "未分类" }
        AlertDialog(
            onDismissRequest = { editCat = null },
            title = { Text("收藏分类", fontSize = 15.sp) },
            text = {
                Column {
                    OutlinedTextField(value = catInput, onValueChange = { catInput = it },
                        singleLine = true, label = { Text("分类名(留空=未分类,支持 父/子 嵌套)") })
                    if (existing.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("已有分类", color = C.t2, fontSize = 11.sp)
                        existing.forEach { c ->
                            TextButton(onClick = { catInput = c }) {
                                Text(c, color = C.t1, fontSize = 13.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setFavCategory(editCat!!, catInput.trim())
                    editCat = null
                    // v0.4.9: 分类变化后重置子类筛选,避免指向已删除子类显示空列表
                    curSub = ""
                }) { Text("确定", color = C.t1) }
            },
            dismissButton = {
                TextButton(onClick = { editCat = null }) { Text("取消", color = C.t2) }
            },
            containerColor = C.card
        )
    }
}

/** 视频书签列表 */
@Composable
private fun BookmarkListScreen(vm: BiliViewModel, onBack: () -> Unit, onPlay: (Video, List<Video>) -> Unit, onPlayBookmark: (Video, Long, Long, List<Video>) -> Unit) {
    val videoByBvid = remember(vm.vids) { vm.vids.associateBy { it.bvid } }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("视频书签", style = MaterialTheme.typography.titleMedium, color = C.t1)
        }
        Spacer(Modifier.height(12.dp))
        if (vm.bookmarks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无书签\n播放视频时点书签按钮标记", color = C.t2,
                    fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.bookmarks, key = { it.id }) { b ->
                var renaming by remember(b.id) { mutableStateOf(false) }
                var note by remember(b.id) { mutableStateOf(b.note) }
                val v = videoByBvid[b.bvid]
                Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                    onClick = {
                        if (v != null) onPlayBookmark(v, b.cid, b.timeSec, listOf(v))
                    },
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        // 标题（颜色比备注浅）
                        Text(b.videoTitle, color = C.t2, fontSize = 13.sp, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        // 备注（黑体、字号更大、深黑，突出）
                        if (b.note.isNotEmpty()) {
                            Text(b.note, color = C.t1, fontSize = 17.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2)
                            Spacer(Modifier.height(4.dp))
                        }
                        // 分P + 时间点（次级信息）
                        Text((if (b.pageTitle.isNotEmpty()) "${b.pageTitle} · " else "") +
                             "@ ${b.timeSec / 60}:${(b.timeSec % 60).toString().padStart(2, '0')}",
                             color = C.t2, fontSize = 12.sp)
                        if (renaming) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = note, onValueChange = { note = it },
                                    singleLine = true, modifier = Modifier.weight(1f),
                                    label = { Text("备注") })
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { vm.renameBookmark(b.id, note); renaming = false }) { Text("确定", color = C.t1) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { renaming = true }) { Text("重命名", color = C.t2, fontSize = 12.sp) }
                        TextButton(onClick = { vm.deleteBookmark(b.id) }) { Text("删除", color = Color(0xFFFF3B30), fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

/** 播放历史(带进度,点击续播;v0.3:连播上下文) */
@Composable
private fun HistoryScreen(vm: BiliViewModel, onBack: () -> Unit, onPlay: (Video, List<Video>) -> Unit, onPlayWithCid: (Video, Long, Long, List<Video>) -> Unit) {
    val upName = remember(vm.ups) { vm.ups.associate { it.id to it.name } }
    val videoById = remember(vm.vids) { vm.vids.associateBy { it.id } }
    // 历史对应的视频列表(作为连播上下文)
    val historyVideos = remember(vm.history, vm.vids) { vm.history.mapNotNull { videoById[it.videoId] } }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("历史记录", style = MaterialTheme.typography.titleMedium, color = C.t1)
            Spacer(Modifier.weight(1f))
            if (vm.history.isNotEmpty()) {
                TextButton(onClick = { vm.clearHistory() }) { Text("清空", color = C.t2) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (vm.history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无播放历史", color = C.t2, fontSize = 13.sp)
            }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.history, key = { it.videoId }) { w ->
                val v = videoById[w.videoId]
                Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                    onClick = { v?.let { onPlayWithCid(it, w.cid, w.secs, historyVideos) } }) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (v != null && v.pic.isNotEmpty()) {
                            AsyncImage(model = coverThumb(v.pic, 320, 180), contentDescription = v.title,
                                modifier = Modifier.width(120.dp).height(68.dp)
                                    .clip(RoundedCornerShape(6.dp)).background(C.line))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(v?.title ?: "已删除的视频", color = C.t1, fontSize = 14.sp, maxLines = 2)
                            Spacer(Modifier.height(4.dp))
                            Text((v?.let { upName[it.upId] ?: "" }?.let { "$it · " } ?: "") +
                                 "看到 ${w.secs / 60}:${(w.secs % 60).toString().padStart(2, '0')}",
                                 color = C.t2, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 关于与声明(v0.4.19:展示完整的 LEGAL 法律声明 + PRIVACY 隐私政策两份文档) */
@Composable
private fun DisclaimerScreen(onBack: () -> Unit) {
    val ctx = LocalContextSafe()
    val legal = remember { readAsset(ctx, "legal.md") }
    val privacy = remember { readAsset(ctx, "privacy.md") }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("关于与声明", style = MaterialTheme.typography.titleMedium, color = C.t1)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("开源致谢与法律声明", color = C.t1, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            item { Text(legal, color = C.t3, fontSize = 13.sp, lineHeight = 20.sp) }
            item { Spacer(Modifier.height(8.dp)) }
            item { Text("隐私政策", color = C.t1, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            item { Text(privacy, color = C.t3, fontSize = 13.sp, lineHeight = 20.sp) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 读取 assets 文本(隐私政策/法律声明) */
private fun readAsset(ctx: Context, name: String): String =
    try {
        ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: Exception) { "" }

/** 账号管理:退出登录(带二次确认) */
@Composable
private fun AccountScreen(vm: BiliViewModel, onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val ctx = LocalContextSafe()
    var confirmLogout by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("账号管理", style = MaterialTheme.typography.titleMedium, color = C.t1)
        }
        Spacer(Modifier.height(16.dp))
        Text("退出登录只清除登录状态,本地已添加的 UP 与学习记录会保留。", color = C.t2, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (confirmLogout) {
            Text("确认退出登录?", color = C.t1, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { LoginSession.clear(ctx); onLoggedOut() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                    modifier = Modifier.weight(1f)) {
                    Text("确认退出", color = Color.White)
                }
                OutlinedButton(onClick = { confirmLogout = false }, modifier = Modifier.weight(1f)) {
                    Text("取消", color = C.t1)
                }
            }
        } else {
            OutlinedButton(onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth()) {
                Text("退出登录", color = Color(0xFFFF3B30))
            }
        }
    }
}

/** 管理UP主:三个入口(添加 / 删除 / 分组) */
@Composable
fun ManageUPScreen(vm: BiliViewModel, onBack: () -> Unit = {}) {
    var mode by remember { mutableStateOf(0) } // 0 首页 1 添加 2 删除 3 分组
    BackHandler { if (mode == 0) onBack() else mode = 0 }

    if (mode == 0) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
                Text("管理UP主", style = MaterialTheme.typography.titleMedium, color = C.t1)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { mode = 1 },
                colors = ButtonDefaults.buttonColors(containerColor = C.block),
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("添加UP主", color = C.onBlock, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { mode = 2 },
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("删除UP主", color = C.t1, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { mode = 3 },
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("UP分组(首页快速筛选)", color = C.t1, fontSize = 16.sp)
            }
        }
        return
    }

    if (mode == 1) {
        AddUpScreen(vm, onBack = { mode = 0 })
        return
    }
    if (mode == 3) {
        GroupUpScreen(vm, onBack = { mode = 0 })
        return
    }

    // mode == 2: 删除UP主(带二次确认)
    DeleteUpScreen(vm, onBack = { mode = 0 })
}

/** v0.3: UP 分组管理(为 UP 设置分组名,首页按组快速筛选视频) */
@Composable
private fun GroupUpScreen(vm: BiliViewModel, onBack: () -> Unit) {
    var editUp by remember { mutableStateOf<Up?>(null) }
    var grpInput by remember { mutableStateOf("") }
    BackHandler { if (editUp != null) editUp = null else onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("UP分组", style = MaterialTheme.typography.titleMedium, color = C.t1)
        }
        Spacer(Modifier.height(4.dp))
        Text("为 UP 设置分组(如:数学/英语/物理),设置后首页会出现分组筛选条",
            color = C.t2, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (vm.ups.isEmpty()) {
            Text("尚未添加任何 UP", color = C.t2, fontSize = 13.sp)
            return@Column
        }
        LazyColumn {
            items(vm.ups, key = { it.id }) { u ->
                Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        UpAvatar(u.face, u.name, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.name, color = C.t1)
                            Text(if (u.grp.isBlank()) "未分组" else "#${u.grp}",
                                color = if (u.grp.isBlank()) C.t2 else C.t1,
                                fontSize = 12.sp)
                        }
                        TextButton(onClick = { editUp = u; grpInput = u.grp }) {
                            Text("分组", color = C.t1, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    // 分组名输入对话框(可选已有分组或输入新分组)
    if (editUp != null) {
        val existing = vm.upGroups
        AlertDialog(
            onDismissRequest = { editUp = null },
            title = { Text("设置分组:${editUp!!.name}", fontSize = 15.sp) },
            text = {
                Column {
                    OutlinedTextField(value = grpInput, onValueChange = { grpInput = it },
                        singleLine = true, label = { Text("分组名(留空=取消分组)") })
                    if (existing.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("已有分组", color = C.t2, fontSize = 11.sp)
                        existing.forEach { g ->
                            TextButton(onClick = { grpInput = g }) {
                                Text("#$g", color = C.t1, fontSize = 13.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setUpGroup(editUp!!, grpInput.trim())
                    editUp = null
                }) { Text("确定", color = C.t1) }
            },
            dismissButton = {
                TextButton(onClick = { editUp = null }) { Text("取消", color = C.t2) }
            },
            containerColor = C.card
        )
    }
}

/** 添加 UP:搜索 + 添加 */
@Composable
private fun AddUpScreen(vm: BiliViewModel, onBack: () -> Unit) {
    var kw by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("添加UP主", style = MaterialTheme.typography.titleMedium, color = C.t1)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(value = kw, onValueChange = { kw = it },
                placeholder = { Text("搜索 UP 主名", color = C.t2, fontSize = 13.sp) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = C.soft,
                    unfocusedContainerColor = C.soft,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = C.t1),
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { submitted = kw; vm.searchUps(kw) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.block)) {
                Text("搜索", color = C.onBlock)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.msg.isNotEmpty()) Text(vm.msg, color = C.t2, fontSize = 12.sp)
        LazyColumn {
            items(vm.searchResults, key = { it.id }) { up ->
                val added = remember(up.id, vm.ups) { vm.ups.any { it.id == up.id } }
                Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        // UP 头像
                        UpAvatar(up.face, up.name, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(up.name, color = C.t1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text(if (up.fans > 0) "粉丝 ${fmtFans(up.fans)}" else "—", color = C.t2, fontSize = 12.sp)
                        }
                        if (added) {
                            Text("已添加", color = C.t2, fontSize = 12.sp)
                        } else {
                            Button(onClick = { vm.addUpAndSync(up) },
                                colors = ButtonDefaults.buttonColors(containerColor = C.block)) { Text("添加", color = C.onBlock) }
                        }
                    }
                }
            }
        }
    }
}

/** 删除 UP:列表 + 二次确认 */
@Composable
private fun DeleteUpScreen(vm: BiliViewModel, onBack: () -> Unit) {
    var confirmMid by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("删除UP主", style = MaterialTheme.typography.titleMedium, color = C.t1)
        }
        Spacer(Modifier.height(12.dp))
        if (vm.ups.isEmpty()) {
            Text("尚未添加任何 UP", color = C.t2, fontSize = 13.sp)
            return@Column
        }
        LazyColumn {
            items(vm.ups, key = { it.id }) { u ->
                Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        UpAvatar(u.face, u.name, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(u.name, color = C.t1, modifier = Modifier.weight(1f))
                        if (confirmMid == u.id) {
                            Row {
                                TextButton(onClick = { vm.removeUp(u.id); confirmMid = null }) {
                                    Text("确认删除", color = Color(0xFFFF3B30))
                                }
                                TextButton(onClick = { confirmMid = null }) { Text("取消", color = C.t2) }
                            }
                        } else {
                            TextButton(onClick = { confirmMid = u.id }) { Text("删除", color = Color(0xFFFF3B30)) }
                        }
                    }
                }
            }
        }
    }
}

private fun fmtFans(f: Long): String = when {
    f >= 10000 -> "%.1f万".format(f / 10000.0)
    else -> f.toString()
}

// ============================================================================
// v0.3 新增页面:UP 主合集浏览 / 云端收藏夹
// ============================================================================

/** UP 主合集浏览:选 UP → 看该 UP 的合集/系列列表 → 点开合集 → 合集视频(自动连播) */
@Composable
fun SeasonBrowserScreen(vm: BiliViewModel, onBack: () -> Unit, onPlay: (Video, List<Video>) -> Unit) {
    // 0: 选 UP; 1: 合集列表; 2: 合集视频列表
    var stage by remember { mutableStateOf(0) }
    BackHandler { if (stage == 0) onBack() else stage-- }
    val upName = remember(vm.ups) { vm.ups.associate { it.id to it.name } }

    when (stage) {
        0 -> {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
                    Text("UP主合集", style = MaterialTheme.typography.titleMedium, color = C.t1)
                }
                Spacer(Modifier.height(4.dp))
                Text("查看 UP 主在 B 站创建的合集/系列(按课程分组,不用再翻播放量找大合集)",
                    color = C.t2, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                if (vm.ups.isEmpty()) {
                    Text("尚未添加任何 UP", color = C.t2, fontSize = 13.sp)
                    return@Column
                }
                LazyColumn {
                    items(vm.ups, key = { it.id }) { u ->
                        Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = { vm.loadSeasons(u.id, u.name); stage = 1 }) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                UpAvatar(u.face, u.name, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(u.name, color = C.t1)
                                    if (u.grp.isNotBlank()) Text("#${u.grp}", color = C.t2, fontSize = 12.sp)
                                }
                                Text("查看合集 →", color = C.t2, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        1 -> {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { stage = 0 }) { Text("← 返回", color = C.t1) }
                    Text("${vm.seasonsOwnerName} 的合集", style = MaterialTheme.typography.titleMedium,
                        color = C.t1, maxLines = 1)
                }
                Spacer(Modifier.height(8.dp))
                if (vm.seasonsLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = C.t1)
                    }
                    return@Column
                }
                if (vm.seasons.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("该 UP 暂无合集/系列\n(合集是 UP 主在 B 站手动创建的章节)",
                            color = C.t2, fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    return@Column
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.seasons, key = { it.seasonId }) { s ->
                        Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { vm.loadSeasonVideos(s.seasonId, s.title, s.type) { }; stage = 2 }) {
                            Row(Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, color = C.t1, fontSize = 14.sp,
                                        maxLines = 2, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                    Text("共 ${s.total} 集", color = C.t2, fontSize = 12.sp)
                                }
                                Text("▶", color = C.t1, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
        else -> {
            // stage 2: 合集视频(点第一条即开始连播)
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { stage = 1 }) { Text("← 返回", color = C.t1) }
                    Text(vm.seasonTitle, style = MaterialTheme.typography.titleMedium,
                        color = C.t1, maxLines = 1, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Text("共 ${vm.seasonVideos.size} 集 · 播放完自动播下一集",
                    color = C.t2, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                if (vm.seasonVideosLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = C.t1)
                    }
                    return@Column
                }
                if (vm.seasonVideos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("合集内容为空", color = C.t2, fontSize = 13.sp)
                    }
                    return@Column
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.seasonVideos, key = { it.id }) { v ->
                        VideoRow(v, vm.seasonsOwnerName, onClick = { onPlay(v, vm.seasonVideos) })
                    }
                }
            }
        }
    }
}

/** 云端收藏夹:登录账号在 B 站的收藏夹(同步展示,可播放) */
@Composable
fun CloudFavScreen(vm: BiliViewModel, onBack: () -> Unit, onPlay: (Video, List<Video>) -> Unit) {
    var openFolder by remember { mutableStateOf<Long?>(null) } // null=夹列表, 非null=夹内视频
    BackHandler { if (openFolder != null) openFolder = null else onBack() }

    if (openFolder == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
                Text("B站收藏", style = MaterialTheme.typography.titleMedium, color = C.t1)
            }
            Spacer(Modifier.height(4.dp))
            Text("B 站账号里的收藏夹(实时读取,点击进入)",
                color = C.t2, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (vm.favFoldersLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.t1)
                }
                return@Column
            }
            if (vm.favFolders.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (vm.msg.isNotEmpty()) vm.msg else "未获取到收藏夹\n(需登录 B 站账号)",
                        color = C.t2, fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.favFolders, key = { it.id }) { f ->
                    Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.loadFavFolderVideos(f.id, f.title, f.count); openFolder = f.id }) {
                        Row(Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.title, color = C.t1, fontSize = 14.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text("共 ${f.count} 条", color = C.t2, fontSize = 12.sp)
                            }
                            Text("▶", color = C.t1, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    } else {
        val folder = vm.favFolders.firstOrNull { it.id == openFolder }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { openFolder = null }) { Text("← 返回", color = C.t1) }
                Text(folder?.title ?: "收藏夹", style = MaterialTheme.typography.titleMedium,
                    color = C.t1, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Text("共 ${vm.favFolderVideos.size} 条 · 失效视频已自动隐藏",
                color = C.t2, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (vm.favFolderVideosLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.t1)
                }
                return@Column
            }
            if (vm.favFolderVideos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("收藏夹为空(或全部失效)", color = C.t2, fontSize = 13.sp)
                }
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.favFolderVideos, key = { it.id }) { v ->
                    VideoRow(v, "", onClick = { onPlay(v, vm.favFolderVideos) })
                }
            }
        }
    }
}

/** 离线缓存管理页(v0.4.1) */
@Composable
fun CacheScreen(vm: BiliViewModel, onBack: () -> Unit, onPlayCache: (Video, String) -> Unit) {
    BackHandler { onBack() }
    // v0.4.19: 删除前二次确认
    var pendingDelete by remember { mutableStateOf<BiliViewModel.CachedVideo?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("离线缓存", style = MaterialTheme.typography.titleMedium, color = C.t1)
            Spacer(Modifier.weight(1f))
            Text("${vm.cachedVideos.size} 个", color = C.t2, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text("已缓存的视频可无网播放。缓存文件保存在本机。", color = C.t2, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))

        // v0.4.19: 缓存队列(下载中/排队中,带进度条)
        if (vm.cacheTasks.isNotEmpty()) {
            Text("缓存队列（${vm.cacheTasks.size}）", color = C.t2, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.cacheTasks, key = { it.bvid }) { task ->
                    Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(task.title, color = C.t1, fontSize = 13.sp, maxLines = 1,
                                    modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when (task.status) {
                                        "完成" -> "完成"
                                        "失败" -> "失败"
                                        "下载中" -> "${task.progress}%"
                                        else -> "排队中"
                                    },
                                    color = when (task.status) {
                                        "失败" -> Color(0xFFFF3B30)
                                        "完成" -> C.block
                                        else -> C.t2
                                    },
                                    fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            // 进度条：下载中显示实际进度;排队中显示 0;完成显示满;失败显示红
                            val prog = when (task.status) {
                                "完成" -> 1f
                                "下载中" -> (task.progress / 100f).coerceIn(0f, 1f)
                                else -> 0f
                            }
                            LinearProgressIndicator(
                                progress = { prog },
                                modifier = Modifier.fillMaxWidth().height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = if (task.status == "失败") Color(0xFFFF3B30) else C.block,
                                trackColor = C.line
                            )
                            if (task.status == "下载中" && task.total > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text("${fmtSize(task.done)} / ${fmtSize(task.total)}",
                                    color = C.t2, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (vm.cachedVideos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (vm.cacheTasks.isNotEmpty()) "缓存中…\n下载完成后会显示在这里"
                     else "暂无缓存\n在播放页控制条点「缓存」下载视频",
                    color = C.t2,
                    fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        val total = vm.cachedVideos.sumOf { it.size }
        Text("共占用 ${fmtSize(total)}", color = C.t2, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.cachedVideos, key = { it.path }) { c ->
                Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            // 分P标题(若有)+ 合集标题(最多12字,超出用…)
                            val shortTitle = if (c.title.length > 12) c.title.take(12) + "…" else c.title
                            val display = if (c.partTitle.isNotBlank()) "${c.partTitle} · $shortTitle" else shortTitle
                            Text(display, color = C.t1, fontSize = 13.sp, maxLines = 2)
                            Text(fmtSize(c.size), color = C.t2, fontSize = 11.sp)
                        }
                        // 播放本地文件
                        TextButton(onClick = {
                            val v = Video(id = c.bvid.hashCode().toLong(), bvid = c.bvid, cid = c.cid,
                                upId = "", title = c.title, durationSec = 0)
                            onPlayCache(v, c.path)
                        }) {
                            Text("播放", color = C.t1, fontSize = 13.sp)
                        }
                        TextButton(onClick = { pendingDelete = c }) {
                            Text("删除", color = Color(0xFFFF3B30), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // 删除二次确认
    pendingDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除缓存", color = C.t1, fontSize = 16.sp) },
            text = { Text("确定删除「${c.title.take(20)}」的缓存吗？删除后需重新下载。", color = C.t2, fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = {
                    vm.deleteCache(c.path)
                    pendingDelete = null
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30))) {
                    Text("删除", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消", color = C.t2) }
            },
            containerColor = C.card
        )
    }
}

/** v0.4.17 安全加固:bvid 只保留字母数字/横线/下划线,防路径遍历(导入数据可能被篡改) */
private fun safeBvid(bvid: String): String = bvid.replace(Regex("[^A-Za-z0-9_-]"), "_")

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
    else -> "$bytes B"
}

// ============================================================================
// v0.4.4: 数据同步(导出/导入 JSON 备份) + 日志查看/导出
// ============================================================================
@Composable
private fun SyncScreen(vm: BiliViewModel, onBack: () -> Unit) {
    val ctx = LocalContextSafe()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var logTotal by remember { mutableStateOf(0) }

    // 导出数据 → 用户选择保存位置(JSON)
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true; resultMsg = ""
            try {
                val json = withContext(Dispatchers.IO) { vm.exportAllData() }
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw Exception("无法写入文件")
                }
                // v0.4.5: 导出结果带统计,并提示保存路径
                val cnt = try {
                    val r = org.json.JSONObject(json)
                    "UP ${r.optJSONArray("ups")?.length() ?: 0} · 视频 ${r.optJSONArray("videos")?.length() ?: 0}" +
                    " · 已看 ${r.optJSONArray("watch")?.length() ?: 0} · 书签 ${r.optJSONArray("bookmarks")?.length() ?: 0}"
                } catch (_: Exception) { "" }
                resultMsg = "✓ 导出成功($cnt)\n文件已保存到你选择的位置,可在另一台设备导入"
                com.bililite.core.BiliLog.i("SyncUI", "导出数据成功: $cnt")
            } catch (e: Exception) {
                resultMsg = "导出失败: ${e.message}"
                com.bililite.core.BiliLog.e("SyncUI", "导出失败: ${e.message}", e)
            }
            finally { busy = false }
        }
    }
    // 导入数据 → 用户选择 JSON 备份文件
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true; resultMsg = ""
            try {
                val json = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw Exception("无法读取文件")
                }
                resultMsg = withContext(Dispatchers.IO) { vm.importAllData(json) }
            } catch (e: Exception) {
                resultMsg = "导入失败: ${e.message}"
                com.bililite.core.BiliLog.e("SyncUI", "导入失败: ${e.message}", e)
            }
            finally { busy = false }
        }
    }
    // 导出日志 → 用户选择保存位置(txt)。v0.4.5: 合并当前日志+轮转旧日志
    val logExportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true; resultMsg = ""
            try {
                val text = withContext(Dispatchers.IO) { com.bililite.core.BiliLog.exportText() }
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                resultMsg = "✓ 日志已导出(${text.lines().size} 行)"
            } catch (e: Exception) { resultMsg = "日志导出失败: ${e.message}" }
            finally { busy = false }
        }
    }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("数据同步与日志", style = MaterialTheme.typography.titleMedium, color = C.t1)
        }
        Spacer(Modifier.height(12.dp))
        Text("多端同步:把收藏/UP主/已看记录/书签/队列/设置导出为备份文件,"
            + "在另一台设备上导入即可恢复。",
            color = C.t2, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = { exportLauncher.launch("bililite_backup_${System.currentTimeMillis()}.json") },
            enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("导出全部数据(收藏/UP/已看/书签/队列/设置)", color = C.t1)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("从备份文件导入数据", color = C.t1)
        }

        Spacer(Modifier.height(24.dp))
        Text("日志(遇到问题请先导出日志,反馈时附上日志文件)",
            color = C.t2, fontSize = 12.sp)
        // v0.4.19: 标注反馈邮箱,提示带日志反馈
        Text("反馈邮箱：duanyou88@outlook.com（请附上导出的日志文件）",
            color = C.t2, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { logExportLauncher.launch("bililite_log_${System.currentTimeMillis()}.txt") },
            enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("导出日志文件", color = C.t1)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            scope.launch {
                logLines = withContext(Dispatchers.IO) { com.bililite.core.BiliLog.tail(300) }
                logTotal = logLines.size
                showLog = true
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (showLog) "刷新日志(已显示 $logTotal 条)" else "查看日志(最近300条)", color = C.t1)
        }

        if (showLog) {
            Spacer(Modifier.height(12.dp))
            if (logLines.isEmpty()) {
                Text("暂无日志", color = C.t2, fontSize = 13.sp)
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(logLines) { line ->
                        // v0.4.5: 按级别着色(E 红 / W 橙 / I 蓝 / D 灰),一眼定位问题
                        val color = when {
                            line.contains("] E/") -> Color(0xFFD32F2F)
                            line.contains("] W/") -> Color(0xFFF57C00)
                            line.contains("] I/") -> Color(0xFF1C88E8)
                            else -> C.t2
                        }
                        Text(line, color = color, fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }

        if (busy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(color = C.t1, modifier = Modifier.size(28.dp))
        }
        if (resultMsg.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(resultMsg, color = if (resultMsg.startsWith("✓") || resultMsg.startsWith("导入完成"))
                Color(0xFF1C88E8) else Color(0xFFFF3B30), fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
    }
}
