package com.bililite.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.view.TextureView
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bililite.core.BiliApi
import com.bililite.core.C
import com.bililite.data.Bookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

/** 分P信息 */
data class PageInfo(val cid: Long, val part: String)

/** 沉浸式:隐藏状态栏+导航栏(API30+ 用 WindowInsetsController) */
private fun setImmersive(activity: Activity?, on: Boolean) {
    val a = activity ?: return
    try {
        if (on) {
            if (Build.VERSION.SDK_INT >= 30) {
                a.window.setDecorFitsSystemWindows(false)
                a.window.insetsController?.apply {
                    hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                a.window.decorView.systemUiVisibility =
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            if (Build.VERSION.SDK_INT >= 30) {
                a.window.setDecorFitsSystemWindows(true)
                a.window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                a.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    } catch (_: Exception) {}
}

@Composable
fun PlayerScreen(
    ctx: Context, api: BiliApi,
    bvid: String, cid: Long, title: String,
    onBack: () -> Unit,
    resolveCid: (suspend (String) -> Long)? = null,
    upName: String = "",
    upFace: String = "",
    videoId: Long = 0,
    durationSec: Int = 0,
    playCount: Long = 0,
    pubdate: Long = 0,
    loadInitialSec: (suspend (Long) -> Long)? = null,
    onProgress: (Long) -> Unit = {},
    onProgressCid: (Long, Long) -> Unit = { _, _ -> },
    onFlush: (Long) -> Unit = {},
    bookmarkSeekSec: Long? = null,
    onBookmarkConsumed: () -> Unit = {},
    bookmarks: List<Bookmark> = emptyList(),
    onAddBookmark: (bvid: String, videoTitle: String, cid: Long, pageIndex: Int, pageTitle: String, timeSec: Long) -> Unit = { _, _, _, _, _, _ -> },
    onRenameBookmark: (Long, String) -> Unit = { _, _ -> },
    onDeleteBookmark: (Long) -> Unit = {},
    relatedVideos: List<com.bililite.data.Video> = emptyList(),
    onPlayOther: (com.bililite.data.Video) -> Unit = {},
    playlist: List<com.bililite.data.Video> = emptyList(),
    onEnded: () -> Unit = {},
    localPath: String? = null,
    // v0.4.20: 按当前分P cid 查缓存的函数。用于修复"合集中缓存了某P,其它P也显示已缓存"——
    // localPath 是进入视频时算的一次性值,切分P后不会更新;改用 resolver 随 curCid 动态查。
    cachedPathResolver: ((Long) -> String?)? = null,
    onCache: ((Int, Long) -> Unit)? = null,
    caching: Boolean = false,
    cacheMsg: String = ""
) {
    // v0.4.4 修复:所有与视频相关的状态都按 bvid 键控。
    // 之前 curCid/pages/movablePlayer 未键控,点"更多视频"切换 bvid 时,
    // 标题更新了但播放器仍持有旧的 bvid/cid(视频不切换、退出重进卡死)。
    var pages by remember(bvid) { mutableStateOf<List<PageInfo>>(emptyList()) }
    var curCid by remember(bvid) { mutableStateOf(cid) }
    // v0.4.20: 当前分P是否已缓存(随 curCid 动态);有 resolver 时以它为准,否则退回外部传入的 localPath
    val effectiveLocalPath = if (cachedPathResolver != null) {
        cachedPathResolver(curCid)
    } else localPath
    // v0.4.20 修复:断点续播只对「首次进入的那个分P」生效。切到其他分P时,
    // initialSec 仍是上一个P的进度,会导致"切P跳到上一P的上次播放位置"。
    // 用 firstCid 记录首次进入的分P,只有 curCid == firstCid 才应用断点。
    var firstCid by remember(bvid) { mutableStateOf(cid) }
    var err by remember(bvid) { mutableStateOf("") }
    var initialSec by remember(bvid) { mutableStateOf(0L) }
    var desc by remember(bvid) { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var toast by remember(bvid) { mutableStateOf("") }
    // 书签 seek 请求（侧栏点击书签 → 传递到 Player 触发 seekTo）
    var seekRequest by remember(bvid) { mutableStateOf<Long?>(null) }
    // 缩放状态（由 Player 回调更新），用于返回手势撤销缩放
    var zoomed by remember(bvid) { mutableStateOf(false) }
    var resetZoomTrigger by remember(bvid) { mutableStateOf(0) }
    // v0.3: 播放结束信号(由 Player 在 STATE_ENDED 时递增)
    var endedSignal by remember(bvid) { mutableStateOf(0) }
    // v0.3.1: 自动连播开关(记住用户选择)
    var autoNext by remember {
        mutableStateOf(ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).getBoolean("auto_next", true))
    }

    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()

    // v0.3: 播放结束 → 先看有没有下一P(分P连播),没有则请求连播列表的下一集
    LaunchedEffect(endedSignal) {
        if (endedSignal > 0 && autoNext) {
            val idx = pages.indexOfFirst { it.cid == curCid }
            val nextP = pages.getOrNull(idx + 1)
            if (nextP != null && nextP.cid != curCid) {
                curCid = nextP.cid       // 自动切下一P
            } else {
                onEnded()                // 请求下一集(由上层切换视频)
            }
        }
    }

    // toast 提示
    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) { delay(1500); toast = "" }
    }

    // 读取上次进度
    LaunchedEffect(videoId) {
        if (videoId != 0L && loadInitialSec != null) {
            initialSec = try { withContext(Dispatchers.IO) { loadInitialSec(videoId) } } catch (_: Exception) { 0L }
        }
    }
    // 拉简介
    LaunchedEffect(bvid) {
        try {
            val d = withContext(Dispatchers.IO) { api.videoView(bvid) }.optJSONObject("data")
            desc = d?.optString("desc", "") ?: ""
        } catch (_: Exception) {}
    }
    // 解析分P
    LaunchedEffect(bvid) {
        try {
            val arr = withContext(Dispatchers.IO) { api.pagelist(bvid) }
            pages = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                if (o == null) null else PageInfo(o.optLong("cid", 0L), o.optString("part", "P${i + 1}"))
            }
            if (curCid == 0L) { curCid = pages.firstOrNull()?.cid ?: 0L; firstCid = curCid }
            if (curCid == 0L && resolveCid != null) { curCid = withContext(Dispatchers.IO) { resolveCid(bvid) }; firstCid = curCid }
        } catch (_: Exception) {}
    }

    // 返回手势：全屏→退全屏；缩放→撤销缩放；否则交给上层的 onBack
    BackHandler(enabled = fullscreen || zoomed) {
        when {
            zoomed -> { resetZoomTrigger++; zoomed = false }
            fullscreen -> { fullscreen = false; setImmersive(activity, false) }
        }
    }
    DisposableEffect(Unit) { onDispose { setImmersive(activity, false) } }

    // 当前分P序号/标题（用于书签记录）
    val curPageIdx = pages.indexOfFirst { it.cid == curCid }.coerceAtLeast(0)
    val curPageTitle = pages.getOrNull(curPageIdx)?.part ?: ""

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) {
                Surface(color = C.bg) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
                        Text(title, color = C.t1, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }

            // Player 单一实例（可移动）。键控 bvid:切换视频时重建播放器,避免复用旧 bvid/cid。
            val movablePlayer = remember(bvid) {
                movableContentOf {
                    Player(api, bvid, curCid,
                        activity = activity,
                        onToggleFullscreen = { fullscreen = !fullscreen; setImmersive(activity, fullscreen) },
                        fullscreen = fullscreen,
                        modifier = Modifier.fillMaxSize(),
initialSec = if (bookmarkSeekSec != null) bookmarkSeekSec
                            else if (curCid == firstCid) initialSec else 0L,
                        videoDurSec = durationSec,
                        onSeeked = { if (bookmarkSeekSec != null) onBookmarkConsumed() },
                        onProgress = { secs ->
                            onProgress(secs)
                            onProgressCid(secs, curCid)
                        },
                        onFlush = { secs -> onFlush(secs) },
                        onEndedSignal = { endedSignal++ },
                        onAddBookmark = {
                            onAddBookmark(bvid, title, curCid, curPageIdx, curPageTitle, it / 1000)
                            toast = "标记成功"
                        },
                        seekRequest = seekRequest,
                        onSeekConsumed = { seekRequest = null },
                        onZoomChanged = { zoomed = it },
                        resetZoomTrigger = resetZoomTrigger,
                        localPath = effectiveLocalPath,
                        onCache = onCache,
                        caching = caching,
                        cacheMsg = cacheMsg
                    )
                }
            }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val isWide = maxWidth >= 600.dp
                // v0.4.9: 书签按当前视频隔离(之前传全部书签,不同视频共用书签列表)
                val curBookmarks = remember(bvid, bookmarks) { bookmarks.filter { it.bvid == bvid } }
                if (fullscreen) {
                    movablePlayer()
                } else if (isWide) {
                    Row(Modifier.fillMaxSize().padding(12.dp)) {
                        Box(Modifier.fillMaxHeight().weight(2f)) { movablePlayer() }
                        Spacer(Modifier.width(12.dp))
                        InfoPanel(upName, upFace, pubdate, title, playCount, desc, pages, curCid,
                            bookmarks = curBookmarks,
                            onSelectPage = { curCid = it },
                            onBookmarkClick = { seekRequest = it.timeSec * 1000 },
                            onRename = { id, note -> onRenameBookmark(id, note) },
                            onDelete = { id -> onDeleteBookmark(id) },
                            relatedVideos = relatedVideos,
                            onPlayOther = onPlayOther,
                            modifier = Modifier.fillMaxHeight().weight(1f))
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(0.30f)) { movablePlayer() }
                        Spacer(Modifier.height(10.dp))
                        // v0.3: 信息区改为整体可滚动(LazyColumn 平铺),
                        // 下拉可见 分P/连播提示/同 UP 主相关学习视频
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                            if (upName.isNotEmpty()) item { Text("UP主：$upName", color = C.t2, fontSize = 12.sp); }
                            if (curBookmarks.isNotEmpty()) item { Text("书签 ${curBookmarks.size}", color = C.t2, fontSize = 11.sp) }
                            item { Text(title, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2) }
                            if (playCount > 0) item { Text("${fmtPlayCount(playCount)}播放", color = C.t2, fontSize = 12.sp) }
                            // v0.3.1: 断点续播提示"上次看到 xx:xx"
                            if (initialSec > 0) {
                                item { Text("上次看到 ${fmtTime(initialSec)}", color = Color(0xFF1C88E8), fontSize = 12.sp) }
                            }
                            if (desc.isNotBlank()) item { Text(desc, color = C.t3, fontSize = 12.sp, maxLines = 4) }
                            // v0.3.1: 自动连播开关
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (playlist.size > 1) "自动连播(共 ${playlist.size} 集)" else "自动连播",
                                        color = C.t2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Switch(checked = autoNext, onCheckedChange = {
                                        autoNext = it
                                        ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).edit()
                                            .putBoolean("auto_next", it).apply()
                                    })
                                }
                            }
                            if (pages.size > 1) {
                                item { Text("分P · 共 ${pages.size} 集", color = C.t2, fontSize = 12.sp) }
                                items(pages, key = { "p${it.cid}" }) { p ->
                                    val selected = p.cid == curCid
                                    Surface(color = if (selected) C.block else C.card,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                            .clickable { curCid = p.cid }) {
                                        Text(p.part, color = if (selected) C.onBlock else C.t1,
                                            fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                                    }
                                }
                            }
                            // v0.3: 同 UP 主相关视频(避免全站推荐混入娱乐内容)
                            if (relatedVideos.isNotEmpty()) {
                                item { Text(if (upName.isNotEmpty()) "更多 ${upName} 的视频" else "相关视频",
                                    color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                items(relatedVideos, key = { "r${it.id}" }) { rv ->
                                    Surface(color = C.card,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().clickable { onPlayOther(rv) }) {
                                        Row(Modifier.fillMaxWidth().padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            if (rv.pic.isNotEmpty()) {
                                                coil.compose.AsyncImage(model = rv.pic, contentDescription = rv.title,
                                                    modifier = Modifier.width(96.dp).height(54.dp)
                                                        .clip(RoundedCornerShape(6.dp)).background(C.line))
                                                Spacer(Modifier.width(10.dp))
                                            }
                                            Column(Modifier.weight(1f)) {
                                                Text(rv.title, color = C.t1, fontSize = 13.sp, maxLines = 2)
                                                Text("${rv.durationSec / 60}:${(rv.durationSec % 60).toString().padStart(2, '0')}",
                                                    color = C.t2, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
            }
        }

        // 顶部 toast
        if (toast.isNotEmpty()) {
            Surface(color = Color(0xE61C1C1E), shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) {
                Text("标记成功", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

/** 时间戳(秒) → yyyy-MM-dd */
private fun fmtDate(sec: Long): String =
    if (sec <= 0) "" else try {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(sec * 1000))
    } catch (_: Exception) { "" }

private fun fmtPlayCount(c: Long): String = when {
    c >= 100000000 -> "%.1f亿".format(c / 100000000.0)
    c >= 10000 -> "%.1f万".format(c / 10000.0)
    else -> c.toString()
}

private fun fmtTime(sec: Long): String =
    "%02d:%02d".format(sec / 60, sec % 60)

/** qn 数值 → 清晰度名称 */
private fun qnName(qn: Int): String = when (qn) {
    127 -> "8K超高清"
    126 -> "杜比视界"
    125 -> "HDR真彩"
    120 -> "4K超清"
    116 -> "1080P60帧"
    112 -> "1080P高码率"
    100 -> "智能修复"
    80 -> "1080P"
    74 -> "720P60帧"
    64 -> "720P"
    32 -> "480P"
    16 -> "360P"
    6 -> "240P"
    else -> "清晰度$qn"
}

/** 右侧/下方信息面板:UP主 + 视频信息 + 分P + 书签列表 + 相关视频。
 *  v0.4.9 平板优化:内容区改为"分P/书签/相关"三个标签页切换,避免列表互相挤压显示差。 */
@Composable
private fun InfoPanel(upName: String, upFace: String, pubdate: Long, title: String,
                      playCount: Long, desc: String,
                      pages: List<PageInfo>, curCid: Long,
                      bookmarks: List<Bookmark>,
                      onSelectPage: (Long) -> Unit,
                      onBookmarkClick: (Bookmark) -> Unit,
                      onRename: (Long, String) -> Unit,
                      onDelete: (Long) -> Unit,
                      relatedVideos: List<com.bililite.data.Video> = emptyList(),
                      onPlayOther: (com.bililite.data.Video) -> Unit = {},
                      modifier: Modifier = Modifier) {
    Column(modifier.background(C.card).clip(RoundedCornerShape(10.dp)).padding(16.dp)) {
        if (upName.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (upFace.isNotEmpty()) {
                    coil.compose.AsyncImage(model = upFace, contentDescription = upName,
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(C.line))
                    Spacer(Modifier.width(10.dp))
                }
                Text(upName, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (pubdate > 0) { Spacer(Modifier.width(8.dp)); Text(fmtDate(pubdate), color = C.t2, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(title, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 3)
        if (playCount > 0) { Spacer(Modifier.height(6.dp)); Text("${fmtPlayCount(playCount)}播放", color = C.t2, fontSize = 12.sp) }
        if (desc.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(desc, color = C.t3, fontSize = 12.sp, maxLines = 4) }

        Spacer(Modifier.height(12.dp))

        // v0.4.9: 标签页切换(分P / 书签 / 相关视频)
        val hasPages = pages.size > 1
        val hasBookmarks = bookmarks.isNotEmpty()
        val hasRelated = relatedVideos.isNotEmpty()
        var selTab by remember { mutableStateOf(if (hasPages) 0 else if (hasBookmarks) 1 else 2) }
        // v0.4.9: 数据异步到达后自动修正选中标签(避免选中不可用的页签)
        LaunchedEffect(hasPages, hasBookmarks, hasRelated) {
            if (selTab == 0 && !hasPages) selTab = if (hasBookmarks) 1 else 2
            if (selTab == 1 && !hasBookmarks) selTab = if (hasPages) 0 else 2
            if (selTab == 2 && !hasRelated) selTab = if (hasPages) 0 else 1
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("分P" to hasPages, "书签(${bookmarks.size})" to hasBookmarks, "相关(${relatedVideos.size})" to hasRelated)
                .forEachIndexed { i, (label, enabled) ->
                    if (enabled) {
                        val on = selTab == i
                        Surface(
                            color = if (on) C.t1 else C.soft,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.clickable { selTab = i }
                        ) {
                            Text(label, fontSize = 12.sp,
                                color = if (on) C.onBlock else C.t1,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                        }
                    }
                }
        }
        Spacer(Modifier.height(8.dp))

        when (selTab) {
            1 -> LazyColumn(Modifier.weight(1f)) {
                items(bookmarks, key = { it.id }) { b ->
                    BookmarkRow(b, onClick = { onBookmarkClick(b) }, onRename = { onRename(b.id, it) }, onDelete = { onDelete(b.id) })
                }
            }
            2 -> LazyColumn(Modifier.weight(1f)) {
                items(relatedVideos, key = { "r${it.id}" }) { rv ->
                    Surface(color = C.soft, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onPlayOther(rv) }) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (rv.pic.isNotEmpty()) {
                                coil.compose.AsyncImage(model = rv.pic, contentDescription = rv.title,
                                    modifier = Modifier.width(80.dp).height(45.dp)
                                        .clip(RoundedCornerShape(6.dp)).background(C.line))
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(rv.title, color = C.t1, fontSize = 12.sp, maxLines = 2)
                            }
                        }
                    }
                }
            }
            else -> LazyColumn(Modifier.weight(1f)) {
                items(pages, key = { it.cid }) { p ->
                    val selected = p.cid == curCid
                    Surface(color = if (selected) C.t1 else C.soft,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onSelectPage(p.cid) }) {
                        Text(p.part, color = if (selected) C.onBlock else C.t1, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(b: Bookmark, onClick: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var note by remember(b.id) { mutableStateOf(b.note) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Bookmark, contentDescription = "书签", tint = C.t1, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(fmtTime(b.timeSec), color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (b.note.isNotEmpty()) Text(b.note, color = C.t2, fontSize = 11.sp)
    }
    if (renaming) {
        TextField(value = note, onValueChange = { note = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onRename(note); renaming = false }) { Text("确定", color = C.t1) }
            TextButton(onClick = { renaming = false }) { Text("取消", color = C.t2) }
        }
    } else {
        // 长按重命名 / 删除（用三个点的简化：这里用长按触发重命名）
        Box(Modifier.fillMaxWidth()) {
            TextButton(onClick = { renaming = true }, modifier = Modifier.align(Alignment.CenterEnd)) { Text("重命名", color = C.t2, fontSize = 11.sp) }
        }
    }
}

/** Player 组件：TextureView + 手势 + 自定义控制条 + 缩放 */
@Composable
private fun Player(
    api: BiliApi, bvid: String, cid: Long,
    activity: Activity?,
    onToggleFullscreen: () -> Unit,
    fullscreen: Boolean = false,
    modifier: Modifier = Modifier,
    initialSec: Long = 0,
    videoDurSec: Int = 0,
    onSeeked: () -> Unit = {},
    onProgress: (Long) -> Unit = {},
    onFlush: (Long) -> Unit = {},
    onEndedSignal: () -> Unit = {},
    onAddBookmark: (Long) -> Unit = {},
    seekRequest: Long? = null,
    onSeekConsumed: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {},
    resetZoomTrigger: Int = 0,
    localPath: String? = null,
    onCache: ((Int, Long) -> Unit)? = null,
    caching: Boolean = false,
    cacheMsg: String = ""
) {
    val ctx = LocalContext.current
    // v0.4.4: 自定义缓冲策略——1.5s 快速开播 + 更大缓冲上限,减少高码率下的反复卡顿
    val exo = remember {
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 20000,
                /* maxBufferMs */ 60000,
                /* bufferForPlaybackMs */ 1500,
                /* bufferForPlaybackAfterRebufferMs */ 3000)
            .build()
        ExoPlayer.Builder(ctx).setLoadControl(loadControl).build().apply {
            playWhenReady = true
            // v0.4.4: 优先选中文字幕轨道(与 subConfig 的 language=zh + SELECTION_FLAG_DEFAULT 配合,
            // 否则 DefaultTrackSelector 可能不自动选中字幕,导致 onCues 不触发、字幕不显示)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage("zh")
                .setPreferredTextRoleFlags(androidx.media3.common.C.ROLE_FLAG_SUBTITLE)
                .build()
        }
    }
    var url by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(true) }
    var posMs by remember { mutableStateOf(0L) }
    var durMs by remember { mutableStateOf(0L) }
    var showControls by remember { mutableStateOf(false) }
    // v0.4.19: 缓存清晰度选择菜单
    var showCacheQuality by remember { mutableStateOf(false) }

    // v0.4.19插件系统:把当前播放器挂到 PlayerBridge,供插件 player.* 控制;退出时解绑
    DisposableEffect(exo, bvid, cid) {
        com.bililite.plugin.PlayerBridge.attach(exo, mapOf(
            "bvid" to bvid, "cid" to cid,
            "title" to (com.bililite.plugin.PlayerBridge.currentVideo["title"] ?: ""),
            "duration" to (if (videoDurSec > 0) videoDurSec.toDouble() else 0.0)))
        onDispose {
            com.bililite.plugin.PlayerBridge.detach()
            com.bililite.plugin.EventBus.post("videoChanged", mapOf("bvid" to "", "cid" to 0L))
        }
    }
    // v0.4.4: 全屏锁定(锁定当前横屏方向,防止误旋转)
    var screenLocked by remember { mutableStateOf(false) }
    // 进度条拖动状态：拖动时用本地值，避免 posMs 500ms 更新打断拖动
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableStateOf(0f) }
    // 倍速（用户手动设置，长按临时 2x 不覆盖此值）。v0.3.1: 记住用户倍速
    var speed by remember {
        mutableStateOf(ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).getFloat("speed", 1.0f))
    }
    var longPressBoost by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    // 亮度/音量指示
    var indicator by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // 缩放
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zoomed by remember { mutableStateOf(false) }
    // 待 seek 的目标（毫秒），在 READY 后执行，解决书签/续播定位时机问题
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    // 初始 position 已应用标志（避免异步 initialSec 扰动重复 seek）
    // v0.4.8: 去掉键控(同字幕 bug——listener 挂在 LaunchedEffect(exo) 上,键变化后读旧 State,
    // 导致分P切换/续播 seek 失效)。重置逻辑已由加载 Effect 显式处理(initialSeekApplied = false)
    var initialSeekApplied by remember { mutableStateOf(false) }
    // v0.3: 上次上报进度的秒数(节流:≥4s 才回调一次,避免高频写库卡顿)
    var lastReportedSec by remember { mutableStateOf(-1L) }
    // v0.3.1: 缓冲进度(毫秒) + 重试触发 + 清晰度
    var bufferedMs by remember { mutableStateOf(0L) }
    var retryToken by remember { mutableStateOf(0) }
    var qualityList by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) } // qn -> 描述
    // v0.4.4: 清晰度全局统一——从 SharedPreferences 读取上次选择,而非每次重置为 64
    var currentQn by remember {
        // v0.4.19: 默认清晰度 1080P(qn=80);用户手动切换后仍持久化到 SharedPreferences
        mutableStateOf(ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).getInt("quality", 80))
    }
    // v0.4.4: 切换清晰度前保存的播放进度(毫秒)。LaunchedEffect 重载时优先用它恢复,
    // 避免重载瞬间 currentPosition 读到 0 导致"切清晰度跳回开头"。
    var qnSwitchPos by remember { mutableStateOf<Long?>(null) }
    // v0.4.5: 切清晰度前记住播放/暂停状态,重载后保持(原来强制 play,暂停中切清晰度会突然播放)
    var wasPlayingBeforeQnSwitch by remember { mutableStateOf<Boolean?>(null) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var qualityErr by remember { mutableStateOf("") }
    // v0.4.1: 字幕状态。v0.4.4 改为手动渲染:解析字幕 json 成 cue 列表,
    // 按播放进度直接取当前字幕文本显示(放弃 Media3 字幕管线,onCues 不稳定、不触发)。
    // v0.4.8 关键修复:这些状态曾被 remember(bvid,cid) 键控——cid 从 0 解析为真实值时
    // 会创建新的 State 对象,而 200ms 渲染循环挂在 LaunchedEffect(exo) 上仍读旧 State,
    // 导致"字幕已解析却不显示"(字幕自 v0.4.4 起从未真正显示过的根因)。
    // 改为无键控 remember,切换 bvid/cid 时在加载 Effect 中显式重置。
    var subtitleCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var hasSubtitle by remember { mutableStateOf(false) }
    var subtitleOn by remember { mutableStateOf(true) }
    var currentSubtitle by remember { mutableStateOf("") }
    // v0.4.13: 上次合法播放位置(毫秒),currentPosition 异常时沿用,避免字幕乱跳
    var lastSubPos by remember { mutableStateOf(0L) }
    // v0.4.9: 字幕字号/位置可调(持久化)。subSize: 0小 1中 2大;subPos: 0底 1中 2上
    var subSize by remember {
        mutableStateOf(ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).getInt("sub_size", 1))
    }
    var subPos by remember {
        mutableStateOf(ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).getInt("sub_pos", 0))
    }
    var showSubMenu by remember { mutableStateOf(false) }
    // AspectRatioFrameLayout 引用（用于按视频宽高比设置，避免拉伸）
    val arflRef = remember { mutableStateOf<androidx.media3.ui.AspectRatioFrameLayout?>(null) }

    // 当前清晰度显示名
    fun currentQnName(): String {
        if (currentQn == 0) return "自动"
        val q = qualityList.firstOrNull { it.first == currentQn }?.second
        if (q != null && q.isNotBlank()) return q
        // currentQn 不在可选列表(被 B 站按可用档降级) → 显示实际最高可用档
        val first = qualityList.firstOrNull()
        return if (first != null) first.second.ifBlank { qnName(first.first) } else qnName(currentQn)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // 拉取字幕(带本地缓存)。返回 cue 列表,无字幕返回空列表。
    // v0.4.5 修复:轨道选择按 人工中文(zh*且非 ai-zh)→AI中文(ai-zh)→任意 的优先级,
    // 并加日志记录每一步失败原因(可在「数据同步与日志」查看)。
    // v0.4.6: cid=0(尚未解析)时跳过;协程取消不记错误。
    suspend fun fetchSubtitleCues(): List<SubtitleCue> {
        if (cid == 0L) return emptyList()
        return try {
            val tracks = withContext(Dispatchers.IO) { api.subtitleTracks(bvid, cid) }
            if (tracks.isEmpty()) {
                com.bililite.core.BiliLog.i("Player", "无字幕轨道 bvid=$bvid cid=$cid(视频可能无 CC/AI 字幕)")
                return emptyList()
            }
            val track = tracks.firstOrNull { it.lan.contains("zh", ignoreCase = true) && it.aiType == 0 }
                ?: tracks.firstOrNull { it.lan.contains("zh", ignoreCase = true) }
                ?: tracks.first()
            com.bililite.core.BiliLog.i("Player", "选中字幕: ${track.lanDoc}(${track.lan}) aiType=${track.aiType} url=${track.subtitleUrl.take(80)}")
            val subUrl = track.subtitleUrl
            if (subUrl.isEmpty()) {
                com.bililite.core.BiliLog.e("Player", "字幕 url 为空 bvid=$bvid")
                return emptyList()
            }
            val json = withContext(Dispatchers.IO) { api.downloadText(subUrl) }
            // v0.4.16: 传入视频时长(秒)做单位判定,修复长视频字幕误判毫秒导致的"字幕加速"
            val cues = biliSubtitleToCues(json, videoDurSec)
            com.bililite.core.BiliLog.i("Player", "字幕解析: ${cues.size} 条 bvid=$bvid cid=$cid")
            cues
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.bililite.core.BiliLog.e("Player", "字幕获取失败 bvid=$bvid cid=$cid: ${e.message}", e)
            emptyList()
        }
    }

    // 拉取播放流(带重试)。返回 (StreamInfo?, 最后一次错误详情)
    suspend fun fetchStream(): Pair<BiliApi.StreamInfo?, String> {
        var lastErr = ""
        for (attempt in 1..3) {
            try {
                val s = withContext(Dispatchers.IO) { api.playStream(bvid, cid, currentQn) }
                if (s.videoUrl.isNotEmpty()) return s to ""
                lastErr = api.lastApiError.ifBlank { "接口返回空地址" }
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { lastErr = (e.message ?: e.javaClass.simpleName).take(80) }
            kotlinx.coroutines.delay(800L * attempt)
        }
        return null to lastErr
    }

    // 加载（依赖 bvid+cid+重试+画质;initialSec 变化不触发重载）
    // v0.4.3 修复:
    // ① 流与字幕并发获取,消除"字幕异步到达→retryToken++→整段重载跳回开头"的双重加载(即"点字幕视频跳回开头""加载慢"根因)
    // ② 重载(切清晰度/字幕开关/重试)时保留当前进度,不再跳回原点
    // v0.4.6: cid=0(尚未解析)时直接跳过,避免 -400 无效请求与错误日志;
    //         待 resolveCid 得到真实 cid 后本 Effect 会自动重启。
    LaunchedEffect(bvid, cid, retryToken, currentQn) {
        if (cid == 0L) return@LaunchedEffect
        err = ""
        qualityErr = ""
        // v0.4.4: 重载前优先用 qnSwitchPos(切清晰度时显式保存),否则读当前播放位置
        val resumePos = qnSwitchPos ?: exo.currentPosition
        qnSwitchPos = null
        val resumePlaying = wasPlayingBeforeQnSwitch
        wasPlayingBeforeQnSwitch = null
        if (resumePos > 0) {
            com.bililite.core.BiliLog.i("Player", "重载恢复进度 ${resumePos}ms (bvid=$bvid cid=$cid qn=$currentQn)")
        }
        exo.stop(); exo.clearMediaItems()
        pendingSeekMs = null
        initialSeekApplied = false
        // v0.4.8: 无键控后手动重置字幕状态(切换 bvid/cid/重试时清掉旧字幕)
        subtitleCues = emptyList(); hasSubtitle = false; currentSubtitle = ""; lastSubPos = 0

        // v0.4.1: 优先本地缓存播放(离线)
        if (localPath != null && java.io.File(localPath).exists()) {
            url = "file://$localPath"
            if (resumePos > 0) pendingSeekMs = resumePos
            else if (initialSec > 0) pendingSeekMs = initialSec * 1000
            applySource(ctx, exo, url)
            exo.prepare()
            if (resumePlaying != null) exo.playWhenReady = resumePlaying else exo.play()
            return@LaunchedEffect
        }

        // 并发:拉流 + 拉字幕(互不阻塞,视频不因字幕变慢)
        val (streamResult, subCues) = coroutineScope {
            val stream = async(Dispatchers.IO) { fetchStream() }
            val sub = async(Dispatchers.IO) { fetchSubtitleCues() }
            stream.await() to sub.await()
        }
        val st = streamResult.first
        val streamErr = streamResult.second
        subtitleCues = subCues
        hasSubtitle = subCues.isNotEmpty()
        currentSubtitle = ""

        if (st == null || st.videoUrl.isEmpty()) {
            err = "无法获取播放地址\n原因: ${streamErr.ifBlank { "会员专享/登录失效/网络问题" }}"
            com.bililite.core.BiliLog.e("Player", "播放失败 bvid=$bvid cid=$cid qn=$currentQn err=$streamErr")
        } else {
            // 构建清晰度列表:优先 acceptQuality/acceptDesc,为空则退回 support_formats。
            // v0.4.4:顶部加"自动"(qn=0,由 B 站按账号权限选最佳清晰度)。
            val ql = if (st.acceptQuality.isNotEmpty()) {
                st.acceptQuality.mapIndexed { i, qn ->
                    qn to st.acceptDesc.getOrNull(i).orEmpty()
                }.filter { it.second.isNotEmpty() || it.first > 0 }
            } else if (st.qualities.isNotEmpty()) {
                st.qualities.map { qn -> qn to qnName(qn) }
            } else emptyList()
            qualityList = (listOf(0 to "自动") + ql).distinctBy { it.first }
            val u = if (st.audioUrl.isNotEmpty()) "dash:${st.videoUrl}|${st.audioUrl}" else st.videoUrl
            url = u
            if (resumePos > 0) pendingSeekMs = resumePos
            else if (initialSec > 0) pendingSeekMs = initialSec * 1000
            applySource(ctx, exo, u)
            exo.prepare()
            if (resumePlaying != null) exo.playWhenReady = resumePlaying else exo.play()
        }
    }

    // initialSec 异步到达后，若尚未 seek 且已 READY，则补充 seek（断点续播/书签）
    LaunchedEffect(initialSec) {
        if (initialSec > 0 && exo.playbackState == Player.STATE_READY && !initialSeekApplied) {
            exo.seekTo(initialSec * 1000)
            initialSeekApplied = true
            onSeeked()
        }
    }

    // 播放状态监听：READY 后执行待 seek（书签定位/断点续播），并回调 onSeeked 一次
    // v0.3: STATE_ENDED → 触发连播信号(分P下一P 或 播放列表下一集)
    LaunchedEffect(exo) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(pl: Boolean) {
                isPlaying = pl
                com.bililite.plugin.PlayerBridge.notifyState(exo.playbackState, pl)
                if (pl) com.bililite.plugin.PlayerBridge.notifyPlay()
                else com.bililite.plugin.PlayerBridge.notifyPause()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    durMs = exo.duration.coerceAtLeast(0)
                    val seek = pendingSeekMs
                    if (seek != null && seek > 0 && !initialSeekApplied) {
                        exo.seekTo(seek)
                        pendingSeekMs = null
                        initialSeekApplied = true
                        onSeeked()
                    }
                }
                if (state == Player.STATE_ENDED) {
                    com.bililite.plugin.PlayerBridge.notifyComplete()
                    onEndedSignal()
                }
            }
            override fun onVideoSizeChanged(vs: androidx.media3.common.VideoSize) {
                val w = vs.width; val h = vs.height
                if (w > 0 && h > 0) {
                    arflRef.value?.setAspectRatio(w.toFloat() / h.toFloat())
                }
            }
        }
        exo.addListener(l)
        // v0.4.9 流畅度优化:拆分循环——进度 500ms(原来 200ms 全量),字幕匹配独立 150ms,
        // 降低主线程状态写入频率与重组压力
        while (true) {
            delay(500)
            posMs = exo.currentPosition
            bufferedMs = exo.bufferedPosition   // v0.3.1: 缓冲进度(灰色条)
            com.bililite.plugin.PlayerBridge.notifyProgress(exo.currentPosition, exo.duration)
            // v0.3 性能修复:进度上报节流(≥4s 或跳跃≥5s 才回调)。
            // 原实现每 500ms 回调 → 上层每秒写库 2 次 + 全量刷新历史列表,是全局卡顿主因
            if (exo.playWhenReady && exo.isPlaying) {
                val sec = exo.currentPosition / 1000
                if (lastReportedSec < 0 || sec - lastReportedSec >= 4 || sec < lastReportedSec) {
                    lastReportedSec = sec
                    onProgress(sec)
                }
            }
        }
    }

    // v0.4.13: 二分查找字幕——返回最后一个 toMs < p 的索引(无状态,任意 p 都精确,不受历史游标影响)
    fun findSubtitleIndex(cues: List<SubtitleCue>, p: Long): Int {
        var lo = 0
        var hi = cues.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].toMs < p) lo = mid + 1 else hi = mid
        }
        var i = lo
        while (i > 0 && cues[i - 1].fromMs > p) i--
        return i
    }

    // v0.4.9: 字幕匹配独立循环(150ms,只在有字幕时做匹配,不影响进度 UI 刷新)
    LaunchedEffect(exo) {
        while (true) {
            delay(150)
            if (subtitleOn && subtitleCues.isNotEmpty()) {
                val cues = subtitleCues
                val dur = durMs
                var p = exo.currentPosition
                // v0.4.13 修复"开头把所有字幕过一遍 / 后面没字幕":
                //  ① p 非法(负数/超出视频时长+2s 容差)时沿用上次合法位置,避免 currentPosition 异常时字幕乱跳
                //  ② 改为无状态二分查找,去掉 subIdx 游标——游标在位置大幅跳变(seek/续播/播放器时钟错乱)时
                //     会被推到末尾残留,导致后续字幕永远不匹配("后面应该有字幕的地方没有字幕")
                if (p < 0 || (dur > 0 && p > dur + 2000)) {
                    p = lastSubPos
                } else {
                    lastSubPos = p
                }
                val i = findSubtitleIndex(cues, p)
                val cue = cues.getOrNull(i)?.takeIf { p >= it.fromMs && p < it.toMs }
                val txt = cue?.text ?: ""
                if (txt != currentSubtitle) {
                    // v0.4.8: 首次匹配到字幕时记录诊断日志(验证渲染循环工作)
                    if (txt.isNotEmpty() && currentSubtitle.isEmpty()) {
                        com.bililite.core.BiliLog.i("Player", "字幕开始显示 @${p}ms: ${txt.take(30)}")
                    }
                    currentSubtitle = txt
                }
            } else if (currentSubtitle.isNotEmpty()) {
                currentSubtitle = ""
            }
        }
    }

    // 外部 seek 请求（侧栏点击书签 → 跳转到书签时间点）
    LaunchedEffect(seekRequest) {
        if (seekRequest != null) {
            val target = seekRequest
            if (exo.playbackState == Player.STATE_READY) {
                exo.seekTo(target)
            } else {
                pendingSeekMs = target
            }
            onSeekConsumed()
        }
    }

    // 生命周期
    // v0.4.20: 支持后台播放——background_play 开关开启时,退后台不暂停(继续听)。
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    onFlush(exo.currentPosition / 1000)
                    val bg = ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE)
                        .getBoolean("background_play", false)
                    if (!bg) exo.playWhenReady = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            onFlush(exo.currentPosition / 1000)   // v0.3: 退出播放时强制落库(不受节流限制)
            exo.playWhenReady = false; exo.stop(); exo.release()
        }
    }

    // v0.4.4: 播放时屏幕常亮(退出播放页自动清除)
    DisposableEffect(activity) {
        if (activity != null) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (activity != null) {
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // v0.4.4: 退出全屏时自动解除屏幕锁定(避免方向被卡在横屏)
    LaunchedEffect(fullscreen) {
        if (!fullscreen && screenLocked) {
            screenLocked = false
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 单击/双击/三击仲裁
    fun togglePlay() { if (isPlaying) exo.pause() else exo.play() }
    fun doBookmark() { onAddBookmark(exo.currentPosition); showControls = false }

    // v0.4.4: 全屏屏幕锁定——锁定时固定横屏方向,防止播放中误旋转
    fun toggleScreenLock() {
        screenLocked = !screenLocked
        activity?.requestedOrientation = if (screenLocked) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 倍速生效：长按 2x 优先，否则用用户设置的速度
    LaunchedEffect(speed, longPressBoost) {
        exo.setPlaybackSpeed(if (longPressBoost) 2.0f else speed)
        // v0.3.1: 记住倍速(变速不变调,media3 setPlaybackSpeed 本身保持音调)
        if (!longPressBoost) {
            ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).edit()
                .putFloat("speed", speed).apply()
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 4f)
        val base = if (scale >= 1f) {
            Offset((offset.x + panChange.x).coerceIn(
                -ctx.resources.displayMetrics.widthPixels * (scale - 1f) / 2f,
                ctx.resources.displayMetrics.widthPixels * (scale - 1f) / 2f),
                (offset.y + panChange.y).coerceIn(
                    -ctx.resources.displayMetrics.heightPixels * (scale - 1f) / 2f,
                    ctx.resources.displayMetrics.heightPixels * (scale - 1f) / 2f))
        } else {
            // 缩小时仍允许平移，范围限制在缩放余量内
            Offset((offset.x + panChange.x).coerceIn(-200f, 200f),
                   (offset.y + panChange.y).coerceIn(-200f, 200f))
        }
        offset = base
        val isZoomed = (scale - 1f).absoluteValue > 0.01f
        if (isZoomed != zoomed) { zoomed = isZoomed; onZoomChanged(isZoomed) }
    }

    // 外部触发撤销缩放（返回手势）
    LaunchedEffect(resetZoomTrigger) {
        if (resetZoomTrigger > 0) {
            scale = 1f
            offset = Offset.Zero
            zoomed = false
            onZoomChanged(false)
        }
    }

    Box(modifier.background(Color.Black)) {
        if (url.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (err.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(err, color = Color.White, fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        // v0.3.1: 加载失败重试按钮
                        Button(onClick = { retryToken++ }) {
                            Text("重试", color = Color.White)
                        }
                    }
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
        // 统一 transform 容器：TextureView + 手势层都在其内，避免双重缩放
        // v0.4.20: 仅全屏允许缩放;且缩放/平移统一由 transformable 处理(支持连续多次缩放)
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }
            .transformable(transformState, enabled = fullscreen)
        ) {
            // 视频容器：AspectRatioFrameLayout(FIT) 保持原比例，TextureView 不被拉伸
            // v0.3: keepScreenOn——学习播放中屏幕保持常亮不息屏
            AndroidView(
                factory = { c ->
                    androidx.media3.ui.AspectRatioFrameLayout(c).apply {
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        keepScreenOn = true
                        addView(TextureView(c).apply {
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        }, 0)
                        setBackgroundColor(android.graphics.Color.BLACK)
                        arflRef.value = this
                    }
                },
                update = { arfl ->
                    val tv = arfl.getChildAt(0) as? TextureView
                    if (tv != null) exo.setVideoTextureView(tv)
                },
                modifier = Modifier.fillMaxSize()
            )
            // v0.4.4: 字幕显示层——手动渲染,底部白字黑边
            // v0.4.9: 字号三档 + 位置(底/中/上)可调
            if (subtitleOn && hasSubtitle && currentSubtitle.isNotEmpty()) {
                // v0.4.20: 平板字幕放大 3 倍,手机保持原大小(按设备区分)
                val isTabletDevice = ctx.resources.configuration.smallestScreenWidthDp >= 600
                val subScale = if (isTabletDevice) 3 else 1
                val subFontSize = when (subSize) {
                    0 -> (13 * subScale).sp
                    2 -> (19 * subScale).sp
                    else -> (15 * subScale).sp
                }
                val subAlign = when (subPos) { 1 -> Alignment.Center; 2 -> Alignment.TopCenter; else -> Alignment.BottomCenter }
                val subPadding = when (subPos) { 1 -> 48.dp; 2 -> 36.dp; else -> 12.dp }
                Box(
                    Modifier.fillMaxWidth().align(subAlign)
                        .padding(horizontal = 16.dp, vertical = subPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        currentSubtitle,
                        color = Color.White,
                        fontSize = subFontSize,
                        modifier = Modifier
                            .background(Color(0x99000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            // 手势捕获层（覆盖在视频上，捕获 tap / drag）
            Box(Modifier.fillMaxSize()
                .pointerInput(zoomed, screenLocked) {
                    if (!screenLocked && zoomed) {
                        // v0.4.20: 缩放状态下，平移/缩放统一交给 transformable 处理，
                        // 这里不再用 detectDragGestures 抢手势，避免与 transformable 冲突导致
                        // "缩放后无法继续缩放"。此处只做空实现(手势由上层 transformable 接管)。
                    } else {
                        // 非缩放状态:上/下滑 = 左亮度/右音量;左/右滑 = 进度 seek(全屏)。
                        // v0.4.4:改用 detectDragGestures 判定主轴,新增全屏左右拖动进度。
                        var dragSide: Int? = null   // 0 亮度 1 音量 2 进度
                        var startBrightness = 0f
                        var startVolLevel = 0f
                        var lastVolIndex = -1
                        var startSeekMs = 0L
                        var horizAccum = 0f
                        var vertAccum = 0f
                        var brightAccum = 0f
                        var volAccum = 0f
                        var startX = 0f
                        detectDragGestures(
                            onDragStart = { start ->
                                dragSide = null
                                startX = start.x
                                startBrightness = activity?.window?.attributes?.screenBrightness ?: 0f
                                startVolLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol.toFloat()
                                lastVolIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                startSeekMs = exo.currentPosition
                                brightAccum = 0f; volAccum = 0f
                                horizAccum = 0f; vertAccum = 0f
                            },
                            onDrag = { change, dragAmount ->
                                horizAccum += dragAmount.x
                                vertAccum += dragAmount.y
                                if (dragSide == null) {
                                    dragSide = when {
                                        kotlin.math.abs(horizAccum) > kotlin.math.abs(vertAccum) -> 2  // 左右滑 = 调进度(手机/平板/全屏均生效)
                                        kotlin.math.abs(vertAccum) > kotlin.math.abs(horizAccum) ->
                                            if (startX < size.width / 2f) 0 else 1
                                        else -> null
                                    }
                                }
                                when (dragSide) {
                                    2 -> {
                                        if (durMs > 0) {
                                            val target = (startSeekMs + (horizAccum / size.width * durMs).toLong()).coerceIn(0L, durMs)
                                            exo.seekTo(target)
                                            posMs = target
                                            indicator = "进度" to (target * 100 / durMs).toInt()
                                        }
                                    }
                                    0 -> {
                                        brightAccum += -dragAmount.y
                                        val frac = (brightAccum / size.height).coerceIn(-1f, 1f)
                                        val eased = frac * (1f - 0.25f * frac.absoluteValue)
                                        val target = (startBrightness + eased * 0.75f).coerceIn(0.01f, 1f)
                                        activity?.window?.apply { val lp = attributes; lp.screenBrightness = target; attributes = lp }
                                        indicator = "亮度" to (target * 100).toInt()
                                    }
                                    1 -> {
                                        volAccum += -dragAmount.y
                                        val frac = (volAccum / size.height).coerceIn(-1f, 1f)
                                        val eased = if (frac >= 0) frac * frac else -(frac * frac)
                                        val level = (startVolLevel + eased).coerceIn(0f, 1f)
                                        val idx = (level * maxVol).toInt().coerceIn(0, maxVol)
                                        if (idx != lastVolIndex) {
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
                                            lastVolIndex = idx
                                        }
                                        indicator = "音量" to (level * 100).toInt()
                                    }
                                }
                            },
                            onDragEnd = { dragSide = null; indicator = null },
                            onDragCancel = { dragSide = null; indicator = null }
                        )
                    }
                }
                .pointerInput(Unit) {
                    // v0.4.4: 单击仅显隐控制条(避免误触暂停) + 双击播放/暂停 + 长按临时2倍速
                    detectTapGestures(
                        onTap = {
                            showControls = !showControls
                        },
                        onDoubleTap = { togglePlay() },
                        onLongPress = {
                            longPressBoost = true
                            indicator = "倍速" to 200
                        },
                        onPress = {
                            try {
                                val released = tryAwaitRelease()
                                if (released && longPressBoost) {
                                    longPressBoost = false
                                    indicator = null
                                }
                            } catch (_: Exception) {}
                        }
                    )
                }
            )
        }

        // 亮度/音量/倍速/进度指示器。倍速提示显示在顶部,其余(亮度/音量/进度)居中。
        indicator?.let { (label, pct) ->
            Surface(color = Color(0xAA000000), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(if (label == "倍速") Alignment.TopCenter else Alignment.Center)
                    .then(if (label == "倍速") Modifier.padding(top = 16.dp) else Modifier)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (label == "倍速") {
                        Text("2x", color = Color.White, fontSize = 28.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    } else if (label == "进度") {
                        // 左右滑动调进度:显示进度百分比 + 时间
                        Text("进度", color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        // 进度条
                        Box(Modifier.width(120.dp).height(4.dp).background(Color(0x40FFFFFF), RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth((pct / 100f).coerceIn(0f, 1f)).height(4.dp)
                                .background(Color.White, RoundedCornerShape(2.dp)))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("$pct%", color = Color.White, fontSize = 14.sp)
                    } else {
                        Icon(
                            imageVector = if (label == "亮度") Icons.Filled.BrightnessHigh else Icons.Filled.VolumeUp,
                            contentDescription = label,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("$pct%", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }

        // v0.4.4: 全屏顶部控制栏——左上角退出全屏 + 屏幕锁定(仅全屏且控制条可见时显示)
        if (fullscreen && showControls) {
            Row(Modifier.align(Alignment.TopStart).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleFullscreen) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "退出全屏",
                        tint = Color.White, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { toggleScreenLock() }) {
                    Icon(if (screenLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (screenLocked) "解除锁定" else "锁定屏幕",
                        tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }

        // 撤销缩放按钮
        if (zoomed) {
            TextButton(onClick = { scale = 1f; offset = Offset.Zero; zoomed = false; onZoomChanged(false) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp)) {
                Text("撤销缩放", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }

        // 自定义控制条（还原 Media3 官方观感：底部渐变 + 时间分置 + 进度条居中）
        if (showControls) {
            val pct = if (durMs > 0) (if (scrubbing) scrubPos else posMs.toFloat() / durMs.toFloat()) else 0f
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xCC000000), Color(0xE6000000))
                    )
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // 时间行：当前时间 左 / 总时长 右（官方样式）
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtTime((if (scrubbing) (scrubPos * durMs).toLong() else posMs) / 1000),
                            color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(fmtTime(durMs / 1000), color = Color.White, fontSize = 12.sp)
                    }
                    // 进度条：细线样式（非粗杠），点击/拖动指哪打哪，松手精确 seek
                    // v0.3.1: 灰色缓冲进度 + 已看进度
                    SlimProgressBar(
                        progress = pct,
                        buffered = if (durMs > 0) (bufferedMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else 0f,
                        onSeek = { v ->
                            scrubbing = true
                            scrubPos = v
                            if (durMs > 0) exo.seekTo((v * durMs).toLong())
                        },
                        onSeekFinished = {
                            if (durMs > 0) exo.seekTo((scrubPos * durMs).toLong())
                            scrubbing = false
                        },
                        modifier = Modifier.fillMaxWidth().height(28.dp)
                    )
                    // 按钮行：播放/暂停 + 书签 + 倍速 + 画质 .. 全屏
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { togglePlay() }) {
                            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = { doBookmark() }) {
                            Icon(Icons.Filled.BookmarkBorder, contentDescription = "标记",
                                tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        // 倍速按钮（点击弹菜单）
                        Box {
                            TextButton(onClick = { showSpeedMenu = true }) {
                                Text("${speed}x", color = Color.White, fontSize = 13.sp)
                            }
                            DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f).forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text("${s}x", color = if (s == speed) com.bililite.core.C.t1 else com.bililite.core.C.t2) },
                                        onClick = { speed = s; showSpeedMenu = false })
                                }
                            }
                        }
                        // v0.3.1: 画质选择按钮（仅全屏显示）
                        if (fullscreen && qualityList.isNotEmpty()) {
                            Box {
                                TextButton(onClick = { showQualityMenu = true }) {
                                    Text(currentQnName(), color = Color.White, fontSize = 12.sp)
                                }
                                DropdownMenu(expanded = showQualityMenu, onDismissRequest = { showQualityMenu = false }) {
                                    qualityList.forEach { (qn, desc) ->
                                        DropdownMenuItem(
                                            text = { Text(desc.ifBlank { qnName(qn) },
                                                color = if (qn == currentQn) com.bililite.core.C.t1 else com.bililite.core.C.t2) },
                                            onClick = {
                                                // v0.4.4: 先保存当前进度,切换后恢复到该位置;并全局记住所选清晰度
                                                // v0.4.5: 同时记住播放/暂停状态,重载后保持
                                                qnSwitchPos = exo.currentPosition.coerceAtLeast(0)
                                                wasPlayingBeforeQnSwitch = isPlaying
                                                com.bililite.core.BiliLog.i("Player",
                                                    "切换清晰度 → qn=$qn 恢复位置=${qnSwitchPos}ms 播放=$isPlaying")
                                                currentQn = qn
                                                showQualityMenu = false
                                                ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).edit()
                                                    .putInt("quality", qn).apply()
                                            })
                                    }
                                }
                            }
                        }
                        // v0.4.1: 字幕按钮(有字幕轨道时显示)。v0.4.9: 弹出菜单含开关/字号/位置（仅全屏显示）
                        if (fullscreen && hasSubtitle) {
                            Box {
                                TextButton(onClick = { showSubMenu = true }) {
                                    Text(if (subtitleOn) "字幕" else "字幕关",
                                        color = if (subtitleOn) Color.White else Color(0x80FFFFFF),
                                        fontSize = 12.sp)
                                }
                                DropdownMenu(expanded = showSubMenu, onDismissRequest = { showSubMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (subtitleOn) "关闭字幕" else "开启字幕", color = C.t3) },
                                        onClick = { subtitleOn = !subtitleOn })
                                    DropdownMenuItem(
                                        text = { Text("字号: ${listOf("小", "中", "大")[subSize]}", color = C.t3) },
                                        onClick = {
                                            subSize = (subSize + 1) % 3
                                            ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).edit()
                                                .putInt("sub_size", subSize).apply()
                                        })
                                    DropdownMenuItem(
                                        text = { Text("位置: ${listOf("底部", "中部", "顶部")[subPos]}", color = C.t3) },
                                        onClick = {
                                            subPos = (subPos + 1) % 3
                                            ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).edit()
                                                .putInt("sub_pos", subPos).apply()
                                        })
                                }
                            }
                        }
                        // v0.4.1: 离线缓存按钮(v0.4.3: 显示缓存中/已缓存状态;v0.4.19 点弹清晰度选择)
                        if (onCache != null) {
                            Box {
                                TextButton(onClick = { showCacheQuality = true }, enabled = !caching) {
                                    Text(
                                        when {
                                            caching -> "缓存中"
                                            localPath != null -> "已缓存"
                                            else -> "缓存"
                                        },
                                        color = when {
                                            caching -> Color(0xFFFFB300)
                                            localPath != null -> Color(0xFFBBBBBB)
                                            else -> Color.White
                                        },
                                        fontSize = 12.sp)
                                }
                                DropdownMenu(expanded = showCacheQuality, onDismissRequest = { showCacheQuality = false }) {
                                    listOf(
                                        16 to "360P", 32 to "480P", 64 to "720P", 80 to "1080P", 0 to "自动(最高)"
                                    ).forEach { (qn, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label, color = com.bililite.core.C.t1, fontSize = 13.sp) },
                                            onClick = { showCacheQuality = false; onCache(qn, cid) })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onToggleFullscreen) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = "全屏",
                                tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }

        // v0.4.3: 缓存状态/进度提示(顶部小胶囊,缓存完成后自动消失)
        if (cacheMsg.isNotEmpty()) {
            Surface(color = Color(0xCC000000), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
                Text(cacheMsg, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
    }
}

// v0.4.6: OkHttp 客户端单例(原来每次加载/切清晰度都新建,浪费线程池与连接)
private val sharedOkHttp by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

private fun applySource(ctx: android.content.Context, e: ExoPlayer, url: String) {
    fun https(u: String): String = if (u.startsWith("http://")) "https://" + u.substringAfter("http://") else u

    // v0.4.4 关键修复:统一用 DefaultDataSource(http/https→OkHttp,file://→FileDataSource)。
    val okhttp = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(sharedOkHttp).apply {
        setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/122.0 Mobile Safari/537.36")
        setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com/"))
    }
    val ds = androidx.media3.datasource.DefaultDataSource.Factory(ctx, okhttp)

    // v0.4.1: 本地文件(离线缓存)
    if (url.startsWith("file://")) {
        e.setMediaSource(ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(url)))
        return
    }
    if (url.startsWith("dash:")) {
        val body = url.removePrefix("dash:"); val parts = body.split("|")
        val vUrl = https(parts.getOrNull(0) ?: "")
        val aUrl = https(parts.getOrNull(1) ?: "")
        // video + audio 两个独立 fMP4 流合并（设置 mimeType 帮助识别）
        val videoItem = MediaItem.Builder().setUri(vUrl).setMimeType("video/mp4").build()
        val audioItem = MediaItem.Builder().setUri(aUrl).setMimeType("audio/mp4").build()
        val videoSource = ProgressiveMediaSource.Factory(ds).createMediaSource(videoItem)
        val audioSource = ProgressiveMediaSource.Factory(ds).createMediaSource(audioItem)
        e.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(videoSource, audioSource))
    } else {
        e.setMediaSource(ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(https(url))))
    }
}

/** 一条字幕:起止时间(毫秒)+文本 */
data class SubtitleCue(val fromMs: Long, val toMs: Long, val text: String)

/** B 站 CC 字幕 json → cue 列表(手动渲染用)。
 *  v0.4.5:兼容 from/to 的两种单位——标准为秒(float),个别接口返回毫秒(int)。
 *  v0.4.16 修复"字幕加速":原判定(数值>5000 视为毫秒)对超过 83 分钟的长课程视频失效——
 *  秒字幕 to 超过 5000 被误判毫秒 → 时间轴压缩 1000 倍 → 字幕狂奔。
 *  现按视频时长量级判定:最大 to 超过时长(秒)2 倍才视为毫秒;无时长时退回原逻辑。 */
private fun biliSubtitleToCues(json: String, videoDurSec: Int = 0): List<SubtitleCue> {
    return try {
        val obj = org.json.JSONObject(json)
        val body = obj.optJSONArray("body") ?: return emptyList()
        val raw = (0 until body.length()).mapNotNull { i ->
            val o = body.optJSONObject(i) ?: return@mapNotNull null
            Triple(o.optDouble("from", 0.0), o.optDouble("to", 0.0), o.optString("content", "").trim())
        }.filter { it.third.isNotEmpty() }
        val maxTo = raw.maxOfOrNull { it.second } ?: 0.0
        val inMillis = if (videoDurSec > 0) maxTo > videoDurSec * 2L
                       else maxTo > 5000
        if (inMillis) {
            com.bililite.core.BiliLog.i("Player", "字幕按毫秒解析(maxTo=$maxTo, durSec=$videoDurSec)")
        }
        raw.map { (from, to, content) ->
            SubtitleCue(
                if (inMillis) from.toLong() else (from * 1000).toLong(),
                if (inMillis) to.toLong() else (to * 1000).toLong(),
                content)
        }
    } catch (e: Exception) {
        com.bililite.core.BiliLog.e("Player", "字幕 JSON 解析失败: ${e.message}", e)
        emptyList()
    }
}

/**
 * 细线进度条（非粗杠 Slider）：3dp 细线，点击/拖动定位，带极小指示点。
 * progress ∈ 0..1；buffered ∈ 0..1 为灰色缓冲进度。
 */
@Composable
private fun SlimProgressBar(
    progress: Float,
    buffered: Float = 0f,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    BoxWithConstraints(
        // v0.4.4:改用 detectDragGestures(不限轴向、更易触发),修复全屏下进度条难拖拽。
        // 之前 detectHorizontalDragGestures 要求首段位移严格水平,轻微斜拉即失效。
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { pos ->
                    dragging = true
                    val w = size.width.toFloat()
                    if (w > 0) onSeek((pos.x / w).coerceIn(0f, 1f))
                },
                onDrag = { change, _ ->
                    val w = size.width.toFloat()
                    if (w > 0) onSeek((change.position.x / w).coerceIn(0f, 1f))
                },
                onDragEnd = { dragging = false; onSeekFinished() },
                onDragCancel = { dragging = false; onSeekFinished() }
            )
        }
        .pointerInput(Unit) {
            detectTapGestures { pos ->
                val w = size.width.toFloat()
                if (w > 0) {
                    onSeek((pos.x / w).coerceIn(0f, 1f))
                    onSeekFinished()
                }
            }
        },
        contentAlignment = Alignment.CenterStart
    ) {
        val w = maxWidth
        val dotSize = if (dragging) 10.dp else 6.dp
        // 背景细线
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF), RoundedCornerShape(2.dp)))
        // v0.3.1: 缓冲进度细线(灰色)
        Box(Modifier.fillMaxWidth(buffered.coerceIn(0f, 1f)).height(3.dp)
            .background(Color(0x80FFFFFF), RoundedCornerShape(2.dp)))
        // 已播放细线
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
            .background(Color.White, RoundedCornerShape(2.dp)))
        // 指示点（极小白点，跟在进度末端）
        Box(Modifier
            .offset(x = w * progress.coerceIn(0f, 1f) - dotSize / 2)
            .size(dotSize)
            .background(Color.White, CircleShape))
    }
}
