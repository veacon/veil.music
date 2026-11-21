package com.veacon.veilmusic.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.veacon.veilmusic.ui.navigation.TopNavTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 波次1 删掉了节拍 DSP（P0-2）：顶栏三根小条不再订阅 BeatAnalyzer，只保留自走摆动。
 * 这里只锁定「正在播放 + isPlaying 时出现、暂停时消失」这条可见行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
class DynamicPlayingIndicatorTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun browsingShowsIndicatorOnlyWhilePlaying() {
        var playing by mutableStateOf(true)
        compose.setContent {TopNavBar(TopNavTab.ListenNow,{},isPlaying=playing)}
        compose.onNodeWithTag("dynamicPlayingIndicator",useUnmergedTree=true).assertExists()
        compose.runOnIdle {playing=false}
        compose.onNodeWithTag("dynamicPlayingIndicator",useUnmergedTree=true).assertDoesNotExist()
        compose.runOnIdle {playing=true}
        compose.onNodeWithTag("dynamicPlayingIndicator",useUnmergedTree=true).assertExists()
    }
}
