package com.veacon.veilmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.veacon.veilmusic.BuildConfig
import com.veacon.veilmusic.ui.theme.AppPalette

/**
 * App 每一页滚动内容最末尾的版本行：`Veil Music v2.2.9`。
 *
 * - **随内容滚动**：挂在各页列表 / 网格的最后一个 item 上（由 ReturnLazyColumn /
 *   ReturnLazyVerticalGrid 的 [com.veacon.veilmusic.ui.navigation.versionFooter] 开关统一追加），
 *   不滑到最底部就看不见，不再是固定在屏幕底部的浮层；
 * - **可聚焦**：内容走到底后光标会落在它上面（高亮提示这就是页面终点）；
 * - **Down 出口**：迷你播放胶囊开着时按「下」直接落到胶囊（[LocalMiniCapsuleFocus]），
 *   胶囊没开时停在原地——版本行就是每一页的绝对底部；
 * - 水平居中，11sp（比正文 13sp 小一号），次级文字色
 *   （浅色 #6E6E73 / 白 ≈ 4.9:1，深色 #B1B1B8 / #101012 ≈ 8:1，满足 WCAG AA 小字 4.5:1）。
 */
@Composable
fun AppVersionFooter(modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val capsuleFocus = LocalMiniCapsuleFocus.current
    Box(
        modifier
            .fillMaxWidth()
            .focusable()
            .focusProperties {
                down = capsuleFocus ?: FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
        content = {
            Box(
                Modifier
                    .then(
                        if (focused) Modifier.background(AppPalette.adaptive(Color(0x14000000), Color(0x26FFFFFF))).padding(horizontal = 12.dp, vertical = 5.dp)
                        else Modifier,
                    ),
            ) {
                Text(
                    "Veil Music v${BuildConfig.VERSION_NAME}",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    color = AppPalette.secondary,
                )
            }
        },
    )
}
