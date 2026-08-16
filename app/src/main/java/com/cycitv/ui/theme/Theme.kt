package com.cycitv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Primary = Color(0xFF7AA2FF)
val PrimaryDim = Color(0x337AA2FF)
val OnPrimary = Color(0xFF08131F)
val Surface = Color(0xFF1D1D26)
val SurfaceHi = Color(0xFF2A2A36)
val TextMain = Color(0xFFF2F3F7)
val TextDim = Color(0xFF9A9CAF)
val Backdrop = Color(0xFF121218)

private val Scheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color(0xFF08131F),
    secondary = PrimaryDim,
    background = Backdrop,
    surface = Surface,
    onBackground = TextMain,
    onSurface = TextMain,
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = TextDim,
)

@Composable
fun CycTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
