package com.bililite.core

import java.security.MessageDigest
import android.util.Base64
import org.json.JSONObject

/**
 * B 站 WBI 签名（仿照 bilibili-API-collect：mixinKeyEncTab 置换表）。
 * 用于对 x/web-interface、x/space/wbi 等需要 wbi 签名的接口做参数签名。
 */
object WbiSign {
    // 官方常量表
    private val MIXIN_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52
    )

    /** 从 nav 接口返回的 wbi_img 两个 url 中摘出 imgKey/subKey。 */
    fun imgKeys(imgUrl: String, subUrl: String): Pair<String, String> {
        fun key(u: String): String {
            // nav 的 wbi_img 形如 https://i0.hdslb.com/bfs/wbi/xxx.png
            val i = u.indexOf("wbi/")
            val start = if (i >= 0) i + 4 else u.lastIndexOf('/') + 1
            val base = u.substring(start).substringBefore('.')
            return base.take(32)
        }
        return key(imgUrl) to key(subUrl)
    }

    /** 计算 mixinKey = 置换后的 32 字符。 */
    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val sb = StringBuilder()
        for (i in MIXIN_TAB) if (i < raw.length) sb.append(raw[i])
        return sb.toString().take(32)
    }

    // dm_* 反爬参数(参考 MyTVB 的 WbiGenerator, arc/search 缺了会被风控)
    private val DM_PARAMS = mapOf(
        "dm_img_list" to "[]",
        "dm_img_str" to "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ",
        "dm_cover_img_str" to "QU5HTEUgKEludGVsLCBJbnRlbChSKSBJcmlzKFIpIFhlIEdyYXBoaWNzICgweDAwMDA0NkE2KSBEaXJlY3QzRDExIHZzXzVfMCBwc181XzAsIEQzRDExKUdvb2dsZSBJbmMuIChJbnRlbC",
        "dm_img_inter" to "{\"ds\":[],\"wh\":[5032,6004,10],\"of\":[425,850,425]}"
    )

    /** 对参数字典做 wbi 签名：加 wts(时间戳) + w_rid(md5) + dm_* 反爬参数。返回拼好的 query。 */
    fun sign(params: Map<String, String>, imgKey: String, subKey: String): String {
        val mixin = mixinKey(imgKey, subKey)
        val p = LinkedHashMap<String, String>()
        p.putAll(params.filterKeys { it != "wts" && it != "w_rid" })
        p.putAll(DM_PARAMS)
        p["wts"] = (System.currentTimeMillis() / 1000).toString()
        val sorted = p.toSortedMap()
        val query = buildString {
            sorted.entries.forEachIndexed { i, e ->
                if (i > 0) append("&")
                append(percentEncode(e.key)).append("=").append(percentEncode(e.value))
            }
        }
        val wrid = md5(query + mixin)
        return query + "&w_rid=" + wrid
    }

    /** RFC3986 非保留字符外的全部百分号编码(关键:md5 必须用编码后的字符串)。 */
    private fun percentEncode(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val unreserved = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
                c in '0'.code..'9'.code || c == '-'.code || c == '_'.code ||
                c == '.'.code || c == '~'.code
            if (unreserved) sb.append(c.toChar())
            else sb.append('%').append("0123456789ABCDEF"[c ushr 4]).append("0123456789ABCDEF"[c and 0x0F])
        }
        return sb.toString()
    }

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val d = md.digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    // 占位：防止未用警告
    @Suppress("unused")
    private val b64 = object { fun e(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP) }
}
