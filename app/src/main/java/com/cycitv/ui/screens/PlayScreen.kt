package com.cycitv.ui.screens

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import com.cycitv.App
import com.cycitv.data.dto.SectionDto
import com.cycitv.data.room.HistoryEntity
import com.cycitv.ui.components.tvFocus
import com.cycitv.ui.theme.Primary
import com.cycitv.ui.theme.Surface
import com.cycitv.ui.theme.TextDim
import com.cycitv.ui.theme.TextMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f) // 看动漫同款速度档

/** 创建播放器;software=true 时只允许软件 AVC 解码器(硬解崩溃时用于绕过 OMX.MS.AVC.Decoder 的 bug) */
private fun createPlayer(ctx: Context, software: Boolean, bandwidthMeter: DefaultBandwidthMeter? = null): ExoPlayer {
    val renderersFactory = DefaultRenderersFactory(ctx).apply {
        setEnableDecoderFallback(true)
        // 小米 MStar 硬解对 C2 异步队列(DynamicANWBuffer)适配有 bug,个别流 configure 即崩;强制同步队列规避
        forceDisableMediaCodecAsynchronousQueueing()
        if (software) {
            setMediaCodecSelector(object : MediaCodecSelector {
                override fun getDecoderInfos(
                    mimeType: String,
                    requiresSecureDecoder: Boolean,
                    requiresTunnelingDecoder: Boolean,
                ): List<MediaCodecInfo> {
                    val all = try {
                        MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (MimeTypes.VIDEO_H264 == mimeType) {
                        // 小米 MStar 硬解(OMX.MS.AVC)对个别流运行时崩溃,只保留 Google 软解
                        return all.filter { it.name.contains("c2.android.avc") || it.name.contains("OMX.google.h264") }
                    }
                    return all
                }
            })
        }
    }
    return ExoPlayer.Builder(ctx, renderersFactory)
        .apply { if (bandwidthMeter != null) setBandwidthMeter(bandwidthMeter) }
        .setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx).setDataSourceFactory(
                androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(20_000) // CDN 首字节慢(实测 ~8.4s), 默认 8s 超时导致无限重试恶性循环
                    .setReadTimeoutMs(20_000)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            )
        )
        .build().apply {
        playWhenReady = true
        repeatMode = Player.REPEAT_MODE_OFF
    }
}

