package com.veil.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.veil.music.data.store.LocalList
import com.veil.music.ui.components.TrackRow
import com.veil.music.ui.viewmodel.LibraryViewModel
import com.veil.music.ui.viewmodel.PlayerViewModel
import com.veil.music.ui.navigation.LocalNavBarFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 我的。左侧歌单导航（我喜欢 / 播放历史 / 自建歌单），右侧曲目列表。
 * 数据全部在本机（[com.veil.music.data.store.LibraryStore]），没有账号。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    playerVm: PlayerViewModel,
    modifier: Modifier = Modifier,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val playerState by playerVm.stableState.collectAsState()
    val navBarFocus = LocalNavBarFocus.current
    // 右侧「播放全部」与第一首曲目之间的上下通道。
    // LazyColumn 是独立的滚动容器，几何焦点搜索从列表往上出不去，
    // 表现就是「第一首歌按上键没反应」—— 所以给两端各绑一个 requester，显式搬焦点。
    val playAllFocus = remember { FocusRequester() }
    val firstTrackFocus = remember { FocusRequester() }
    val trackListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Row(modifier.fillMaxSize().padding(top = 18.dp)) {
        // 左侧导航
        Column(Modifier.width(232.dp).fillMaxHeight().padding(start = 32.dp)) {
            Text("我的", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(state.lists, key = { _, it -> it.id }) { i, list ->
                    ListItem(
                        list = list,
                        selected = list.id == state.selectedId,
                        // 左栏在最左边，和顶栏居中的胶囊横向不重叠，必须显式给一条向上通道。
                        upFocus = if (i == 0) navBarFocus else null,
                        onClick = { vm.select(list.id) },
                        onLongClick = { if (!list.system) vm.deleteList(list.id) },
                    )
                }
                item("new") {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        onClick = vm::createList,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color(0xFF2C2C2E),
                        ),
                    ) {
                        Text(
                            "＋ 新建歌单",
                            color = Color(0xFF8E8E93),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(24.dp))

        // 右侧曲目
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.selected?.name ?: "我喜欢",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(12.dp))
                Text("${state.songs.size} 首", color = Color(0xFF8E8E93), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (state.songs.isNotEmpty()) {
                    Surface(
                        onClick = { playerVm.playAlbum(state.songs, 0) },
                        modifier = Modifier
                            .focusRequester(playAllFocus)
                            .onKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                    scope.launch {
                                        runCatching { trackListState.animateScrollToItem(0) }
                                        delay(60)
                                        runCatching { firstTrackFocus.requestFocus() }
                                    }
                                    true
                                } else false
                            },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFFFA233B)),
                    ) {
                        Text("播放全部", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = { playerVm.shufflePlayPlaylist(state.songs) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                    ) {
                        Text("随机", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    }
                }
                Spacer(Modifier.width(48.dp))
            }

            Spacer(Modifier.height(10.dp))

            if (state.songs.isEmpty()) {
                Text(
                    if (state.selectedId == com.veil.music.data.store.LibraryStore.LOVE)
                        "还没有喜欢的歌 —— 在播放页按 ··· 收藏"
                    else "这个歌单还是空的",
                    color = Color(0xFF6E6E73),
                    fontSize = 13.sp,
                )
            }

            LazyColumn(
                state = trackListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(end = 48.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                    TrackRow(
                        song = song,
                        index = index + 1,
                        isCurrent = playerState.currentSong?.id == song.id,
                        modifier = if (index == 0) {
                            Modifier
                                .focusRequester(firstTrackFocus)
                                .onKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                                        runCatching { playAllFocus.requestFocus() }
                                        true
                                    } else false
                                }
                        } else Modifier,
                        onClick = { playerVm.playAlbum(state.songs, index) },
                        onLongClick = { vm.removeFromSelected(song.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ListItem(
    list: LocalList,
    selected: Boolean,
    upFocus: FocusRequester? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.focusProperties { up = upFocus ?: FocusRequester.Default },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFF2A2A31) else Color.Transparent,
            focusedContainerColor = Color(0xFF3A3A42),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                list.name,
                color = if (selected) Color(0xFFFA233B) else Color.White,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("${list.songs.size}", color = Color(0xFF6E6E73), fontSize = 12.sp)
        }
    }
}
