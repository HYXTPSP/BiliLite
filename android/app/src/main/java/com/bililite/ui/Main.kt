package com.bililite.app

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bililite.ui.BiliViewModel
import com.bililite.ui.BiliVMFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bililite.core.LoginSession
import com.bililite.data.Video
import com.bililite.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 捕获崩溃并把堆栈写进 logcat + 文件,便于定位
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.e("BiliLite", "UNCATCHED", e)
            try {
                val f = java.io.File(filesDir, "bililite_crash.txt")
                f.writeText(Log.getStackTraceString(e))
            } catch (_: Exception) {}
            try {
                com.bililite.core.BiliLog.e("Crash", "uncaught exception", e)
            } catch (_: Exception) {}
            // 保留崩溃(不吞错),但堆栈已写 logcat + 文件
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        // v0.4.4: 初始化文件日志(崩溃/网络错误/播放错误都会记录,可在「我的」→数据同步里导出)
        com.bililite.core.BiliLog.init(this)
        // v0.4.9: 初始化主题(深色模式)
        com.bililite.core.BiliTheme.init(this)
        // v0.4.9: 平板保留系统状态栏(平板状态栏适配),手机保持沉浸模式
        val isTablet = (resources.configuration.smallestScreenWidthDp >= 600)
        if (!isTablet) hideSystemBars()
        setContent { BiliLiteApp() }
    }

    private fun hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.apply {
                    hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        } catch (_: Exception) {}
    }
}

@Composable
fun BiliLiteApp() {
    val ctx = LocalContext.current.applicationContext
    val isDark = com.bililite.core.BiliTheme.dark
    // v0.4.11: 全局 MaterialTheme 动态配色——TextField 文字/占位符、下拉菜单、对话框等
    // 默认颜色自动跟随深色模式(此前搜索框等在深色下仍是黑字)
    MaterialTheme(colorScheme = if (isDark) com.bililite.core.BiliDarkScheme
                                else com.bililite.core.BiliLightScheme) {
        BiliAppContent()
    }
}

