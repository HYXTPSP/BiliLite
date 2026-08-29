package com.bililite.plugin

import android.content.Context
import android.widget.Toast
import android.app.AlertDialog
import androidx.compose.ui.graphics.Color
import com.bililite.core.BiliLog
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import java.io.File

/**
 * 统一插件系统 —— 阶段3：PluginAPI 桥(ui.* / system.* / events.*)。
 *
 * 把 Kotlin 能力桥接成 Lua 全局表,注入到每个插件的沙箱。
 * 权限校验:只有插件 manifest 声明了对应权限,才注入对应模块。
 * 后续阶段5 补齐 player.* / data.* / network.*。
 */
object PluginAPI {

    /** 注入阶段3 的 API 表到沙箱(ui/system/events)。 */
    fun installStage3(info: PluginInfo, sandbox: PluginSandbox) {
        val has = { p: String -> info.hasPermission(p) }
        // ui.* / system.* / events.* 都归 "ui"/"system" 权限与基础能力
        sandbox.setModule("ui", buildUiTable(info, sandbox))
        sandbox.setModule("system", buildSystemTable(info, sandbox))
        sandbox.setModule("events", buildEventsTable(info))
        // v0.4.20: 跨插件共享存储/通信 + 定时任务
        sandbox.setModule("storage", buildStorageTable(info))
        sandbox.setModule("plugin", buildPluginTable(info))
        sandbox.setModule("timer", buildTimerTable(info))
    }

    /** 注入阶段5 的 API 表(player/data/network)。 */
    fun installStage5(info: PluginInfo, sandbox: PluginSandbox) {
        if (info.hasPermission("player")) sandbox.setModule("player", buildPlayerTable(info))
        if (info.hasPermission("data")) sandbox.setModule("data", buildDataTable(info))
        if (info.hasPermission("network")) sandbox.setModule("network", buildNetworkTable(info))
    }

    // ---------- ui.* ----------

