package com.bililite.plugin

import android.content.Context
import com.bililite.ui.BiliViewModel

/**
 * 统一插件系统 —— 运行时上下文(单例)。
 *
 * 插件 API 桥需要访问主程序的运行时对象:
 *  - appContext: ui.toast / openUrl 等
 *  - vm: data.*(收藏/历史/书签/UP列表)
 *  - 播放器控制:player.*(阶段5,通过 PlayerBridge)
 *
 * 由主界面在合适的生命周期注入/更新,避免插件直接持有各屏幕的内部对象。
 */
object PluginRuntime {
    @Volatile
    var appContext: Context? = null

    @Volatile
    var vm: BiliViewModel? = null

    /** 主线程 UI 操作调度器(ui.toast / ui.dialog 需在主线程) */
    @Volatile
    var uiHandler: android.os.Handler? = null
        get() = field ?: android.os.Handler(android.os.Looper.getMainLooper()).also { field = it }

    /** 初始化(在 App.onCreate 或 MainActivity.onCreate 调用) */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }
}