@Composable
fun PlayScreen(
    videoId: Long,
    initialSectionId: Long,
    playerCode: String,
    animeTitle: String,
    initialSections: List<SectionDto> = emptyList(),
    directUrl: String? = null, // 调试/兜底:非空则绕过 play-url 直接播该地址
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val scope = rememberCoroutineScope()

    var sections by remember { mutableStateOf(initialSections) }
    var players by remember { mutableStateOf(listOf(playerCode)) }
    var sectionId by remember { mutableLongStateOf(initialSectionId) }
    var playing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // 控制条:默认隐藏,播放中全屏;按 OK 暂停并呼出,播放中几秒无操作自动隐藏
    var showControls by remember { mutableStateOf(false) }
    var showEpList by remember { mutableStateOf(false) }
    var showSpeedList by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var hideCountdown by remember { mutableLongStateOf(0L) } // 控制条自动隐藏倒计时(ms)
    var wasPlayingOnStop by remember { mutableStateOf(false) }   // 退后台前是否在播(HOME 恢复用)
    var playerCodeNow by remember { mutableStateOf(playerCode) }
    var speedIdx by remember { mutableIntStateOf(2) } // 1.0x
    var resolving by remember { mutableStateOf(false) } // 正在请求播放地址
    var buffering by remember { mutableStateOf(true) }  // 播放器缓冲中
    // 软解模式:进入时若本集已记录"需要软解"(硬解崩溃过),直接走软解
    var softwareDecode by remember { mutableStateOf(initialSectionId in app.softDecodeSections) }
    // 网络带宽表:供"视频信息"按钮显示实时网速
    val bandwidthMeter = remember { DefaultBandwidthMeter.Builder(ctx).build() }
    var bandwidthKbps by remember { mutableStateOf(0L) } // 由 ticker 刷新
    // 自管控制条焦点(Compose 焦点树在 AnimatedContent 嵌套下不可靠):0=播放/暂停 1=下一话 2=重播 3=选集 4=速度 5=信息 6=进度条
    var ctrlSel by remember { mutableIntStateOf(0) }
    // 弹层自管焦点:选集/速度弹层的选项行(Compose 焦点树被 PlayerView 阻断,必须自管)
    var panelSel by remember { mutableIntStateOf(0) }
    var errSel by remember { mutableIntStateOf(0) } // 错误弹窗:0=重试 1=退出
    // 进度/时长由 ticker 驱动,保证进度条和时间为实时值
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    // OSD 反馈图标(OK 切换播放/暂停时中央短暂显示)
    var osdIcon by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) } // 播放器实际暂停中(非"加载流程"标志)

    // seek 提示短暂显示后消失
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(1200)
            seekHint = null
        }
    }

    // OSD 图标短暂显示后消失
    LaunchedEffect(osdIcon) {
        if (osdIcon != null) {
            delay(1000)
            osdIcon = null
        }
    }

    // 可重建播放器:硬解崩溃时切软解重建
    var player by remember { mutableStateOf(createPlayer(ctx, softwareDecode, bandwidthMeter)) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            posMs = player.currentPosition
            durMs = player.duration
            paused = !player.isPlaying
            bandwidthKbps = bandwidthMeter.bitrateEstimate / 1000 // 实时网速 Kbps
            // 控制条自动隐藏:播放中 5 秒无按键操作
            if (showControls && player.isPlaying) {
                hideCountdown += 500
                if (hideCountdown >= 5000) {
                    showControls = false
                    hideCountdown = 0
                }
            } else {
                hideCountdown = 0
            }
        }
    }

    DisposableEffect(player) {
        val p = player // 捕获当前值:不能读最新 state,否则切软解重建时会误释放新播放器
        onDispose { p.release() }
    }

    // 播放时保持屏幕常亮;退后台(HOME)暂停播放并释放常亮,回前台恢复
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val win = (ctx as? android.app.Activity)?.window
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (player.isPlaying) {
                        wasPlayingOnStop = true
                        player.pause()
                        showControls = false
                    }
                    win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                Lifecycle.Event.ON_START -> {
                    if (wasPlayingOnStop) {
                        wasPlayingOnStop = false
                        player.play()
                        osdIcon = "▶"
                    }
                    win?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        win?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 播放指定集(含线路轮换重试)
    fun loadSection(id: Long, playerIdx: Int = 0) {
        scope.launch {
            playing = true
            resolving = true
            errorMsg = null
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val url = app.api.playUrl(id).url
                android.util.Log.e("CYC", "loadSection playUrl ${android.os.SystemClock.elapsedRealtime() - t0}ms url=${url.take(80)}")
                if (url.isBlank()) throw IllegalStateException("播放地址为空")
                resolving = false
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
                sectionId = id
                // 断点续播
                val hist = try { app.db().historyDao().bySection(id) } catch (_: Exception) { null }
                if (hist != null && hist.progressMs > 10_000 && hist.progressMs < hist.durationMs - 10_000) {
                    player.seekTo(hist.progressMs)
                }
            } catch (e: Exception) {
                val next = playerIdx + 1
                if (next < players.size) {
                    playerCodeNow = players[next]
                    sections = try { app.api.allSections(videoId, playerCodeNow) } catch (_: Exception) { sections }
                    loadSection(id, next)
                } else {
                    errorMsg = if (e is com.cycitv.data.api.ApiException && (e.apiCode == 401 || e.apiCode == 1002)) {
                        "未登录或登录已过期\n请到「设置 → 登录账号」扫码登录后重试"
                    } else {
                        "播放失败: ${e.message}"
                    }
                    playing = false
                }
            }
        }
    }

    // 调试/兜底:直接播放指定地址(绕过 play-url)
    fun loadDirect(url: String) {
        scope.launch {
            playing = true
            resolving = true
            errorMsg = null
            try {
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
                resolving = false
                sectionId = initialSectionId
            } catch (e: Exception) {
                errorMsg = "播放失败: ${e.message}"
                playing = false
                resolving = false
            }
        }
    }

    // 硬解崩溃 → 切软解重建播放器并重放当前集
    fun switchToSoftwareDecoder() {
        if (softwareDecode) return
        softwareDecode = true
        player.release()
        player = createPlayer(ctx, software = true, bandwidthMeter)
        if (directUrl != null) loadDirect(directUrl) else loadSection(sectionId)
    }

    // 播放器状态监听:缓冲中 / 播放出错(硬解崩溃自动切软解重放)
    DisposableEffect(player) {
        val p = player // 捕获当前值
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
            }
            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                if (!softwareDecode && error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) {
                    // 小米电视硬解偶发崩溃:本集记入软解名单,切软解重放。
                    // 注意:此回调在播放器内部线程,所有播放器操作必须回主线程执行
                    app.softDecodeSections.add(sectionId)
                    scope.launch {
                        try { switchToSoftwareDecoder() } catch (_: Exception) {}
                    }
                } else {
                    errorMsg = "播放出错: ${error.errorCodeName}\n${error.message?.take(160) ?: ""}"
                }
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }

    // 加载选集与线路:详情页已传选集则立即播放(只差一个播放地址请求),后台再刷新线路/选集
    LaunchedEffect(videoId) {
        if (directUrl != null) {
            // 调试直达:直接播指定地址,不请求 play-url
            loadDirect(directUrl)
            return@LaunchedEffect
        }
        if (sections.isNotEmpty()) {
            loadSection(initialSectionId)
            // 后台刷新最新线路与选集(播放不等待)
            scope.launch {
                try {
                    val d = app.api.videoDetail(videoId)
                    val ps = d.playFrom.map { it.code }.ifEmpty { listOf(playerCode) }
                    if (ps.none { it == playerCodeNow }) playerCodeNow = ps.first()
                    val secs = app.api.allSections(videoId, playerCodeNow)
                    if (secs.isNotEmpty()) {
                        sections = secs
                        if (sections.none { it.id == sectionId }) loadSection(sections.first().id)
                    }
                } catch (_: Exception) {}
            }
            return@LaunchedEffect
        }
        // 无传入选集:完整加载后播放
        val d = try { app.api.videoDetail(videoId) } catch (_: Exception) { null }
        players = d?.playFrom?.map { it.code }?.ifEmpty { listOf(playerCode) } ?: listOf(playerCode)
        if (players.none { it == playerCodeNow }) playerCodeNow = players.first()
        sections = try { app.api.allSections(videoId, playerCodeNow) } catch (_: Exception) { emptyList() }
        if (sections.isNotEmpty()) {
            loadSection(initialSectionId)
        } else {
            errorMsg = "选集加载失败"
        }
    }

    // 周期上报进度
    LaunchedEffect(sectionId) {
        while (true) {
            delay(10_000)
            val pos = player.currentPosition
            val dur = player.duration
            if (dur > 0 && pos > 0) {
                try {
                    app.db().historyDao().upsert(
                        HistoryEntity(
                            sectionId = sectionId,
                            animeId = videoId,
                            title = animeTitle,
                            sectionTitle = sections.firstOrNull { it.id == sectionId }?.title ?: "",
                            progressMs = pos,
                            durationMs = dur,
                        )
                    )
                } catch (_: Exception) {}
            }
        }
    }

    // 播放结束自动下一集
    LaunchedEffect(Unit) {
        var last = -1L
        while (true) {
            delay(1500)
            val pos = player.currentPosition
            val dur = player.duration
            if (pos > 0 && dur > 0 && pos > dur - 8000 && last != sectionId) {
                last = sectionId
                val idx = sections.indexOfFirst { it.id == sectionId }
                val next = sections.getOrNull(idx + 1)
                if (next != null) {
                    sectionId = next.id
                    loadSection(next.id)
                }
            }
        }
    }

    fun seek(deltaMs: Long) {
        player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))
        val s = deltaMs / 1000
        seekHint = if (deltaMs > 0) "+$s 秒" else "$s 秒"
    }

    fun goEpisode(delta: Int) {
        val idx = sections.indexOfFirst { it.id == sectionId }
        val target = sections.getOrNull(idx + delta) ?: return
        sectionId = target.id
        loadSection(target.id)
    }

    // 重播当前集:回到开头从头开始
    fun replayCurrent() {
        showSpeedList = false
        player.seekTo(0)
        if (!player.isPlaying) player.play()
        osdIcon = "↻"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                when {
                    code == KeyEvent.KEYCODE_BACK ->
                        if (showEpList) { showEpList = false; true }
                        else if (showSpeedList) { showSpeedList = false; true }
                        else if (showInfo) { showInfo = false; true }
                        else if (showControls) { showControls = false; true }
                        else { onExit(); true }

                    errorMsg != null -> when (code) {
                        // 错误弹窗自管焦点:LEFT/RIGHT 切换 重试/退出,OK 执行
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { errSel = 1 - errSel; true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                            if (errSel == 0) {
                                errorMsg = null
                                loadSection(sectionId)
                            } else {
                                onExit()
                            }
                            true
                        }
                        else -> false
                    }

                    // 右侧选集弹层导航(自管焦点:UP/DOWN 选择,OK 确定)
                    showEpList -> when (code) {
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M,
                        KeyEvent.KEYCODE_DPAD_LEFT -> { showEpList = false; true }
                        KeyEvent.KEYCODE_DPAD_UP -> { panelSel = (panelSel - 1).coerceAtLeast(0); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { panelSel = (panelSel + 1).coerceAtMost(sections.size - 1); true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                            sections.getOrNull(panelSel)?.let { s ->
                                sectionId = s.id
                                loadSection(s.id)
                            }
                            showEpList = false
                            true
                        }
                        else -> false // 放行给弹层自身焦点系统(上下移动/OK 选择)
                    }

                    // 右侧速度弹层导航(自管焦点:UP/DOWN 选择,OK 确定)
                    showSpeedList -> when (code) {
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M,
                        KeyEvent.KEYCODE_DPAD_LEFT -> { showSpeedList = false; true }
                        KeyEvent.KEYCODE_DPAD_UP -> { panelSel = (panelSel - 1).coerceAtLeast(0); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { panelSel = (panelSel + 1).coerceAtMost(SPEEDS.size - 1); true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                            speedIdx = panelSel
                            player.setPlaybackSpeed(SPEEDS[speedIdx])
                            showSpeedList = false
                            true
                        }
                        else -> false
                    }

                    // 右侧信息弹层导航
                    showInfo -> when (code) {
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            showInfo = false; true
                        }
                        else -> false
                    }

                    // 控制条呼出时自管焦点(方向键移动选择,OK 执行;操作即重置自动隐藏倒计时)
                    showControls -> when (code) {
                        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M -> { showControls = false; true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            hideCountdown = 0
                            if (ctrlSel == 6) seek(-10_000) else ctrlSel = (ctrlSel - 1).coerceAtLeast(0)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            hideCountdown = 0
                            if (ctrlSel == 6) seek(10_000) else ctrlSel = (ctrlSel + 1).coerceAtMost(6)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> { hideCountdown = 0; if (ctrlSel == 6) ctrlSel = 0; true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { hideCountdown = 0; ctrlSel = if (ctrlSel == 6) 0 else 6; true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                            hideCountdown = 0
                            when (ctrlSel) {
                                0 -> if (player.isPlaying) { player.pause(); osdIcon = "⏸" }
                                else { player.play(); osdIcon = "▶" }
                                1 -> { showEpList = false; showSpeedList = false; showInfo = false; goEpisode(1) }
                                2 -> replayCurrent()
                                3 -> {
                                    panelSel = sections.indexOfFirst { it.id == sectionId }.coerceAtLeast(0)
                                    showEpList = true; showSpeedList = false; showInfo = false
                                }
                                4 -> {
                                    panelSel = speedIdx
                                    showSpeedList = true; showEpList = false; showInfo = false
                                }
                                5 -> { showInfo = true; showEpList = false; showSpeedList = false }
                                6 -> seek(0)
                            }
                            true
                        }
                        else -> false
                    }

                    // 默认态(控制条隐藏):OK 暂停并呼出控制条;UP/MENU 只呼出不暂停;左右 seek
                    else -> when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { seek(-10_000); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { seek(10_000); true }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M -> {
                            showControls = true; true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                            showControls = true
                            if (player.isPlaying) { player.pause(); osdIcon = "⏸" }
                            true
                        }
                        KeyEvent.KEYCODE_0 -> { sectionId = sections.firstOrNull()?.id ?: sectionId; true }
                        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                            val n = ev.nativeKeyEvent.keyCode - KeyEvent.KEYCODE_1
                            sections.getOrNull(n)?.let { sectionId = it.id }
                            true
                        }
                        else -> false
                    }
                }
            }
    ) {
        AndroidView(
            factory = { c: Context ->
                PlayerView(c).apply {
                    this.player = player
                    useController = false
                    // 禁止 PlayerView 抢焦点,否则按键事件不进入 Compose
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { view -> view.player = player }, // 切软解重建播放器后重新绑定
            modifier = Modifier.fillMaxSize()
        )

        // 顶部信息(随控制条一起显隐,全屏播放时保持干净)
        if (showControls) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp)
            ) {
                Text(animeTitle, color = TextMain, fontSize = 20.sp, maxLines = 1)
                Text(
                    sections.firstOrNull { it.id == sectionId }?.title ?: "",
                    color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1
                )
            }
        }

        // 右侧选集弹层(竖列,参照看动漫:按选集按钮后右侧出现整排直列选项)
        if (showEpList) {
            val epListState = rememberLazyListState()
            // 打开时定位到当前集(选集列表可能很长,默认从第 1 集开始不合理)
            LaunchedEffect(Unit) {
                val idx = sections.indexOfFirst { it.id == sectionId }.coerceAtLeast(0)
                epListState.scrollToItem(idx)
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(0.85f)
                    .width(300.dp)
                    .background(Color(0xE6121218), RoundedCornerShape(12.dp))
                    .padding(vertical = 16.dp)
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                    Text("选集", color = TextMain, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
                    LazyColumn(state = epListState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        itemsIndexed(sections) { i, s ->
                            val sel = i == panelSel // 自管焦点高亮
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { sectionId = s.id; loadSection(s.id); showEpList = false }
                                    .height(46.dp)
                                    .background(
                                        if (sel) Primary else Surface,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    (if (s.id == sectionId) "● " else "") + (s.title.ifBlank { "${s.id}" }),
                                    color = when {
                                        sel -> Color(0xFF08131F)
                                        s.id == sectionId -> Primary
                                        else -> TextMain
                                    },
                                    fontSize = 15.sp, maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // 右侧速度弹层(竖列,参照看动漫:0.5/0.75/1.0/1.25/1.5/2.0/3.0/4.0)
        if (showSpeedList) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(220.dp)
                    .background(Color(0xE6121218), RoundedCornerShape(12.dp))
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("播放速度", color = TextMain, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
                SPEEDS.forEachIndexed { i, sp ->
                    val sel = i == panelSel // 自管焦点高亮
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .clickable {
                                speedIdx = i
                                player.setPlaybackSpeed(sp)
                                showSpeedList = false
                            }
                            .height(44.dp)
                            .background(if (sel) Primary else Surface, RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            "${sp}x",
                            color = if (sel) Color(0xFF08131F) else TextMain,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }

        // 右侧信息浮层(网速/画质等,参照看动漫按钮6)
        if (showInfo) {
            val idx = sections.indexOfFirst { it.id == sectionId }
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(320.dp)
                    .background(Color(0xE6121218), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Text("视频信息", color = TextMain, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
                InfoRow("片名", animeTitle)
                InfoRow("当前集", sections.getOrNull(idx)?.title ?: "${idx + 1}")
                InfoRow("画质", if (player.videoSize.height > 1080) "${player.videoSize.height}p 高清" else if (player.videoSize.height > 0) "${player.videoSize.height}p" else "未知")
                InfoRow("网速", if (bandwidthKbps > 0) "${bandwidthKbps / 1000.0} Mbps" else "计算中…")
                InfoRow("倍速", "${SPEEDS[speedIdx]}x")
                InfoRow("播放状态", if (player.isPlaying) "▶ 播放中" else "⏸ 已暂停")
                Text("按 返回 关闭", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }

        // 底部控制条(隐藏态全屏;OK/UP 呼出,播放中 5 秒无操作自动隐藏)
        if (showControls) {
            val idx = sections.indexOfFirst { it.id == sectionId }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xE6121218))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CtrlButton(
                        if (player.isPlaying) "⏸ 暂停" else "▶ 播放",
                        selected = ctrlSel == 0
                    ) {
                        if (player.isPlaying) { player.pause(); osdIcon = "⏸" }
                        else { player.play(); osdIcon = "▶" }
                    }
                    CtrlButton("下一话", enabled = idx in 0 until sections.size - 1, selected = ctrlSel == 1) {
                        showEpList = false; showSpeedList = false; showInfo = false; goEpisode(1)
                    }
                    CtrlButton("重播", selected = ctrlSel == 2) { replayCurrent() }
                    CtrlButton("选集", selected = ctrlSel == 3) {
                        showEpList = true; showSpeedList = false; showInfo = false
                    }
                    CtrlButton("${SPEEDS[speedIdx]}x", selected = ctrlSel == 4) {
                        showSpeedList = true; showEpList = false; showInfo = false
                    }
                    CtrlButton("信息", selected = ctrlSel == 5) {
                        showInfo = true; showEpList = false; showSpeedList = false
                    }
                }
                // 进度条行:时间文字左/进度条中/总时长右,同级并排(与看动漫一致)
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(formatMs(posMs), color = TextDim, fontSize = 13.sp)
                    SeekBar(
                        pos = posMs, dur = durMs,
                        selected = ctrlSel == 6,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                    )
                    Text(formatMs(durMs), color = TextDim, fontSize = 13.sp)
                }
            }
        }

        // 加载/缓冲指示
        if (resolving || buffering) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.CircularProgressIndicator(color = Primary)
                Text(
                    if (resolving) "正在获取播放地址…" else "加载中…",
                    color = TextDim, fontSize = 18.sp,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        }

        // 错误弹窗:居中显示,提供重试/退出(自管焦点 errSel)
        errorMsg?.let { msg ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xEE121218), RoundedCornerShape(16.dp))
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("播放失败", color = Color(0xFFFF8080), fontSize = 26.sp)
                Text(
                    msg, color = TextDim, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 12.dp), textAlign = TextAlign.Center
                )
                Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    CtrlButton("重试", selected = errSel == 0) {
                        errorMsg = null
                        loadSection(sectionId)
                    }
                    CtrlButton("退出", selected = errSel == 1) { onExit() }
                }
            }
        }

        // 暂停角标(控制条隐藏时暂停,提示当前为暂停态)
        if (paused && !showControls && errorMsg == null && !resolving && !buffering) {
            Text(
                "已暂停",
                color = TextDim, fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 130.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // seek 视觉反馈(控制条常驻,仍保留中央提示)
        seekHint?.let {
            Text(
                it,
                color = Color.White, fontSize = 40.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 26.dp, vertical = 12.dp)
            )
        }

        // OK 切换播放/暂停的中央 OSD 图标
        osdIcon?.let {
            Text(
                it,
                color = Color.White, fontSize = 72.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(18.dp))
                    .padding(horizontal = 44.dp, vertical = 22.dp)
            )
        }
    }
}

