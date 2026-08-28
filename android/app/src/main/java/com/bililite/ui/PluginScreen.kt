package com.bililite.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bililite.core.C
import com.bililite.plugin.PluginInfo
import com.bililite.plugin.PluginManager

/**
 * 统一插件系统 —— 阶段3：插件管理 UI。
 *
 * 列表展示所有已安装插件:名称/版本/作者/类型/状态(启用/禁用)，
 * 支持启用开关、卸载(二次确认)、详情(权限/描述/目录)。
 * 顶栏有「重新扫描」入口(方便手动放 zip 后刷新)。
 */
@Composable
fun PluginScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val manager = remember { PluginManager.get(ctx) }
    // 用可变状态驱动重绘(插件列表变化后刷新)
    var version by remember { mutableStateOf(0) }
    var confirmUninstall by remember { mutableStateOf<PluginInfo?>(null) }
    var detail by remember { mutableStateOf<PluginInfo?>(null) }
    var installMsg by remember { mutableStateOf("") }

    // SAF 文件选择器:选 zip 安装
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            installMsg = try {
                val info = manager.installFromUri(uri)
                manager.scan(); version++
                "已安装「${info.name}」"
            } catch (e: Exception) {
                "安装失败: ${e.message ?: "未知错误"}"
            }
        }
    }

    fun refresh() { manager.scan(); version++ }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text("插件", style = MaterialTheme.typography.titleMedium, color = C.t1)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { pickZip.launch("*/*") }) { Text("安装", color = C.t1) }
            TextButton(onClick = { refresh() }) { Text("重新扫描", color = C.t2) }
        }
        if (installMsg.isNotEmpty()) {
            Text(installMsg, color = if (installMsg.startsWith("安装失败")) Color(0xFFFF3B30) else C.t1,
                fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text("已安装 ${manager.plugins.size} 个插件。插件为 zip 包(含 plugin.json)。",
            color = C.t2, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))

        if (manager.plugins.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无插件\n将插件 zip 放入插件目录后点击「重新扫描」", color = C.t2,
                    fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(manager.plugins, key = { it.id }) { p ->
                val enabled = manager.isEnabled(p.id)
                Card(colors = CardDefaults.cardColors(containerColor = com.bililite.app.BILICARD),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = C.t1, fontSize = 14.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text("v${p.version} · ${typeLabel(p.type)}" +
                                 (if (p.author.isNotBlank()) " · ${p.author}" else ""),
                                color = C.t2, fontSize = 11.sp)
                        }
                        // 启用开关
                        Switch(checked = enabled,
                            onCheckedChange = { on ->
                                manager.setEnabled(p.id, on)
                                version++
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = C.block,
                                uncheckedTrackColor = C.line))
                        // 详情
                        TextButton(onClick = { detail = p }) {
                            Text("详情", color = C.t2, fontSize = 12.sp)
                        }
                        // 卸载
                        TextButton(onClick = { confirmUninstall = p }) {
                            Text("卸载", color = Color(0xFFFF3B30), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // 卸载二次确认
    confirmUninstall?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text("卸载插件", color = C.t1, fontSize = 16.sp) },
            text = { Text("确定卸载「${p.name}」吗？卸载后其功能与数据将被移除。", color = C.t2, fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = {
                    manager.uninstall(p.id)
                    confirmUninstall = null
                    version++
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30))) {
                    Text("卸载", color = Color.White)
                }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("取消", color = C.t2) } },
            containerColor = C.card)
    }

    // 详情弹窗
    detail?.let { p ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(p.name, color = C.t1, fontSize = 16.sp) },
            text = {
                Column {
                    Text("ID：${p.id}", color = C.t2, fontSize = 12.sp)
                    Text("版本：${p.version}", color = C.t2, fontSize = 12.sp)
                    Text("类型：${typeLabel(p.type)}", color = C.t2, fontSize = 12.sp)
                    if (p.author.isNotBlank()) Text("作者：${p.author}", color = C.t2, fontSize = 12.sp)
                    if (p.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(p.description, color = C.t1, fontSize = 13.sp)
                    }
                    if (p.permissions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("权限：${p.permissions.joinToString(", ")}", color = C.t2, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭", color = C.t1) } },
            containerColor = C.card)
    }
}

private fun typeLabel(t: String): String = when (t) {
    "theme" -> "主题"
    "feature" -> "功能"
    "resource" -> "资源"
    else -> t
}