package com.bililite.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * BilyLite 的 B 站接入层(参考 bilibili-pure-apk 与 MyTVB 的实现):
 *  - cookie 用 SESSDATA + bili_jct + DedeUserID
 *  - 加对了 Referer/Origin(尤其 /x/space/ 用 space.bilibili.com)
 *  - WBI 签名只对 /wbi/ 与 search/type 端点做,避免过度签名被风控
 *  - playurl 解析出可直接播放的 url(durl[0] 或 dash)
 *  - 二维码登录从 data.url 的 query 提取三种 cookie(与官方一致)
 */
class BiliApi(private val ctx: Context) {
    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "Chrome/122.0 Mobile Safari/537.36 bililite/0.1"
    }

    // 由 LoginSession 填好后再注入
    var cookie = ""
    var lastSetCookie: String = ""

    // ---------- HTTP(带 Referer/Origin/Cookie,风控友好) ----------
    private suspend fun get(pathUrl: String, space: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(pathUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = 8000; conn.readTimeout = 10000
            if (cookie.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookie)
                if (space) conn.setRequestProperty("Referer", "https://space.bilibili.com/")
                else conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { "" }
                throw Exception("HTTP $code ${errBody?.take(200) ?: ""}")
            }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        }

    private suspend fun getResult(pathUrl: String, space: Boolean): Pair<JSONObject, String> =
        withContext(Dispatchers.IO) {
            val conn = URL(pathUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = 8000; conn.readTimeout = 10000
            if (cookie.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("Referer", if (space) "https://space.bilibili.com/" else "https://www.bilibili.com/")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            var sc = ""
            try { conn.headerFields?.get("Set-Cookie")?.firstOrNull()?.let { sc = it.substringBefore("; Path=") } } catch (_: Exception) {}
            JSONObject(body) to sc
        }

    // ---------- 登录：二维码 ----------
    suspend fun qrcodeGenerate(): JSONObject =
        get("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")

    /** 轮询+取 Set-Cookie;成功后通常在 data.url 的 query 里带三种 cookie(优先用那个)。 */
    suspend fun qrcodePollWithCookie(qrcodeKey: String): JSONObject {
        val (j, sc) = getResult("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" +
            URLEncoder.encode(qrcodeKey, "UTF-8"), false)
        lastSetCookie = sc
        return j
    }

    fun assignCookie(c: String) { cookie = c }
    fun clearCookie() { cookie = "" }

    /** POST x-www-form-urlencoded,返回 (body, cookies map)。用于登录类接口。 */
    private suspend fun postForm(pathUrl: String, fields: Map<String, String>): Pair<JSONObject, Map<String, String>> =
        withContext(Dispatchers.IO) {
            val conn = URL(pathUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.setRequestProperty("Origin", "https://www.bilibili.com")
            if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            val body = fields.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val cookies = mutableMapOf<String, String>()
            conn.headerFields?.get("Set-Cookie")?.forEach { sc ->
                val part = sc.substringBefore(";"); val eq = part.indexOf("=")
                if (eq > 0) cookies[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
            JSONObject(resp) to cookies
        }

    // ---------- 验证码登录 / 密码登录(参考 bilibili-pure) ----------
    /** 获取极验 captcha: 返回 (token, gt, challenge) */
    suspend fun getCaptcha(): Triple<String, String, String> {
        val j = get("https://passport.bilibili.com/x/passport-login/captcha?source=main_web")
        if (j.optInt("code", -1) != 0) throw Exception(j.optString("message", "获取验证码失败"))
        val d = j.optJSONObject("data") ?: return Triple("", "", "")
        val token = d.optString("token", "")
        val g = d.optJSONObject("geetest")
        val gt = g?.optString("gt", "") ?: ""
        val challenge = g?.optString("challenge", "") ?: ""
        return Triple(token, gt, challenge)
    }

    /** 获取 RSA 公钥 (hash/key),用于密码加密 */
    suspend fun getWebKey(): Pair<String, String> {
        val j = get("https://passport.bilibili.com/x/passport-login/web/key")
        if (j.optInt("code", -1) != 0) throw Exception(j.optString("message", "获取密钥失败"))
        val d = j.optJSONObject("data")
        return (d?.optString("hash", "") ?: "") to (d?.optString("key", "") ?: "")
    }

    /** 发送短信验证码: 成功返回 captcha_key */
    suspend fun sendSmsCode(tel: String, token: String, challenge: String, validate: String, seccode: String): String {
        val (j, _) = postForm("https://passport.bilibili.com/x/passport-login/web/sms/send",
            mapOf("cid" to "86", "tel" to tel, "source" to "main_web", "token" to token,
                  "challenge" to challenge, "validate" to validate, "seccode" to seccode))
        if (j.optInt("code", -1) != 0) throw Exception(j.optString("message", "发送失败"))
        return j.optJSONObject("data")?.optString("captcha_key", "") ?: ""
    }

    /** 验证码登录: 成功返回 cookies map */
    suspend fun smsLogin(tel: String, code: String, captchaKey: String): Map<String, String> {
        val (j, cookies) = postForm("https://passport.bilibili.com/x/passport-login/web/login/sms",
            mapOf("cid" to "86", "tel" to tel, "code" to code, "source" to "main_web",
                  "captcha_key" to captchaKey, "go_url" to "https://www.bilibili.com", "keep" to "1"))
        checkLoginResult(j, cookies)
        return cookies
    }

    /** 密码登录: password 需已用 RSA(hash+key) 加密 */
    suspend fun passwordLogin(username: String, password: String, token: String, challenge: String,
                              validate: String, seccode: String): Map<String, String> {
        val (j, cookies) = postForm("https://passport.bilibili.com/x/passport-login/web/login",
            mapOf("username" to username, "password" to password, "keep" to "0", "token" to token,
                  "challenge" to challenge, "validate" to validate, "seccode" to seccode,
                  "go_url" to "https://www.bilibili.com", "source" to "main_web"))
        checkLoginResult(j, cookies)
        return cookies
    }

    /** 校验登录结果: code!=0 抛错; 无 SESSDATA/bili_jct 视为环境风控 */
    private fun checkLoginResult(j: JSONObject, cookies: Map<String, String>) {
        val code = j.optInt("code", -1)
        if (code != 0) throw Exception(j.optString("message", "登录失败($code)"))
        val hasSession = cookies.containsKey("SESSDATA") && cookies.containsKey("bili_jct")
        if (!hasSession) {
            val dm = j.optJSONObject("data")?.optString("message", "") ?: ""
            throw Exception(dm.ifBlank { "本次登录环境存在风险，无法获取登录凭证" })
        }
    }

    /** 从登录成功 data.url 的 query 提取 SESSDATA/bili_jct/DedeUserID(参考实现). */
    fun extractCookiesFromUrl(url: String): Triple<String, String, String>? {
        return try {
            val q = URLDecoder.decode(url.substringAfter('?'), "UTF-8")
            fun p(k: String) = q.split("&").firstOrNull { it.startsWith("$k=") }?.substringAfter("=") ?: ""
            val s = p("SESSDATA"); val j = p("bili_jct"); val d = p("DedeUserID")
            if (s.isEmpty() || j.isEmpty() || d.isEmpty()) null else Triple(s, j, d)
        } catch (_: Exception) { null }
    }

    /** 登录态下取当前用户信息(拿 uname 等) */
    suspend fun nav(): JSONObject = get("https://api.bilibili.com/x/web-interface/nav")

    // ---------- WBI 签名(缓存 24h,参考实现) ----------
    private var imgKey = ""; private var subKey = ""; private var wbiFetchedAt = 0L
    private val WBI_TTL = 24L * 3600 * 1000

    suspend private fun fetchWbiKeys(): Boolean = try {
        val j = get("https://api.bilibili.com/x/web-interface/nav")
        val wbi = j.optJSONObject("data")?.optJSONObject("wbi_img") ?: return false
        val (ik, sk) = WbiSign.imgKeys(wbi.optString("img_url", ""), wbi.optString("sub_url", ""))
        imgKey = ik; subKey = sk; wbiFetchedAt = System.currentTimeMillis(); true
    } catch (_: Exception) { false }

    suspend private fun wbiGet(path: String, base: Map<String, String>, space: Boolean): JSONObject {
        if (System.currentTimeMillis() - wbiFetchedAt > WBI_TTL || imgKey.isEmpty()) fetchWbiKeys()
        val q = WbiSign.sign(HashMap<String, String>(base), imgKey, subKey)
        return get(path + "?" + q, space)
    }

    // ---------- 搜索 UP(用户) ----------
    suspend fun searchUser(keyword: String): JSONArray {
        val j = get("https://api.bilibili.com/x/web-interface/wbi/search/type" +
            "?search_type=bili_user&keyword=" + URLEncoder.encode(keyword, "UTF-8"))
        return j.optJSONObject("data")?.optJSONArray("result") ?: JSONArray()
    }

    /** 搜索视频(供"搜索范围仅限已添加UP的视频库"失败时退回) */
    suspend fun searchVideo(keyword: String): JSONArray {
        val j = get("https://api.bilibili.com/x/web-interface/wbi/search/type" +
            "?search_type=video&keyword=" + URLEncoder.encode(keyword, "UTF-8"))
        return j.optJSONObject("data")?.optJSONArray("result") ?: JSONArray()
    }

    // ---------- UP 空间信息 / 视频列表 ----------
    suspend fun spaceAccInfo(mid: Long): JSONObject =
        get("https://api.bilibili.com/x/space/acc/info?mid=$mid", space = true)

    suspend fun userVideos(mid: Long, page: Int = 1): JSONObject = wbiGet(
        "https://api.bilibili.com/x/space/wbi/arc/search",
        mapOf("mid" to mid.toString(), "ps" to "30", "pn" to page.toString(), "order" to "pubdate"),
        space = true)

    /** 非 WBI 的 UP 视频列表(兜底:arc/search 被 412 风控时用,只需 cookie+referer)。 */
    suspend fun userVideosNoWbi(mid: Long, page: Int = 1): JSONObject =
        get("https://api.bilibili.com/x/space/arc/list?mid=$mid&pn=$page&ps=30", space = true)

    // ---------- 分P / 播放地址 ----------
    suspend fun pagelist(bvid: String): JSONArray {
        val j = get("https://api.bilibili.com/x/player/pagelist?bvid=$bvid")
        return j.optJSONArray("data") ?: JSONArray()
    }

    /** 通过 bvid 取视频详情(标题/UP主/封面/时长/分P)。用于按 bvid 搜索。 */
    suspend fun videoView(bvid: String): JSONObject =
        get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")

    /** 解析出可直接播放的 url。用 platform=html5&fnval=0 拿渐进式 mp4(durl),media3 可直接播。 */
    suspend fun playUrl(bvid: String, cid: Long, qn: Int = 80): String {
        val j = get("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=$qn&fnval=0&fnver=0&fourk=1&platform=html5")
        val d = j.optJSONObject("data") ?: return ""
        val durl = d.optJSONArray("durl")
        if (durl != null && durl.length() > 0) {
            val u = durl.optJSONObject(0).optString("url", "")
            if (u.isNotEmpty()) return u
        }
        // 兜底:dash 的 1080p 视频+音频(通常 .m4s,仅当 durl 不存在时)
        val dash = d.optJSONObject("dash")
        if (dash != null) {
            val vids = dash.optJSONArray("video"); val auds = dash.optJSONArray("audio")
            if (vids != null && vids.length() > 0 && auds != null && auds.length() > 0) {
                val v = vids.optJSONObject(0).optString("baseUrl", "")
                val a = auds.optJSONObject(0).optString("baseUrl", "")
                if (v.isNotEmpty()) return "dash:$v|$a"
            }
        }
        return ""
    }
}
