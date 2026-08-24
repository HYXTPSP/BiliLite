package com.bililite.ui

import com.bililite.app.BILICARD
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import com.bililite.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- ViewModel ----------
class BiliViewModel(private val db: BiliDb, private val ctx: Context) : ViewModel() {
    val api = BiliApi(ctx).apply { assignCookie(LoginSession.cookieString(ctx)) }
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
    }

    suspend fun reload() {
        val u = withContext(Dispatchers.IO) { db.upDao().all() }
        ups = u
        val v = if (u.isEmpty()) emptyList()
                else withContext(Dispatchers.IO) { db.videoDao().byUps(u.map { it.id }) }
        vids = v
        // 稳定随机顺序:只在视频列表真正变化时打乱(切 tab/回首页不重排)
        feedVids = v.shuffled()
        // 同步收藏列表
        favs = withContext(Dispatchers.IO) { db.videoDao().favorites() }
        // 同步书签列表(持久化,App 启动即加载)
        bookmarks = withContext(Dispatchers.IO) { db.bookmarkDao().all() }
    }

    /** 懒解析分P的 cid(播放前用),失败返回 0。 */
    suspend fun resolveCid(bvid: String): Long {
        val arr = withContext(Dispatchers.IO) { api.pagelist(bvid) }
        val first = arr.optJSONObject(0)
        return first?.optLong("cid", 0L) ?: 0L
    }

    /** 从真实 API 拉取 UP 的全部视频并入库(参考 space/wbi/arc/search,自动翻页)。带重试应对偶发风控。 */
    fun syncUp(mid: String, name: String) {
        if (syncing) return
        syncing = true; msg = "同步 $name …"
        viewModelScope.launch {
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
            if (!ok) { msg = "同步失败: $lastErr"; syncing = false; return@launch }
            if (all.isEmpty()) {
                msg = "该 UP 暂无公开视频"
            } else {
                val merged = withContext(Dispatchers.IO) { mergeFavorites(all) }
                withContext(Dispatchers.IO) { db.videoDao().upsertAll(merged) }
                reload()
                msg = "已同步 ${all.size} 个视频"
            }
            syncing = false
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
                    val fresh = withContext(Dispatchers.IO) { fetchAllVideos(u.id) }
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
    private suspend fun fetchAllVideos(mid: String): List<Video> {
        val out = ArrayList<Video>()
        var pn = 1
        var useWbi = false      // 默认走 arc/list(稳定)
        var fallbackTried = false
        while (pn <= 60) {  // 安全上限 1800 个
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
    fun toggleFavorite(video: Video) {
        val newFav = !video.favorite
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.videoDao().setFavorite(video.id, newFav) }
            // 原位更新 vids / feedVids 的 favorite 标记,不 shuffle
            vids = vids.map { if (it.id == video.id) it.copy(favorite = newFav) else it }
            feedVids = feedVids.map { if (it.id == video.id) it.copy(favorite = newFav) else it }
            // 更新收藏列表
            favs = if (newFav) (favs + video.copy(favorite = true)).distinctBy { it.id }
                    else favs.filter { it.id != video.id }
        }
    }

    /** 加载收藏列表 */
    fun loadFavorites() {
        viewModelScope.launch {
            favs = withContext(Dispatchers.IO) { db.videoDao().favorites() }
        }
    }

    // ---------- 播放历史 / 断点续播 ----------
    /** 记录播放进度(secs=已播秒数,durSec=总时长秒数,cid=当前分P) */
    fun recordProgress(videoId: Long, secs: Long, durSec: Int, cid: Long = 0) {
        viewModelScope.launch {
            val progress = if (durSec > 0) ((secs * 100) / durSec).toInt().coerceIn(0, 100) else 0
            val w = Watch(videoId = videoId, cid = cid, progress = progress, secs = secs, startedAt = System.currentTimeMillis())
            withContext(Dispatchers.IO) { db.watchDao().upsert(w) }
            // 同步历史列表
            history = withContext(Dispatchers.IO) { db.watchDao().history() }
        }
    }

    /** 取某视频上次进度(秒),无记录返回 0 */
    suspend fun lastPosition(videoId: Long): Long =
        withContext(Dispatchers.IO) { db.watchDao().get(videoId)?.secs ?: 0L }

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
@Composable
fun HomeScreen(vm: BiliViewModel, onAddUp: (String) -> Unit = {}, onPlay: (Video) -> Unit = {}, isTablet: Boolean = false) {
    var input by remember { mutableStateOf("") }        // 输入框内容(仅临时)
    var showUpPicker by remember { mutableStateOf(false) }

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
                placeholder = { Text("搜索已添加 UP 的视频", color = Color(0xFF8E8E93), fontSize = 13.sp) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFFF2F2F7),
                    unfocusedContainerColor = Color(0xFFF2F2F7),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color(0xFF1C1C1E)),
                modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { doSearch() }))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { doSearch() },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                modifier = Modifier.height(46.dp)) {
                Text("搜索", color = Color.White)
            }
        }

        // 排序 + UP 筛选行
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // 综合 / 播放量 / 按时间
            listOf("综合", "播放量", "按时间").forEachIndexed { i, label ->
                Surface(
                    color = if (vm.homeSortMode == i) Color(0xFF1C1C1E) else Color.White,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    border = if (vm.homeSortMode == i) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E5EA)),
                    modifier = Modifier.clickable { vm.homeSortMode = i }
                ) {
                    Text(label, fontSize = 12.sp,
                        color = if (vm.homeSortMode == i) Color.White else Color(0xFF1C1C1E),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            // UP 筛选
            Surface(
                color = if (vm.homeFilterMids.isNotEmpty()) Color(0xFF1C1C1E) else Color.White,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                border = if (vm.homeFilterMids.isNotEmpty()) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E5EA)),
                modifier = Modifier.clickable { showUpPicker = true }
            ) {
                Text(
                    if (vm.homeFilterMids.isEmpty()) "全部UP主"
                    else if (vm.homeFilterMids.size == 1) upName[vm.homeFilterMids.first()] ?: "已选1个"
                    else "已选${vm.homeFilterMids.size}个UP",
                    fontSize = 12.sp,
                    color = if (vm.homeFilterMids.isNotEmpty()) Color.White else Color(0xFF1C1C1E),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }

        // UP 选择弹层
        if (showUpPicker) {
            AlertDialog(
                onDismissRequest = { showUpPicker = false },
                confirmButton = { TextButton(onClick = { showUpPicker = false }) { Text("确定", color = Color(0xFF1C1C1E)) } },
                dismissButton = { TextButton(onClick = { vm.homeFilterMids = emptySet() }) { Text("清除", color = Color(0xFF8E8E93)) } },
                title = { Text("选择UP主(可多选)", color = Color(0xFF1C1C1E), fontSize = 16.sp) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(vm.ups) { u ->
                            val on = u.id in vm.homeFilterMids
                            Surface(
                                color = if (on) Color(0xFF1C1C1E) else Color.White,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                    .clickable {
                                        vm.homeFilterMids = if (on) vm.homeFilterMids - u.id else vm.homeFilterMids + u.id
                                    }
                            ) {
                                Text(u.name, color = if (on) Color.White else Color(0xFF1C1C1E),
                                    fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                },
                containerColor = Color.White
            )
        }

        if (vm.ups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("尚未添加 UP\n去「我的」→「管理UP主」添加", color = Color(0xFF8E8E93),
                    fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        if (vm.newCount > 0 && !vm.checking) {
            Text("已自动更新 ${vm.newCount} 个新视频", color = Color(0xFF8E8E93), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (vm.msg.isNotEmpty() && vm.syncing) {
            Text(vm.msg, color = Color(0xFF8E8E93), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
        }

        // bvid 查找结果(单条)
        if (vm.homeBvidMode) {
            val r = vm.bvidResult
            if (vm.searching) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF1C1C1E))
                }
            } else if (r != null) {
                Column(Modifier.padding(16.dp)) {
                    VideoRow(r, upName[r.upId] ?: "", onClick = { onPlay(r) })
                    Spacer(Modifier.height(8.dp))
                    Text("输入「BV 开头的视频码」可直接查找视频", color = Color(0xFF8E8E93), fontSize = 11.sp)
                }
            } else if (vm.msg.isNotEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(vm.msg, color = Color(0xFF8E8E93), fontSize = 13.sp)
                }
            }
            return@Column
        }

        // 过滤 + 排序
        var base = vm.feedVids
        if (vm.homeFilterMids.isNotEmpty()) base = base.filter { it.upId in vm.homeFilterMids }
        // 宽松关键词匹配:拆分空格,任意一个词命中标题即可
        if (vm.homeKeyword.isNotBlank()) {
            val words = vm.homeKeyword.split(Regex("\\s+")).filter { it.isNotBlank() }
            base = base.filter { v ->
                words.any { w -> v.title.contains(w, ignoreCase = true) }
            }
        }
        val feed = when (vm.homeSortMode) {
            1 -> base.sortedByDescending { it.playCount }   // 播放量
            2 -> base.sortedByDescending { it.pubdate }     // 按时间(新→旧)
            else -> base                                    // 综合(保持原随机顺序)
        }
        if (feed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (vm.homeKeyword.isNotBlank()) "没有匹配的视频" else "暂无视频", color = Color(0xFF8E8E93))
            }
            return@Column
        }
        if (isTablet) {
            // 平板:多列网格(自适应列宽,横屏更多列)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                gridItems(feed) { v -> VideoCardVertical(v, upName[v.upId] ?: "",
                    onClick = { onPlay(v) }, onToggleFav = { vm.toggleFavorite(v) }) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                items(feed) { v -> VideoRow(v, upName[v.upId] ?: "", onClick = { onPlay(v) },
                    onToggleFav = { vm.toggleFavorite(v) }) }
            }
        }
    }
}

