package com.bililite.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    var dedeUserId: String = ""   // 当前登录用户 mid(收藏夹等接口需要)

    // buvid3:B 站风控标识。缺了它,部分网络/IP 下 api.bilibili.com 会被限流(返回 -352/412),
    // 表现为播放"获取地址失败"。这里懒加载一次并缓存,随每个请求携带。
    private var buvid3 = ""

    /** 懒加载 buvid3(从 finger/spi 接口,无需登录)。失败静默。 */
    suspend fun ensureBuvid() {
        if (buvid3.isNotEmpty()) return
        buvid3 = ctx.getSharedPreferences("bililite_buvid", Context.MODE_PRIVATE).getString("b3", "") ?: ""
        if (buvid3.isNotEmpty()) return
        try {
            val j = get("https://api.bilibili.com/x/frontend/finger/spi")
            val b3 = j.optJSONObject("data")?.optString("b_3", "") ?: ""
            if (b3.isNotEmpty()) {
                buvid3 = b3
                ctx.getSharedPreferences("bililite_buvid", Context.MODE_PRIVATE)
                    .edit().putString("b3", b3).apply()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    /** 组装完整 Cookie:buvid3 + 登录 cookie。 */
    private fun fullCookie(): String {
        val parts = mutableListOf<String>()
        if (buvid3.isNotEmpty()) parts.add("buvid3=$buvid3")
        if (cookie.isNotEmpty()) parts.add(cookie)
        return parts.joinToString("; ")
    }

    // ---------- HTTP(带 Referer/Origin/Cookie,风控友好) ----------
    /** 日志用:取 path 部分(去掉敏感 query 值) */
    private fun shortUrl(u: String): String =
        u.substringAfter("://").substringBefore("?").removePrefix("api.bilibili.com")
            .removePrefix("passport.bilibili.com").removePrefix("www.bilibili.com")

    private suspend fun get(pathUrl: String, space: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val conn = URL(pathUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = 8000; conn.readTimeout = 10000
            // v0.4.1 修复:Referer 始终携带(原版仅在登录后携带,导致未登录时
            // captcha/qrcode 等登录接口被风控拒绝,手机号验证码登录失败)
            conn.setRequestProperty("Referer", if (space) "https://space.bilibili.com/" else "https://www.bilibili.com/")
            conn.setRequestProperty("Origin", "https://www.bilibili.com")
            val fc = fullCookie()
            try {
            if (fc.isNotEmpty()) {
                conn.setRequestProperty("Cookie", fc)
            }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - t0
            if (code !in 200..299) {
                val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                com.bililite.core.BiliLog.e("Http", "GET ${shortUrl(pathUrl)} → HTTP $code (${ms}ms) ${errBody.take(200)}")
                throw Exception("HTTP $code ${errBody.take(200)}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val biz = j.optInt("code", 0)
            if (biz != 0) {
                com.bililite.core.BiliLog.w("Http", "GET ${shortUrl(pathUrl)} → 业务码 $biz ${j.optString("message", "")} (${ms}ms)")
            } else {
                com.bililite.core.BiliLog.d("Http", "GET ${shortUrl(pathUrl)} → 200 (${ms}ms, ${body.length}B)")
            }
            j
            } finally {
                // v0.4.17: 释放连接(避免 keep-alive 连接堆积导致卡顿)
                try { conn.disconnect() } catch (_: Exception) {}
            }
        }

    private suspend fun getResult(pathUrl: String, space: Boolean): Pair<JSONObject, String> =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val conn = URL(pathUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = 8000; conn.readTimeout = 10000
            conn.setRequestProperty("Referer", if (space) "https://space.bilibili.com/" else "https://www.bilibili.com/")
            conn.setRequestProperty("Origin", "https://www.bilibili.com")
            if (cookie.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookie)
            }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - t0
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            com.bililite.core.BiliLog.d("Http", "GET ${shortUrl(pathUrl)} → HTTP $code (${ms}ms)")
            var sc = ""
            try { conn.headerFields?.get("Set-Cookie")?.firstOrNull()?.let { sc = it.substringBefore("; Path=") } } catch (_: Exception) {}
            try { conn.disconnect() } catch (_: Exception) {}  // v0.4.17: 释放连接
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
            val code = conn.responseCode
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            com.bililite.core.BiliLog.d("Http", "POST ${shortUrl(pathUrl)} → HTTP $code ${respBody.take(120)}")
            val cookies = mutableMapOf<String, String>()
            conn.headerFields?.get("Set-Cookie")?.forEach { sc ->
                val part = sc.substringBefore(";"); val eq = part.indexOf("=")
                if (eq > 0) cookies[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
            try { conn.disconnect() } catch (_: Exception) {}  // v0.4.17: 释放连接
            JSONObject(respBody) to cookies
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
        return checkLoginResult(j, cookies)
    }

    /** 密码登录: password 需已用 RSA(hash+key) 加密 */
    suspend fun passwordLogin(username: String, password: String, token: String, challenge: String,
                              validate: String, seccode: String): Map<String, String> {
        val (j, cookies) = postForm("https://passport.bilibili.com/x/passport-login/web/login",
            mapOf("username" to username, "password" to password, "keep" to "0", "token" to token,
                  "challenge" to challenge, "validate" to validate, "seccode" to seccode,
                  "go_url" to "https://www.bilibili.com", "source" to "main_web"))
        return checkLoginResult(j, cookies)
    }

    /**
     * 校验登录结果并合并 cookie。v0.4.4 修复:
     * B 站登录成功时凭证在 data.url 的 query 里(而非仅 Set-Cookie 头),
     * 之前只读 Set-Cookie 头 → 验证码正确却报"无法获取登录凭证"。
     * 现在优先 Set-Cookie,缺失时从 data.url 提取(与二维码登录一致)。
     */
    private fun checkLoginResult(j: JSONObject, cookies: Map<String, String>): Map<String, String> {
        val code = j.optInt("code", -1)
        if (code != 0) throw Exception(j.optString("message", "登录失败($code)"))
        val merged = HashMap(cookies)
        val url = j.optJSONObject("data")?.optString("url", "") ?: ""
        if (url.isNotEmpty()) {
            val tri = extractCookiesFromUrl(url)
            if (tri != null) {
                merged["SESSDATA"] = tri.first
                merged["bili_jct"] = tri.second
                merged["DedeUserID"] = tri.third
            }
        }
        if (!merged.containsKey("SESSDATA") || !merged.containsKey("bili_jct")) {
            val dm = j.optJSONObject("data")?.optString("message", "") ?: ""
            throw Exception(dm.ifBlank { "本次登录环境存在风险，无法获取登录凭证" })
        }
        return merged
    }

    /** 从登录成功 data.url 的 query 提取 SESSDATA/bili_jct/DedeUserID(参考实现). */
    fun extractCookiesFromUrl(url: String): Triple<String, String, String>? {
        return try {
            val q = URLDecoder.decode(url.substringAfter('?'), "UTF-8")
            fun p(k: String) = q.split("&").firstOrNull { it.startsWith("$k=") }?.substringAfter("=") ?: ""
            val s = p("SESSDATA"); val j = p("bili_jct"); val d = p("DedeUserID")
            // 仅要求 SESSDATA + bili_jct(登录必需),DedeUserID 可后续由 nav 补齐
            if (s.isEmpty() || j.isEmpty()) null else Triple(s, j, d)
        } catch (_: Exception) { null }
    }

    /** 登录态下取当前用户信息(拿 uname 等) */
    suspend fun nav(): JSONObject = get("https://api.bilibili.com/x/web-interface/nav")

    // ---------- WBI 签名(缓存 24h,参考实现) ----------
    private var imgKey = ""; private var subKey = ""; private var wbiFetchedAt = 0L
    private val WBI_TTL = 24L * 3600 * 1000

    suspend private fun fetchWbiKeys(): Boolean {
        return try {
            val j = get("https://api.bilibili.com/x/web-interface/nav")
            val wbi = j.optJSONObject("data")?.optJSONObject("wbi_img") ?: return false
            val (ik, sk) = WbiSign.imgKeys(wbi.optString("img_url", ""), wbi.optString("sub_url", ""))
            imgKey = ik; subKey = sk; wbiFetchedAt = System.currentTimeMillis(); true
        } catch (_: Exception) { false }
    }

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

    /** DASH 播放流信息(video + audio 分离,播放器合并) */
    data class StreamInfo(
        val videoUrl: String,
        val audioUrl: String,          // 空 = durl 渐进式(音视频合一),非空 = DASH 分离流
        val qualities: List<Int>,      // support_formats 里的 quality 列表
        val acceptQuality: List<Int>,  // accept_quality(可选清晰度)
        val acceptDesc: List<String>   // accept_description(清晰度中文名)
    )

    /**
     * 解析播放流。v0.4.1 关键修复:
     * ① 优先用 fnval=0 拿 durl(渐进式完整 MP4)——media3 ProgressiveMediaSource 完美支持,
     *    流畅不卡、不黑屏。durl 是 B 站低清晰度(≤1080P 部分)与老视频的稳定格式。
     * ② 仅当 durl 为空(部分视频只给 DASH)时才降级 fnval=16 的 DASH 分离流。
     *    (原版/上一版默认 DASH,ProgressiveMediaSource 硬播 fMP4 导致卡顿黑屏)
     */
    /** 最近一次 playurl 接口的 B 站错误码:信息(用于诊断"获取地址失败"的真实原因)。 */
    var lastApiError = ""

    suspend fun playStream(bvid: String, cid: Long, qn: Int = 64): StreamInfo {
        ensureBuvid()
        lastApiError = ""
        // qn=0 表示"自动"清晰度:不指定 qn,由 B 站按账号权限选
        val qnParam = if (qn > 0) "&qn=$qn" else ""

        // 并发请求:durl(主播放,渐进式 mp4)+ dash(完整清晰度列表/DASH 兜底),减少加载延迟
        val (durlJ, dashJ) = coroutineScope {
            val a = async {
                try { get("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid$qnParam&fnval=0&fnver=0&fourk=1&platform=html5") }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            val b = async {
                try { get("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid$qnParam&fnval=16&fnver=0&fourk=1") }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            a.await() to b.await()
        }
        val d = durlJ?.optJSONObject("data")
        val dashD = dashJ?.optJSONObject("data")
        var videoUrl = ""
        var audioUrl = ""
        val durl = d?.optJSONArray("durl")
        if (durl != null && durl.length() > 0) {
            videoUrl = durl.optJSONObject(0).optString("url", "")
        }
        if (videoUrl.isEmpty() && durlJ != null && durlJ.optInt("code", -1) != 0) {
            lastApiError = "${durlJ.optInt("code")}: ${durlJ.optString("message")}"
        }

        // DASH 兜底(仅 durl 为空时使用);优先选 AVC(codecid=7)避免 HEVC 解码兼容问题
        if (videoUrl.isEmpty()) {
            if (dashJ != null && dashJ.optInt("code", -1) != 0) lastApiError = "${dashJ.optInt("code")}: ${dashJ.optString("message")}"
            val dash = dashD?.optJSONObject("dash")
            if (dash != null) {
                val videos = dash.optJSONArray("video")
                val audios = dash.optJSONArray("audio")
                val vlist = (0 until (videos?.length() ?: 0)).mapNotNull { i -> videos?.optJSONObject(i) }
                val videoPick = vlist.firstOrNull { it.optInt("codecid", 0) == 7 } ?: vlist.firstOrNull()
                videoUrl = videoPick?.optString("baseUrl", "") ?: ""
                audioUrl = (0 until (audios?.length() ?: 0)).mapNotNull { i -> audios?.optJSONObject(i) }
                    .maxByOrNull { it.optInt("bandwidth", 0) }?.optString("baseUrl", "") ?: ""
            }
        }

        if (videoUrl.isEmpty() && lastApiError.isEmpty()) lastApiError = "接口返回空地址"
        // v0.4.4: 播放地址获取失败时写文件日志(设置页可导出排查)
        if (videoUrl.isEmpty()) {
            com.bililite.core.BiliLog.e("PlayUrl", "获取播放地址失败 bvid=$bvid cid=$cid qn=$qn → $lastApiError")
        } else {
            com.bililite.core.BiliLog.i("PlayUrl", "获取播放地址成功 bvid=$bvid cid=$cid qn=$qn 类型=${if (audioUrl.isNotEmpty()) "DASH分离流" else "durl渐进流"}")
        }
        // 清晰度列表统一取 DASH 响应(完整);DASH 缺失时退回 durl 响应
        fun parseQ(src: JSONObject?): List<Int> = src?.optJSONArray("accept_quality")?.let { a ->
            (0 until a.length()).map { i -> a.optInt(i) }
        } ?: emptyList()
        fun parseDesc(src: JSONObject?): List<String> = src?.optJSONArray("accept_description")?.let { a ->
            (0 until a.length()).map { i -> a.optString(i) }
        } ?: emptyList()
        val acceptQuality = parseQ(dashD).ifEmpty { parseQ(d) }
        val acceptDesc = parseDesc(dashD).ifEmpty { parseDesc(d) }
        val qualities = dashD?.optJSONArray("support_formats")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.optInt("quality", 0) }
        } ?: emptyList()
        return StreamInfo(videoUrl, audioUrl, qualities, acceptQuality, acceptDesc)
    }

    /** 兼容旧调用:返回 "dash:video|audio"(无音频时只返回 video url)。 */
    suspend fun playUrl(bvid: String, cid: Long, qn: Int = 64): String {
        val s = playStream(bvid, cid, qn)
        return when {
            s.videoUrl.isEmpty() -> ""
            s.audioUrl.isNotEmpty() -> "dash:${s.videoUrl}|${s.audioUrl}"
            else -> s.videoUrl
        }
    }

    /** B 站字幕轨道信息。 */
    data class SubtitleTrack(
        val lan: String,          // 如 "zh-CN" / "ai-zh"
        val lanDoc: String,       // 如 "中文(中国)" / "中文(自动生成)"
        val subtitleUrl: String,  // 优先 subtitle_url(明文);缺则 subtitle_url_v2
        val aiType: Int           // 0=人工,1=AI 生成
    )

    /** B 站 CC 字幕轨道列表。返回可能为空列表(视频无字幕)。
     *  v0.4.6 修复:
     *  ① subtitle_url 是明文地址(//aisubtitle.hdslb.com/...),subtitle_url_v2 是加密串
     *     (//subtitle.bilibili.com/... 无法解析)。之前优先取 v2 导致字幕必然下载失败。
     *     现在优先 subtitle_url,v2 仅作兜底。
     *  ② /x/player/wbi/v2 的 WBI 签名必须带 aid(仅 bvid+cid 会被风控拒,返回空字幕)。
     *  ③ 协程取消(CancellationException)不当作错误记录。 */
    suspend fun subtitleTracks(bvid: String, cid: Long): List<SubtitleTrack> {
        ensureBuvid()
        val raw: JSONArray = try {
            val aid = aidOf(bvid)
            val j = wbiGet("https://api.bilibili.com/x/player/wbi/v2",
                mapOf("aid" to aid.toString(), "bvid" to bvid, "cid" to cid.toString(),
                      "platform" to "web", "web_location" to "1550101"), false)
            val code = j.optInt("code", 0)
            if (code != 0) {
                com.bililite.core.BiliLog.e("Subtitle", "wbi/v2 返回错误 code=$code msg=${j.optString("message")} bvid=$bvid")
                JSONArray()
            } else {
                j.optJSONObject("data")?.optJSONObject("subtitle")?.optJSONArray("subtitles")
                    ?: JSONArray()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.bililite.core.BiliLog.e("Subtitle", "wbi/v2 请求失败 bvid=$bvid: ${e.message}", e)
            try {
                // 兜底:旧接口(登录用户可返回字幕)
                val j2 = get("https://api.bilibili.com/x/player/v2?bvid=$bvid&cid=$cid")
                j2.optJSONObject("data")?.optJSONObject("subtitle")?.optJSONArray("subtitles")
                    ?: JSONArray()
            } catch (e2: kotlinx.coroutines.CancellationException) {
                throw e2
            } catch (e2: Exception) {
                com.bililite.core.BiliLog.e("Subtitle", "v2 兜底也失败 bvid=$bvid: ${e2.message}", e2)
                JSONArray()
            }
        }
        val tracks = (0 until raw.length()).mapNotNull { i ->
            val o = raw.optJSONObject(i) ?: return@mapNotNull null
            // v0.4.6: 优先明文 subtitle_url;subtitle_url_v2 是加密串,仅作兜底
            val url = o.optString("subtitle_url", "").ifBlank { o.optString("subtitle_url_v2", "") }
            if (url.isBlank()) return@mapNotNull null
            SubtitleTrack(
                lan = o.optString("lan", ""),
                lanDoc = o.optString("lan_doc", ""),
                subtitleUrl = url,
                aiType = o.optInt("ai_type", if (o.optString("lan", "").startsWith("ai-")) 1 else 0))
        }
        com.bililite.core.BiliLog.i("Subtitle", "bvid=$bvid cid=$cid 字幕轨道 ${tracks.size} 条: " +
            tracks.joinToString(" | ") { "${it.lanDoc}(${it.lan})${if (it.aiType == 1) "[AI]" else ""}" })
        return tracks
    }

    /** bvid → aid 缓存(view 接口)。wbi/v2 签名必需,失败返回 0(仍可尝试)。 */
    private var aidCache = mutableMapOf<String, Long>()
    private suspend fun aidOf(bvid: String): Long {
        aidCache[bvid]?.let { return it }
        return try {
            val j = get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
            val aid = j.optJSONObject("data")?.optLong("aid", 0L) ?: 0L
            aidCache[bvid] = aid
            aid
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.bililite.core.BiliLog.e("Subtitle", "获取 aid 失败 bvid=$bvid: ${e.message}")
            0L
        }
    }

    /** 下载字幕 json 原文(B 站字幕是私有 json 格式,由调用方转 cue)。
     *  v0.4.5: 带完整 cookie(buvid3+登录)与 Origin,否则部分字幕地址被 CDN 拒(403)。 */
    suspend fun downloadText(url: String): String {
        val full = if (url.startsWith("//")) "https:$url" else if (url.startsWith("http")) url else "https:$url"
        return withContext(Dispatchers.IO) {
            val conn = URL(full).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.setRequestProperty("Origin", "https://www.bilibili.com")
            val fc = fullCookie()
            if (fc.isNotEmpty()) conn.setRequestProperty("Cookie", fc)
            conn.connectTimeout = 8000; conn.readTimeout = 15000
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        }
    }

    /** 下载视频流到本地文件(离线缓存)。返回是否成功。 */
    suspend fun downloadToFile(url: String, dest: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            val full = if (url.startsWith("//")) "https:$url" else if (url.startsWith("http")) url else "https:$url"
            val conn = URL(full).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.connectTimeout = 10000; conn.readTimeout = 60000
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.length() > 0
        } catch (_: Exception) { false }
    }

    /** 带进度的视频流下载(离线缓存进度反馈)。onProgress(doneBytes, totalBytes)。 */
    suspend fun downloadToFileProgress(url: String, dest: java.io.File, onProgress: (Long, Long) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val full = if (url.startsWith("//")) "https:$url" else if (url.startsWith("http")) url else "https:$url"
                val conn = URL(full).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Referer", "https://www.bilibili.com/")
                conn.connectTimeout = 10000; conn.readTimeout = 120000
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                dest.length() > 0
            } catch (_: Exception) { false }
        }

    // ---------- UP 主合集(seasons)与系列(series) ----------
    /** UP 主的合集/系列列表。
     *  v0.4.1 修复:/x/polymer/web-space 系列接口现在需要 wbi 签名,否则被风控。 */
    suspend fun seasonsSeriesList(mid: Long, pageNum: Int = 1): JSONObject =
        wbiGet("https://api.bilibili.com/x/polymer/web-space/seasons_series_list",
            mapOf("mid" to mid.toString(), "page_num" to pageNum.toString(), "page_size" to "20"),
            space = true)

    /** 某个合集(season)内的视频列表(分页)。 */
    suspend fun seasonArchivesList(seasonId: Long, pageNum: Int = 1): JSONObject =
        wbiGet("https://api.bilibili.com/x/polymer/web-space/seasons_archives_list",
            mapOf("season_id" to seasonId.toString(), "page_num" to pageNum.toString(),
                  "page_size" to "30", "sort_reverse" to "false"),
            space = true)

    /** 某个系列(series)内的视频列表(分页)。系列接口与合集不同。 */
    suspend fun seriesArchivesList(seriesId: Long, pageNum: Int = 1): JSONObject =
        wbiGet("https://api.bilibili.com/x/series/archives",
            mapOf("series_id" to seriesId.toString(), "pn" to pageNum.toString(),
                  "ps" to "30", "sort_reverse" to "false"),
            space = true)

    // ---------- 云端收藏夹(B 站账号收藏夹) ----------
    /** 当前登录用户的收藏夹列表(含默认收藏夹)。
     *  注意:/x/v3/fav 系列接口现在需要 wbi 签名,否则被风控(code=-352)。 */
    suspend fun favFolders(): JSONObject =
        wbiGet("https://api.bilibili.com/x/v3/fav/folder/created/list-all",
            mapOf("up_mid" to dedeUserId), false)

    /** 某个收藏夹内的视频列表(分页,按收藏时间倒序)。 */
    suspend fun favResourceList(mediaId: Long, pn: Int = 1): JSONObject =
        wbiGet("https://api.bilibili.com/x/v3/fav/resource/list",
            mapOf("media_id" to mediaId.toString(), "pn" to pn.toString(), "ps" to "20",
                  "keyword" to "", "order" to "mtime", "type" to "0", "tid" to "0",
                  "platform" to "web"), false)

    /** 插件系统 network.* 用:通用 GET(自动带登录态 cookie + Referer/UA)。仅允许 https。 */
    suspend fun publicGet(url: String): JSONObject =
        if (url.startsWith("https://")) get(url, space = false)
        else throw Exception("仅支持 https 请求")

    /** 插件 system/network 用:通用 POST x-www-form-urlencoded(带登录态)。 */
    suspend fun publicPost(url: String, fields: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.setRequestProperty("Origin", "https://www.bilibili.com")
            val fc = fullCookie()
            if (fc.isNotEmpty()) conn.setRequestProperty("Cookie", fc)
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            val body = fields.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            try { conn.disconnect() } catch (_: Exception) {}
            resp
        }
}
