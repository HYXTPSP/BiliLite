package com.bililite.plugin

import androidx.compose.runtime.mutableStateOf

/**
 * 统一插件系统 —— 插件自定义菜单/入口注册表。
 *
 * 插件通过 ui.registerMenu(id, label, handler) 在「我的」页注册一个自定义入口。
 * ProfileScreen 读取本表,把注册的菜单项渲染成按钮。点击时回调到插件沙箱执行 handler。
 *
 * handler 是 LuaFunction,点击由插件线程安全地调用(在主线程 post 到沙箱)。
 */
object PluginMenus {
    data class MenuItem(
        val id: String,
        val label: String,
        val pluginId: String,
        val handler: org.luaj.vm2.LuaFunction
    )

    private val _menus = mutableStateOf<List<MenuItem>>(emptyList())
    val menus: List<MenuItem> get() = _menus.value

    fun register(pluginId: String, id: String, label: String, handler: org.luaj.vm2.LuaFunction) {
        _menus.value = _menus.value.filter { it.id != id } + MenuItem(id, label, pluginId, handler)
    }

    fun unregister(id: String) {
        _menus.value = _menus.value.filter { it.id != id }
    }

    /** 某个插件被卸载时,清掉它注册的所有菜单 */
    fun clearPlugin(pluginId: String) {
        _menus.value = _menus.value.filter { it.pluginId != pluginId }
    }
}