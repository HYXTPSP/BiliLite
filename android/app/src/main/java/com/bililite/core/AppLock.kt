package com.bililite.core

import android.content.Context
import java.security.MessageDigest

/**
 * 专注密码(6 位数字): 一次设置不可找回(仅存 hash)。
 * 管理UP主 / 删除UP 等敏感操作需此密码。
 * v0.4.17 安全加固:SHA-256 + 盐 + 12 万轮迭代(替代可碰撞的 String.hashCode)。
 * 旧哈希(HashCode)兼容验证,验证通过后自动升级为新哈希。
 */
object AppLock {
    private const val PREFS = "bililite_lock"
    private const val KEY = "pin_hash"
    private const val SALT = "bililite_focus"
    private const val ROUNDS = 120_000
    private const val NEW_HASH_LEN = 64 // SHA-256 hex 长度,用于区分新旧格式

    fun hasPin(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

    fun setPin(ctx: Context, pin: String): Boolean {
        if (!pin.matches(Regex("\\d{6}"))) return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, hashNew(pin)).apply()
        return true
    }

    fun verify(ctx: Context, pin: String): Boolean {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return false
        return when {
            stored.length == NEW_HASH_LEN -> stored == hashNew(pin)
            stored == hashOld(pin) -> {
                // 旧格式验证通过 → 自动升级为新哈希
                setPin(ctx, pin); true
            }
            else -> false
        }
    }

    /** 新哈希:SHA-256(salt+pin+salt) 迭代 ROUNDS 轮 */
    private fun hashNew(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var d = (SALT + pin + SALT).toByteArray(Charsets.UTF_8)
        repeat(ROUNDS) { d = md.digest(d) }
        return d.joinToString("") { "%02x".format(it) }
    }

    /** 旧哈希(v0.4.4 及之前),仅用于兼容验证 */
    private fun hashOld(pin: String): String =
        (SALT + pin + SALT).hashCode().toLong().let { it xor (it ushr 32) }.toString(16)
}
