package com.bililite.ui

import com.bililite.core.LoginSession
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 登录界面(白主题):扫码 / 验证码 / 密码 三个标签。 */
@Composable
fun LoginScreen(vm: LoginViewModel, onDone: () -> Unit) {
    val ctx = LocalContextSafe()
    LaunchedEffect(Unit) {
        vm.applySavedCookie()
        if (LoginSession.isLoggedIn(ctx)) { onDone(); return@LaunchedEffect }
        vm.generate()
        vm.onLoggedIn = onDone
    }
    // 切后台暂停轮询,回前台恢复,避免后台超时"轮询失败"
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> vm.pausePoll()
                Lifecycle.Event.ON_RESUME -> { vm.applySavedCookie(); if (!LoginSession.isLoggedIn(ctx)) vm.resumePoll() else vm.onLoggedIn?.invoke() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); vm.pausePoll() }
    }

    var tab by remember { mutableStateOf(0) } // 0 扫码 1 验证码 2 密码
    Column(
        Modifier.fillMaxSize().background(com.bililite.core.C.bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("BiliLite", fontSize = 24.sp, color = com.bililite.core.C.t1)
        Spacer(Modifier.height(8.dp))
        Text("学习专注型 B 站客户端", fontSize = 13.sp, color = com.bililite.core.C.t2)
        Spacer(Modifier.height(24.dp))

        // 三个标签
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent,
            contentColor = com.bililite.core.C.t1) {
            listOf("扫码登录", "验证码登录", "密码登录").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = {
                    Text(t, color = if (tab == i) com.bililite.core.C.t1 else com.bililite.core.C.t2, fontSize = 14.sp)
                })
            }
        }
        Spacer(Modifier.height(8.dp))

        when (tab) {
            0 -> QrTab(vm)
            1 -> SmsTab(vm)
            2 -> PasswordTab(vm)
        }

        // 登录错误提示(全局)
        val st = vm.state.value
        if (st is LoginState.Error) {
            Spacer(Modifier.height(12.dp))
            Text(st.msg, color = Color(0xFFFF3B30), textAlign = TextAlign.Center, fontSize = 12.sp)
        }
    }
}

@Composable
private fun QrTab(vm: LoginViewModel) {
    val state = vm.state.value
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        when (state) {
            is LoginState.Loading -> CircularProgressIndicator(color = com.bililite.core.C.t1)
            is LoginState.NeedScan -> {
                val url = state.url
                val bmp = remember(url) { if (url.isNotEmpty()) encodeQr(url) else null }
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = "登录二维码",
                        modifier = Modifier.size(220.dp))
                } else {
                    Box(Modifier.size(220.dp).background(Color.White)){}
                }
                Spacer(Modifier.height(16.dp))
                Text("用 B 站 App 扫码登录", fontSize = 14.sp, color = com.bililite.core.C.t1)
                Text("扫码后在手机确认,等待自动跳转", fontSize = 11.sp, color = com.bililite.core.C.t2)
                Spacer(Modifier.height(14.dp))
                Button(colors = ButtonDefaults.buttonColors(containerColor = com.bililite.core.C.t1),
                    onClick = { vm.startPoll() }) { Text("我已扫码,刷新状态", color = Color.White) }
            }
            is LoginState.Scanned -> {
                Text("✓", fontSize = 34.sp, color = com.bililite.core.C.t1)
                Spacer(Modifier.height(10.dp))
                Text(state.msg, fontSize = 14.sp, color = com.bililite.core.C.t1)
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(color = com.bililite.core.C.t1, strokeWidth = 3.dp)
            }
            is LoginState.Success -> { }
            is LoginState.Expired -> {
                Text("二维码已失效", color = com.bililite.core.C.t2)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { vm.generate() }, colors = ButtonDefaults.buttonColors(containerColor = com.bililite.core.C.t1)) { Text("重新获取", color = Color.White) }
            }
            is LoginState.Error -> { } // 错误在全局显示
        }
    }
}

@Composable
private fun SmsTab(vm: LoginViewModel) {
    var tel by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var captchaToken by remember { mutableStateOf("") }
    var captchaGt by remember { mutableStateOf("") }
    var captchaChallenge by remember { mutableStateOf("") }
    var showCaptcha by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }

    LaunchedEffect(countdown) { if (countdown > 0) { delay(1000); countdown-- } }

    if (showCaptcha) {
        GeetestCaptcha(gt = captchaGt, challenge = captchaChallenge,
            onResult = { r -> showCaptcha = false; vm.sendSms(tel, captchaToken, r) },
            onDismiss = { showCaptcha = false })
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = tel, onValueChange = { tel = it.filter { c -> c.isDigit() }.take(11) },
            label = { Text("手机号") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = code, onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("验证码") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                if (tel.length != 11) { vm.smsMsg = "请输入 11 位手机号"; return@OutlinedButton }
                vm.viewModelScopeLaunchCaptcha { c ->
                    captchaToken = c.first; captchaGt = c.second; captchaChallenge = c.third; showCaptcha = true
                }
            }, enabled = countdown == 0) {
                Text(if (countdown > 0) "${countdown}s" else "获取验证码", color = com.bililite.core.C.t1)
            }
        }
        if (vm.smsMsg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(vm.smsMsg, color = com.bililite.core.C.t2, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.smsLogin(tel, code) },
            enabled = code.length >= 4 && tel.length == 11,
            colors = ButtonDefaults.buttonColors(containerColor = com.bililite.core.C.t1),
            modifier = Modifier.fillMaxWidth()) { Text("登录", color = Color.White) }
    }
}

@Composable
private fun PasswordTab(vm: LoginViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captchaToken by remember { mutableStateOf("") }
    var captchaGt by remember { mutableStateOf("") }
    var captchaChallenge by remember { mutableStateOf("") }
    var showCaptcha by remember { mutableStateOf(false) }

    if (showCaptcha) {
        GeetestCaptcha(gt = captchaGt, challenge = captchaChallenge,
            onResult = { r -> showCaptcha = false; vm.passwordLogin(username, password, captchaToken, r) },
            onDismiss = { showCaptcha = false })
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = username, onValueChange = { username = it.trim() },
            label = { Text("手机号/邮箱/用户名") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text("密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            if (username.isBlank() || password.isBlank()) { vm.smsMsg = "请输入账号和密码"; return@Button }
            vm.viewModelScopeLaunchCaptcha { c ->
                captchaToken = c.first; captchaGt = c.second; captchaChallenge = c.third; showCaptcha = true
            }
        }, enabled = username.isNotBlank() && password.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = com.bililite.core.C.t1),
            modifier = Modifier.fillMaxWidth()) { Text("登录", color = Color.White) }
    }
}

// 简易 LocalContext 封装(避免每处 import)
@Composable
fun LocalContextSafe(): android.content.Context = androidx.compose.ui.platform.LocalContext.current
