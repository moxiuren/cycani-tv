package com.cycitv.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.cycitv.App
import com.cycitv.data.api.CycaniApi
import com.cycitv.data.dto.VideoCardDto
import com.cycitv.data.PhoneInputServer
import com.cycitv.data.room.SearchEntity
import com.cycitv.ui.components.PosterCard
import com.cycitv.ui.components.QrCode
import com.cycitv.ui.components.tvFocus
import com.cycitv.ui.theme.OnPrimary
import com.cycitv.ui.theme.Primary
import com.cycitv.ui.theme.PrimaryDim
import com.cycitv.ui.theme.SurfaceHi
import com.cycitv.ui.theme.TextDim
import com.cycitv.ui.theme.TextMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

// 搜索页: 输入完全通过手机扫码完成(局域网 WebSocket), 遥控器只做浏览
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    api: CycaniApi,
    onOpen: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<VideoCardDto>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var history by remember { mutableStateOf<List<SearchEntity>>(emptyList()) }
    var jumpToResults by remember { mutableStateOf(false) }
    var showPhoneInput by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val flow = remember { MutableStateFlow("") }
    val app = LocalContext.current.applicationContext as App
    val resultFocus = remember { FocusRequester() }

    // 扫码输入服务器: 进入搜索页启动, 离开时停止
    DisposableEffect(Unit) {
        PhoneInputServer.start()
        onDispose { PhoneInputServer.stop() }
    }

    // 手机扫码: 边打字实时同步到电视输入框
    LaunchedEffect(Unit) {
        PhoneInputServer.keyword.collect { q ->
            query = q
            q.takeIf { it.isNotBlank() }?.let { scope.launch { flow.emit(it) } }
        }
    }

    // 手机扫码: 点击"搜索"按钮 → 电视立即搜索并跳到结果
    LaunchedEffect(Unit) {
        PhoneInputServer.submit.collect { q ->
            query = q
            scope.launch { flow.emit(q) }
            jumpToResults = true
            showPhoneInput = false
        }
    }

    fun runSearch(q: String) {
        scope.launch {
            searching = true
            errMsg = null
            try {
                val data = api.search(q, 1, 96)
                results = data
                if (data.isNotEmpty()) {
                    val db = app.db()
                    db.searchDao().upsert(SearchEntity(keyword = q))
                }
            } catch (e: Exception) {
                errMsg = "搜索失败，请重试"
                android.util.Log.e("CYC", "search fail q=$q", e)
            } finally {
                searching = false
            }
        }
    }

    fun setQuery(q: String) {
        query = q
        scope.launch { flow.emit(q) }
    }

    // 输入防抖 500ms 后自动搜索
    LaunchedEffect(Unit) {
        flow.debounce(500).collect { q ->
            if (q.isBlank()) {
                results = emptyList()
                errMsg = null
                searching = false
            } else {
                runSearch(q)
            }
        }
    }

    // 站方热词 + 本地搜索历史
    LaunchedEffect(Unit) {
        runCatching { hotWords = api.hotKeywords() }
        app.db().searchDao().all().collect { history = it }
    }

    // 按「搜索」/菜单键后,结果出来后把焦点跳到第一个卡片
    // (等一帧确保结果卡片已布局, 避免 FocusRequester 未 attach 时崩溃)
    LaunchedEffect(results, jumpToResults) {
        if (jumpToResults && results.isNotEmpty()) {
            android.view.Choreographer.getInstance().postFrameCallback { _ ->
                runCatching { resultFocus.requestFocus() }
            }
            jumpToResults = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 36.dp, top = 20.dp, end = 36.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) {
                    if (query.isNotBlank() && !jumpToResults) {
                        runSearch(query)
                        jumpToResults = true
                    }
                    true
                } else false
            }
    ) {
        Text("搜索", fontSize = 28.sp, color = TextMain)
        // 输入框 + 扫码输入入口
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .background(SurfaceHi, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    if (query.isEmpty()) "点「手机扫码输入」用手机搜，或点选下方热门词…" else query,
                    fontSize = 24.sp,
                    color = if (query.isEmpty()) TextDim else TextMain,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .tvFocus()
                    .clickable { showPhoneInput = !showPhoneInput }
                    .background(if (showPhoneInput) Primary else SurfaceHi, RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text(
                    "手机扫码输入",
                    fontSize = 20.sp,
                    color = if (showPhoneInput) OnPrimary else TextMain,
                )
            }
        }

        // 无输入时:搜索历史 + 站方热门词快捷入口
        if (query.isEmpty()) {
            if (history.isNotEmpty() || hotWords.isNotEmpty()) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (history.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("历史", fontSize = 15.sp, color = TextDim, modifier = Modifier.width(40.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                                items(history, key = { it.id }) { h ->
                                    Chip(h.keyword, Modifier.tvFocus().combinedClickable(
                                        onClick = { setQuery(h.keyword); jumpToResults = true },
                                        onLongClick = { scope.launch { app.db().searchDao().remove(h.id) } },
                                    ))
                                }
                            }
                            Chip("清空", Modifier.tvFocus().clickable { scope.launch { app.db().searchDao().clear() } })
                        }
                    }
                    if (hotWords.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("热门", fontSize = 15.sp, color = TextDim, modifier = Modifier.width(40.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                                items(hotWords) { w ->
                                    Chip(w, Modifier.tvFocus().clickable { setQuery(w); jumpToResults = true })
                                }
                            }
                        }
                    }
                }
            }
        }

        // 扫码输入浮层: 手机扫码 → 网页输入 → 电视自动搜索
        if (showPhoneInput) {
            val url = remember { PhoneInputServer.url() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .background(SurfaceHi, RoundedCornerShape(14.dp))
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                if (url != null) {
                    QrCode(content = url, size = 220.dp)
                } else {
                    Box(
                        Modifier
                            .size(220.dp)
                            .background(TextDim.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("无网络", color = TextDim, fontSize = 18.sp) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    Text("手机扫码输入搜索词", fontSize = 22.sp, color = TextMain)
                    Text(
                        "1. 用手机相机/微信扫一扫左侧二维码\n2. 在打开的网页输入番剧名并发送\n3. 电视会自动开始搜索，无需遥控器打字",
                        fontSize = 17.sp,
                        color = TextDim,
                        lineHeight = 26.sp,
                    )
                    Text(
                        "需手机与电视连接同一 WiFi", fontSize = 15.sp, color = PrimaryDim,
                    )
                }
            }
        }

        // 结果
        Box(Modifier.weight(1f)) {
            when {
                searching && results.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                errMsg != null -> Text(
                    errMsg!!, color = TextMain, fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                results.isEmpty() && query.isNotEmpty() -> Text(
                    "未找到「$query」", color = TextDim, fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                results.isEmpty() -> Text(
                    "输入关键词，或点选历史/热门词开始搜索", color = TextDim, fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(bottom = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    results.forEachIndexed { i, v ->
                        item(key = v.videoId) {
                            PosterCard(
                                v, { onOpen(v.videoId) },
                                modifier = if (i == 0) Modifier.focusRequester(resultFocus).tvFocus() else Modifier.tvFocus()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(SurfaceHi, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, fontSize = 17.sp, color = TextMain, maxLines = 1)
    }
}