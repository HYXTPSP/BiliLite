// ============================================================================
// BiliLite — 主题(v0.4.14 视觉优化)
// Material Design 3：中性色为主，主色(蓝)仅用于关键操作与选中态。
// 语义化颜色 C.* 供界面取色，深色模式切换即时生效并持久化。
// 播放器视频区保持纯黑，不受主题影响。
// 尺寸/字号/圆角统一走 res 的 dimens 资源(见 values/values-night/values-small/values-large)，
// Compose 中用 dimensionResource(R.dimen.xxx) 引用，禁止硬编码 dp/sp。
// ============================================================================
package com.bililite.core

import android.content.Context
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// ColorScheme（TextField/下拉菜单/对话框默认颜色自动跟随）
// ---------------------------------------------------------------------------
val BiliLightScheme = lightColorScheme(
    primary = Color(0xFF1C1C1E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E5EA),
    onPrimaryContainer = Color(0xFF1C1C1E),
    surface = Color.White,
    background = Color(0xFFFAFAFA),
    onSurface = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    surfaceVariant = Color(0xFFF2F2F7),
    outline = Color(0xFFE5E5EA),
    onBackground = Color(0xFF1C1C1E),
    error = Color(0xFFFF3B30)
)

val BiliDarkScheme = darkColorScheme(
    primary = Color(0xFFE8E8EA),
    onPrimary = Color(0xFF1C1C1E),
    primaryContainer = Color(0xFF3A3A40),
    onPrimaryContainer = Color(0xFFE8E8EA),
    surface = Color(0xFF232327),
    background = Color(0xFF141416),
    onSurface = Color(0xFFE8E8EA),
    onSurfaceVariant = Color(0xFFBDC1C6),
    surfaceVariant = Color(0xFF2E2E33),
    outline = Color(0xFF4A4A52),
    onBackground = Color(0xFFE8E8EA),
    error = Color(0xFFFF6B60)
)

// ---------------------------------------------------------------------------
// 主题状态
// ---------------------------------------------------------------------------
object BiliTheme {
    @Volatile
    private var appCtx: Context? = null
    var dark by mutableStateOf(false)
        private set

    // ---- 插件主题覆盖(阶段4) ----
    // 插件通过 ui.setTheme 设置;colorOverrides 里的 key 对应 C.* 语义 token(bg/card/t1/t2/t3/line/block/soft/onBlock/...)
    var colorOverrides by mutableStateOf<Map<String, Color>>(emptyMap())
        private set
    var radiusOverride by mutableStateOf<Float?>(null)   // 全局圆角(dp),null=用默认
        private set
    var backgroundImagePath by mutableStateOf<String?>(null)  // 背景图(手机,插件 assets 解压后的绝对路径)
        private set
    var backgroundImagePathTablet by mutableStateOf<String?>(null)  // 背景图(平板)
        private set

    fun init(ctx: Context) {
        if (appCtx == null) {
            appCtx = ctx.applicationContext
            dark = ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).getBoolean("dark", false)
        }
    }

    fun toggle(ctx: Context) {
        dark = !dark
        ctx.getSharedPreferences("bililite_pref", Context.MODE_PRIVATE).edit().putBoolean("dark", dark).apply()
        com.bililite.core.BiliLog.i("Theme", "深色模式 = $dark")
    }

    /** 导入备份时恢复深色模式 */
    fun applyDark(v: Boolean) { dark = v }

    /** 插件主题覆盖:colors 为 key=语义token → 色值(如 "bg"→"#F5F0E8") */
    fun applyPluginTheme(colors: Map<String, Color>, radius: Float?, backgroundImagePath: String?, backgroundImagePathTablet: String? = null) {
        colorOverrides = colors
        radiusOverride = radius
        this.backgroundImagePath = backgroundImagePath
        this.backgroundImagePathTablet = backgroundImagePathTablet
    }

    /** 卸载/禁用插件主题 → 恢复默认 */
    fun clearPluginTheme() {
        colorOverrides = emptyMap()
        radiusOverride = null
        backgroundImagePath = null
        backgroundImagePathTablet = null
    }

    /** 解析 "#RRGGBB" 或 "#AARRGGBB" 字符串为 Color */
    fun parseColor(s: String): Color? = try {
        val hex = s.removePrefix("#")
        when (hex.length) {
            6 -> Color(0xFF000000L or hex.toLong(16))
            8 -> Color(hex.toLong(16))
            else -> null
        }
    } catch (_: Exception) { null }
}

// ---------------------------------------------------------------------------
// 语义化颜色（C.* 供 Compose 界面取色；成员名保持不变，仅优化取值）
// 对应 colors.xml / values-night/colors.xml 的 token，两处保持一致。
// ---------------------------------------------------------------------------
object C {
    // 插件主题覆盖:先查 override,没有则回退到默认(暗/亮)
    private fun col(key: String, dark: Color, light: Color): Color =
        BiliTheme.colorOverrides[key] ?: (if (BiliTheme.dark) dark else light)

    val bg: Color get() = col("bg", Color(0xFF141416), Color(0xFFFAFAFA))        // 页面背景
    val card: Color get() = col("card", Color(0xFF232327), Color.White)          // 卡片/白底
    val t1: Color get() = col("t1", Color(0xFFE8E8EA), Color(0xFF1A1A1E))        // 主文字
    val t2: Color get() = col("t2", Color(0xFFBDC1C6), Color(0xFF5F6368))        // 次文字(≥4.5:1)
    val t3: Color get() = col("t3", Color(0xFFC5C5CA), Color(0xFF3A3A3C))        // 正文
    val line: Color get() = col("line", Color(0xFF3A3A40), Color(0xFFE0E0E4))    // 分割线/占位
    val block: Color get() = col("block", Color(0xFFE8E8EA), Color(0xFF1C1C1E))  // 关键操作按钮/选中态(近黑,去蓝)
    val soft: Color get() = col("soft", Color(0xFF2E2E33), Color(0xFFF2F2F7))    // 浅灰块/次级容器
    val onBlock: Color get() = col("onBlock", Color(0xFF1C1C1E), Color.White)    // 块上文字(黑底白字)
    val inputBg: Color get() = col("inputBg", Color(0xFF2A2A2F), Color(0xFFF2F2F7)) // 输入框底
    // 已看徽章:去绿,改灰底+灰色文字(符合"仅金/红两处彩色"的规范)
    val watchedBg: Color get() = col("watchedBg", Color(0xFF3A3A40), Color(0xFFE5E5EA))
    val watchedFg: Color get() = col("watchedFg", Color(0xFFBDC1C6), Color(0xFF8E8E93))
    // 主色（进度条/强调等,改为近黑,非蓝）
    val primary: Color get() = col("primary", Color(0xFFE8E8EA), Color(0xFF1C1C1E))
}