/** 平板竖版卡片:上封面,下标题/UP/信息 */
@Composable
private fun VideoCardVertical(v: Video, upName: String, onClick: () -> Unit, onToggleFav: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = BILICARD), onClick = onClick,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            if (v.pic.isNotEmpty()) {
                AsyncImage(model = v.pic, contentDescription = v.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(Color(0xFFE5E5EA)))
            }
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.title, color = Color(0xFF1C1C1E), fontSize = 14.sp, maxLines = 2,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onToggleFav, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(if (v.favorite) "★" else "☆",
                            color = if (v.favorite) Color(0xFFFFB300) else Color(0xFF8E8E93),
                            fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text((if (upName.isNotEmpty()) "$upName" else "") +
                     (if (v.playCount > 0) " · ${fmtPlay(v.playCount)}播放" else ""),
                     color = Color(0xFF8E8E93), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** 单条横版大卡:左侧封面,右侧标题/UP/信息 */
@Composable
fun VideoRow(v: Video, upName: String = "", onClick: () -> Unit = {}, onToggleFav: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
        onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 封面(16:9 缩略)
            if (v.pic.isNotEmpty()) {
                AsyncImage(model = v.pic, contentDescription = v.title,
                    modifier = Modifier.width(120.dp).height(68.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(Color(0xFFE5E5EA)))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(v.title, color = Color(0xFF1C1C1E), fontSize = 14.sp, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text((if (upName.isNotEmpty()) "$upName · " else "") +
                     "${v.durationSec / 60}:${(v.durationSec % 60).toString().padStart(2, '0')}" +
                     (if (v.playCount > 0) " · ${fmtPlay(v.playCount)}播放" else ""),
                     color = Color(0xFF8E8E93), fontSize = 11.sp)
            }
            if (onToggleFav != null) {
                TextButton(onClick = onToggleFav) {
                    Text(if (v.favorite) "★" else "☆",
                        color = if (v.favorite) Color(0xFFFFB300) else Color(0xFF8E8E93),
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
        AsyncImage(model = normalizeCover(face), contentDescription = name,
            modifier = Modifier.size(size).clip(CircleShape).background(Color(0xFFE5E5EA)))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Color(0xFFE5E5EA)),
            contentAlignment = Alignment.Center) {
            Text(name.take(1), color = Color(0xFF8E8E93), fontSize = (size.value * 0.4f).sp)
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
        Text("待学习队列(骨架) · 精读需写总结", color = Color(0xFF8E8E93), fontSize = 12.sp)
        Text("已学习队列 · 0", color = Color(0xFF8E8E93), fontSize = 12.sp)
    }
}

@Composable
fun ReportScreen(vm: BiliViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("学习报告 · 本周", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1C1C1E))
        Text("学习时长 0 分钟 · 已看 0 · 精读 0 · 平均完成度 0%", color = Color(0xFF8E8E93), fontSize = 13.sp)
    }
}


@Composable
fun ProfileScreen(vm: BiliViewModel, onLoggedOut: () -> Unit = {}, onPlay: (Video) -> Unit = {}, onPlayWithCid: (Video, Long, Long) -> Unit = { _, _, _ -> }, onPlayBookmark: (Video, Long, Long) -> Unit = { _, _, _ -> }) {
    val ctx = LocalContextSafe()
    var showManage by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showFavs by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }

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
    if (showDisclaimer) {
        DisclaimerScreen(onBack = { showDisclaimer = false })
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // ---- 用户资料头:头像 + 用户名 + 签名 ----
        Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = vm.face.ifEmpty { null },
                    contentDescription = "头像",
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(Color(0xFFE5E5EA))
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.uname.ifBlank { "B站用户" }, color = Color(0xFF1C1C1E),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 18.sp)
                    if (vm.sign.isNotBlank())
                        Text(vm.sign, color = Color(0xFF8E8E93), fontSize = 12.sp, maxLines = 2)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 收藏 入口 ----
        OutlinedButton(onClick = { vm.loadFavorites(); showFavs = true },
            modifier = Modifier.fillMaxWidth()) {
            Text("收藏", color = Color(0xFF1C1C1E))
        }

        Spacer(Modifier.height(12.dp))

        // ---- 历史记录 入口 ----
        OutlinedButton(onClick = { vm.loadHistory(); showHistory = true },
            modifier = Modifier.fillMaxWidth()) {
            Text("历史记录", color = Color(0xFF1C1C1E))
        }

        Spacer(Modifier.height(12.dp))

        // ---- 视频书签 入口 ----
        OutlinedButton(onClick = { vm.loadBookmarks(); showBookmarks = true },
            modifier = Modifier.fillMaxWidth()) {
            Text("视频书签", color = Color(0xFF1C1C1E))
        }

        Spacer(Modifier.height(12.dp))

        // ---- 管理UP主 入口 ----
        OutlinedButton(onClick = { showManage = true },
            modifier = Modifier.fillMaxWidth()) {
            Text("管理UP主", color = Color(0xFF1C1C1E))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { showAccount = true },
            modifier = Modifier.fillMaxWidth()) {
            Text("账号管理", color = Color(0xFF1C1C1E))
        }

        Spacer(Modifier.height(12.dp))
        // ---- 免责声明(最后一个按键) ----
        OutlinedButton(onClick = { showDisclaimer = true },
            modifier = Modifier.fillMaxWidth()) {
            Text("免责声明", color = Color(0xFF8E8E93))
        }

        // 底部占满,QQ 群文字贴最下方(加粗放大)
        Spacer(Modifier.weight(1f))
        Text("QQ交流群：811598424", color = Color(0xFF1C1C1E), fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
    }
}

/** 收藏列表 */
@Composable
private fun FavoritesScreen(vm: BiliViewModel, onBack: () -> Unit, onPlay: (Video) -> Unit) {
    val upName = remember(vm.ups) { vm.ups.associate { it.id to it.name } }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF1C1C1E)) }
            Text("收藏", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1C1C1E))
        }
        Spacer(Modifier.height(12.dp))
        if (vm.favs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无收藏\n在首页点视频右侧的 ☆ 收藏", color = Color(0xFF8E8E93),
                    fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.favs) { v ->
                VideoRow(v, upName[v.upId] ?: "", onClick = { onPlay(v) },
                    onToggleFav = { vm.toggleFavorite(v) })
            }
        }
    }
}

