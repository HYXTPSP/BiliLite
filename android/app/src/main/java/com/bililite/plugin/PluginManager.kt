package com.bililite.plugin

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 统一插件系统 —— 阶段1：PluginLoader
 *
 * 职责：
 *  - 启动时扫描 filesDir/plugins/<pluginId>/ 目录，解析 plugin.json 建立插件注册表
 *  - 安装(zip 解压到 plugins/ 下) / 卸载(删目录) / 启用状态持久化
 *  - 维护 PluginContext(插件自身目录 + 私有 prefs)
 *
 * 向后兼容与安全铁律：
 *  - 所有插件目录操作限定在 filesDir/plugins/ 内，绝不越界
 *  - 解析失败/目录损坏的插件被跳过，不影响主程序与其他插件
 *  - 启用状态存 SharedPreferences("bililite_plugins")
 */
class PluginManager private constructor(private val ctx: Context) {

    companion object {
        private const val PREFS = "bililite_plugins"
        private const val KEY_ENABLED = "enabled_"

        @Volatile
        private var I: PluginManager? = null

        /** 全局单例(在 App.onCreate 初始化) */
        fun get(ctx: Context): PluginManager = I ?: synchronized(this) {
            I ?: PluginManager(ctx.applicationContext).also { I = it }
        }

        /** 插件根目录 filesDir/plugins/ */
        fun rootDir(ctx: Context): File = File(ctx.filesDir, "plugins")
    }

    /** 插件注册表(所有已安装的插件,含启用/禁用) */
    @Volatile
    var plugins: List<PluginInfo> = emptyList()
        private set

