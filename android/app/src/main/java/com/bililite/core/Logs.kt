// ============================================================================
// BiliLite — 轻量文件日志(v0.4.17 自动清理)
// 日志保留标准(自动执行,防止越堆越多):
//   ① 单文件上限 1MB,超出轮转到 log.old.txt(只保留最近一份轮转)
//   ② 最多保留 2 个文件(log.txt + log.old.txt),总占用 ≤ 2MB
//   ③ 启动时清理目录下所有超过 7 天的日志文件
// 设置页可预览/导出,方便反馈问题时定位。
// ============================================================================
package com.bililite.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object BiliLog {
    private var logFile: File? = null
    private var oldFile: File? = null
    private var logDir: File? = null
    private val lock = ReentrantLock()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    // v0.4.17 日志保留标准
    private const val MAX_FILE_BYTES = 1_000_000L   // 单文件 ≤ 1MB
    private const val MAX_AGE_DAYS = 7L             // 超过 7 天自动删除
    // 记录最近一次截断/轮转原因,方便预览页展示
    @Volatile var lastRotateNote: String = ""
        private set

    /** 应用启动时调用一次 */
    fun init(ctx: Context) {
        try {
            val dir = File(ctx.filesDir, "bililite_logs")
            dir.mkdirs()
            logDir = dir
            logFile = File(dir, "log.txt")
            oldFile = File(dir, "log.old.txt")
            cleanExpired(dir)
            i("BiliLog", "log started v0.4.17")
        } catch (_: Exception) {}
    }

    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        write("E", tag, msg + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    private fun write(level: String, tag: String, msg: String) {
        val f = logFile ?: return
        val line = "[${fmt.format(Date())}] $level/$tag: $msg"
        lock.withLock {
            try {
                f.appendText(line + "\n")
                rotateIfLarge(f)
            } catch (_: Exception) {}
        }
    }

    /** 超过 1MB:当前日志轮转为 .old(覆盖旧轮转),新日志重开。只保留最近一份轮转,总占用 ≤ 2MB。 */
    private fun rotateIfLarge(f: File) {
        if (f.length() < MAX_FILE_BYTES) return
        try {
            val o = oldFile ?: return
            o.delete()
            f.renameTo(o)
            f.writeText("")
            lastRotateNote = "[${fmt.format(Date())}] 日志已轮转,旧日志保存在 log.old.txt"
        } catch (_: Exception) {}
    }

    /** v0.4.17: 启动时删除超过 7 天的日志文件(崩溃日志/旧轮转等),防止长期堆积 */
    private fun cleanExpired(dir: File) {
        try {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_AGE_DAYS)
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {}
    }

    /** 导出用:合并当前日志 + 轮转日志(新在前) */
    fun file(): File? = logFile

    fun exportText(): String {
        val sb = StringBuilder()
        try {
            oldFile?.takeIf { it.exists() }?.let { o ->
                sb.append("========== 旧日志(log.old.txt) ==========\n")
                sb.append(o.readText())
                sb.append("\n")
            }
            logFile?.takeIf { it.exists() }?.let { f ->
                sb.append("========== 当前日志(log.txt) ==========\n")
                sb.append(f.readText())
            }
        } catch (_: Exception) {}
        return sb.toString().ifBlank { "暂无日志" }
    }

    /** 最近 N 行(设置页预览) */
    fun tail(n: Int = 300): List<String> {
        val f = logFile ?: return emptyList()
        return try { f.readLines().takeLast(n) } catch (_: Exception) { emptyList() }
    }

    fun clear() {
        lock.withLock {
            try { logFile?.delete(); oldFile?.delete() } catch (_: Exception) {}
        }
    }
}
