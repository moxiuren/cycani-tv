package com.cycitv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cycitv.data.api.CycaniApi
import com.cycitv.data.dto.SectionDto
import com.cycitv.data.dto.VideoDetailDto
import com.cycitv.ui.components.tvFocus
import com.cycitv.ui.theme.Primary
import com.cycitv.ui.theme.SurfaceHi
import com.cycitv.ui.theme.TextDim
import com.cycitv.ui.theme.TextMain

@Composable
fun DetailScreen(
    api: CycaniApi,
    videoId: Long,
    onPlay: (Long, Long, String, String, List<SectionDto>) -> Unit,
    onBack: () -> Unit,
) {
    val detail by produceState<VideoDetailDto?>(null, videoId) {
        value = try { api.videoDetail(videoId) } catch (e: Exception) { null }
    }
    val d = detail
    if (d == null) {
        Box(Modifier.fillMaxSize()) {
            Text("加载中…", color = TextDim, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
        }
        return
    }
    val players = d.playFrom
    var playerCode by remember { mutableStateOf(players.firstOrNull()?.code ?: "") }
    var sections by remember { mutableStateOf<List<SectionDto>>(emptyList()) }
    var loadingSections by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    androidx.compose.runtime.LaunchedEffect(videoId, playerCode) {
        if (playerCode.isEmpty()) return@LaunchedEffect
        loadingSections = true
        sections = try {
            api.allSections(videoId, playerCode)
        } catch (e: Exception) { emptyList() }
        loadingSections = false
        // 进入详情页默认聚焦内容区(线路 tab 或第一个选集格),避免焦点留在侧栏误触
        kotlinx.coroutines.delay(150)
        try { firstFocus.requestFocus() } catch (_: Exception) {}
    }

    Column(Modifier.fillMaxSize().padding(start = 36.dp, top = 24.dp, end = 36.dp)) {
        Row(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = d.coverUrl,
                contentDescription = d.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(220.dp).height(312.dp).background(Color(0xFF23232E), RoundedCornerShape(14.dp))
            )
            Column(Modifier.padding(start = 26.dp).weight(1f)) {
                Text(d.title, fontSize = 30.sp, color = TextMain)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Meta("${d.year ?: "-"}年", d.area, d.version, d.language)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Meta("评分 ${d.score ?: "-"}", "热度 ${d.hits}", "共 ${d.total} 话")
                }
                Text(
                    d.description,
                    fontSize = 16.sp,
                    color = TextDim,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    d.tags.take(6).forEach { t ->
                        Text(t, fontSize = 14.sp, color = Primary,
                            modifier = Modifier.background(Color(0x1A7AA2FF), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }
        // 线路
        if (players.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 8.dp)) {
                players.forEachIndexed { i, p ->
                item(key = p.code) {
                    Text(
                        p.title.ifBlank { p.code },
                        fontSize = 18.sp,
                        color = if (p.code == playerCode) Primary else TextDim,
                        modifier = Modifier
                            .tvFocus()
                            .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .clickable { playerCode = p.code }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
                }
            }
        }
        // 选集
        Box(Modifier.weight(1f)) {
            if (loadingSections) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    state = grid,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    sections.forEachIndexed { i, sec ->
                        item(key = sec.id) {
                            SectionCell(sec, Modifier
                                .tvFocus()
                                .then(if (i == 0 && players.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier)
                                .clickable {
                                onPlay(videoId, sec.id, playerCode, d.title, sections)
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Meta(vararg items: String) {
    items.filter { it.isNotBlank() }.forEach { m ->
        Text(m, fontSize = 16.sp, color = TextDim)
    }
}

@Composable
private fun SectionCell(s: SectionDto, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(SurfaceHi, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            s.title.ifBlank { "第${s.id}集" },
            fontSize = 17.sp,
            color = TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
