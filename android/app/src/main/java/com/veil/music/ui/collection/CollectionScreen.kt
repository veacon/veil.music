package com.veil.music.ui.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.veil.music.ui.components.TrackRow
import com.veil.music.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 歌单 / 榜单详情。两者的数据结构在 lx 里不同（歌单有详情接口、榜单按页取），
 * 但在 UI 上都表现为「一串曲目」，所以共用一个页面，由 id 前缀决定取数方式。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionScreen(
    title: String,
    artworkUrl: String,
    playerVm: PlayerViewModel,
    modifier: Modifier = Modifier,
    vm: CollectionViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val playerState by playerVm.stableState.collectAsState()

    // 进入歌单详情后，焦点应落在「播放全部」上，而不是停留在顶栏「发现」标签。
    // 歌曲加载完成、header 渲染出来之后才请求焦点（loading 时按钮还不存在）。
    val playAllFocus = remember { FocusRequester() }
    // 第一首歌的 requester：它按上键要回到「播放全部」。
    val firstTrackFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.songs.isNotEmpty()) {
        if (state.songs.isNotEmpty()) {
            // 等一帧让按钮真正挂到树上，再抢焦点。
            kotlinx.coroutines.delay(50)
            runCatching { playAllFocus.requestFocus() }
        }
    }

    /**
     * 回到「播放全部」。
     *
     * 不能只靠几何焦点搜索：列表滚下去之后 header 那个 item 已经不在组合里，
     * 第一首歌按上键就找不到落点（表现为「按上键没反应」）。所以显式先把列表
     * 滚回顶部，再把焦点交给按钮。
     */
    fun focusPlayAll() {
        scope.launch {
            runCatching { listState.animateScrollToItem(0) }
            delay(60)
            runCatching { playAllFocus.requestFocus() }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item("header") {
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                Box(Modifier.size(168.dp).clip(RoundedCornerShape(12.dp))) {
                    if (artworkUrl.isNotBlank()) {
                        AsyncImage(
                            model = artworkUrl,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(168.dp),
                        )
                    } else {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).padding(top = 6.dp)) {
                    Text("歌单", color = Color(0xFFFA233B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            state.isLoading -> "正在加载…"
                            state.error != null -> state.error!!
                            else -> "${state.songs.size} 首"
                        },
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(
                            "播放全部", filled = true,
                            onClick = { playerVm.playAlbum(state.songs, 0) },
                            focusRequester = playAllFocus,
                            // 往下：直接落到第一首歌上（header 与列表之间没有别的落点）。
                            onDown = {
                                scope.launch {
                                    runCatching { listState.animateScrollToItem(1) }
                                    delay(60)
                                    runCatching { firstTrackFocus.requestFocus() }
                                }
                            },
                        )
                        PillButton("随机播放", filled = false, onClick = { playerVm.shufflePlayPlaylist(state.songs) })
                    }
                }
            }
        }

        itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
            TrackRow(
                song = song,
                index = index + 1,
                isCurrent = playerState.currentSong?.id == song.id,
                showArtwork = false,
                onClick = { playerVm.playAlbum(state.songs, index) },
                onLongClick = { playerVm.playNext(song) },
                modifier = if (index == 0) {
                    Modifier
                        .focusRequester(firstTrackFocus)
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                                focusPlayAll(); true
                            } else false
                        }
                } else Modifier,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PillButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onDown: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (onDown == null) Modifier
                else Modifier.onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                        onDown(); true
                    } else false
                }
            ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (filled) Color(0xFFFA233B) else Color(0xFF2C2C2E),
            focusedContainerColor = if (filled) Color(0xFFFF3B30) else Color.White,
        ),
    ) {
        Text(
            label,
            color = if (filled) Color.White else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 9.dp),
        )
    }
}
