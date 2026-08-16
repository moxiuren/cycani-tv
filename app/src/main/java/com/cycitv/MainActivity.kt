package com.cycitv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cycitv.ui.components.LocalAppImageLoader
import com.cycitv.ui.components.tvFocus
import com.cycitv.ui.nav.NavController
import com.cycitv.ui.nav.PlayIntent
import com.cycitv.ui.nav.Screen
import com.cycitv.ui.nav.rememberNavController
import com.cycitv.ui.screens.CategoryScreen
import com.cycitv.ui.screens.DetailScreen
import com.cycitv.ui.screens.HomeScreen
import com.cycitv.ui.screens.PlayScreen
import com.cycitv.ui.screens.ScheduleScreen
import com.cycitv.ui.screens.SearchScreen
import com.cycitv.ui.screens.SettingsScreen
import com.cycitv.ui.theme.CycTheme
import com.cycitv.ui.theme.Primary
import com.cycitv.ui.theme.Surface
import com.cycitv.ui.theme.TextDim
import com.cycitv.ui.theme.TextMain
import androidx.compose.foundation.clickable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 调试直达:adb shell am start -n com.cycitv/.MainActivity --ei play_section 50665 --ei play_video 3772 --es play_title "标题"
        val sec = intent?.getIntExtra("play_section", 0) ?: 0
        PlayIntent = if (sec > 0) {
            Screen.Play(
                videoId = (intent.getIntExtra("play_video", 0) ?: 0).toLong(),
                sectionId = sec.toLong(),
                playerCode = intent.getStringExtra("play_code") ?: "cychub",
                animeTitle = intent.getStringExtra("play_title") ?: "",
                directUrl = intent.getStringExtra("play_url"), // 调试:--es play_url file:///sdcard/xx.mp4
            )
        } else if (intent.getIntExtra("go_settings", 0) > 0) Screen.Settings else null
        setContent {
            CycTheme {
                val app = LocalContext.current.applicationContext as App
                val nav = rememberNavController()
                val screen by nav.current
                CompositionLocalProvider(LocalAppImageLoader provides app.imageLoader) {

                // 返回:有返回栈则回退;没有(如 adb 直达播放)则直接退出应用
                BackHandler(enabled = screen !is Screen.Home) {
                    if (!nav.back()) finish()
                }

                if (screen is Screen.Play) {
                    val p = screen as Screen.Play
                    PlayScreen(
                        videoId = p.videoId,
                        initialSectionId = p.sectionId,
                        playerCode = p.playerCode,
                        animeTitle = p.animeTitle,
                        initialSections = p.sections,
                        directUrl = p.directUrl,
                        onExit = { if (!nav.back()) finish() },
                    )
                } else {
                Row(Modifier.fillMaxSize()) {
                    SideNav(
                        selected = screen,
                        onSelect = { nav.open(it) },
                        modifier = Modifier.width(150.dp)
                    )
                    Box(Modifier.weight(1f).background(com.cycitv.ui.theme.Backdrop)) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = screen,
                            transitionSpec = {
                                (fadeIn(tween(250)) +
                                    scaleIn(
                                        initialScale = 0.98f,
                                        animationSpec = tween(250)
                                    ))
                                    .togetherWith(fadeOut(tween(200)))
                            },
                            label = "screen"
                        ) { s ->
                            when (s) {
                                is Screen.Home -> HomeScreen(app.home, onOpen = { nav.open(Screen.Detail(it)) })
                                is Screen.Category -> CategoryScreen(app.api, onOpen = { nav.open(Screen.Detail(it)) })
                                is Screen.Schedule -> ScheduleScreen(app.api, onOpen = { nav.open(Screen.Detail(it)) })
                                is Screen.Search -> SearchScreen(app.api, onOpen = { nav.open(Screen.Detail(it)) })
                                is Screen.Settings -> SettingsScreen()
                                is Screen.Detail -> DetailScreen(
                                    app.api,
                                    (s as Screen.Detail).videoId,
                                    onPlay = { vid, sid, code, title, secs ->
                                        nav.open(Screen.Play(vid, sid, code, title, secs))
                                    },
                                    onBack = { nav.back() },
                                )
                                is Screen.Play -> Unit
                            }
                        }
                    }
                }
                }
            }
        }
    }
    }
}

private val NAV_ITEMS = listOf(
    "首页" to Screen.Home,
    "分类" to Screen.Category,
    "时间表" to Screen.Schedule,
    "搜索" to Screen.Search,
    "设置" to Screen.Settings,
)

@Composable
private fun SideNav(
    selected: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Surface)
            .padding(vertical = 40.dp, horizontal = 10.dp)
    ) {
        Text(
            "阿拉蕾TV",
            fontSize = 22.sp,
            color = Primary,
            modifier = Modifier.padding(start = 10.dp, bottom = 30.dp)
        )
        NAV_ITEMS.forEach { (label, screen) ->
            val active = screen::class == selected::class &&
                !(screen is Screen.Detail || screen is Screen.Play)
            Text(
                label,
                fontSize = 21.sp,
                color = if (active) Primary else TextMain,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocus()
                    .clickable { onSelect(screen) }
                    .background(if (active) Color(0x1A7AA2FF) else Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(vertical = 14.dp)
            )
        }
    }
}