/** 视频书签列表 */
@Composable
private fun BookmarkListScreen(vm: BiliViewModel, onBack: () -> Unit, onPlay: (Video) -> Unit, onPlayBookmark: (Video, Long, Long) -> Unit) {
    val videoByBvid = remember(vm.vids) { vm.vids.associateBy { it.bvid } }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF1C1C1E)) }
            Text("视频书签", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1C1C1E))
        }
        Spacer(Modifier.height(12.dp))
        if (vm.bookmarks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无书签\n播放视频时点书签按钮标记", color = Color(0xFF8E8E93),
                    fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.bookmarks) { b ->
                var renaming by remember(b.id) { mutableStateOf(false) }
                var note by remember(b.id) { mutableStateOf(b.note) }
                val v = videoByBvid[b.bvid]
                Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                    onClick = {
                        if (v != null) onPlayBookmark(v, b.cid, b.timeSec)
                    },
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        // 标题（颜色比备注浅）
                        Text(b.videoTitle, color = Color(0xFF8E8E93), fontSize = 13.sp, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        // 备注（黑体、字号更大、深黑，突出）
                        if (b.note.isNotEmpty()) {
                            Text(b.note, color = Color(0xFF1C1C1E), fontSize = 17.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2)
                            Spacer(Modifier.height(4.dp))
                        }
                        // 分P + 时间点（次级信息）
                        Text((if (b.pageTitle.isNotEmpty()) "${b.pageTitle} · " else "") +
                             "@ ${b.timeSec / 60}:${(b.timeSec % 60).toString().padStart(2, '0')}",
                             color = Color(0xFF8E8E93), fontSize = 12.sp)
                        if (renaming) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = note, onValueChange = { note = it },
                                    singleLine = true, modifier = Modifier.weight(1f),
                                    label = { Text("备注") })
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { vm.renameBookmark(b.id, note); renaming = false }) { Text("确定", color = Color(0xFF1C1C1E)) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { renaming = true }) { Text("重命名", color = Color(0xFF8E8E93), fontSize = 12.sp) }
                        TextButton(onClick = { vm.deleteBookmark(b.id) }) { Text("删除", color = Color(0xFFFF3B30), fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

/** 播放历史(带进度,点击续播) */
@Composable
private fun HistoryScreen(vm: BiliViewModel, onBack: () -> Unit, onPlay: (Video) -> Unit, onPlayWithCid: (Video, Long, Long) -> Unit) {
    val upName = remember(vm.ups) { vm.ups.associate { it.id to it.name } }
    val videoById = remember(vm.vids) { vm.vids.associateBy { it.id } }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF1C1C1E)) }
            Text("历史记录", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1C1C1E))
            Spacer(Modifier.weight(1f))
            if (vm.history.isNotEmpty()) {
                TextButton(onClick = { vm.clearHistory() }) { Text("清空", color = Color(0xFF8E8E93)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (vm.history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无播放历史", color = Color(0xFF8E8E93), fontSize = 13.sp)
            }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.history) { w ->
                val v = videoById[w.videoId]
                Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                    onClick = { v?.let { onPlayWithCid(it, w.cid, w.secs) } }) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (v != null && v.pic.isNotEmpty()) {
                            AsyncImage(model = v.pic, contentDescription = v.title,
                                modifier = Modifier.width(120.dp).height(68.dp)
                                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFFE5E5EA)))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(v?.title ?: "已删除的视频", color = Color(0xFF1C1C1E), fontSize = 14.sp, maxLines = 2)
                            Spacer(Modifier.height(4.dp))
                            Text((v?.let { upName[it.upId] ?: "" }?.let { "$it · " } ?: "") +
                                 "看到 ${w.secs / 60}:${(w.secs % 60).toString().padStart(2, '0')}",
                                 color = Color(0xFF8E8E93), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 免责声明 */
@Composable
private fun DisclaimerScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF1C1C1E)) }
            Text("免责声明", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1C1C1E))
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("使用须知", color = Color(0xFF1C1C1E), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            item { Text(DISCLAIMER_TEXT, color = Color(0xFF3A3A3C), fontSize = 13.sp, lineHeight = 20.sp) }
        }
    }
}