    /** 已启用的插件 id 集合 */
    @Volatile
    var enabledIds: Set<String> = emptySet()
        private set

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 扫描插件目录,重建注册表。App 启动时调用一次。 */
    fun scan() {
        val root = rootDir(ctx)
        if (!root.exists()) { root.mkdirs(); plugins = emptyList(); return }
        val list = ArrayList<PluginInfo>()
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val json = File(dir, "plugin.json")
            if (!json.exists()) return@forEach
            try {
                val info = parseManifest(json.readText(), dir.absolutePath)
                list.add(info)
            } catch (_: Exception) {
                // 解析失败的插件静默跳过,不影响主程序
            }
        }
        plugins = list.sortedBy { it.name }
        refreshEnabled()
    }

    /** 从 plugin.json 文本解析出 PluginInfo。installedDir 为解压后目录绝对路径。 */
    private fun parseManifest(text: String, installedDir: String): PluginInfo {
        val o = JSONObject(text)
        val perms = ArrayList<String>()
        o.optJSONArray("permissions")?.let { arr ->
            for (i in 0 until arr.length()) perms.add(arr.optString(i, ""))
        }
        val theme = HashMap<String, Any?>()
        o.optJSONObject("theme")?.let { t ->
            t.keys().forEach { k -> theme[k] = t.opt(k) }
        }
        val disable = ArrayList<String>()
        o.optJSONArray("disable")?.let { arr ->
            for (i in 0 until arr.length()) disable.add(arr.optString(i, ""))
        }
        return PluginInfo(
            id = o.optString("id", "").ifBlank { throw Exception("plugin.json 缺 id") },
            name = o.optString("name", "未命名插件"),
            version = o.optString("version", "1.0.0"),
            apiVersion = o.optInt("apiVersion", 1),
            type = o.optString("type", "feature"),
            author = o.optString("author", ""),
            description = o.optString("description", ""),
            permissions = perms,
            entry = o.optString("entry", "main.lua"),
            theme = theme,
            disable = disable,
            installedDir = installedDir
        )
    }

    /** 安装插件(zip 包路径 → 解压到 plugins/<pluginId>/)。返回插件 info,失败抛异常。 */
    fun install(zipPath: String): PluginInfo {
        val zipFile = File(zipPath)
        if (!zipFile.exists()) throw Exception("插件包不存在")
        // 先读 manifest 拿 id(不落盘)
        val manifestText = readZipEntry(zipFile, "plugin.json")
            ?: throw Exception("插件包缺少 plugin.json")
        val id = org.json.JSONObject(manifestText).optString("id", "")
        if (id.isBlank()) throw Exception("plugin.json 缺 id")

        val destDir = File(rootDir(ctx), safePlugId(id))
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()
        // 解压全部条目到 destDir(限制路径,防 zip 穿越)
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val name = e.name
                val target = File(destDir, name).canonicalFile
                if (!target.path.startsWith(destDir.canonicalPath)) continue  // 防路径穿越
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        // 重新解析并加入注册表
        val info = parseManifest(File(destDir, "plugin.json").readText(), destDir.absolutePath)
        val cur = plugins.filter { it.id != info.id }.toMutableList()
        cur.add(info)
        plugins = cur.sortedBy { it.name }
        return info
    }

    /** 安装插件(从 content Uri,如 SAF 文件选择器)。把 Uri 复制到临时文件再走 install。 */
    fun installFromUri(uri: android.net.Uri): PluginInfo {
        val tmp = File(ctx.cacheDir, "plug_install_${System.currentTimeMillis()}.zip")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw Exception("无法读取插件文件")
            return install(tmp.absolutePath)
        } finally {
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    /** 卸载插件:删目录 + 从注册表移除 + 清理启用状态 + 清理其注册的菜单/导出/禁用 */
    fun uninstall(id: String) {
        plugins.firstOrNull { it.id == id }?.let { p ->
            try { File(p.installedDir).deleteRecursively() } catch (_: Exception) {}
        }
        plugins = plugins.filter { it.id != id }
        prefs.edit().remove(KEY_ENABLED + id).apply()
        refreshEnabled()
        // v0.4.20: 清理插件注册的 UI 菜单、跨插件导出、禁用标记
        PluginMenus.clearPlugin(id)
        PluginShared.clearPlugin(id)
        // 重新应用剩余插件的 disable(被卸载插件禁用的功能恢复)
        FeatureGate.clear()
        plugins.forEach { p -> if (p.disable.isNotEmpty()) FeatureGate.disable(p.disable) }
    }

    /** 设置启用/禁用状态(立即持久化) */
    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED + id, enabled).apply()
        refreshEnabled()
    }

    fun isEnabled(id: String): Boolean = id in enabledIds

    /** 读取持久化的启用状态,重建 enabledIds */
    private fun refreshEnabled() {
        val all = prefs.all
        val set = HashSet<String>()
        plugins.forEach { p ->
            val key = KEY_ENABLED + p.id
            // 首次安装默认启用;只有显式写过 false 才算禁用
            val v = if (all.containsKey(key)) prefs.getBoolean(key, true) else true
            if (v) set.add(p.id)
        }
        enabledIds = set
    }

    /** 已启用的插件列表(供运行时执行 main.lua 等) */
    fun enabledPlugins(): List<PluginInfo> = plugins.filter { it.id in enabledIds }

    // ---------- 工具 ----------

    /** 插件 id 目录名清洗:只保留 [A-Za-z0-9._-],防路径穿越 */
    private fun safePlugId(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** 从 zip 里读单个文本条目内容 */
    private fun readZipEntry(zip: File, entryName: String): String? {
        return try {
            ZipFile(zip).use { zf ->
                val e = zf.getEntry(entryName) ?: return null
                zf.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (_: Exception) { null }
    }

    // ---------- PluginContext(每个插件独立的目录 + prefs) ----------

    /** 插件自身目录(限 plugins/<id>/ 内) */
    fun pluginDir(id: String): File {
        val p = plugins.firstOrNull { it.id == id }
        return if (p != null) File(p.installedDir) else File(rootDir(ctx), safePlugId(id))
    }

    /** 插件私有 KV 存储(SharedPreferences,命名空间隔离) */
    fun pluginPrefs(id: String) =
        ctx.getSharedPreferences("bililite_plugin_$id", Context.MODE_PRIVATE)
}