@Composable
private fun BiliAppContent() {
    val ctx = LocalContext.current.applicationContext
    // v0.4.9: 平板保留状态栏,内容区加状态栏 padding
    val isTablet = ctx.resources.configuration.smallestScreenWidthDp >= 600
    val activity = (LocalContext.current as? android.app.Activity)
    // v0.4.10: 系统栏(状态栏/手势导航栏)背景与图标颜色跟随主题。
    // v0.4.12: 华为(HarmonyOS API31-34)与标准 Android 15 机制不同,双通道兼容:
    //  ① statusBarColor/navigationBarColor——华为等传统窗口设备生效(需 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    //  ② decorView 背景——API35 强制 edge-to-edge 的设备生效
    val dark = com.bililite.core.BiliTheme.dark
    DisposableEffect(activity, dark) {
        val w = activity?.window
        if (w != null) {
            val isDarkNow = com.bililite.core.BiliTheme.dark
            // 系统栏区域背景:与页面背景一致(深色 #141416 / 浅色 #FAFAFA)
            val bgArgb = if (isDarkNow) android.graphics.Color.parseColor("#141416")
                         else android.graphics.Color.parseColor("#FAFAFA")
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            w.statusBarColor = bgArgb
            w.navigationBarColor = bgArgb
            w.decorView.setBackgroundColor(bgArgb)
            if (Build.VERSION.SDK_INT >= 30) {
                val ctrl = w.insetsController
                ctrl?.setSystemBarsAppearance(
                    if (!isDarkNow) (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                                     WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
                    else 0,
                    (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                     WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS))
            } else {
                @Suppress("DEPRECATION")
                val flags = if (!isDarkNow)
                    (android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                     android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                else 0
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = flags
            }
        }
        onDispose { }
    }
    // 开屏动画
    var showSplash by remember { mutableStateOf(true) }
    if (showSplash) {
        SplashScreen { showSplash = false }
        return
    }
    // v0.4.19: 已去除首次进入的"使用须知"弹窗(用户要求),直接进入登录/主界面
    val loginVm: LoginViewModel = viewModel(factory = LoginVMFactory(ctx))
    var loggedIn by remember { mutableStateOf(LoginSession.isLoggedIn(ctx)) }
    // v0.4.6: VM 提前创建(未登录也可),登录成功后必须 rebind() 注入 cookie,
    // 否则主界面 API 无登录态(云端收藏夹 -400 / nav -101 / 用户名不显示)
    val vm: BiliViewModel = viewModel(factory = BiliVMFactory(ctx))
    // 插件系统:注入 VM 到运行时上下文,供插件 data.* API 访问
    com.bililite.plugin.PluginRuntime.vm = vm
    if (!loggedIn) {
        LoginScreen(loginVm, onDone = { vm.rebind(); loggedIn = true })
        return
    }
    var tab by remember { mutableStateOf(0) } // 0 首页 1 我的
    // 播放上下文 (null = 未在播放;含连播列表)
    var playing by remember { mutableStateOf<PlayReq?>(null) }
    // 书签/历史跳转的目标秒数（非 null 时播放器从该时间点开始）
    var pendingSeekSec by remember { mutableStateOf<Long?>(null) }
    // 跳转的目标分P cid（非 null 时优先于 Video.cid，解决多P非P1跳转）
    var pendingCid by remember { mutableStateOf<Long?>(null) }
    val cur = playing
    // 返回手势: 播放页 → 回终端; 非首页 tab → 回首页; 首页 → 双击退出
    var backPressedOnce by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = true) {
        if (cur != null) {
            playing = null; pendingCid = null; pendingSeekSec = null; return@BackHandler
        }
        if (tab != 0) { tab = 0; return@BackHandler }
        if (backPressedOnce) { activity?.finish(); return@BackHandler }
        backPressedOnce = true
        Toast.makeText(ctx, "再按一次返回退出", Toast.LENGTH_SHORT).show()
        scope.launch { delay(2000); backPressedOnce = false }
    }

    // v0.3: 首页列表滚动状态提升到这一层(在 tab 切换/播放返回时都不销毁),
    // 从播放页返回时精确停留在原来的滚动位置
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val homeGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    if (cur != null) {
        val v = cur.video
        val up = vm.ups.firstOrNull { it.id == v.upId }
        val upName = up?.name ?: ""
        val upFace = up?.face ?: ""
        // v0.4.15: 恢复上次播放的分P(历史记录里存的 cid),而不是每次回到第一P;
        // pendingCid(书签/历史跳转)优先级最高
        var histCid by remember(v.id) { mutableStateOf(0L) }
        LaunchedEffect(v.id) { histCid = try { vm.lastWatchCid(v.id) } catch (_: Exception) { 0L } }
        Surface(Modifier.fillMaxSize().then(if (isTablet) Modifier.statusBarsPadding() else Modifier), color = BILIBLACK) {
            PlayerScreen(ctx = ctx, api = vm.api, bvid = v.bvid,
                cid = pendingCid ?: histCid.takeIf { it > 0 } ?: v.cid,
                title = v.title, upName = upName, upFace = upFace,
                onBack = { playing = null; pendingCid = null; pendingSeekSec = null },
                videoId = v.id, durationSec = v.durationSec, playCount = v.playCount,
                pubdate = v.pubdate,
                resolveCid = { bvid -> vm.resolveCid(bvid) },
                loadInitialSec = { id -> vm.lastPosition(id) },
                bookmarkSeekSec = pendingSeekSec,
                onBookmarkConsumed = { pendingSeekSec = null },
                onProgress = { secs -> vm.recordProgress(v.id, secs, v.durationSec, pendingCid ?: v.cid) },
                onProgressCid = { secs, cid -> vm.recordProgress(v.id, secs, v.durationSec, cid) },
                onFlush = { secs -> vm.flushProgress(v.id, secs, v.durationSec, pendingCid ?: v.cid) },
                // v0.3: 播放页下方相关视频(同 UP 主的其他视频)
                relatedVideos = vm.vids.filter { it.upId == v.upId && it.id != v.id }
                    .sortedByDescending { it.pubdate }.take(30),
                onPlayOther = { other ->
                    playing = PlayReq(other, (cur.playlist).filter { it.id != other.id } + listOf(other))
                    pendingCid = null; pendingSeekSec = null
                },
                // v0.3: 播放完自动下一集(先分P,后连播列表)
                playlist = cur.playlist,
                onEnded = {
                    val list = cur.playlist
                    val idx = list.indexOfFirst { it.id == v.id }
                    val next = list.getOrNull(idx + 1)
                    if (next != null) {
                        playing = PlayReq(next, list)
                        pendingCid = null; pendingSeekSec = null
                    }
                },
                bookmarks = vm.bookmarks,
                onAddBookmark = { bvid, t, cid, idx, pt, ts -> vm.addBookmark(bvid, t, cid, idx, pt, ts) },
                onRenameBookmark = { id, note -> vm.renameBookmark(id, note) },
                onDeleteBookmark = { id -> vm.deleteBookmark(id) },
                // v0.4.1: 离线缓存。这里 cid 必须用「实际正在播放的分P」，
                // 否则缓存了某P后,在收藏里切到其他P仍会命中那个缓存文件(精确匹配)。
                localPath = vm.cachedPath(v.bvid, pendingCid ?: v.cid),
                onCache = { qn -> vm.cacheVideo(v, pendingCid ?: v.cid, qn) },
                caching = vm.caching,
                cacheMsg = vm.cacheMsg)
        }
        return
    }
    // v0.4.19插件系统:主题插件的背景图(若有)铺满,内容叠在上层。按设备类型选手机/平板图。
    val bgPath = if (isTablet) com.bililite.core.BiliTheme.backgroundImagePathTablet
                 else com.bililite.core.BiliTheme.backgroundImagePath
    Box(Modifier.fillMaxSize().then(if (isTablet) Modifier.statusBarsPadding() else Modifier)) {
        if (bgPath != null) {
            val bmp = remember(bgPath) {
                try { BitmapFactory.decodeFile(bgPath)?.asImageBitmap() } catch (_: Exception) { null }
            }
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Surface(Modifier.fillMaxSize(), color = if (bgPath == null) BILIBLACK else Color.Transparent) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isTabletLocal = maxWidth >= 600.dp
            if (isTabletLocal) {
                // 平板:左侧常驻导航栏 + 右侧内容(v0.4.16:宽度收敛 80→64dp,减少笨重感)
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(
                        containerColor = com.bililite.core.C.card,
                        modifier = Modifier.fillMaxHeight().width(64.dp)
                    ) {
                        Spacer(Modifier.height(12.dp))
                        listOf("首页", "我的").forEachIndexed { i, t ->
                            NavigationRailItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = {
                                    Icon(
                                        imageVector = if (t == "首页") Icons.Filled.Home else Icons.Filled.Person,
                                        contentDescription = t,
                                        tint = if (tab == i) com.bililite.core.C.t1 else com.bililite.core.C.t2,
                                        modifier = Modifier.size(22.dp))
                                },
                                label = { Text(t, color = if (tab == i) com.bililite.core.C.t1 else com.bililite.core.C.t2,
                                    fontSize = 11.sp) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = com.bililite.core.C.t1,
                                    selectedTextColor = com.bililite.core.C.t1,
                                    indicatorColor = com.bililite.core.C.line,
                                    unselectedIconColor = com.bililite.core.C.t2,
                                    unselectedTextColor = com.bililite.core.C.t2)
                            )
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (tab) {
                            0 -> HomeScreen(vm, onPlay = { v, list -> playing = PlayReq(v, list) },
                                isTablet = true, listState = homeListState, gridState = homeGridState)
                            else -> ProfileScreen(vm, onLoggedOut = { vm.rebind(); loggedIn = false; tab = 0 },
                                onPlay = { v, list -> playing = PlayReq(v, list) },
                                onPlayWithCid = { v, cid, sec, list -> pendingCid = cid; pendingSeekSec = sec; playing = PlayReq(v, list) },
                                onPlayBookmark = { v, cid, sec, list -> pendingCid = cid; pendingSeekSec = sec; playing = PlayReq(v, list) },
                                onPlayCache = { v, path -> playing = PlayReq(v, listOf(v), path) })
                        }
                    }
                }
            } else {
                // 手机:底部导航(v0.4.16:紧凑化——去掉默认 80dp 高度+底部 insets,收敛到 58dp,消除笨重感)
                Scaffold(
                    containerColor = BILIBLACK,
                    bottomBar = {
                        NavigationBar(
                            containerColor = com.bililite.core.C.card,
                            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                            modifier = Modifier.height(58.dp)
                        ) {
                            listOf("首页", "我的").forEachIndexed { i, t ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = com.bililite.core.C.t1,
                                        selectedTextColor = com.bililite.core.C.t1,
                                        indicatorColor = com.bililite.core.C.line,
                                        unselectedIconColor = com.bililite.core.C.t2,
                                        unselectedTextColor = com.bililite.core.C.t2),
                                    label = { Text(t, color = if (tab == i) com.bililite.core.C.t1 else com.bililite.core.C.t2,
                                                     fontSize = 11.sp) }, icon = {})
                            }
                        }
                    }
                ) { p ->
                    // v0.4.20: 手机端首页/我的 支持左右滑动切换
                    var swipeAccum by remember { mutableStateOf(0f) }
                    Box(Modifier.padding(p).pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (swipeAccum > 120f && tab == 1) tab = 0              // 右滑 → 首页
                                else if (swipeAccum < -120f && tab == 0) tab = 1        // 左滑 → 我的
                                swipeAccum = 0f
                            },
                            onHorizontalDrag = { _, dragAmount -> swipeAccum += dragAmount },
                            onDragCancel = { swipeAccum = 0f }
                        )
                    }) {
                        when (tab) {
                            0 -> HomeScreen(vm, onPlay = { v, list -> playing = PlayReq(v, list) },
                                listState = homeListState)
                            else -> ProfileScreen(vm, onLoggedOut = { vm.rebind(); loggedIn = false; tab = 0 },
                                onPlay = { v, list -> playing = PlayReq(v, list) },
                                onPlayWithCid = { v, cid, sec, list -> pendingCid = cid; pendingSeekSec = sec; playing = PlayReq(v, list) },
                                onPlayBookmark = { v, cid, sec, list -> pendingCid = cid; pendingSeekSec = sec; playing = PlayReq(v, list) },
                                onPlayCache = { v, path -> playing = PlayReq(v, listOf(v), path) })
                        }
                    }
                }
            }
        }
        }
    }
}

