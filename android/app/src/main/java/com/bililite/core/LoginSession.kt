package com.bililite.core

import android.content.Context

/**
 * 登录会话：持久化 B 站三种 cookie(SESSDATA/bili_jct/DedeUserID)，
 * 并按参考客户端(bilibili-pure)的格式拼装 Cookie 串。
 */
object LoginSession {
    private const val PREFS = "bililite_login"

    fun setCookies(ctx: Context, sessdata: String, biliJct: String, dede: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("sessdata", sessdata)
            .putString("bili_jct", biliJct)
            .putString("dede_userid", dede)
            .apply()
    }

    fun sessdata(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sessdata", "") ?: ""
    fun biliJct(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("bili_jct", "") ?: ""
    fun dedeUserId(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dede_userid", "") ?: ""

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

    // ---- 用户资料(头像/用户名/签名),登录后由 nav() 写入 ----
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