@Composable
private fun CtrlButton(
    text: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clickable(enabled = enabled, onClick = onClick)
            .height(50.dp)
            .background(
                if (selected) Primary else Surface,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = when {
                selected -> Color(0xFF08131F)
                enabled -> TextMain
                else -> TextDim
            },
            fontSize = 16.sp
        )
    }
}

/** 信息浮层行:左侧标签(暗色),右侧值(亮色) */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextDim, fontSize = 14.sp, modifier = Modifier.width(60.dp))
        Text(
            value, color = TextMain, fontSize = 15.sp,
            modifier = Modifier.weight(1f), maxLines = 1
        )
    }
}

/** 进度条:selected 时显示白色外框(自管焦点,不依赖 Compose 焦点系统);LEFT/RIGHT seek 由外部处理 */
@Composable
private fun SeekBar(
    pos: Long,
    dur: Long,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }
    Box(
        modifier
            .height(44.dp)
            .drawWithContent {
                drawContent()
                val h = size.height
                val y = h / 2
                val trackH = 6.dp.toPx()
                drawRoundRect(
                    Color(0x66FFFFFF),
                    topLeft = Offset(0f, y - trackH / 2),
                    size = Size(size.width, trackH),
                    cornerRadius = CornerRadius(trackH / 2)
                )
                val ratio = if (dur > 0) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                if (ratio > 0) {
                    drawRoundRect(
                        Primary,
                        topLeft = Offset(0f, y - trackH / 2),
                        size = Size(size.width * ratio, trackH),
                        cornerRadius = CornerRadius(trackH / 2)
                    )
                }
                // 播放头
                val thumbX = size.width * ratio
                drawCircle(Primary, radius = 9.dp.toPx(), center = Offset(thumbX, y))
                if (selected) {
                    val inset = strokePx / 2
                    drawRoundRect(
                        Color.White,
                        style = Stroke(width = strokePx),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                    )
                }
            }
    ) { }
}

private fun formatMs(ms: Long): String {
    val safe = ms.coerceAtLeast(0) // 播放器未就绪时 duration/position 可能是 -1(或极小负值)
    val s = safe / 1000
    return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}
