package com.bililite.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.bililite.core.BiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

/**
 * 播放屏(半屏默认 + 分P列表 + B 站式播放器内嵌控制):
 *  - 半屏:顶部播放器(控制项内嵌在播放框里) + 标题/分P列表
 *  - 点全屏进入沉浸式全屏(隐藏状态栏/导航栏)
 */
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
    onProgress: (Long) -> Unit = {}
) {
    var pages by remember { mutableStateOf<List<PageInfo>>(emptyList()) }
    var curCid by remember { mutableStateOf(cid) }
    var err by remember { mutableStateOf("") }
    var initialSec by remember { mutableStateOf(0L) }
    var desc by remember { mutableStateOf("") }

    val activity = LocalContext.current as? Activity

    // 读取上次进度(断点续播)
    LaunchedEffect(videoId) {
        if (videoId != 0L && loadInitialSec != null) {
            initialSec = try { withContext(Dispatchers.IO) { loadInitialSec(videoId) } } catch (_: Exception) { 0L }
        }
    }

    // 拉取视频简介
    LaunchedEffect(bvid) {
        try {
            val d = withContext(Dispatchers.IO) { api.videoView(bvid) }.optJSONObject("data")
            desc = d?.optString("desc", "") ?: ""
        } catch (_: Exception) {}
    }

    // 首次进入:解析分P列表(cid 为 0 时补 cid)
    LaunchedEffect(bvid) {
        try {
            val arr = withContext(Dispatchers.IO) { api.pagelist(bvid) }
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                if (o == null) null else PageInfo(o.optLong("cid", 0L), o.optString("part", "P${i + 1}"))
            }
            pages = list
            if (curCid == 0L) {
                curCid = list.firstOrNull()?.cid ?: 0L
            }
            if (curCid == 0L && resolveCid != null) {
                curCid = withContext(Dispatchers.IO) { resolveCid(bvid) }
            }
        } catch (_: Exception) {}
    }

    var fullscreen by remember { mutableStateOf(false) }
    // 全屏时返回手势 → 退出全屏(而非回桌面)
    BackHandler(enabled = fullscreen) {
        fullscreen = false
        setImmersive(activity, false)
    }
    DisposableEffect(Unit) {
        onDispose { setImmersive(activity, false) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA))) {
        Column(Modifier.fillMaxSize()) {
            // 顶部标题栏(全屏时隐藏)
            if (!fullscreen) {
                Surface(color = Color(0xFFFAFAFA)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF1C1C1E)) }
                        Text(title, color = Color(0xFF1C1C1E), fontSize = 14.sp, maxLines = 1,
                            modifier = Modifier.weight(1f))
                    }
                }
            }

            // 用 movableContentOf 让 Player 在不同布局分支间移动而不丢状态
            val movablePlayer = remember {
                movableContentOf {
                    Player(api, bvid, curCid,
                        onToggleFullscreen = { fullscreen = !fullscreen; setImmersive(activity, fullscreen) },
                        reImmerse = { if (fullscreen) setImmersive(activity, true) },
                        modifier = Modifier.fillMaxSize(),
                        initialSec = if (curCid == cid) initialSec else 0L,
                        onProgress = onProgress)
                }
            }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val isWide = maxWidth >= 600.dp
                if (fullscreen) {
                    // 全屏:播放器占满
                    movablePlayer()
                } else if (isWide) {
                    // 平板:左 2/3 播放 + 右 1/3 信息
                    Row(Modifier.fillMaxSize().padding(12.dp)) {
                        Box(Modifier.fillMaxHeight().weight(2f)) { movablePlayer() }
                        Spacer(Modifier.width(12.dp))
                        InfoPanel(upName, upFace, pubdate, title, playCount, desc, pages, curCid,
                            onSelectPage = { curCid = it }, modifier = Modifier.fillMaxHeight().weight(1f))
                    }
                } else {
                    // 手机:播放器在上,信息在下
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(0.30f)) { movablePlayer() }
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 16.dp)) {
                            if (upName.isNotEmpty()) {
                                Text("UP主：$upName", color = Color(0xFF8E8E93), fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(title, color = Color(0xFF1C1C1E), fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, maxLines = 2)
                            if (playCount > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text("${fmtPlayCount(playCount)}播放", color = Color(0xFF8E8E93), fontSize = 12.sp)
                            }
                            if (desc.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(desc, color = Color(0xFF3A3A3C), fontSize = 12.sp, maxLines = 4)
                            }
                            Spacer(Modifier.height(8.dp))
                            if (pages.size > 1) {
                                Text("分P · 共 ${pages.size} 集", color = Color(0xFF8E8E93), fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                                PageList(pages, curCid, onSelect = { curCid = it },
                                    modifier = Modifier.fillMaxWidth().weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 右侧/下方信息面板(UP主头像+名称+发布日期+标题+播放量+简介+分P) */
@Composable
private fun InfoPanel(upName: String, upFace: String, pubdate: Long, title: String,
                      playCount: Long, desc: String,
                      pages: List<PageInfo>, curCid: Long, onSelectPage: (Long) -> Unit,
                      modifier: Modifier = Modifier) {
    Column(modifier.background(Color.White).clip(RoundedCornerShape(10.dp)).padding(16.dp)) {
        // UP 主行:头像 + 名称 + 发布日期
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (upFace.isNotEmpty()) {
                AsyncImage(model = upFace, contentDescription = upName,
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFE5E5EA)))
                Spacer(Modifier.width(10.dp))
            }
            Column {
                if (upName.isNotEmpty()) {
                    Text(upName, color = Color(0xFF1C1C1E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                if (pubdate > 0) {
                    Text(fmtDate(pubdate), color = Color(0xFF8E8E93), fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, color = Color(0xFF1C1C1E), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 3)
        if (playCount > 0) {
            Spacer(Modifier.height(6.dp))
            Text("${fmtPlayCount(playCount)}播放", color = Color(0xFF8E8E93), fontSize = 12.sp)
        }
        if (desc.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(desc, color = Color(0xFF3A3A3C), fontSize = 12.sp, maxLines = 8)
        }
        Spacer(Modifier.height(12.dp))
        if (pages.size > 1) {
            Text("分P · 共 ${pages.size} 集", color = Color(0xFF8E8E93), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            PageList(pages, curCid, onSelect = onSelectPage, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** 时间戳(秒) → yyyy-MM-dd */
private fun fmtDate(sec: Long): String {
    if (sec <= 0) return ""
    return try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        f.format(java.util.Date(sec * 1000))
    } catch (_: Exception) { "" }
}

private fun fmtPlayCount(c: Long): String = when {
    c >= 100000000 -> "%.1f亿".format(c / 100000000.0)
    c >= 10000 -> "%.1f万".format(c / 10000.0)
    else -> c.toString()
}

/** 分P列表 */
@Composable
private fun PageList(pages: List<PageInfo>, curCid: Long, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        items(pages) { p ->
            val selected = p.cid == curCid
            Surface(
                color = if (selected) Color(0xFF1C1C1E) else Color.White,
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSelect(p.cid) }
            ) {
                Text(p.part, color = if (selected) Color.White else Color(0xFF1C1C1E),
                    fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun Player(
    api: BiliApi, bvid: String, cid: Long,
    onToggleFullscreen: () -> Unit,
    reImmerse: () -> Unit = {},
    modifier: Modifier = Modifier,
    initialSec: Long = 0,
    onProgress: (Long) -> Unit = {}
) {
    val ctx = LocalContext.current
    // 单一 ExoPlayer 实例(切 P/切全屏都复用,绝不重复创建)
    val exo = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    val pvRef = remember { mutableStateOf<PlayerView?>(null) }
    var url by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    val lifecycleOwner = LocalLifecycleOwner.current

    // cid 变化:先停旧源再换新源
    LaunchedEffect(bvid, cid) {
        err = ""
        exo.stop()              // 停掉上一P,避免声音叠加
        exo.clearMediaItems()
        var u = ""
        for (attempt in 1..3) {
            try {
                u = withContext(Dispatchers.IO) { api.playUrl(bvid, cid) }
                if (u.isNotEmpty()) break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(800L * attempt)
        }
        if (u.isEmpty()) { err = "无法获取播放地址(可能需要会员/登录)" }
        else {
            url = u
            applySource(exo, u)
            exo.prepare()
            if (initialSec > 0) exo.seekTo(initialSec * 1000)  // 断点续播(P 切换时 initialSec=0)
            exo.play()
        }
    }

    // 周期上报播放进度(每 3 秒)
    LaunchedEffect(exo) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            if (exo.playWhenReady && exo.isPlaying) {
                onProgress(exo.currentPosition / 1000)
            }
        }
    }

    // 生命周期: 后台暂停, 销毁释放
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    onProgress(exo.currentPosition / 1000)
                    exo.playWhenReady = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            onProgress(exo.currentPosition / 1000)
            exo.playWhenReady = false
            exo.stop()
            exo.release()
        }
    }

    Box(modifier.background(Color.Black)) {
        if (url.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (err.isNotEmpty()) Text(err, color = Color.White, fontSize = 13.sp)
                else CircularProgressIndicator(color = Color.White)
            }
        }
        AndroidView(
            factory = { c: Context ->
                val pv = PlayerView(c)
                pv.player = exo
                // 用 media3 内置控制器(播放/暂停/进度/全屏),接管其全屏按钮
                pv.setFullscreenButtonClickListener { onToggleFullscreen() }
                pv.setControllerVisibilityListener(
                    androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                        if (visibility == androidx.media3.ui.PlayerView.VISIBLE) reImmerse()
                    }
                )
                pvRef.value = pv
                pv
            },
            update = { pv -> pv.player = exo },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun applySource(e: ExoPlayer, url: String) {
    fun https(u: String): String =
        if (u.startsWith("http://")) "https://" + u.substringAfter("http://") else u
    val io = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
        okhttp3.OkHttpClient.Builder().build()
    ).apply {
        setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/122.0 Mobile Safari/537.36")
        setDefaultRequestProperties(mapOf(
            "Referer" to "https://www.bilibili.com/"
        ))
    }
    if (url.startsWith("dash:")) {
        val body = url.removePrefix("dash:")
        val parts = body.split("|")
        val ms = androidx.media3.exoplayer.source.MergingMediaSource(
            ProgressiveMediaSource.Factory(io).createMediaSource(MediaItem.fromUri(https(parts.getOrNull(0) ?: ""))),
            ProgressiveMediaSource.Factory(io).createMediaSource(MediaItem.fromUri(https(parts.getOrNull(1) ?: "")))
        )
        e.setMediaSource(ms)
    } else {
        e.setMediaSource(ProgressiveMediaSource.Factory(io).createMediaSource(MediaItem.fromUri(https(url))))
    }
}