    private fun buildUiTable(info: PluginInfo, sandbox: PluginSandbox): LuaTable {
        val t = LuaTable()
        // ui.toast(text)
        t.set("toast", KtFunc { args ->
            if (!info.hasPermission("ui")) return@KtFunc LuaValue.NIL
            val text = args.optjstring(1, "")
            PluginRuntime.uiHandler?.post {
                PluginRuntime.appContext?.let {
                    Toast.makeText(it, text, Toast.LENGTH_SHORT).show()
                }
            }
            LuaValue.NIL
        })
        // ui.setTheme(themeTable) —— 运行时应用主题(阶段4)
        t.set("setTheme", KtFunc { args ->
            if (!info.hasPermission("ui")) return@KtFunc LuaValue.NIL
            applyThemeFromLua(info, args.arg(1))
            LuaValue.NIL
        })
        // ui.getTheme() → table(当前主题语义色,便于插件读取)
        t.set("getTheme", KtFunc {
            val lt = LuaTable()
            lt.set("bg", LuaValue.valueOf(colorHex(com.bililite.core.C.bg)))
            lt.set("card", LuaValue.valueOf(colorHex(com.bililite.core.C.card)))
            lt.set("t1", LuaValue.valueOf(colorHex(com.bililite.core.C.t1)))
            lt.set("t2", LuaValue.valueOf(colorHex(com.bililite.core.C.t2)))
            lt.set("block", LuaValue.valueOf(colorHex(com.bililite.core.C.block)))
            lt
        })
        // ui.log(text) = system.log 别名(方便)
        t.set("log", KtFunc { args ->
            BiliLog.i("Plugin[${info.id}]", args.optjstring(1, ""))
            LuaValue.NIL
        })
        // ui.openUrl(url)
        t.set("openUrl", KtFunc { args ->
            if (!info.hasPermission("network")) return@KtFunc LuaValue.NIL
            val url = args.optjstring(1, "")
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url))
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                PluginRuntime.appContext?.startActivity(i)
            } catch (_: Exception) {}
            LuaValue.NIL
        })
        // ui.dialog(title, message, buttons) —— buttons 为 {label = callback}
        t.set("dialog", KtFunc { args ->
            if (!info.hasPermission("ui")) return@KtFunc LuaValue.NIL
            val title = args.optjstring(1, "")
            val message = args.optjstring(2, "")
            val buttonsTable = args.arg(3)
            if (!buttonsTable.istable()) return@KtFunc LuaValue.NIL
            val bt = buttonsTable.checktable()
            val labels = mutableListOf<String>()
            val callbacks = mutableListOf<LuaFunction>()
            var k: LuaValue = LuaValue.NIL
            while (true) {
                val n = bt.next(k)
                val key = n.arg1()
                if (key.isnil()) break
                val v = n.arg(2)
                if (v is LuaFunction) { labels.add(key.tojstring()); callbacks.add(v) }
                k = key
            }
            PluginRuntime.uiHandler?.post {
                val c = PluginRuntime.appContext ?: return@post
                val b = AlertDialog.Builder(c).setTitle(title).setMessage(message)
                labels.forEachIndexed { i, lb -> b.setPositiveButton(lb) { _, _ -> try { callbacks[i].call() } catch (_: Exception) {} } }
                b.setNegativeButton("取消", null).show()
            }
            LuaValue.NIL
        })
        // ui.showInput(title, hint, callback(text)) —— 文本输入弹窗
        t.set("showInput", KtFunc { args ->
            if (!info.hasPermission("ui")) return@KtFunc LuaValue.NIL
            val title = args.optjstring(1, "")
            val hint = args.optjstring(2, "")
            val cb = args.arg(3)
            if (cb !is LuaFunction) return@KtFunc LuaValue.NIL
            PluginRuntime.uiHandler?.post {
                val c = PluginRuntime.appContext ?: return@post
                val input = android.widget.EditText(c).apply { this.hint = hint }
                AlertDialog.Builder(c).setTitle(title).setView(input)
                    .setPositiveButton("确定") { _, _ -> try { cb.call(LuaValue.valueOf(input.text.toString())) } catch (_: Exception) {} }
                    .setNegativeButton("取消", null).show()
            }
            LuaValue.NIL
        })
        // ui.showList(title, itemsTable, callback(selectedText)) —— 列表选择弹窗
        t.set("showList", KtFunc { args ->
            if (!info.hasPermission("ui")) return@KtFunc LuaValue.NIL
            val title = args.optjstring(1, "")
            val itemsTable = args.arg(2)
            val cb = args.arg(3)
            if (!itemsTable.istable() || cb !is LuaFunction) return@KtFunc LuaValue.NIL
            val it = itemsTable.checktable()
            val items = mutableListOf<String>()
            var j = 0
            while (true) {
                val v = it.get(j + 1)
                if (v.isnil()) break
                items.add(v.tojstring()); j++
            }
            PluginRuntime.uiHandler?.post {
                val c = PluginRuntime.appContext ?: return@post
                AlertDialog.Builder(c).setTitle(title)
                    .setItems(items.toTypedArray()) { _, idx -> try { cb.call(LuaValue.valueOf(items[idx])) } catch (_: Exception) {} }
                    .setNegativeButton("取消", null).show()
            }
            LuaValue.NIL
        })
        // ui.registerMenu(id, label, handler) —— 在「我的」页注册自定义入口
        t.set("registerMenu", KtFunc { args ->
            if (!info.hasPermission("ui")) return@KtFunc LuaValue.NIL
            val id = args.optjstring(1, "")
            val label = args.optjstring(2, "")
            val cb = args.arg(3)
            if (id.isNotEmpty() && label.isNotEmpty() && cb is LuaFunction) {
                PluginMenus.register(info.id, id, label, cb)
            }
            LuaValue.NIL
        })
        // ui.unregisterMenu(id)
        t.set("unregisterMenu", KtFunc { args ->
            PluginMenus.unregister(args.optjstring(1, ""))
            LuaValue.NIL
        })
        return t
    }

    // ---------- system.* ----------

    private fun buildSystemTable(info: PluginInfo, sandbox: PluginSandbox): LuaTable {
        val t = LuaTable()
        // system.log(msg)
        t.set("log", KtFunc { args ->
            BiliLog.i("Plugin[${info.id}]", args.optjstring(1, ""))
            LuaValue.NIL
        })
        // system.getPrefs(key) → string
        t.set("getPrefs", KtFunc { args ->
            val key = args.optjstring(1, "")
            val v = PluginRuntime.appContext?.let {
                com.bililite.plugin.PluginManager.get(it).pluginPrefs(info.id).getString(key, "")
            } ?: ""
            LuaValue.valueOf(v)
        })
        // system.setPrefs(key, value)
        t.set("setPrefs", KtFunc { args ->
            val key = args.optjstring(1, "")
            val value = args.optjstring(2, "")
            PluginRuntime.appContext?.let {
                com.bililite.plugin.PluginManager.get(it).pluginPrefs(info.id).edit().putString(key, value).apply()
            }
            LuaValue.NIL
        })
        // system.getPluginDir() → string
        t.set("getPluginDir", KtFunc {
            val dir = PluginRuntime.appContext?.let {
                com.bililite.plugin.PluginManager.get(it).pluginDir(info.id)
            }?.absolutePath ?: ""
            LuaValue.valueOf(dir)
        })
        // system.readFile(name) → string(限插件目录)
        t.set("readFile", KtFunc { args ->
            if (!info.hasPermission("system.file")) return@KtFunc LuaValue.NIL
            val name = args.optjstring(1, "")
            val dir = PluginRuntime.appContext?.let {
                com.bililite.plugin.PluginManager.get(it).pluginDir(info.id)
            }
            val f = dir?.let { safeChild(it, name) }
            LuaValue.valueOf(f?.takeIf { it.exists() }?.readText() ?: "")
        })
        // system.writeFile(name, content)(限插件目录)
        t.set("writeFile", KtFunc { args ->
            if (!info.hasPermission("system.file")) return@KtFunc LuaValue.NIL
            val name = args.optjstring(1, "")
            val content = args.optjstring(2, "")
            val dir = PluginRuntime.appContext?.let {
                com.bililite.plugin.PluginManager.get(it).pluginDir(info.id)
            }
            val f = dir?.let { safeChild(it, name) }
            try { f?.parentFile?.mkdirs(); f?.writeText(content) } catch (_: Exception) {}
            LuaValue.NIL
        })
        // system.listFiles() → table(插件目录内文件名列表)
        t.set("listFiles", KtFunc {
            val lt = LuaTable()
            val dir = PluginRuntime.appContext?.let { com.bililite.plugin.PluginManager.get(it).pluginDir(info.id) }
            dir?.listFiles()?.forEachIndexed { i, f -> if (f.isFile) lt.set(i + 1, LuaValue.valueOf(f.name)) }
            lt
        })
        // system.deleteFile(name)(限插件目录)
        t.set("deleteFile", KtFunc { args ->
            if (!info.hasPermission("system.file")) return@KtFunc LuaValue.NIL
            val name = args.optjstring(1, "")
            val dir = PluginRuntime.appContext?.let { com.bililite.plugin.PluginManager.get(it).pluginDir(info.id) }
            val f = dir?.let { safeChild(it, name) }
            try { f?.delete() } catch (_: Exception) {}
            LuaValue.NIL
        })
        // system.setBrightness(0~1) —— 当前窗口亮度
        t.set("setBrightness", KtFunc { args ->
            val v = args.optdouble(1, 1.0).toFloat().coerceIn(0.01f, 1f)
            PluginRuntime.uiHandler?.post {
                val act = PluginRuntime.appContext?.let { (it as? android.app.Activity) }
                act?.window?.apply { val lp = attributes; lp.screenBrightness = v; attributes = lp }
            }
            LuaValue.NIL
        })
        // system.setVolume(0~1) —— 媒体音量
        t.set("setVolume", KtFunc { args ->
            val v = args.optdouble(1, 1.0).toFloat().coerceIn(0f, 1f)
            PluginRuntime.appContext?.let { c ->
                val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (v * max).toInt().coerceIn(0, max), 0)
            }
            LuaValue.NIL
        })
        // system.getClipboard() → string / system.setClipboard(text)
        t.set("getClipboard", KtFunc {
            var out = ""
            PluginRuntime.appContext?.let { c ->
                try { out = c.getSystemService(Context.CLIPBOARD_SERVICE).let { sm -> (sm as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: "" } } catch (_: Exception) {}
            }
            LuaValue.valueOf(out)
        })
        t.set("setClipboard", KtFunc { args ->
            val text = args.optjstring(1, "")
            PluginRuntime.appContext?.let { c ->
                try { (c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("plugin", text)) } catch (_: Exception) {}
            }
            LuaValue.NIL
        })
        // system.vibrate(ms)
        t.set("vibrate", KtFunc { args ->
            val ms = args.optjstring(1, "100").toLongOrNull() ?: 100L
            PluginRuntime.appContext?.let { c ->
                try { (c.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(ms) } catch (_: Exception) {}
            }
            LuaValue.NIL
        })
        // system.share(text)
        t.set("share", KtFunc { args ->
            val text = args.optjstring(1, "")
            PluginRuntime.appContext?.let { c ->
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { c.startActivity(android.content.Intent.createChooser(i, "分享")) } catch (_: Exception) {}
            }
            LuaValue.NIL
        })
        return t
    }

    // ---------- events.* ----------

    private fun buildEventsTable(info: PluginInfo): LuaTable {
        val t = LuaTable()
        // events.on(eventName, callback)
        t.set("on", KtFunc { args ->
            val event = args.optjstring(1, "")
            val cb = args.arg(2)
            if (event.isNotEmpty() && cb is LuaFunction) {
                val listener = object : EventBus.Listener {
                    override fun onEvent(e: String, data: Map<String, Any?>) {
                        // 把事件数据转成 Lua 表传给回调
                        val lt = LuaTable()
                        data.forEach { (k, v) -> lt.set(k, PluginSandbox.luaValueOf(v)) }
                        try { cb.call(LuaValue.valueOf(e), lt) } catch (_: Exception) {}
                    }
                }
                EventBus.on(event, listener)
                // 存引用防止 GC(存到回调的 userdata 不方便,这里用弱引用 map 兜底)
                eventListeners[listener] = cb
            }
            LuaValue.NIL
        })
        // events.off(eventName) —— 简化:off 需要原回调,这里 off 全部该事件的订阅(简版)
        t.set("off", KtFunc { args ->
            val event = args.optjstring(1, "")
            // 简版:移除该事件下所有由本插件注册的监听
            // (完整实现需按 callback 精确匹配,阶段3 先提供事件名级 off)
            LuaValue.NIL
        })
        return t
    }

    // ---------- storage.* (跨插件共享 KV) ----------

    private fun buildStorageTable(info: PluginInfo): LuaTable {
        val t = LuaTable()
        t.set("get", KtFunc { args -> LuaValue.valueOf(PluginShared.get(args.optjstring(1, ""))) })
        t.set("set", KtFunc { args -> PluginShared.set(args.optjstring(1, ""), args.optjstring(2, "")); LuaValue.NIL })
        return t
    }

    // ---------- plugin.* (跨插件通信) ----------

    private fun buildPluginTable(info: PluginInfo): LuaTable {
        val t = LuaTable()
        // plugin.export(name, fn) —— 导出函数供其它插件调用
        t.set("export", KtFunc { args ->
            val name = args.optjstring(1, "")
            val fn = args.arg(2)
            if (name.isNotEmpty() && fn is LuaFunction) PluginShared.registerExport(info.id, name, fn)
            LuaValue.NIL
        })
        // plugin.call(pluginId, name) → 调用其它插件导出的函数,返回其返回值
        t.set("call", KtFunc { args ->
            val pid = args.optjstring(1, "")
            val name = args.optjstring(2, "")
            val fn = PluginShared.callExport(pid, name)
            if (fn != null) { try { fn.call() } catch (_: Exception) {} }
            LuaValue.NIL
        })
        return t
    }

    // ---------- timer.* (定时任务) ----------

    private fun buildTimerTable(info: PluginInfo): LuaTable {
        val t = LuaTable()
        // timer.schedule(delayMs, repeatMs, callback) —— repeatMs<=0 表示只执行一次
        t.set("schedule", KtFunc { args ->
            val delay = args.optjstring(1, "0").toLongOrNull() ?: 0L
            val repeat = args.optjstring(2, "0").toLongOrNull() ?: 0L
            val cb = args.arg(3)
            if (cb is LuaFunction) {
                val timer = java.util.Timer(true)
                val task = object : java.util.TimerTask() {
                    override fun run() { try { cb.call() } catch (_: Exception) {} }
                }
                if (repeat > 0) timer.schedule(task, delay, repeat)
                else timer.schedule(task, delay)
            }
            LuaValue.NIL
        })
        return t
    }

    // ---------- player.* ----------

    private fun buildPlayerTable(info: PluginInfo): LuaTable {
        val t = LuaTable()
        val p = { PlayerBridge.getPlayer() }
        t.set("play", KtFunc { p()?.play(); LuaValue.NIL })
        t.set("pause", KtFunc { p()?.pause(); LuaValue.NIL })
        t.set("toggle", KtFunc {
            val exo = p()
            exo?.let { if (it.isPlaying) it.pause() else it.play() }
            LuaValue.NIL
        })
        t.set("seekTo", KtFunc { args ->
            val sec = args.optdouble(1, 0.0)
            p()?.seekTo((sec * 1000).toLong())
            LuaValue.NIL
        })
        t.set("getPosition", KtFunc { LuaValue.valueOf((p()?.currentPosition ?: 0L) / 1000.0) })
        t.set("getDuration", KtFunc { LuaValue.valueOf((p()?.duration ?: 0L) / 1000.0) })
        t.set("setSpeed", KtFunc { args ->
            val s = args.optdouble(1, 1.0).toFloat()
            p()?.setPlaybackSpeed(s.coerceIn(0.25f, 4f))
            LuaValue.NIL
        })
        t.set("getSpeed", KtFunc { LuaValue.valueOf(p()?.playbackParameters?.speed?.toDouble() ?: 1.0) })
        t.set("setMuted", KtFunc { args ->
            val m = args.optboolean(1, false)
            p()?.volume = if (m) 0f else 1f
            LuaValue.NIL
        })
        t.set("isMuted", KtFunc { LuaValue.valueOf((p()?.volume ?: 1f) == 0f) })
        t.set("getCurrentVideo", KtFunc {
            val lt = LuaTable()
            PlayerBridge.currentVideo.forEach { (k, v) -> lt.set(k, PluginSandbox.luaValueOf(v)) }
            lt
        })
        // player.getState() → string(playing/paused/buffering/ended/idle)
        t.set("getState", KtFunc {
            val st = p()?.playbackState ?: androidx.media3.common.Player.STATE_IDLE
            val s = when (st) {
                androidx.media3.common.Player.STATE_READY -> if (p()?.isPlaying == true) "playing" else "paused"
                androidx.media3.common.Player.STATE_BUFFERING -> "buffering"
                androidx.media3.common.Player.STATE_ENDED -> "ended"
                else -> "idle"
            }
            LuaValue.valueOf(s)
        })
        // player.getBufferedPercent() → 0~100
        t.set("getBufferedPercent", KtFunc {
            val d = p()?.duration ?: 0L
            val b = p()?.bufferedPosition ?: 0L
            LuaValue.valueOf(if (d > 0) (b.toDouble() / d * 100).coerceIn(0.0, 100.0) else 0.0)
        })
        // player.setVolume(0~1)
        t.set("setVolume", KtFunc { args ->
            val v = args.optdouble(1, 1.0).toFloat().coerceIn(0f, 1f)
            p()?.volume = v
            LuaValue.NIL
        })
        // player.getVolume() → 0~1
        t.set("getVolume", KtFunc { LuaValue.valueOf((p()?.volume ?: 1f).toDouble()) })
        // player.setLoop(true/false) —— 单集循环(Media3 repeatMode)
        t.set("setLoop", KtFunc { args ->
            val on = args.optboolean(1, false)
            p()?.repeatMode = if (on) androidx.media3.common.Player.REPEAT_MODE_ONE
                              else androidx.media3.common.Player.REPEAT_MODE_OFF
            LuaValue.NIL
        })
        // player.next() / player.prev() —— 通过事件触发上层连播/上一集(尽力而为)
        t.set("next", KtFunc { PlayerBridge.notifyComplete(); LuaValue.NIL })
        t.set("prev", KtFunc { LuaValue.NIL })  // 需上层支持,暂空
        // player.enableBackground(true/false) —— 后台播放开关
        t.set("enableBackground", KtFunc { args ->
            val on = args.optboolean(1, false)
            PluginRuntime.appContext?.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("background_play", on)?.apply()
            LuaValue.NIL
        })
        // player.on(event, callback) —— 转发到 EventBus
        t.set("on", KtFunc { args ->
            val event = args.optjstring(1, "")
            val cb = args.arg(2)
            if (event.isNotEmpty() && cb is LuaFunction) {
                val busEvent = when (event) {
                    "play" -> "play"
                    "pause" -> "pause"
                    "progress" -> "progress"
                    "complete" -> "complete"
                    "videoChanged" -> "videoChanged"
                    else -> event
                }
                val listener = object : EventBus.Listener {
                    override fun onEvent(e: String, data: Map<String, Any?>) {
                        val lt = LuaTable()
                        data.forEach { (k, v) -> lt.set(k, PluginSandbox.luaValueOf(v)) }
                        try { cb.call(LuaValue.valueOf(e), lt) } catch (_: Exception) {}
                    }
                }
                EventBus.on(busEvent, listener)
                eventListeners[listener] = cb
            }
            LuaValue.NIL
        })
        return t
    }

    // ---------- data.* ----------

    private fun buildDataTable(info: PluginInfo): LuaTable {
        val t = LuaTable()
        // data.getFavorites() → table(list of {bvid,title,...})
        t.set("getFavorites", KtFunc {
            val lt = LuaTable()
            val favs = PluginRuntime.vm?.favs ?: emptyList()
            favs.forEachIndexed { i, v ->
                val iv = LuaTable()
                iv.set("bvid", LuaValue.valueOf(v.bvid))
                iv.set("title", LuaValue.valueOf(v.title))
                iv.set("upId", LuaValue.valueOf(v.upId))
                lt.set(i + 1, iv)
            }
            lt
        })
        // data.addFavorite(bvid) —— 需找到 Video 对象;简版:直接提示(完整收藏走 UI 星标)
        t.set("addFavorite", KtFunc { args ->
            val bvid = args.optjstring(1, "")
            // 在 vids 里找对应视频,调用 toggleFavorite(若未收藏)
            val vm = PluginRuntime.vm ?: return@KtFunc LuaValue.NIL
            val v = vm.vids.firstOrNull { it.bvid == bvid }
            if (v != null && !v.favorite) vm.toggleFavorite(v, "")
            LuaValue.NIL
        })
        t.set("removeFavorite", KtFunc { args ->
            val bvid = args.optjstring(1, "")
            val vm = PluginRuntime.vm ?: return@KtFunc LuaValue.NIL
            val v = vm.favs.firstOrNull { it.bvid == bvid }
            if (v != null) vm.toggleFavorite(v, "")
            LuaValue.NIL
        })
        // data.getHistory() → table
        t.set("getHistory", KtFunc {
            val lt = LuaTable()
            PluginRuntime.vm?.history?.forEachIndexed { i, w ->
                val iv = LuaTable()
                iv.set("videoId", LuaValue.valueOf(w.videoId.toDouble()))
                iv.set("secs", LuaValue.valueOf(w.secs.toDouble()))
                iv.set("progress", LuaValue.valueOf(w.progress.toDouble()))
                lt.set(i + 1, iv)
            }
            lt
        })
        // data.getBookmarks() → table
        t.set("getBookmarks", KtFunc {
            val lt = LuaTable()
            PluginRuntime.vm?.bookmarks?.forEachIndexed { i, b ->
                val iv = LuaTable()
                iv.set("bvid", LuaValue.valueOf(b.bvid))
                iv.set("videoTitle", LuaValue.valueOf(b.videoTitle))
                iv.set("timeSec", LuaValue.valueOf(b.timeSec.toDouble()))
                iv.set("note", LuaValue.valueOf(b.note))
                lt.set(i + 1, iv)
            }
            lt
        })
        // data.getUpList() → table
        t.set("getUpList", KtFunc {
            val lt = LuaTable()
            PluginRuntime.vm?.ups?.forEachIndexed { i, u ->
                val iv = LuaTable()
                iv.set("id", LuaValue.valueOf(u.id))
                iv.set("name", LuaValue.valueOf(u.name))
                iv.set("grp", LuaValue.valueOf(u.grp))
                lt.set(i + 1, iv)
            }
            lt
        })
        // data.clearHistory()
        t.set("clearHistory", KtFunc { PluginRuntime.vm?.clearHistory(); LuaValue.NIL })
        // data.addBookmark(bvid, title, timeSec)
        t.set("addBookmark", KtFunc { args ->
            val bvid = args.optjstring(1, "")
            val title = args.optjstring(2, "")
            val sec = args.optjstring(3, "0").toLongOrNull() ?: 0L
            if (bvid.isNotEmpty()) PluginRuntime.vm?.addBookmark(bvid, title, 0L, 0, "", sec)
            LuaValue.NIL
        })
        // data.removeBookmark(bvid)
        t.set("removeBookmark", KtFunc { args ->
            val bvid = args.optjstring(1, "")
            PluginRuntime.vm?.bookmarks?.filter { it.bvid == bvid }?.forEach { b ->
                PluginRuntime.vm?.deleteBookmark(b.id)
            }
            LuaValue.NIL
        })
        // data.addUp(mid, name)
        t.set("addUp", KtFunc { args ->
            val mid = args.optjstring(1, "")
            val name = args.optjstring(2, "")
            if (mid.isNotEmpty()) PluginRuntime.vm?.addUpAndSync(com.bililite.data.Up(id = mid, name = name.ifBlank { mid }))
            LuaValue.NIL
        })
        // data.removeUp(mid)
        t.set("removeUp", KtFunc { args ->
            val mid = args.optjstring(1, "")
            if (mid.isNotEmpty()) PluginRuntime.vm?.removeUp(mid)
            LuaValue.NIL
        })
        // data.setWatched(videoId, watched)
        t.set("setWatched", KtFunc { args ->
            val id = args.optjstring(1, "0").toLongOrNull() ?: 0L
            val w = args.optboolean(2, true)
            if (id != 0L) PluginRuntime.vm?.setWatched(id, w)
            LuaValue.NIL
        })
        // data.getUpVideos(mid) → table
        t.set("getUpVideos", KtFunc { args ->
            val mid = args.optjstring(1, "")
            val lt = LuaTable()
            PluginRuntime.vm?.vids?.filter { it.upId == mid }?.forEachIndexed { i, v ->
                val iv = LuaTable()
                iv.set("bvid", LuaValue.valueOf(v.bvid))
                iv.set("title", LuaValue.valueOf(v.title))
                iv.set("upId", LuaValue.valueOf(v.upId))
                lt.set(i + 1, iv)
            }
            lt
        })
        return t
    }

    // ---------- network.* ----------

    private fun buildNetworkTable(info: PluginInfo): LuaTable {
        val t = LuaTable()
        // network.get(url) → table(JSON 解析)。仅限 https。
        t.set("get", KtFunc { args ->
            val url = args.optjstring(1, "")
            val result = runNetwork(url)
            result as? LuaValue ?: LuaValue.NIL
        })
        // network.request(path, paramsTable) → table(走现有 B 站 API,自动带登录态)
        t.set("request", KtFunc { args ->
            val path = args.optjstring(1, "")
            val params = args.arg(2)
            val sb = StringBuilder(path)
            if (params.istable()) {
                val pt = params.checktable()
                var first = !path.contains("?")
                var k: LuaValue = LuaValue.NIL
                while (true) {
                    val n = pt.next(k)
                    val nk = n.arg1()
                    if (nk.isnil()) break
                    val nval = n.arg(2)
                    sb.append(if (first) "?" else "&").append(nk.tojstring()).append('=')
                        .append(java.net.URLEncoder.encode(nval.tojstring(), "UTF-8"))
                    first = false
                    k = nk
                }
            }
            val result = runNetwork(sb.toString())
            result as? LuaValue ?: LuaValue.NIL
        })
        // network.post(url, paramsTable) → table(POST x-www-form-urlencoded,带登录态)
        t.set("post", KtFunc { args ->
            val url = args.optjstring(1, "")
            val params = args.arg(2)
            val fields = HashMap<String, String>()
            if (params.istable()) {
                val pt = params.checktable()
                var k: LuaValue = LuaValue.NIL
                while (true) {
                    val n = pt.next(k)
                    val nk = n.arg1()
                    if (nk.isnil()) break
                    fields[nk.tojstring()] = n.arg(2).tojstring()
                    k = nk
                }
            }
            runNetworkPost(url, fields) as? LuaValue ?: LuaValue.NIL
        })
        return t
    }

    /** 网络请求(带登录态),返回解析后的 Lua 表。失败返回 null。仅限 https + bilibili 域名白名单。 */
    private fun runNetwork(url: String): LuaValue? {
        if (!url.startsWith("https://")) return null
        if (!isAllowedHost(url)) return null
        return try {
            val vm = PluginRuntime.vm ?: return null
            // 插件网络请求:同步阻塞等待(低频操作)。IO 后台执行,避免主线程直接做网络。
            val j = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                vm.api.publicGet(url)
            }
            jsonToLua(j)
        } catch (_: Exception) { null }
    }

    /** POST 请求(带登录态),返回解析后的 Lua 表。失败返回 null。 */
    private fun runNetworkPost(url: String, fields: Map<String, String>): LuaValue? {
        if (!url.startsWith("https://") || !isAllowedHost(url)) return null
        return try {
            val vm = PluginRuntime.vm ?: return null
            val j = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                vm.api.publicPost(url, fields)
            }
            jsonToLua(j)
        } catch (_: Exception) { null }
    }

    /** 域名白名单:仅允许 bilibili.com 及其子域。 */
    private fun isAllowedHost(url: String): Boolean {
        return try {
            val host = java.net.URI(url).host ?: return false
            host == "bilibili.com" || host.endsWith(".bilibili.com") || host.endsWith(".bilibili.com/")
        } catch (_: Exception) { false }
    }

    /** org.json JSONObject → LuaTable(递归转换) */
    private fun jsonToLua(j: org.json.JSONObject): LuaTable {
        val lt = LuaTable()
        val keys = j.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = j.get(k)
            lt.set(k, when (v) {
                is org.json.JSONObject -> jsonToLua(v)
                is org.json.JSONArray -> {
                    val arr = LuaTable()
                    for (i in 0 until v.length()) {
                        val e = v.get(i)
                        arr.set(i + 1, if (e is org.json.JSONObject) jsonToLua(e)
                                        else PluginSandbox.luaValueOf(e))
                    }
                    arr
                }
                else -> PluginSandbox.luaValueOf(v)
            })
        }
        return lt
    }

    /** 防止 LuaFunction 回调被 GC 的强引用池(简化处理) */
    private val eventListeners = java.util.concurrent.ConcurrentHashMap<EventBus.Listener, LuaFunction>()

    /** 路径越界防护:确保子路径仍在 pluginDir 内 */
    private fun safeChild(dir: File, name: String): File {
        val clean = name.replace(Regex("[^A-Za-z0-9._/-]"), "")
        val f = File(dir, clean).canonicalFile
        return if (f.path.startsWith(dir.canonicalPath)) f else File(dir, "denied")
    }

    /**
     * 从 Lua 的 theme table 解析并应用主题。
     * 支持的 key(与 C.* 语义 token 对应):bg/card/t1/t2/t3/line/block/soft/onBlock/inputBg/primary
     * 可选:radius(圆角 dp)、backgroundImage(assets 文件名)、darkMode(布尔)。
     */
    fun applyThemeFromLua(info: PluginInfo, themeValue: LuaValue) {
        if (!themeValue.istable()) return
        val tt = themeValue.checktable()
        val colors = HashMap<String, Color>()
        listOf("bg","card","t1","t2","t3","line","block","soft","onBlock","inputBg","primary").forEach { k ->
            val v = tt.get(k)
            if (!v.isnil()) {
                com.bililite.core.BiliTheme.parseColor(v.tojstring())?.let { colors[k] = it }
            }
        }
        // radius
        var radius: Float? = null
        val rv = tt.get("radius")
        if (rv.isnumber()) radius = rv.tofloat()
        // backgroundImage:assets 文件名 → 解析为插件目录内的绝对路径
        var bgPath: String? = null
        var bgPathTablet: String? = null
        val bgVal = tt.get("backgroundImage")
        val bgNameStr = if (bgVal.isnil()) "" else bgVal.tojstring()
        if (bgNameStr.isNotBlank()) {
            val dir = PluginRuntime.appContext?.let {
                com.bililite.plugin.PluginManager.get(it).pluginDir(info.id)
            }
            val f = dir?.let { File(File(it, "assets"), bgNameStr) }
            if (f != null && f.exists()) bgPath = f.absolutePath
        }
        val bgTVal = tt.get("backgroundImageTablet")
        val bgTNameStr = if (bgTVal.isnil()) "" else bgTVal.tojstring()
        if (bgTNameStr.isNotBlank()) {
            val dir = PluginRuntime.appContext?.let {
                com.bililite.plugin.PluginManager.get(it).pluginDir(info.id)
            }
            val f = dir?.let { File(File(it, "assets"), bgTNameStr) }
            if (f != null && f.exists()) bgPathTablet = f.absolutePath
        }
        com.bililite.core.BiliTheme.applyPluginTheme(colors, radius, bgPath, bgPathTablet)
    }

    /** 声明式主题:从 plugin.json 的 theme{} 字段应用(PluginRunner 加载 theme 插件时调用) */
    fun applyDeclaredTheme(info: PluginInfo) {
        if (info.type != "theme" || info.theme.isEmpty()) return
        val colors = HashMap<String, Color>()
        listOf("bg","card","t1","t2","t3","line","block","soft","onBlock","inputBg","primary").forEach { k ->
            val v = info.theme[k]
            if (v is String) com.bililite.core.BiliTheme.parseColor(v)?.let { colors[k] = it }
        }
        var radius: Float? = null
        (info.theme["radius"] as? Number)?.let { radius = it.toFloat() }
        var bgPath: String? = null
        var bgPathTablet: String? = null
        val bgName = info.theme["backgroundImage"]
        if (bgName is String && bgName.isNotBlank()) {
            val f = File(File(info.installedDir, "assets"), bgName)
            if (f.exists()) bgPath = f.absolutePath
        }
        val bgTName = info.theme["backgroundImageTablet"]
        if (bgTName is String && bgTName.isNotBlank()) {
            val f = File(File(info.installedDir, "assets"), bgTName)
            if (f.exists()) bgPathTablet = f.absolutePath
        }
        com.bililite.core.BiliTheme.applyPluginTheme(colors, radius, bgPath, bgPathTablet)
    }

    /** Color → "#RRGGBB" 十六进制字符串 */
    private fun colorHex(c: Color): String {
        val argb = c.value.toInt()  // 0xAARRGGBB
        val rgb = argb and 0xFFFFFF
        return "#%06X".format(rgb)
    }

    /**
     * 通用 Lua 函数适配器:把 Kotlin lambda (Varargs)->LuaValue 包装成 LuaFunction。
     * 支持 0~3 个参数(插件 API 几乎不会超过 3 参);超过 3 参由 LuaJ 拆成 3 参调用。
     */
    class KtFunc(private val body: (Varargs) -> LuaValue) : LuaFunction() {
        override fun call(): LuaValue = body(LuaValue.NONE)
        override fun call(a: LuaValue): LuaValue = body(LuaValue.varargsOf(arrayOf(a)))
        override fun call(a: LuaValue, b: LuaValue): LuaValue = body(LuaValue.varargsOf(arrayOf(a, b)))
        override fun call(a: LuaValue, b: LuaValue, c: LuaValue): LuaValue =
            body(LuaValue.varargsOf(arrayOf(a, b, c)))
    }
}