package com.bililite.plugin

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 统一插件系统 —— PlayerBridge(全局播放器句柄)。
 *
 * 现有播放器 ExoPlayer 在 PlayerScreen 内部 remember 创建,插件无法直接拿到。
 * 这里用一个全局单例「持有当前 ExoPlayer 引用」,供 player.* API 控制播放。
 *
 * PlayerScreen 在播放器创建/释放时绑定/解绑;插件通过 PluginAPI 的 player.* 间接访问。
 * 同时把关键播放事件转发到 EventBus(供 events.on 订阅)。
 */
object PlayerBridge {
    @Volatile
    private var player: ExoPlayer? = null

    /** 当前视频信息(bvid/title/duration),由 Main.kt 在切换视频时更新 */
    @Volatile
    var currentVideo: Map<String, Any?> = emptyMap()

    /** 绑定当前播放器(PlayerScreen 创建后调用) */
    fun attach(p: ExoPlayer, video: Map<String, Any?>) {
        player = p
        currentVideo = video
        EventBus.post("videoChanged", video)
    }

    /** 解绑(播放器释放/退出播放页时调用) */
    fun detach() {
        player = null
        currentVideo = emptyMap()
    }

    fun getPlayer(): ExoPlayer? = player

    // ---- 转发播放状态到 EventBus ----
    /** 由 PlayerScreen 的监听器在状态变化时调用,转发事件 */
    fun notifyState(state: Int, isPlaying: Boolean) {
        when (state) {
            Player.STATE_READY -> {}
            else -> {}
        }
        EventBus.post("playerStateChanged", mapOf(
            "state" to state, "isPlaying" to isPlaying))
    }

    fun notifyPlay() = EventBus.post("play", emptyMap())
    fun notifyPause() = EventBus.post("pause", emptyMap())
    fun notifyProgress(positionMs: Long, durationMs: Long) =
        EventBus.post("progress", mapOf("position" to positionMs / 1000.0, "duration" to durationMs / 1000.0))
    fun notifyComplete() = EventBus.post("complete", emptyMap())
}