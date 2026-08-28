package com.bililite.plugin

import android.content.Context
import com.bililite.core.BiliLog
import java.io.File

/**
 * 统一插件系统 —— 阶段2：插件运行器。
 *
 * 职责：
 *  - 遍历所有「已启用」的插件,为每个插件构造独立沙箱(PluginSandbox)
 *  - 读取其 entry(main.lua)并执行;任何异常仅记录日志、不影响主进程与其他插件
 *  - 维护 id → sandbox 的映射,供 PluginAPI 桥后续注入
 */
class PluginRunner(private val ctx: Context, private val manager: PluginManager) {

    /** 当前已启用且已加载沙箱的插件环境 */
    private val sandboxes = LinkedHashMap<String, PluginSandbox>()

    /** 启动：加载并执行所有已启用插件的主脚本(入口)。 */
    fun start(apiInjector: ((PluginInfo, PluginSandbox) -> Unit)? = null) {
        sandboxes.clear()
        // 先清空功能开关,再收集所有已启用插件的 disable 声明(避免旧开关残留)
        FeatureGate.clear()
        for (p in manager.enabledPlugins()) {
            try {
                // 应用"禁用功能"声明(如禁用 B站收藏)
                if (p.disable.isNotEmpty()) FeatureGate.disable(p.disable)
                // 声明式主题插件:直接从 plugin.json 的 theme{} 应用(无需代码)
                if (p.type == "theme" && p.theme.isNotEmpty()) {
                    PluginAPI.applyDeclaredTheme(p)
                    BiliLog.i("Plugin", "主题插件 ${p.id} 应用成功")
                    continue
                }
                val entryFile = File(p.installedDir, p.entry)
                if (!entryFile.exists() || p.entry.isBlank()) {
                    BiliLog.i("Plugin", "插件 ${p.id} 无入口脚本,跳过")
                    continue
                }
                val sandbox = PluginSandbox()
                // 注入 API(阶段3 的 PluginAPI 桥在这里挂载 ui.*/player.*/... )
                apiInjector?.invoke(p, sandbox)
                sandboxes[p.id] = sandbox
                val src = entryFile.readText()
                sandbox.run(src, p.entry)
                BiliLog.i("Plugin", "插件 ${p.id} 加载成功")
            } catch (e: Exception) {
                // 崩溃隔离:单个插件失败不影响其他插件与主程序
                sandboxes.remove(p.id)
                BiliLog.e("Plugin", "插件 ${p.id} 加载失败: ${e.message}")
            }
        }
    }

    /** 卸载时释放某个插件的沙箱 */
    fun stopPlugin(id: String) {
        sandboxes.remove(id)
    }

    /** 获取已加载的插件沙箱(供 API 桥/主题机制按 id 访问) */
    fun sandboxOf(id: String): PluginSandbox? = sandboxes[id]

    fun loadedIds(): Set<String> = sandboxes.keys.toSet()
}