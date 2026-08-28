package com.bililite.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局 Application：配置 Coil 图片加载器。
 * 修复"主页图片部分不加载 / 加载慢"：
 *  - 统一携带 User-Agent + Referer（B 站 CDN 对无 Referer 的请求会 403，导致封面加载失败）
 *  - 启用内存缓存 + 磁盘缓存，滚动回看时不再重复下载
 *  - 显式超时，避免单个图片请求长时间挂起拖慢列表
 */
class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // 插件系统:启动时扫描已安装插件,建立注册表
        try {
            val pm = com.bililite.plugin.PluginManager.get(this)
            pm.scan()
            // 插件运行时上下文(供 ui.toast 等)
            com.bililite.plugin.PluginRuntime.init(this)
            // 执行已启用插件的入口脚本(注入 ui/system/events + player/data/network API)
            val runner = com.bililite.plugin.PluginRunner(this, pm)
            runner.start { info, sandbox ->
                com.bililite.plugin.PluginAPI.installStage3(info, sandbox)
                com.bililite.plugin.PluginAPI.installStage5(info, sandbox)
            }
        } catch (_: Exception) {}
        // 初始化文件日志
        try { com.bililite.core.BiliLog.init(this) } catch (_: Exception) {}
    }

    override fun newImageLoader(): ImageLoader {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "Chrome/122.0 Mobile Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .build()
                chain.proceed(req)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(httpClient)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("bililite_img"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