private val DISCLAIMER_TEXT = """
1. 本应用（BiliLite）是一款仅供个人学习、研究用途的第三方工具，不隶属于哔哩哔哩（bilibili）或其关联公司，与哔哩哔哩官方无任何合作关系。

2. 本应用展示的全部内容（包括但不限于视频、封面、标题、UP 主信息等）均来自哔哩哔哩公开接口，其著作权、商标权等知识产权归原作者及哔哩哔哩平台所有。

3. 本应用仅提供技术性的浏览与学习辅助功能，不对任何内容的合法性、准确性、时效性负责。用户应遵守中华人民共和国相关法律法规及哔哩哔哩用户协议、社区规范。

4. 用户使用本应用所进行的登录、观看、收藏等行为，均基于用户本人主动操作。因用户违反平台规则、法律法规或第三方权益所产生的任何后果，由用户自行承担。

5. 本应用不存储、不上传、不传播任何受版权保护的内容，所有内容均实时获取自官方接口，旨在方便用户进行学习场景下的内容整理。

6. 开发者不参与、不支持任何形式的侵权或盗版行为。若权利人认为相关内容侵害其合法权益，请及时通过平台官方渠道处理，开发者可在收到通知后配合下架相关功能。

7. 本应用为免费开源性质，开发者不对因使用本应用造成的任何直接或间接损失承担责任。

8. 开发者保留对本应用及本声明的最终解释权。使用本应用即视为您已阅读并同意上述全部条款。
""".trimIndent()

