package com.bililite.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * 统一插件系统 —— 跨插件共享存储与通信。
 * 插件间可通过 storage.set/get 读写共享 KV,通过 plugin.call 调用其它插件的导出函数。
 */
object PluginShared {
    private val kv = ConcurrentHashMap<String, String>()

    fun get(key: String): String = kv[key] ?: ""
    fun set(key: String, value: String) { kv[key] = value }

    /** 插件注册的可被其它插件调用的函数池:pluginId -> (name -> LuaFunction) */
    private val exports = ConcurrentHashMap<String, MutableMap<String, org.luaj.vm2.LuaFunction>>()

    fun registerExport(pluginId: String, name: String, fn: org.luaj.vm2.LuaFunction) {
        exports.getOrPut(pluginId) { ConcurrentHashMap() }[name] = fn
    }

    fun callExport(pluginId: String, name: String): org.luaj.vm2.LuaFunction? =
        exports[pluginId]?.get(name)

    fun clearPlugin(pluginId: String) {
        exports.remove(pluginId)
    }
}