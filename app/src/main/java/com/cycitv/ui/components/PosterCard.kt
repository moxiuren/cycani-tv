package com.cycitv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cycitv.data.dto.VideoCardDto
import com.cycitv.ui.theme.TextDim
import com.cycitv.ui.theme.TextMain

/**
 * 卡片: 宽度由所在容器决定(网格=列宽, 行=传 coverWidth),
 * 图片固定 宽:高 = 4:5 竖版比例, 全站视觉统一。
 */
@Composable
fun PosterCard(
    v: VideoCardDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverWidth: Int? = null, // null=填满父容器; 传值=固定宽度(如首页横向行)
    coverRatio: Float = 1.25f, // 高/宽 = 4:5, 全站统一
) {
    val loader = LocalAppImageLoader.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / coverRatio) // 宽:高 = 4:5
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF23232E))
        ) {
            if (loader != null) {
                AsyncImage(
                    model = v.coverUrl,
                    imageLoader = loader,
                    contentDescription = v.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
            }
            // 左上: 评分
            v.score?.takeIf { it > 0 }?.let {
                Box(Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text("$it", color = Color(0xFFFFD24A), fontSize = 13.sp)
                }
            }
            // 右下: 话数
            if (v.total > 0) {
                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text("${v.total}话", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        // 标题: 两行完整显示 + 年份徽章
        Text(
            v.title,
            color = TextMain,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp)
        )
        v.year?.takeIf { it > 0 }?.let {
            Row(
                Modifier.padding(start = 4.dp, top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.background(TextDim.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Text("$it", color = TextDim, fontSize = 11.sp)
                }
            }
        }
    }
}