/** 账号管理:退出登录(带二次确认) */
@Composable
private fun AccountScreen(vm: BiliViewModel, onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val ctx = LocalContextSafe()
    var confirmLogout by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF1C1C1E)) }
            Text("账号管理", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1C1C1E))
        }
        Spacer(Modifier.height(16.dp))
        Text("退出登录后,本地已添加的 UP 与学习记录将被清除。", color = Color(0xFF8E8E93), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (confirmLogout) {
            Text("确认退出登录?", color = Color(0xFF1C1C1E), fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { LoginSession.clear(ctx); onLoggedOut() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                    modifier = Modifier.weight(1f)) {
                    Text("确认退出", color = Color.White)
                }
                OutlinedButton(onClick = { confirmLogout = false }, modifier = Modifier.weight(1f)) {
                    Text("取消", color = Color(0xFF1C1C1E))
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

/** 管理UP主:两个入口(添加 / 删除) */
@Composable
fun ManageUPScreen(vm: BiliViewModel, onBack: () -> Unit = {}) {
    var mode by remember { mutableStateOf(0) } // 0 首页(两个按钮) 1 添加 2 删除
    BackHandler { if (mode == 0) onBack() else mode = 0 }

    if (mode == 0) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF1C1C1E)) }
                Text("管理UP主", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1C1C1E))
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { mode = 1 },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("添加UP主", color = Color.White, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { mode = 2 },
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("删除UP主", color = Color(0xFF1C1C1E), fontSize = 16.sp)
            }
        }
        return
    }

    if (mode == 1) {
        AddUpScreen(vm, onBack = { mode = 0 })
        return
    }

    // mode == 2: 删除UP主(带二次确认)
    DeleteUpScreen(vm, onBack = { mode = 0 })
}

