package com.cycitv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 用 zxing 生成二维码并绘制为 Compose 图形.
 * 纯 Java 实现, 无 native 依赖, 兼容 32 位电视.
 */
@Composable
fun QrCode(
    content: String,
    size: Dp = 260.dp,
    foreground: Color = Color(0xFF15181E),
    background: Color = Color.White,
) {
    val bitMatrix = remember(content) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
        }.getOrNull()
    } ?: return

    val pxSize = bitMatrix.width
    Canvas(Modifier.size(size)) {
        drawRect(background, size = this.size)
        val cell = (this.size.minDimension) / pxSize
        val offset = (this.size.minDimension - cell * pxSize) / 2f
        for (y in 0 until pxSize) {
            for (x in 0 until pxSize) {
                if (bitMatrix.get(x, y)) {
                    drawRect(
                        color = foreground,
                        topLeft = Offset(offset + x * cell, offset + y * cell),
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}