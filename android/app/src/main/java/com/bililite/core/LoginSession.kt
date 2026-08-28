package com.bililite.core

import android.content.Context

/**
 * 登录会话：持久化 B 站三种 cookie(SESSDATA/bili_jct/DedeUserID)，
 * 并按参考客户端(bilibili-pure)的格式拼装 Cookie 串。
 * v0.4.17 安全加固:登录凭证经 Android Keystore(AES-256-GCM)加密后存储,
 * 明文不再落盘。旧版本明文数据在首次读取时自动迁移加密(无需重新登录)。
 */
object LoginSession {
    private const val PREFS = "bililite_login"
    private const val ENC_PREFIX = "enc:"

    // ---- 加密读写封装(带旧明文自动迁移) ----
    private fun putEnc(ctx: Context, prefsKey: String, value: String) {
        val stored = if (value.isEmpty()) "" else ENC_PREFIX + CryptoUtil.encrypt(value)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(prefsKey, stored).apply()
    }

    private fun getDec(ctx: Context, prefsKey: String): String {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(prefsKey, "") ?: ""
        if (raw.isEmpty()) return ""
        if (raw.startsWith(ENC_PREFIX)) return CryptoUtil.decrypt(raw.removePrefix(ENC_PREFIX))
        // v0.4.17 之前存的明文:读出后立即重存为加密格式(一次性迁移)
        putEnc(ctx, prefsKey, raw)
        com.bililite.core.BiliLog.i("Login", "登录凭证已从明文迁移为加密存储")
        return raw
    }

    fun setCookies(ctx: Context, sessdata: String, biliJct: String, dede: String) {
        putEnc(ctx, "sessdata", sessdata)
        putEnc(ctx, "bili_jct", biliJct)
        putEnc(ctx, "dede_userid", dede)
    }

    fun sessdata(ctx: Context) = getDec(ctx, "sessdata")
    fun biliJct(ctx: Context) = getDec(ctx, "bili_jct")
    fun dedeUserId(ctx: Context) = getDec(ctx, "dede_userid")

    /** 组装 Cookie 串,形如 SESSDATA=..; bili_jct=..; DedeUserID=.. */
    fun cookieString(ctx: Context): String {
        val s = sessdata(ctx); val j = biliJct(ctx); val d = dedeUserId(ctx)
        if (s.isEmpty() && j.isEmpty() && d.isEmpty()) return ""
        return buildString {
            if (s.isNotEmpty()) append("SESSDATA=").append(s)
            if (j.isNotEmpty()) { if (isNotEmpty()) append("; "); append("bili_jct=").append(j) }
            if (d.isNotEmpty()) { if (isNotEmpty()) append("; "); append("DedeUserID=").append(d) }
        }
    }

    fun isLoggedIn(ctx: Context): Boolean = sessdata(ctx).isNotEmpty()

    // ---- 用户资料(头像/用户名/签名,公开信息不加密),登录后由 nav() 写入 ----
    fun setProfile(ctx: Context, uname: String, face: String, sign: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("uname", uname)
            .putString("face", face)
            .putString("sign", sign)
            .apply()
    }
    fun uname(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("uname", "") ?: ""
    fun face(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("face", "") ?: ""
    fun sign(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sign", "") ?: ""

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
