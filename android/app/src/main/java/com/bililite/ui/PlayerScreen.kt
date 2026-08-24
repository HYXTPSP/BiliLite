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
import com.bililite.data.Bookmark
import kotlinx.coroutines.Dispatchers
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
    bookmarkSeekSec: Long? = null,
    onBookmarkConsumed: () -> Unit = {},
    bookmarks: List<Bookmark> = emptyList(),
    onAddBookmark: (bvid: String, videoTitle: String, cid: Long, pageIndex: Int, pageTitle: String, timeSec: Long) -> Unit = { _, _, _, _, _, _ -> },
    onRenameBookmark: (Long, String) -> Unit = { _, _ -> },
    onDeleteBookmark: (Long) -> Unit = {}
) {
    var pages by remember { mutableStateOf<List<PageInfo>>(emptyList()) }
    var curCid by remember { mutableStateOf(cid) }
    var err by remember { mutableStateOf("") }
    var initialSec by remember { mutableStateOf(0L) }
    var desc by remember { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }
    // 书签 seek 请求（侧栏点击书签 → 传递到 Player 触发 seekTo）
    var seekRequest by remember { mutableStateOf<Long?>(null) }
    // 缩放状态（由 Player 回调更新），用于返回手势撤销缩放
    var zoomed by remember { mutableStateOf(false) }
    var resetZoomTrigger by remember { mutableStateOf(0) }

    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()

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
            if (curCid == 0L) curCid = pages.firstOrNull()?.cid ?: 0L
            if (curCid == 0L && resolveCid != null) curCid = withContext(Dispatchers.IO) { resolveCid(bvid) }
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

    Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA))) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) {
                Surface(color = Color(0xFFFAFAFA)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF1C1C1E)) }
                        Text(title, color = Color(0xFF1C1C1E), fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }

            // Player 单一实例（可移动）
            val movablePlayer = remember {
                movableContentOf {
                    Player(api, bvid, curCid,
                        activity = activity,
                        onToggleFullscreen = { fullscreen = !fullscreen; setImmersive(activity, fullscreen) },
                        modifier = Modifier.fillMaxSize(),
                        initialSec = if (bookmarkSeekSec != null) bookmarkSeekSec else initialSec,
                        onSeeked = { if (bookmarkSeekSec != null) onBookmarkConsumed() },
                        onProgress = { secs ->
                            onProgress(secs)
                            onProgressCid(secs, curCid)
                        },
                        onAddBookmark = {
                            onAddBookmark(bvid, title, curCid, curPageIdx, curPageTitle, it / 1000)
                            toast = "标记成功"
                        },
                        seekRequest = seekRequest,
                        onSeekConsumed = { seekRequest = null },
                        onZoomChanged = { zoomed = it },
                        resetZoomTrigger = resetZoomTrigger
                    )
                }
            }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val isWide = maxWidth >= 600.dp
                if (fullscreen) {
                    movablePlayer()
                } else if (isWide) {
                    Row(Modifier.fillMaxSize().padding(12.dp)) {
                        Box(Modifier.fillMaxHeight().weight(2f)) { movablePlayer() }
                        Spacer(Modifier.width(12.dp))
                        InfoPanel(upName, upFace, pubdate, title, playCount, desc, pages, curCid,
                            bookmarks = bookmarks,
                            onSelectPage = { curCid = it },
                            onBookmarkClick = { seekRequest = it.timeSec * 1000 },
                            onRename = { id, note -> onRenameBookmark(id, note) },
                            onDelete = { id -> onDeleteBookmark(id) },
                            modifier = Modifier.fillMaxHeight().weight(1f))
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(0.30f)) { movablePlayer() }
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 16.dp)) {
                            if (upName.isNotEmpty()) { Text("UP主：$upName", color = Color(0xFF8E8E93), fontSize = 12.sp); Spacer(Modifier.height(4.dp)) }
                            if (bookmarks.isNotEmpty()) {
                                Text("书签 ${bookmarks.size}", color = Color(0xFF8E8E93), fontSize = 11.sp)
                                Spacer(Modifier.height(2.dp))
                            }
                            Text(title, color = Color(0xFF1C1C1E), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                            if (playCount > 0) { Spacer(Modifier.height(4.dp)); Text("${fmtPlayCount(playCount)}播放", color = Color(0xFF8E8E93), fontSize = 12.sp) }
                            if (desc.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(desc, color = Color(0xFF3A3A3C), fontSize = 12.sp, maxLines = 4) }
                            Spacer(Modifier.height(8.dp))
                            if (pages.size > 1) {
                                Text("分P · 共 ${pages.size} 集", color = Color(0xFF8E8E93), fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                                PageList(pages, curCid, onSelect = { curCid = it }, modifier = Modifier.fillMaxWidth().weight(1f))
                            }
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

/** 右侧/下方信息面板：UP主 + 视频信息 + 分P + 书签列表 */
@Composable
private fun InfoPanel(upName: String, upFace: String, pubdate: Long, title: String,
                      playCount: Long, desc: String,
                      pages: List<PageInfo>, curCid: Long,
                      bookmarks: List<Bookmark>,
                      onSelectPage: (Long) -> Unit,
                      onBookmarkClick: (Bookmark) -> Unit,
                      onRename: (Long, String) -> Unit,
                      onDelete: (Long) -> Unit,
                      modifier: Modifier = Modifier) {
    Column(modifier.background(Color.White).clip(RoundedCornerShape(10.dp)).padding(16.dp)) {
        if (upName.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (upFace.isNotEmpty()) {
                    coil.compose.AsyncImage(model = upFace, contentDescription = upName,
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFE5E5EA)))
                    Spacer(Modifier.width(10.dp))
                }
                Text(upName, color = Color(0xFF1C1C1E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (pubdate > 0) { Spacer(Modifier.width(8.dp)); Text(fmtDate(pubdate), color = Color(0xFF8E8E93), fontSize = 11.sp) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(title, color = Color(0xFF1C1C1E), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 3)
        if (playCount > 0) { Spacer(Modifier.height(6.dp)); Text("${fmtPlayCount(playCount)}播放", color = Color(0xFF8E8E93), fontSize = 12.sp) }
        if (desc.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(desc, color = Color(0xFF3A3A3C), fontSize = 12.sp, maxLines = 6) }

        Spacer(Modifier.height(12.dp))

        // 书签列表
        if (bookmarks.isNotEmpty()) {
            Text("书签（${bookmarks.size}）", color = Color(0xFF1C1C1E), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(bookmarks) { b ->
                    BookmarkRow(b, onClick = { onBookmarkClick(b) }, onRename = { onRename(b.id, it) }, onDelete = { onDelete(b.id) })
                }
            }
        } else if (pages.size > 1) {
            Text("分P · 共 ${pages.size} 集", color = Color(0xFF8E8E93), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            PageList(pages, curCid, onSelect = onSelectPage, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BookmarkRow(b: Bookmark, onClick: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var note by remember(b.id) { mutableStateOf(b.note) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Bookmark, contentDescription = "书签", tint = Color(0xFF1C1C1E), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(fmtTime(b.timeSec), color = Color(0xFF1C1C1E), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (b.note.isNotEmpty()) Text(b.note, color = Color(0xFF8E8E93), fontSize = 11.sp)
    }
    if (renaming) {
        TextField(value = note, onValueChange = { note = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onRename(note); renaming = false }) { Text("确定", color = Color(0xFF1C1C1E)) }
            TextButton(onClick = { renaming = false }) { Text("取消", color = Color(0xFF8E8E93)) }
        }
    } else {
        // 长按重命名 / 删除（用三个点的简化：这里用长按触发重命名）
        Box(Modifier.fillMaxWidth()) {
            TextButton(onClick = { renaming = true }, modifier = Modifier.align(Alignment.CenterEnd)) { Text("重命名", color = Color(0xFF8E8E93), fontSize = 11.sp) }
        }
    }
}

/** 分P列表 */
@Composable
private fun PageList(pages: List<PageInfo>, curCid: Long, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        items(pages) { p ->
            val selected = p.cid == curCid
            Surface(color = if (selected) Color(0xFF1C1C1E) else Color.White,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelect(p.cid) }) {
                Text(p.part, color = if (selected) Color.White else Color(0xFF1C1C1E), fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

/** Player 组件：TextureView + 手势 + 自定义控制条 + 缩放 */
@Composable
private fun Player(
    api: BiliApi, bvid: String, cid: Long,
    activity: Activity?,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    initialSec: Long = 0,
    onSeeked: () -> Unit = {},
    onProgress: (Long) -> Unit = {},
    onAddBookmark: (Long) -> Unit = {},
    seekRequest: Long? = null,
    onSeekConsumed: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {},
    resetZoomTrigger: Int = 0
) {
    val ctx = LocalContext.current
    val exo = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    var url by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(true) }
    var posMs by remember { mutableStateOf(0L) }
    var durMs by remember { mutableStateOf(0L) }
    var showControls by remember { mutableStateOf(false) }
    // 进度条拖动状态：拖动时用本地值，避免 posMs 500ms 更新打断拖动
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableStateOf(0f) }
    // 倍速（用户手动设置），长按临时 2x 不覆盖此值
    var speed by remember { mutableStateOf(1.0f) }
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
    var initialSeekApplied by remember(bvid, cid) { mutableStateOf(false) }
    // AspectRatioFrameLayout 引用（用于按视频宽高比设置，避免拉伸）
    val arflRef = remember { mutableStateOf<androidx.media3.ui.AspectRatioFrameLayout?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // 加载（仅依赖 bvid+cid，避免 initialSec 变化导致重新加载整段视频）
    LaunchedEffect(bvid, cid) {
        err = ""
        exo.stop(); exo.clearMediaItems()
        pendingSeekMs = null
        initialSeekApplied = false
        var u = ""
        for (attempt in 1..3) {
            try {
                u = withContext(Dispatchers.IO) { api.playUrl(bvid, cid) }
                if (u.isNotEmpty()) break
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {}
            kotlinx.coroutines.delay(800L * attempt)
        }
        if (u.isEmpty()) { err = "无法获取播放地址(可能需要会员/登录)" }
        else {
            url = u
            // 加载时就把当前 initialSec 作为首个 seek 目标
            if (initialSec > 0) pendingSeekMs = initialSec * 1000
            applySource(exo, u)
            exo.prepare()
            exo.play()
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
    LaunchedEffect(exo) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(pl: Boolean) { isPlaying = pl }
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
            }
            override fun onVideoSizeChanged(vs: androidx.media3.common.VideoSize) {
                val w = vs.width; val h = vs.height
                if (w > 0 && h > 0) {
                    arflRef.value?.setAspectRatio(w.toFloat() / h.toFloat())
                }
            }
        }
        exo.addListener(l)
        while (true) {
            delay(500)
            posMs = exo.currentPosition
            if (exo.playWhenReady && exo.isPlaying) onProgress(exo.currentPosition / 1000)
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
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> { onProgress(exo.currentPosition / 1000); exo.playWhenReady = false }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            onProgress(exo.currentPosition / 1000)
            exo.playWhenReady = false; exo.stop(); exo.release()
        }
    }

    // 单击/双击/三击仲裁
    fun togglePlay() { if (isPlaying) exo.pause() else exo.play() }
    fun doBookmark() { onAddBookmark(exo.currentPosition); showControls = false }

    // 倍速生效：长按 2x 优先，否则用用户设置的速度
    LaunchedEffect(speed, longPressBoost) {
        exo.setPlaybackSpeed(if (longPressBoost) 2.0f else speed)
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
                if (err.isNotEmpty()) Text(err, color = Color.White, fontSize = 13.sp)
                else CircularProgressIndicator(color = Color.White)
            }
        }
        // 统一 transform 容器：TextureView + 手势层都在其内，避免双重缩放
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }
            .transformable(transformState)
        ) {
            // 视频容器：AspectRatioFrameLayout(FIT) 保持原比例，TextureView 不被拉伸
            AndroidView(
                factory = { c ->
                    androidx.media3.ui.AspectRatioFrameLayout(c).apply {
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
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
            // 手势捕获层（覆盖在视频上，捕获 tap / drag）
            Box(Modifier.fillMaxSize()
                .pointerInput(zoomed) {
                    if (zoomed) {
                        // 缩放状态：单指拖动 = 平移画面（调用 transform 的 offset）
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val maxX = ctx.resources.displayMetrics.widthPixels * (scale - 1f) / 2f
                            val maxY = ctx.resources.displayMetrics.heightPixels * (scale - 1f) / 2f
                            offset = Offset(
                                (offset.x + dragAmount.x).coerceIn(-maxX, maxX),
                                (offset.y + dragAmount.y).coerceIn(-maxY, maxY))
                        }
                    } else {
                        // 非缩放状态：左滑亮度 / 右滑音量
                        var dragSide: Int? = null
                        var startBrightness = 0f
                        var startVolLevel = 0f
                        var brightAccum = 0f
                        var volAccum = 0f
                        var lastVolIndex = -1
                        detectVerticalDragGestures(
                            onDragStart = { start ->
                                dragSide = if (start.x < size.width / 2f) 0 else 1
                                startBrightness = activity?.window?.attributes?.screenBrightness ?: 0f
                                startVolLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol.toFloat()
                                lastVolIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                brightAccum = 0f; volAccum = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                when (dragSide) {
                                    0 -> {
                                        brightAccum += -dragAmount
                                        val frac = (brightAccum / size.height).coerceIn(-1f, 1f)
                                        val eased = frac * (1f - 0.25f * frac.absoluteValue)
                                        val target = (startBrightness + eased * 0.75f).coerceIn(0.01f, 1f)
                                        activity?.window?.apply { val lp = attributes; lp.screenBrightness = target; attributes = lp }
                                        indicator = "亮度" to (target * 100).toInt()
                                    }
                                    1 -> {
                                        volAccum += -dragAmount
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
                    // 单击(控制条) + 双击(暂停/播放) + 长按(临时2倍速，松手恢复)
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { if (isPlaying) exo.pause() else exo.play() },
                        onLongPress = { longPressBoost = true },
                        onPress = {
                            try {
                                val released = tryAwaitRelease()
                                if (released && longPressBoost) longPressBoost = false
                            } catch (_: Exception) {}
                        }
                    )
                }
            )
        }

        // 亮度/音量指示器
        indicator?.let { (label, pct) ->
            Surface(color = Color(0xAA000000), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
                    SlimProgressBar(
                        progress = pct,
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
                    // 按钮行：播放/暂停 + 书签 + 倍速 .. 全屏
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
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text("${s}x", color = if (s == speed) Color.Black else Color(0xFF3A3A3C)) },
                                        onClick = { speed = s; showSpeedMenu = false })
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
    }
}

private fun applySource(e: ExoPlayer, url: String) {
    fun https(u: String): String = if (u.startsWith("http://")) "https://" + u.substringAfter("http://") else u
    val io = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okhttp3.OkHttpClient.Builder().build()).apply {
        setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/122.0 Mobile Safari/537.36")
        setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com/"))
    }
    if (url.startsWith("dash:")) {
        val body = url.removePrefix("dash:"); val parts = body.split("|")
        val vUrl = https(parts.getOrNull(0) ?: "")
        val aUrl = https(parts.getOrNull(1) ?: "")
        // video + audio 两个独立 fMP4 流合并（设置 mimeType 帮助识别）
        val videoItem = MediaItem.Builder().setUri(vUrl).setMimeType("video/mp4").build()
        val audioItem = MediaItem.Builder().setUri(aUrl).setMimeType("audio/mp4").build()
        val ms = androidx.media3.exoplayer.source.MergingMediaSource(
            ProgressiveMediaSource.Factory(io).createMediaSource(videoItem),
            ProgressiveMediaSource.Factory(io).createMediaSource(audioItem)
        )
        e.setMediaSource(ms)
    } else {
        e.setMediaSource(ProgressiveMediaSource.Factory(io).createMediaSource(MediaItem.fromUri(https(url))))
    }
}

/**
 * 细线进度条（非粗杠 Slider）：3dp 细线，点击/拖动定位，带极小指示点。
 * progress ∈ 0..1
 */
@Composable
private fun SlimProgressBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = true },
                onHorizontalDrag = { change, _ ->
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
