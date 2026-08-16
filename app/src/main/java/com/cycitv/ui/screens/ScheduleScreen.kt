package com.cycitv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cycitv.data.api.CycaniApi
import com.cycitv.data.dto.VideoCardDto
import com.cycitv.ui.components.LocalAppImageLoader
import com.cycitv.ui.components.tvFocus
import com.cycitv.ui.theme.Primary
import com.cycitv.ui.theme.Surface
import com.cycitv.ui.theme.SurfaceHi
import com.cycitv.ui.theme.TextDim
import com.cycitv.ui.theme.TextMain
import kotlinx.coroutines.delay

private val WEEK = listOf(1, 2, 3, 4, 5, 6, 7)
private val WEEK_NAMES = mapOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")
private val TIME_RE = Regex("""^(\d+)\|周[一二三四五六日](\d{1,2}):(\d{2})后$""")

/** remarks -> (当天更新分钟数, 最新集数字符串);无法解析返回 null */
private fun parseRemarks(remarks: String): Pair<Int?, String?> {
    val m = TIME_RE.find(remarks) ?: return null to null
    val h = m.groupValues[2].toInt()
    val min = m.groupValues[3].toInt()
    return (h * 60 + min) to m.groupValues[1]
}

private fun nowMinuteOfDay(): Int {
    val c = java.util.Calendar.getInstance()
    return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
}

private fun todayWeekday(): Int {
    val c = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
    return if (c == 1) 7 else c - 1
}

private fun fmtMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

private data class Entry(
    val v: VideoCardDto,
    val minute: Int?,     // 更新分钟(当天),null=无时间信息
    val episode: String?, // 最新集数字符串
    val raw: String,      // 原始 remarks
)

