package com.veil.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.collectAsState
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.veil.music.data.model.Song
import com.veil.music.ui.viewmodel.PlayerViewModel

/**
 * 「添加到歌单」对话框。
 *
 * TV 上没有软键盘，所以不提供「输入歌单名」这条路径——新建一律用 `歌单 N` 的默认名，
 * 需要改名的去「我的」页（那里有输入框，遥控器可操作）。少一个输入环节，遥控器的
 * 方向键就够走完全流程。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddToDialog(
    playerVm: PlayerViewModel,
    song: Song,
    onDismiss: () -> Unit,
) {
    val libState by playerVm.libraryState.collectAsState()
    val lists = libState.userLists
    val firstFocus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        // 用 Box + clip 而不是 tv.material3.Surface：非点击的 Surface 重载在这个版本里
        // 参数签名对不上，而对话框背景只需要一个纯色圆角，没必要为了它跟 API 版本较劲。
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1B1B1E))
                .padding(20.dp)
        ) {
                Text(
                    "添加到歌单",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    song.title,
                    fontSize = 13.sp,
                    color = Color(0xFF8E8E93),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )

                if (lists.isEmpty()) {
                    Text(
                        "还没有自建歌单。点下面的「新建歌单」直接建一个。",
                        fontSize = 13.sp,
                        color = Color(0xFF8E8E93),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }

                lists.forEachIndexed { index, list ->
                    val already = list.songs.any { it.id == song.id }
                    ListRow(
                        label = list.name,
                        hint = if (already) "已在其中" else "${list.songs.size} 首",
                        checked = already,
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = {
                            if (!already) playerVm.addSongToList(list.id, song)
                            onDismiss()
                        },
                    )
                }

                ListRow(
                    label = "新建歌单",
                    hint = "「歌单 ${lists.size + 1}」",
                    icon = Glyph.ADD_TO,
                    modifier = if (lists.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = {
                        playerVm.createListWithSong(song, "歌单 ${lists.size + 1}")
                        onDismiss()
                    },
                )

                LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ListRow(
    label: String,
    hint: String?,
    modifier: Modifier = Modifier,
    icon: Glyph? = null,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color(0xFF303034),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterStart) {
                    Icon(icon, size = 15.dp, color = Color(0xFFC0C0C4))
                }
            }
            Text(
                label,
                fontSize = 14.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hint != null) {
                Text(hint, fontSize = 12.sp, color = Color(0xFF8E8E93))
            }
            if (checked) {
                Spacer(Modifier.width(8.dp))
                Icon(Glyph.CHECK, size = 14.dp, color = Color(0xFFFA233B))
            }
        }
    }
}
