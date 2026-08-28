package com.bililite.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.bililite.core.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 登录状态(真实二维码) */
sealed class LoginState {
    object Loading : LoginState()
    data class NeedScan(val url: String) : LoginState()
    data class Scanned(val msg: String) : LoginState()
    object Expired : LoginState()
    data class Success(val name: String) : LoginState()
    data class Error(val msg: String) : LoginState()
}

class LoginViewModel(private val ctx: Context) : ViewModel() {
    val api = BiliApi(ctx)
    var qrcodeKey = ""
    var state = androidx.compose.runtime.mutableStateOf<LoginState>(LoginState.Loading)
    var onLoggedIn: (() -> Unit)? = null
    private var pollJob: Job? = null
    private var pollFails = 0
    private var lastQrUrl = ""

    val curUrl: String get() = (state.value as? LoginState.NeedScan)?.url ?: lastQrUrl
    private fun qrUrlOf(): String = lastQrUrl

    fun applySavedCookie() {
        val c = LoginSession.cookieString(ctx)
        if (c.isNotEmpty()) api.assignCookie(c)
        api.dedeUserId = LoginSession.dedeUserId(ctx)
    }

    /** 从官方接口取真实二维码并开始轮询。 */
    fun generate() {
        pollJob?.cancel()
        state.value = LoginState.Loading
        viewModelScope.launch {
            try {
                val j = api.qrcodeGenerate()
                if (j.optInt("code", -1) != 0) {
                    state.value = LoginState.Error("接口错误: " + j.optString("message", j.toString()))
                    return@launch
                }
                val d = j.optJSONObject("data") ?: JSONObject()
                val url = d.optString("url", "")
                qrcodeKey = d.optString("qrcode_key", "")
                lastQrUrl = url
                if (url.isEmpty()) { state.value = LoginState.Error("二维码生成失败"); return@launch }
                state.value = LoginState.NeedScan(url)
                startPoll()
            } catch (e: Exception) {
                state.value = LoginState.Error("网络错误: " + (e.message ?: "无法连接 B 站") + "\n请检查网络后重试")
            }
        }
    }

