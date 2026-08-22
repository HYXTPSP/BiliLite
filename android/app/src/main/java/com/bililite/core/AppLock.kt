package com.bililite.core

import android.content.Context

/**
 * 专注密码(6 位数字): 一次设置不可找回(仅存 hash)。
 * 管理UP主 / 删除UP 等敏感操作需此密码。
 */
object AppLock {
    private const val PREFS = "bililite_lock"
    private const val KEY = "pin_hash"
    private const val SALT = "bililite_focus"

    fun hasPin(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

    fun setPin(ctx: Context, pin: String): Boolean {
        if (!pin.matches(Regex("\\d{6}"))) return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, hash(pin)).apply()
        return true
    }

    fun verify(ctx: Context, pin: String): Boolean {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return false
        return stored == hash(pin)
    }

    private fun hash(pin: String): String = (SALT + pin + SALT).hashCode().toLong().let { it xor (it ushr 32) }.toString(16)
}
