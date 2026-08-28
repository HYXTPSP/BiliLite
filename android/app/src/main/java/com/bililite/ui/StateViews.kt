// ============================================================================
// BiliLite — 统一状态页样式模板(v0.4.14 视觉优化)
// 加载中 / 空页面 / 网络错误 三种状态的统一自适应样式。
// 仅作为样式组件供各页面复用，不改变任何业务状态切换逻辑。
// 字号/间距/圆角/图标全部通过 dimens 资源引用(禁止硬编码 dp/sp)。
// ============================================================================
package com.bililite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.bililite.app.R
import com.bililite.core.C

// 显式返回类型，避免 dimensionResource 的 Dp/TextUnit 重载推断歧义
@Composable
private fun dpRes(id: Int): Dp = dimensionResource(id)

// 精确读取 sp 资源：getDimension 对 sp 返回 px(=sp×scaledDensity)，除以 scaledDensity 还原 sp 值
@Composable
private fun spRes(id: Int): TextUnit {
    val res = LocalContext.current.resources
    @Suppress("DEPRECATION")
    return (res.getDimension(id) / res.displayMetrics.scaledDensity).sp
}

/**
 * 统一状态页样式。
 * @param loading 是否加载中
 * @param empty   是否空数据
 * @param error   错误文案（非空时显示错误态）
 * @param onRetry 可选的重试回调（仅错误态显示重试按钮）
 */
@Composable
fun BiliStateView(
    loading: Boolean = false,
    empty: Boolean = false,
    error: String = "",
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        color = C.primary,
                        modifier = Modifier.size(dpRes(R.dimen.size_icon_lg))
                    )
                    Spacer(Modifier.height(dpRes(R.dimen.space_md)))
                    Text("加载中…", color = C.t2, fontSize = spRes(R.dimen.text_caption))
                }
                error.isNotEmpty() -> {
                    Text("!", color = C.t2, fontSize = spRes(R.dimen.text_display))
                    Spacer(Modifier.height(dpRes(R.dimen.space_md)))
                    Text(error, color = C.t2,
                        fontSize = spRes(R.dimen.text_subtitle),
                        textAlign = TextAlign.Center)
                    if (onRetry != null) {
                        Spacer(Modifier.height(dpRes(R.dimen.space_lg)))
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(dpRes(R.dimen.radius_md))
                        ) {
                            Text("重试", color = C.t1,
                                fontSize = spRes(R.dimen.text_subtitle))
                        }
                    }
                }
                empty -> {
                    Text("○", color = C.line, fontSize = spRes(R.dimen.text_display))
                    Spacer(Modifier.height(dpRes(R.dimen.space_md)))
                    Text("暂无内容", color = C.t2, fontSize = spRes(R.dimen.text_subtitle))
                }
            }
        }
    }
}