/** 添加 UP:搜索 + 添加 */
@Composable
private fun AddUpScreen(vm: BiliViewModel, onBack: () -> Unit) {
    var kw by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF1C1C1E)) }
            Text("添加UP主", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1C1C1E))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(value = kw, onValueChange = { kw = it },
                placeholder = { Text("搜索 UP 主名", color = Color(0xFF8E8E93), fontSize = 13.sp) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFFF2F2F7),
                    unfocusedContainerColor = Color(0xFFF2F2F7),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color(0xFF1C1C1E)),
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { submitted = kw; vm.searchUps(kw) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E))) {
                Text("搜索", color = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.msg.isNotEmpty()) Text(vm.msg, color = Color(0xFF8E8E93), fontSize = 12.sp)
        LazyColumn {
            items(vm.searchResults) { up ->
                val added = remember(up.id, vm.ups) { vm.ups.any { it.id == up.id } }
                Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        // UP 头像
                        UpAvatar(up.face, up.name, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(up.name, color = Color(0xFF1C1C1E), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text(if (up.fans > 0) "粉丝 ${fmtFans(up.fans)}" else "—", color = Color(0xFF8E8E93), fontSize = 12.sp)
                        }
                        if (added) {
                            Text("已添加", color = Color(0xFF8E8E93), fontSize = 12.sp)
                        } else {
                            Button(onClick = { vm.addUpAndSync(up) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E))) { Text("添加", color = Color.White) }
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
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF1C1C1E)) }
            Text("删除UP主", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1C1C1E))
        }
        Spacer(Modifier.height(12.dp))
        if (vm.ups.isEmpty()) {
            Text("尚未添加任何 UP", color = Color(0xFF8E8E93), fontSize = 13.sp)
            return@Column
        }
        LazyColumn {
            items(vm.ups) { u ->
                Card(colors = CardDefaults.cardColors(containerColor = BILICARD),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        UpAvatar(u.face, u.name, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(u.name, color = Color(0xFF1C1C1E), modifier = Modifier.weight(1f))
                        if (confirmMid == u.id) {
                            Row {
                                TextButton(onClick = { vm.removeUp(u.id); confirmMid = null }) {
                                    Text("确认删除", color = Color(0xFFFF3B30))
                                }
                                TextButton(onClick = { confirmMid = null }) { Text("取消", color = Color(0xFF8E8E93)) }
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