// 纯黑白主题(v0.4.9: 动态跟随深色模式)
val BILIBLACK: Color get() = com.bililite.core.C.bg
val BILICARD: Color get() = com.bililite.core.C.card
val BILILINE: Color get() = com.bililite.core.C.line

// ============================================================================
// v0.4.19: 首次使用同意弹窗(免责 + 隐私)
// ============================================================================
private fun readAsset(ctx: Context, name: String): String =
    try { ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() } }
    catch (_: Exception) { "" }

@Composable
private fun TosDialog(onAgree: () -> Unit, onDisagree: () -> Unit) {
    val ctx = LocalContext.current
    val legal = remember { readAsset(ctx, "legal.md") }
    val privacy = remember { readAsset(ctx, "privacy.md") }
    Dialog(onDismissRequest = { /* 必须明确选择,点外部不可关闭 */ }) {
        Card(shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = com.bililite.core.C.card)) {
            Column(Modifier.padding(16.dp)) {
                Text("使用须知", color = com.bililite.core.C.t1, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("请阅读并同意以下《开源致谢与法律声明》与《隐私政策》后继续使用。",
                    color = com.bililite.core.C.t2, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(legal, color = com.bililite.core.C.t3, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(privacy, color = com.bililite.core.C.t3, fontSize = 12.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDisagree, modifier = Modifier.weight(1f)) {
                        Text("不同意并退出", color = com.bililite.core.C.t2)
                    }
                    Button(onClick = onAgree, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = com.bililite.core.C.block)) {
                        Text("同意并继续", color = com.bililite.core.C.onBlock)
                    }
                }
            }
        }
    }
}

/** 开屏动画:显示品牌图并淡出 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var alpha by remember { mutableStateOf(0f) }
    val bmp: ImageBitmap? = remember {
        try {
            val d = BitmapFactory.decodeResource(ctx.resources, R.drawable.splash)
            d?.asImageBitmap()
        } catch (_: Exception) { null }
    }
    LaunchedEffect(Unit) {
        alpha = 1f
        delay(1500)          // 停留 1.5s
        alpha = 0f           // 淡出 0.3s
        delay(300)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = "开屏",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha })
        }
    }
}
