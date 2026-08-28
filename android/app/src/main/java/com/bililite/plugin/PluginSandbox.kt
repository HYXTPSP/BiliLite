package com.bililite.plugin

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.ByteArrayInputStream

/**
 * 统一插件系统 —— 阶段2：Lua 沙箱执行环境。
 *
 * 设计目标：
 *  - 每个插件一个独立 Globals(标准 Lua 5.2 环境,不含 luajava 反射桥)
 *  - 只加载「安全」标准库:base/package/table/string/math/bit32/coroutine。
 *    不加载 io / os / debug / luajava——插件要做文件/系统操作,必须走带权限校验的
 *    PluginAPI 桥(system.file 等),而不是直接碰 Lua 的系统库。
 *  - 崩溃/语法错误抛出 PluginScriptException,由调用方 try-catch 隔离,绝不波及主进程。
 */
class PluginSandbox {

    /** 每个插件独立的 Lua 全局环境 */
    val globals: Globals = Globals()

    init {
        globals.load(JseBaseLib())
        globals.load(PackageLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(JseMathLib())
        globals.load(CoroutineLib())
        LoadState.install(globals)
        LuaC.install(globals)
    }

    /** 注入单个全局变量(供 API 桥把 Kotlin 函数/表桥接进来) */
    fun setGlobal(name: String, value: LuaValue) {
        globals.set(name, value)
    }

    /** 注入一个模块表(如 ui / player 整体作为一个 Lua table) */
    fun setModule(name: String, table: LuaValue) {
        globals.set(name, table)
    }

    /**
     * 执行一段 Lua 源码。
     * @param source 源码文本
     * @param chunkName 脚本名(报错时定位,如 main.lua)
     * @return 脚本返回值(通常 nil)
     */
    fun run(source: String, chunkName: String): LuaValue {
        val chunk = try {
            globals.load(ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)), chunkName, "t", globals)
        } catch (e: Exception) {
            throw PluginScriptException("脚本编译失败: ${e.message}")
        }
        return try {
            chunk.call()
        } catch (e: Exception) {
            throw PluginScriptException("脚本运行失败: ${e.message}")
        }
    }

    companion object {
        /** Lua 值 → 简单的 Java/Kotlin 类型(供 API 返回用) */
        fun luaToAny(v: LuaValue): Any? = when (v.type()) {
            LuaValue.TNIL -> null
            LuaValue.TBOOLEAN -> v.toboolean()
            LuaValue.TNUMBER -> v.todouble()
            LuaValue.TSTRING -> v.tojstring()
            else -> v.tostring()
        }

        /** Kotlin/Java 值 → LuaValue(供 API 把数据回传给 Lua 用) */
        fun luaValueOf(v: Any?): LuaValue = when (v) {
            null -> LuaValue.NIL
            is Boolean -> LuaValue.valueOf(v)
            is Int -> LuaValue.valueOf(v)
            is Long -> LuaValue.valueOf(v.toDouble())
            is Double -> LuaValue.valueOf(v)
            is Float -> LuaValue.valueOf(v.toDouble())
            is String -> LuaValue.valueOf(v)
            else -> LuaValue.valueOf(v.toString())
        }
    }
}

/** 插件脚本异常(用于与主进程隔离,不 panic) */
class PluginScriptException(message: String) : Exception(message)