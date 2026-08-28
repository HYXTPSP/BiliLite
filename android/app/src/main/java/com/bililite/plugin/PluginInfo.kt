package com.bililite.plugin

/**
 * 插件清单(plugin.json 的解析结果)。
 * 一个 zip 包解压后就是这样一个插件，含 id/name/version/type 等基本描述与权限声明。
 */
data class PluginInfo(
    val id: String,                  // 唯一标识,如 com.bililite.theme.ink
    val name: String,                // 显示名,如 "水墨主题"
    val version: String = "1.0.0",
    val apiVersion: Int = 1,         // 目标 API 版本(本框架当前实现 version 1)
    val type: String = "feature",    // theme | feature | resource
    val author: String = "",
    val description: String = "",
    val permissions: List<String> = emptyList(),  // ui/player/data/network/system.file/...
    val entry: String = "main.lua",  // 功能入口(可选)
    val theme: Map<String, Any?> = emptyMap(),     // theme 声明(可选)
    val disable: List<String> = emptyList(),        // 要禁用的主程序功能 id(如 cloudFav)
    // 运行时状态(非清单字段,由框架填充)
    val installedDir: String = ""    // 解压后的目录绝对路径
) {
    /** 是否启用了某个权限 */
    fun hasPermission(p: String): Boolean = permissions.contains(p)
}