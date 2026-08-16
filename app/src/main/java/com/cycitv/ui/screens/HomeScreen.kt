package com.cycitv.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cycitv.data.repository.HomeRepository
import com.cycitv.ui.components.VideoRow
import com.cycitv.ui.theme.TextDim

@Composable
fun HomeScreen(
    repo: HomeRepository,
    onOpen: (Long) -> Unit,
    onRefresh: () -> Unit = {},
) {
    var version by remember { mutableStateOf(0) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val state by produceState<HomeUiState>(HomeUiState.Loading, version, repo) {
        value = HomeUiState.Loading
        value = try {
            HomeUiState.Ready(repo.recommend(force = version > 0))
        } catch (e: Exception) {
            HomeUiState.Error(e.message ?: "加载失败").also { android.util.Log.e("CYC", "home load fail", e) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            is HomeUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is HomeUiState.Error -> Column(Modifier.align(Alignment.Center)) {
                Text("加载失败", color = TextDim, fontSize = 24.sp)
                Text(s.msg, color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            }
            is HomeUiState.Ready -> {
                if (s.rows.isEmpty()) {
                    Text("暂无数据", color = TextDim, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(s.rows, key = { "row-${it.id}-${it.name}" }) { row ->
                            VideoRow(row.name, row.videos, onOpen)
                        }
                        item {
                            Text(
                                "按菜单键刷新 · 按返回键回到顶部",
                                fontSize = 13.sp, color = TextDim,
                                modifier = Modifier.padding(vertical = 20.dp).fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val rows: List<com.cycitv.data.dto.RecommendRowDto>) : HomeUiState
    data class Error(val msg: String) : HomeUiState
}
