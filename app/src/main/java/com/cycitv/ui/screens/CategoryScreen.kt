package com.cycitv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cycitv.data.api.CycaniApi
import com.cycitv.data.dto.VideoCardDto
import com.cycitv.ui.components.PosterCard
import com.cycitv.ui.components.tvFocus
import com.cycitv.ui.theme.Primary
import com.cycitv.ui.theme.SurfaceHi
import com.cycitv.ui.theme.TextDim
import com.cycitv.ui.theme.TextMain
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val SORTS = listOf(
    "hits" to "热度",
    "score" to "评分",
    "update_time" to "更新",
)

@Composable
fun CategoryScreen(
    api: CycaniApi,
    onOpen: (Long) -> Unit,
) {
    val zones by produceState<List<com.cycitv.data.dto.ZoneDto>>(emptyList(), api) {
        value = try { api.zones() } catch (e: Exception) { emptyList() }
    }
    var zoneId by remember { mutableIntStateOf(zones.firstOrNull()?.id ?: 1) }
    var orderBy by remember { mutableStateOf("hits") }
    var page by remember { mutableIntStateOf(1) }
    var videos by remember { mutableStateOf<List<VideoCardDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var end by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()

    fun load(reset: Boolean) {
        scope.launch {
            loading = true
            errMsg = null
            try {
                val p = if (reset) 1 else page + 1
                val data = api.videos(zoneId, p, 48, orderBy)
                videos = if (reset) data.list else videos + data.list
                page = p
                end = data.list.size < 48
            } catch (e: Exception) {
                errMsg = "加载失败: ${e.message ?: e.javaClass.simpleName}，按菜单键重试"
                end = true
                android.util.Log.e("CYC", "cat load fail", e)
            } finally {
                loading = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        load(true)
    }

    // 滚动接近底部时自动加载下一页(提前 2 行触发),无需手动按"加载更多"
    val shouldLoadMore by remember {
        androidx.compose.runtime.derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            last.index >= grid.layoutInfo.totalItemsCount - 12
        }
    }
    androidx.compose.runtime.LaunchedEffect(shouldLoadMore, loading, end) {
        if (shouldLoadMore && !loading && !end) load(false)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 36.dp, top = 20.dp, end = 36.dp)) {
            Text("分类浏览", fontSize = 28.sp, color = TextMain)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp)) {
                zones.forEach { z ->
                    item(key = "zone-${z.id}") {
                        Chip(z.name, z.id == zoneId, Modifier.tvFocus().onClick { zoneId = z.id; videos = emptyList(); load(true) })
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                SORTS.forEach { (k, label) ->
                    item(key = "sort-$k") {
                        Chip(label, k == orderBy, Modifier.tvFocus().onClick { orderBy = k; videos = emptyList(); load(true) })
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                if (videos.isEmpty() && loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (videos.isEmpty() && errMsg != null) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(errMsg!!, color = TextDim, fontSize = 18.sp)
                        Text(
                            "按菜单键重试",
                            color = Primary,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        state = grid,
                        contentPadding = PaddingValues(bottom = 30.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        videos.forEach { v ->
                            item(key = v.videoId) {
                                PosterCard(
                                    v, { onOpen(v.videoId) },
                                    modifier = Modifier.tvFocus(),
                                )
                            }
                        }
                        if (!end) {
                            item {
                                LoadMoreChip(loading, Modifier.tvFocus().onClick { load(false) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier) {
    Text(
        label,
        fontSize = 19.sp,
        color = if (selected) Primary else TextDim,
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

@Composable
private fun LoadMoreChip(loading: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (loading) "加载中…" else "加载更多 (OK)",
            fontSize = 18.sp, color = TextDim
        )
    }
}

private fun Modifier.onClick(onClick: () -> Unit): Modifier =
    this.then(clickable(onClick = onClick))
