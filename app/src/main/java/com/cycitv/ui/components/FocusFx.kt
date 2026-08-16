package com.cycitv.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cycitv.ui.theme.Primary

/**
 * TV 焦点效果:聚焦时弹性放大(spring 过冲 = Q 弹)+ 蓝色发光描边。
 * 发光由多层半透明描边叠加模拟;主描边细而亮,居中于边界,不压内容。
 */
@Composable
fun Modifier.tvFocus(
    scale: Float = 1.07f,
    cornerRadius: Dp = 12.dp,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
        // 短促弹性:140ms 过冲 easing(Q 弹)立即完成,无长动画追赶感
    val s by animateFloatAsState(
        targetValue = if (isFocused) scale else 1f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 140,
            easing = androidx.compose.animation.core.CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f),
        ),
        label = "focus-scale",
    )
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }
    val cornerPx = with(LocalDensity.current) { cornerRadius.toPx() }
    return Modifier
        .graphicsLayer { scaleX = s; scaleY = s }
        .focusable(interactionSource = interaction)
        .drawWithContent {
            drawContent()
            // 静态光晕:只在焦点状态变化时重绘一次,不做每帧模糊动画(性能关键)
            if (isFocused) {
                val blurPx = 1.5.dp.toPx()
                drawIntoCanvas { canvas ->
                    val glowPaint = android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = strokePx * 1.2f
                        isAntiAlias = true
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f).toArgb()
                        maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    val inset = strokePx * 0.4f
                    val path = android.graphics.Path().apply {
                        addRoundRect(
                            android.graphics.RectF(-inset, -inset, size.width + inset, size.height + inset),
                            cornerPx, cornerPx, android.graphics.Path.Direction.CW
                        )
                    }
                    canvas.nativeCanvas.drawPath(path, glowPaint)
                }
            }
        }
}

fun Modifier.focusRing(focused: Boolean): Modifier =
    then(if (focused) Modifier.border(3.dp, Primary) else Modifier)
