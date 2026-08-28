package com.bililite.plugin

import androidx.compose.runtime.mutableStateOf

/**
 * 统一插件系统 —— 功能开关(FeatureGate)。
 *
 * 某些插件用于「禁用」主程序的某个功能(如"禁用 B站收藏"帮助专注学习)。
 * 插件在 plugin.json 里声明 "disable": ["cloudFav", ...],
 * 加载后对应功能 id 进入禁用集合,主界面读取后把相关入口置灰、不可点击。
 */
object FeatureGate {
    private val _disabled = mutableStateOf<Set<String>>(emptySet())
    val disabled: Set<String> get() = _disabled.value

    /** 插件加载时调用:加入禁用集合 */
    fun disable(ids: List<String>) {
        if (ids.isEmpty()) return
        _disabled.value = _disabled.value + ids
    }

    /** 插件卸载/重载时调用:清除(由 PluginRunner 全量重建) */
    fun clear() {
        _disabled.value = emptySet()
    }

    /** 某功能是否被禁用 */
    fun isDisabled(id: String): Boolean = id in _disabled.value
}