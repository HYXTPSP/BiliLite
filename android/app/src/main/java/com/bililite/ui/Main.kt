package com.bililite.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bililite.ui.BiliViewModel
import com.bililite.ui.BiliVMFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bililite.core.LoginSession
import com.bililite.data.Video
import com.bililite.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // 捕获崩溃并把堆栈写进 logcat + 文件,便于定位
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.e("BiliLite", "UNCATCHED", e)
            try {
                val f = java.io.File(filesDir, "bililite_crash.txt")
                f.writeText(Log.getStackTraceString(e))
            } catch (_: Exception) {}
            // 保留崩溃(不吞错),但堆栈已写 logcat + 文件
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        setContent { BiliLiteApp() }
    }
}

@Composable
fun BiliLiteApp() {
    val ctx = LocalContext.current.applicationContext
    // 开屏动画
    var showSplash by remember { mutableStateOf(true) }
    if (showSplash) {
        SplashScreen { showSplash = false }
        return
    }
    val loginVm: LoginViewModel = viewModel(factory = LoginVMFactory(ctx))
    var loggedIn by remember { mutableStateOf(LoginSession.isLoggedIn(ctx)) }
    if (!loggedIn) {
        LoginScreen(loginVm, onDone = { loggedIn = true })
        return
    }
    val vm: BiliViewModel = viewModel(factory = BiliVMFactory(ctx))
    var tab by remember { mutableStateOf(0) } // 0 首页 1 收藏 2 我的
    // 播放中的视频 (null = 未在播放)
    var playing by remember { mutableStateOf<Video?>(null) }
    // 书签/历史跳转的目标秒数（非 null 时播放器从该时间点开始）
    var pendingSeekSec by remember { mutableStateOf<Long?>(null) }
    // 跳转的目标分P cid（非 null 时优先于 Video.cid，解决多P非P1跳转）
    var pendingCid by remember { mutableStateOf<Long?>(null) }
    val cur = playing
    // 返回手势: 播放页 → 回终端; 非首页 tab → 回首页; 首页 → 双击退出
    val activity = (LocalContext.current as? android.app.Activity)
    var backPressedOnce by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = true) {
        if (cur != null) { playing = null; return@BackHandler }
        if (tab != 0) { tab = 0; return@BackHandler }
        if (backPressedOnce) { activity?.finish(); return@BackHandler }
        backPressedOnce = true
        Toast.makeText(ctx, "再按一次返回退出", Toast.LENGTH_SHORT).show()
        scope.launch { delay(2000); backPressedOnce = false }
    }
    if (cur != null) {
        val up = vm.ups.firstOrNull { it.id == cur.upId }
        val upName = up?.name ?: ""
        val upFace = up?.face ?: ""
        Surface(Modifier.fillMaxSize(), color = BILIBLACK) {
            PlayerScreen(ctx = ctx, api = vm.api, bvid = cur.bvid, cid = pendingCid ?: cur.cid,
                title = cur.title, upName = upName, upFace = upFace, onBack = { playing = null; pendingCid = null },
                videoId = cur.id, durationSec = cur.durationSec, playCount = cur.playCount,
                pubdate = cur.pubdate,
                resolveCid = { bvid -> vm.resolveCid(bvid) },
                loadInitialSec = { id -> vm.lastPosition(id) },
                bookmarkSeekSec = pendingSeekSec,
                onBookmarkConsumed = { pendingSeekSec = null },
                onProgress = { secs -> vm.recordProgress(cur.id, secs, cur.durationSec) },
                onProgressCid = { secs, cid -> vm.recordProgress(cur.id, secs, cur.durationSec, cid) },
                bookmarks = vm.bookmarks,
                onAddBookmark = { bvid, t, cid, idx, pt, ts -> vm.addBookmark(bvid, t, cid, idx, pt, ts) },
                onRenameBookmark = { id, note -> vm.renameBookmark(id, note) },
                onDeleteBookmark = { id -> vm.deleteBookmark(id) })
        }
        return
    }
    Surface(Modifier.fillMaxSize(), color = BILIBLACK) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isTablet = maxWidth >= 600.dp
            if (isTablet) {
                // 平板:左侧常驻导航栏 + 右侧内容
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(
                        containerColor = Color.White,
                        modifier = Modifier.fillMaxHeight().width(80.dp)
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
                                        tint = if (tab == i) Color.Black else Color(0xFF8E8E93),
                                        modifier = Modifier.size(22.dp))
                                },
                                label = { Text(t, color = if (tab == i) Color.Black else Color(0xFF8E8E93),
                                    fontSize = 11.sp) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Color.Black,
                                    selectedTextColor = Color.Black,
                                    indicatorColor = Color(0xFFE5E5EA),
                                    unselectedIconColor = Color(0xFF8E8E93),
                                    unselectedTextColor = Color(0xFF8E8E93))
                            )
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (tab) {
                            0 -> HomeScreen(vm, onPlay = { playing = it }, isTablet = true)
                            else -> ProfileScreen(vm, onLoggedOut = { loggedIn = false; tab = 0 },
                                onPlay = { playing = it },
                                onPlayWithCid = { v, cid, sec -> pendingCid = cid; pendingSeekSec = sec; playing = v },
                                onPlayBookmark = { v, cid, sec -> pendingCid = cid; pendingSeekSec = sec; playing = v })
                        }
                    }
                }
            } else {
                // 手机:底部导航
                Scaffold(
                    containerColor = BILIBLACK,
                    bottomBar = {
                        NavigationBar(containerColor = Color.White) {
                            listOf("首页", "我的").forEachIndexed { i, t ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        selectedTextColor = Color.Black,
                                        indicatorColor = Color(0xFFE5E5EA),
                                        unselectedIconColor = Color(0xFF8E8E93),
                                        unselectedTextColor = Color(0xFF8E8E93)),
                                    label = { Text(t, color = if (tab == i) Color.Black else Color(0xFF8E8E93),
                                                     fontSize = 12.sp) }, icon = {})
                            }
                        }
                    }
                ) { p ->
                    Box(Modifier.padding(p)) {
                        when (tab) {
                            0 -> HomeScreen(vm, onPlay = { playing = it })
                            else -> ProfileScreen(vm, onLoggedOut = { loggedIn = false; tab = 0 },
                                onPlay = { playing = it },
                                onPlayWithCid = { v, cid, sec -> pendingCid = cid; pendingSeekSec = sec; playing = v },
                                onPlayBookmark = { v, cid, sec -> pendingCid = cid; pendingSeekSec = sec; playing = v })
                        }
                    }
                }
            }
        }
    }
}

// 纯黑白主题
val BILIBLACK = Color(0xFFFAFAFA)
val BILICARD = Color(0xFFFFFFFF)
val BILILINE = Color(0xFFE5E5EA)

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
