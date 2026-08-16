package com.cycitv.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

sealed interface Screen {
    data object Home : Screen
    data object Category : Screen
    data object Schedule : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Detail(val videoId: Long) : Screen
    data class Play(
        val videoId: Long,
        val sectionId: Long,
        val playerCode: String,
        val animeTitle: String,
        val sections: List<com.cycitv.data.dto.SectionDto> = emptyList(),
        val directUrl: String? = null, // 调试/兜底:绕过 play-url 接口直接播该地址(支持 file://)
    ) : Screen
}

class NavController(initial: Screen = Screen.Home) {
    val current = mutableStateOf<Screen>(initial)
    private val stack = ArrayDeque<Screen>()

    fun open(s: Screen) {
        if (s != current.value) {
            stack.addLast(current.value)
            current.value = s
        }
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        current.value = stack.removeLast()
        return true
    }

    fun reset() {
        stack.clear()
        current.value = Screen.Home
    }
}

@Composable
fun rememberNavController(): NavController = remember { NavController(PlayIntent ?: Screen.Home) }

/** adb 调试直达播放页的初始 Screen;正常启动为 null */
var PlayIntent: Screen? = null
