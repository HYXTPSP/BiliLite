package com.bililite.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

data class GeetestResult(val challenge: String, val validate: String, val seccode: String)

/** 极验滑动验证(WebView 内嵌 gt.js,JS 经 onJsPrompt 回传结果)。参考 bilibili-pure。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GeetestCaptcha(
    gt: String, challenge: String,
    onResult: (GeetestResult) -> Unit, onDismiss: () -> Unit
) {
    var resultHandled by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("请完成验证", style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF1C1C1E), modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭", color = Color(0xFF1C1C1E)) }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString =
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                webChromeClient = object : WebChromeClient() {
                                    override fun onJsPrompt(view: WebView?, url: String?, message: String?,
                                                            defaultValue: String?, result: JsPromptResult?): Boolean {
                                        if (message != null && !resultHandled) {
                                            val ci = message.indexOf(':')
                                            if (ci > 0) {
                                                val type = message.substring(0, ci)
                                                val json = message.substring(ci + 1)
                                                if (type == "success") {
                                                    try {
                                                        val o = JSONObject(json)
                                                        val c = o.optString("geetest_challenge", "")
                                                        val v = o.optString("geetest_validate", "")
                                                        val s = o.optString("geetest_seccode", "")
                                                        if (c.isNotEmpty() && v.isNotEmpty() && s.isNotEmpty()) {
                                                            resultHandled = true
                                                            onResult(GeetestResult(c, v, s))
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                        result?.confirm("")
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {}
                                val html = """
                                    <!DOCTYPE html><html><head>
                                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                    <style>*{margin:0;padding:0;box-sizing:border-box}body{background:#f5f5f5;display:flex;justify-content:center;align-items:flex-start;padding-top:20px}#captcha-box{background:white;border-radius:8px;padding:10px;width:100%;max-width:340px}</style>
                                    </head><body><div id="captcha-box"></div>
                                    <script src="https://static.geetest.com/static/tools/gt.js"></script>
                                    <script>
                                    function R(t,d){try{prompt(t+':'+JSON.stringify(d))}catch(e){}}
                                    try{initGeetest({gt:"$gt",challenge:"$challenge",lang:"zh-cn",width:"100%",product:"float",offline:false},
                                    function(o){o.appendTo("#captcha-box");
                                    o.onSuccess(function(){R("success",o.getValidate())});
                                    o.onError(function(e){R("error",e)});
                                    o.onClose(function(){R("close",{})})})}catch(e){R("error",{msg:e.message})}
                                    </script></body></html>
                                """.trimIndent()
                                loadDataWithBaseURL("https://www.bilibili.com", html, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/** RSA 加密密码: 用 hash + 公钥 加密 "hash+password",Base64 输出 */
fun encryptPassword(hash: String, publicKeyPem: String, password: String): String {
    val pem = publicKeyPem
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("\n", "").replace("\r", "")
    val keyBytes = Base64.getDecoder().decode(pem)
    val keySpec = X509EncodedKeySpec(keyBytes)
    val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, publicKey)
    return Base64.getEncoder().encodeToString(cipher.doFinal((hash + password).toByteArray()))
}
