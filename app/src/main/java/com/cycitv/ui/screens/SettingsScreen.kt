package com.cycitv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cycitv.App
import com.cycitv.AuthStore
import com.cycitv.data.PhoneInputServer
import com.cycitv.ui.components.QrCode
import com.cycitv.ui.theme.Primary
import com.cycitv.ui.theme.Surface
import com.cycitv.ui.theme.TextDim
import com.cycitv.ui.theme.TextMain
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    // 登录子页:true 显示扫码登录,false 显示设置主页
    var showLogin by remember { mutableStateOf(false) }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as App
    // 登录态(用户名):随退出/登录变化,remember 不够,用 observable 值
    var username by remember { mutableStateOf(app.authUsername) }

    // 监听登录结果:手机扫码登录成功后更新登录态
    LaunchedEffect(Unit) {
        PhoneInputServer.loginResult.collect { r ->
            if (r.ok) {
                app.authUsername = r.msg
                username = r.msg
                showLogin = false
            }
        }
    }

    if (showLogin) {
        LoginSubPage(
            onBack = { showLogin = false },
            onLoggedIn = { u ->
                app.authUsername = u
                username = u
                showLogin = false
            },
        )
    } else {
        Column(Modifier.fillMaxSize().padding(start = 36.dp, top = 20.dp)) {
            Text("设置", fontSize = 28.sp, color = TextMain)
            // 账号行
            Row(
                Modifier.padding(top = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (username != null) "已登录: $username" else "未登录",
                    fontSize = 20.sp,
                    color = if (username != null) Primary else TextDim,
                )
                if (username != null) {
                    Box(
                        Modifier
                            .padding(start = 24.dp)
                            .background(Surface, RoundedCornerShape(8.dp))
                            .clickable {
                                app.scope.launch {
                                    AuthStore.clear(app)
                                    app.api.token = null
                                    app.authUsername = null
                                }
                                username = null
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text("退出登录", color = TextMain, fontSize = 16.sp)
                    }
                } else {
                    Box(
                        Modifier
                            .padding(start = 24.dp)
                            .background(Primary, RoundedCornerShape(8.dp))
                            .clickable { showLogin = true }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text("登录账号", color = Color(0xFF08131F), fontSize = 16.sp)
                    }
                }
            }
            Text(
                "阿拉蕾TV v0.1.0\n\n" +
                    "操作说明\n" +
                    "· OK 确认 / 播放暂停\n" +
                    "· 左右键 快进快退 10 秒\n" +
                    "· 菜单键 播放页呼出选集\n" +
                    "· 数字键 播放页直达指定集\n" +
                    "· 返回键 逐级返回, 长按回首页\n\n" +
                    "数据源: 公开索引\n播放: Media3 (ExoPlayer)\n" +
                    "需登录账号后正常播放(扫码注册/登录)\n仅供个人学习使用",
                fontSize = 18.sp,
                color = TextDim,
                modifier = Modifier.padding(top = 24.dp)
            )
        }
    }
}

/** 扫码登录子页:电视显示二维码,手机扫码注册/登录,验证码/token 全程经局域网 WS */
@Composable
private fun LoginSubPage(onBack: () -> Unit, onLoggedIn: (String) -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    val url = remember { PhoneInputServer.loginUrl() }
    var status by remember { mutableStateOf("等待手机扫码…") }
    var statusOk by remember { mutableStateOf(false) }

    // 进入登录页启动扫码服务器;离开停止(搜索页也会启动,双保险互斥由 server 自己处理)
    DisposableEffect(Unit) {
        PhoneInputServer.start()
        onDispose { PhoneInputServer.stop() }
    }

    // 登录结果流:成功 → 保存 token + 更新登录态
    LaunchedEffect(Unit) {
        PhoneInputServer.loginResult.collect { r ->
            if (r.ok && r.token.isNotBlank()) {
                app.scope.launch {
                    AuthStore.save(app, r.token, r.username)
                    app.api.token = r.token
                    app.authUsername = r.username
                    status = "登录成功 ✓ 欢迎 $r.username"
                    statusOk = true
                }
                onLoggedIn(r.username)
            } else {
                status = r.msg.ifBlank { "登录失败" }
                statusOk = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 36.dp, top = 20.dp, end = 36.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("扫码登录", fontSize = 28.sp, color = TextMain)
            Text(
                "按 返回 退出登录页",
                fontSize = 15.sp,
                color = TextDim,
                modifier = Modifier.padding(start = 20.dp)
            )
        }
        if (url == null) {
            Text(
                "未检测到局域网地址，请确认电视已连接网络",
                color = Color(0xFFFF8080), fontSize = 18.sp,
                modifier = Modifier.padding(top = 40.dp)
            )
            return@Column
        }
        Row(
            Modifier.padding(top = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 二维码
            Box(
                Modifier
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                QrCode(url, size = 240.dp)
            }
            Column(
                Modifier.padding(start = 40.dp).width(500.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("1. 手机扫码打开登录页", color = TextMain, fontSize = 18.sp)
                Text("2. 已有账号直接登录；没有账号可注册（邮箱收验证码）", color = TextDim, fontSize = 16.sp)
                Text("3. 登录成功后本页自动关闭", color = TextDim, fontSize = 16.sp)
                Text(
                    if (statusOk) status else status,
                    color = if (statusOk) Color(0xFF7EE787) else (if (status.startsWith("登录成功")) Color(0xFF7EE787) else TextDim),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}