package com.bililite.plugin

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 统一插件系统 —— EventBus(核心事件总线)。
 *
 * 所有核心事件广播给订阅者(插件通过 events.on 订阅)。
 * 事件: appForeground / appBackground / screenRotate / pageChanged / playerStateChanged /
 *       play / pause / progress / complete / videoChanged ...
 *
 * 线程安全:用 ConcurrentHashMap + CopyOnWriteArrayList,任意线程可 post/subscribe。
 * 回调异常被隔离(单个订阅者抛错不影响其他订阅者)。
 */
object EventBus {

    /** 监听器签名: (事件名, 载荷 Map) -> Unit */
    interface Listener {
        fun onEvent(event: String, data: Map<String, Any?>)
    }

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Listener>>()

    /** 订阅某事件;返回取消句柄(等同 off) */
    fun on(event: String, l: Listener) {
        listeners.getOrPut(event) { CopyOnWriteArrayList() }.add(l)
    }

    fun off(event: String, l: Listener) {
        listeners[event]?.remove(l)
    }

    /** 广播事件(同步). 单个订阅者异常被吞掉,不影响其余订阅者与主进程。 */
    fun post(event: String, data: Map<String, Any?> = emptyMap()) {
        listeners[event]?.forEach { l ->
            try { l.onEvent(event, data) } catch (_: Exception) {}
        }
        // 通配订阅 "*" 也能收到所有事件
        listeners["*"]?.forEach { l ->
            try { l.onEvent(event, data) } catch (_: Exception) {}
        }
    }
}