package com.cycitv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cycitv.data.dto.VideoCardDto
import com.cycitv.ui.theme.TextDim

@Composable
fun VideoRow(
    title: String,
    videos: List<VideoCardDto>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            fontSize = 24.sp,
            color = TextDim,
            modifier = Modifier.padding(start = 36.dp, top = 10.dp, bottom = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            videos.forEach { v ->
                item(key = v.videoId) {
                    Box(Modifier.width(140.dp)) {
                        PosterCard(
                            v = v,
                            onClick = { onOpen(v.videoId) },
                            modifier = Modifier.tvFocus()
                        )
                    }
                }
            }
        }
    }
}