    fun startPoll() {
        pollFails = 0
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            for (i in 0 until 60) {
                delay(2500)
                try {
                    val j = api.qrcodePollWithCookie(qrcodeKey)
                    val setCookies = api.lastSetCookie
                    val data = j.optJSONObject("data") ?: JSONObject()
                    when (data.optInt("code", -1)) {
                        0 -> {
                            // 优先从 data.url 提取三种 cookie(与参考客户端一致);备用 Set-Cookie
                            val tri = api.extractCookiesFromUrl(data.optString("url", ""))
                            if (tri != null) {
                                LoginSession.setCookies(ctx, tri.first, tri.second, tri.third)
                                api.assignCookie(LoginSession.cookieString(ctx))
                            } else if (setCookies.isNotEmpty()) {
                                LoginSession.setCookies(ctx,
                                    setCookies.substringAfter("SESSDATA=", "").substringBefore(";"),
                                    setCookies.substringAfter("bili_jct=", "").substringBefore(";"),
                                    setCookies.substringAfter("DedeUserID=", "").substringBefore(";"))
                                api.assignCookie(LoginSession.cookieString(ctx))
                            }
                            val name = try {
                                val nav = api.nav().optJSONObject("data")
                                val uname = nav?.optString("uname") ?: "B站用户"
                                val face = nav?.optString("face") ?: ""
                                val sign = nav?.optString("sign") ?: ""
                                LoginSession.setProfile(ctx, uname, face, sign)
                                uname
                            } catch (_: Exception) { "B站用户" }
                            state.value = LoginState.Success(name)
                            onLoggedIn?.invoke(); return@launch
                        }
                        86101 -> state.value = LoginState.NeedScan(qrUrlOf())   // 未扫码:继续等待,仍显示二维码
                        86038 -> state.value = LoginState.Scanned("已扫码,请在手机确认登录…")
                        86090 -> { state.value = LoginState.Expired; return@launch }
                        else -> { /* 继续轮询 */ }
                    }
                } catch (e: Exception) {
                    // 轮询偶发网络抖动:不立即放弃,累计失败 4 次才报错(真扫码需几十秒)
                    pollFails++
                    if (pollFails >= 4) { state.value = LoginState.Error("轮询失败: " + (e.message ?: "网络异常")); return@launch }
                }
            }
            state.value = LoginState.Expired
        }
    }

    fun pausePoll() { pollJob?.cancel(); pollJob = null }
    fun resumePoll() { if (state.value is LoginState.NeedScan) startPoll() }

    fun logout() {
        pollJob?.cancel(); LoginSession.clear(ctx); api.clearCookie()
        state.value = LoginState.NeedScan("")
    }

    // ---------- 验证码 / 密码登录(参考 bilibili-pure) ----------
    var smsMsg by androidx.compose.runtime.mutableStateOf("")
    var smsSending by androidx.compose.runtime.mutableStateOf(false)
    var smsCaptchaKey = ""

    /** 获取极验 captcha(token/gt/challenge)并回调(供验证码/密码登录共用) */
    fun viewModelScopeLaunchCaptcha(onCaptcha: (Triple<String, String, String>) -> Unit) {
        viewModelScope.launch {
            try {
                val c = api.getCaptcha()
                if (c.first.isEmpty()) { smsMsg = "获取验证码失败"; return@launch }
                onCaptcha(c)
            } catch (e: Exception) {
                smsMsg = "获取验证码失败: ${e.message}"
            }
        }
    }

    /** 把 cookies 写入会话并拉取资料,成功回调 */
    private suspend fun completeLogin(cookies: Map<String, String>) {
        val s = cookies["SESSDATA"]; val j = cookies["bili_jct"]
        val d = cookies["DedeUserID"]
        if (s.isNullOrEmpty() || j.isNullOrEmpty()) {
            state.value = LoginState.Error("未获取到登录凭证(SESSDATA/bili_jct)")
            return
        }
        LoginSession.setCookies(ctx, s, j, d ?: "")
        api.assignCookie(LoginSession.cookieString(ctx))
        api.dedeUserId = d ?: ""
        try {
            val nav = api.nav().optJSONObject("data")
            val uname = nav?.optString("uname") ?: "B站用户"
            LoginSession.setProfile(ctx, uname, nav?.optString("face") ?: "", nav?.optString("sign") ?: "")
            state.value = LoginState.Success(uname)
        } catch (_: Exception) {
            state.value = LoginState.Success("B站用户")
        }
        onLoggedIn?.invoke()
    }

    /** 发送短信验证码(需先过极验),成功后把 captcha_key 存回 */
    fun sendSms(tel: String, token: String, geetest: GeetestResult) {
        viewModelScope.launch {
            smsSending = true; smsMsg = ""
            try {
                val key = api.sendSmsCode(tel, token, geetest.challenge, geetest.validate, geetest.seccode)
                smsMsg = "验证码已发送"
                smsCaptchaKey = key
            } catch (e: Exception) {
                smsMsg = "发送失败: ${e.message}"
            } finally { smsSending = false }
        }
    }

    /** 验证码登录 */
    fun smsLogin(tel: String, code: String) {
        if (smsCaptchaKey.isEmpty()) { smsMsg = "请先获取验证码"; return }
        viewModelScope.launch {
            try {
                val cookies = api.smsLogin(tel, code, smsCaptchaKey)
                completeLogin(cookies)
            } catch (e: Exception) {
                state.value = LoginState.Error("登录失败: ${e.message}")
            }
        }
    }

    /** 密码登录(用户名 + 密码,内部做 RSA 加密) */
    fun passwordLogin(username: String, password: String, token: String, geetest: GeetestResult) {
        viewModelScope.launch {
            try {
                val (hash, key) = api.getWebKey()
                val enc = withContext(Dispatchers.IO) { encryptPassword(hash, key, password) }
                val cookies = api.passwordLogin(username, enc, token, geetest.challenge, geetest.validate, geetest.seccode)
                completeLogin(cookies)
            } catch (e: Exception) {
                state.value = LoginState.Error("登录失败: ${e.message}")
            }
        }
    }

}

/** zxing 把内容编码成二维码 Bitmap
 *  v0.3 性能修复:改为一次性 setPixels(原逐像素 setPixel 26 万次,登录页卡顿 1~2 秒) */
fun encodeQr(content: String, size: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val matrix = try { writer.encode(content, BarcodeFormat.QR_CODE, size, size) }
                  catch (_: Exception) { return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565) }
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val rowOff = y * size
        for (x in 0 until size) {
            pixels[rowOff + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}

class LoginVMFactory(private val ctx: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LoginViewModel(ctx) as T
}