@Composable
fun ScheduleScreen(
    api: CycaniApi,
    onOpen: (Long) -> Unit,
) {
    val rows by produceState<Map<Int, List<VideoCardDto>>>(emptyMap(), api) {
        value = try {
            api.weekday().list.associate { it.weekday to it.videos }
        } catch (e: Exception) { emptyMap() }
    }
    val today = remember { todayWeekday() }
    var selected by remember { mutableIntStateOf(today) }
    var nowMinute by remember { mutableIntStateOf(nowMinuteOfDay()) }
    val listState = rememberLazyListState()
    val isToday = selected == today
    // 固定焦点行:焦点框停在可视区第 2 行(屏幕中间),滚动的是选项。
    // LazyColumn item 序列 = [now 头部, entries[0], entries[1]...],要让 entries[i] 停在可视 index 2:
    // 滚动目标 = (i + 1) - 2 = i - 1
    val FOCUS_ROW = 2
    var focusIndex by remember { mutableIntStateOf(0) }
    var focusInList by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    fun scrollTarget(i: Int) = (i + 1 - FOCUS_ROW).coerceAtLeast(0)

    // 每分钟刷新当前时间
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMinute = nowMinuteOfDay()
        }
    }

    // 只展示未完结的番:空 remarks(已完结)或"更新至"(完结)不显示;正在更新 / 即将放送保留
    val entries: List<Entry> = (rows[selected] ?: emptyList())
        .filter { it.remarks.isNotBlank() && !it.remarks.startsWith("更新至") }
        .map { v ->
            val (minute, ep) = parseRemarks(v.remarks)
            Entry(v, minute, ep, v.remarks)
        }
        .sortedWith(compareBy<Entry> { it.minute ?: Int.MAX_VALUE }.thenBy { it.v.title })

    // 进入页面时滚动到"现在"附近
    LaunchedEffect(selected, rows.isNotEmpty()) {
        if (isToday && entries.isNotEmpty()) {
            val idx = entries.indexOfFirst { it.minute != null && it.minute > nowMinute }
            val target = if (idx <= 0) 0 else idx - 1
            listState.scrollToItem(target)
        }
    }

    Row(Modifier.fillMaxSize().padding(start = 36.dp, top = 20.dp, end = 36.dp)) {
        // 左侧星期切换(竖排)
        Column(
            Modifier.width(110.dp).padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WEEK.forEach { w ->
                val isTodayTab = w == today
                Text(
                    (WEEK_NAMES[w] ?: "") + if (isTodayTab) " · 今" else "",
                    fontSize = 20.sp,
                    color = if (w == selected) Primary else if (isTodayTab) Color(0xFFFFD24A) else TextDim,
                    modifier = Modifier
                        .tvFocus()
                        .clickable { selected = w }
                        .background(if (w == selected) Color(0x1A7AA2FF) else Color.Transparent, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
        Box(Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (entries.isEmpty()) {
                Text("当日暂无更新", color = TextDim, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 时间轴头部:当前时间指示
                    item(key = "now") {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(Color(0xFFFF5A5A), RoundedCornerShape(5.dp))
                            )
                            Text(
                                "现在 ${fmtMinute(nowMinute)}",
                                fontSize = 20.sp,
                                color = Color(0xFFFF5A5A),
                                modifier = Modifier.padding(start = 12.dp)
                            )
                            Text(
                                if (isToday) " · 红色之前已更新,之后待更新" else " · 当日更新计划",
                                fontSize = 14.sp,
                                color = TextDim,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                    items(entries, key = { it.v.videoId }) { e ->
                        ScheduleRow(e, isToday, nowMinute, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    e: Entry,
    isToday: Boolean,
    nowMinute: Int,
    onOpen: (Long) -> Unit,
) {
    val loader = LocalAppImageLoader.current
    // 状态判定
    val updated = e.minute != null && (!isToday || e.minute <= nowMinute)
    val upcoming = e.minute != null && isToday && e.minute > nowMinute
    val finished = e.minute == null && (e.raw.isBlank() || e.raw.startsWith("更新至"))
    val soon = e.raw.contains("即将")
    val timeColor = when {
        upcoming -> TextDim
        else -> Primary
    }
    val statusText = when {
        e.minute != null && !isToday -> {
            val ep = if (e.episode != null) "更新至第${e.episode}集" else "已更新"
            "$ep · ${fmtMinute(e.minute)} 更新"
        }
        updated -> if (e.episode != null) "已更新至第${e.episode}集" else "已更新"
        upcoming -> "今日 ${fmtMinute(e.minute!!)} 更新" + (e.episode?.let { " · 已更至第${it}集" } ?: "")
        soon -> "即将放送"
        finished -> if (e.raw.isNotBlank()) e.raw else "已完结"
        else -> "待更新"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(scale = 1.03f, cornerRadius = 12.dp) // 全宽行轻微放大,过渡连贯
            .clickable { onOpen(e.v.videoId) }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 时间列 + 状态点
        Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (e.minute != null) fmtMinute(e.minute) else "—",
                fontSize = 18.sp,
                color = if (upcoming) TextDim else TextMain,
            )
            Text(
                if (updated) "已更新" else if (upcoming) "待更新" else if (soon) "未开播" else if (finished) "完结" else "",
                fontSize = 12.sp,
                color = if (updated) Primary else TextDim,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        // 状态圆点
        Box(
            Modifier
                .size(10.dp)
                .background(
                    when {
                        updated -> Primary
                        upcoming -> Color(0x66FFFFFF)
                        soon -> Color(0xFFFFD24A)
                        finished -> Color(0x66FFFFFF)
                        else -> Color(0x66FFFFFF)
                    },
                    RoundedCornerShape(5.dp)
                )
                .padding(horizontal = 18.dp)
        )
        // 封面
        if (loader != null && e.v.coverUrl.isNotBlank()) {
            AsyncImage(
                model = e.v.coverUrl,
                imageLoader = loader,
                contentDescription = e.v.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(54.dp)
                    .height(76.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface)
            )
        }
        // 标题 + 集数
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                e.v.title,
                fontSize = 19.sp,
                color = if (upcoming) TextDim else TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                statusText,
                fontSize = 14.sp,
                color = if (upcoming) TextDim else Primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
