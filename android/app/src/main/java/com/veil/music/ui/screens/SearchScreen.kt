package com.veil.music.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.veil.music.data.model.SourceID
import com.veil.music.ui.components.TrackRow
import com.veil.music.ui.viewmodel.PlayerViewModel
import com.veil.music.ui.viewmodel.SearchViewModel

/**
 * 搜索页。四个平台可单选（默认跟随首页数据源），也可以「全部」并发搜。
 *
 * 遥控器输入体验沿用原项目的一条硬规则：输入框聚焦即弹 IME，失焦必须同时调用
 * `hide()` 与 InputMethodManager —— Fire TV 上 `hide()` 是空操作。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    playerVm: PlayerViewModel,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val playerState by playerVm.stableState.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { vm.loadHot() }

    Column(modifier.fillMaxSize().padding(top = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("搜索", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(24.dp))
            SourceChip("全部", state.source == null) { vm.setSource(null) }
            SourceID.homePageOrder.forEach { src ->
                Spacer(Modifier.width(8.dp))
                SourceChip(src.displayName, state.source == src) { vm.setSource(src) }
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            onClick = { editing = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF1C1C22),
                focusedContainerColor = Color(0xFF2C2C33),
            ),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
                if (state.query.isEmpty() && !editing) {
                    Text("输入歌名、歌手或专辑", color = Color(0xFF6E6E73), fontSize = 14.sp)
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color(0xFFFA233B)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        editing = false
                        keyboard?.hide()
                        vm.search()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            if (focus.isFocused) editing = true
                        },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        when {
            state.isLoading -> {
                Text("正在搜索…", color = Color(0xFF8E8E93), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 48.dp))
            }

            state.error != null -> {
                Text(state.error!!, color = Color(0xFFFF453A), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 48.dp))
            }

            !state.hasSearched && state.hot.isNotEmpty() -> {
                Text("热门搜索", color = Color(0xFF8E8E93), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 48.dp))
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.hot, key = { it }) { word ->
                        Surface(
                            onClick = { vm.search(word) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF1C1C22),
                                focusedContainerColor = Color.White,
                            ),
                        ) {
                            Text(word, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                TrackRow(
                    song = song,
                    index = index + 1,
                    isCurrent = playerState.currentSong?.id == song.id,
                    onClick = { playerVm.playAlbum(state.songs, index) },
                    onLongClick = { playerVm.playNext(song) },
                )
            }
            if (state.hasSearched && state.songs.isEmpty() && !state.isLoading) {
                item("empty") {
                    Text("没找到结果，换个关键词或换一个源试试", color = Color(0xFF8E8E93), fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White else Color(0xFF1C1C22),
            focusedContainerColor = Color(0xFF3A3A40),
        ),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color(0xFFC7C7CC),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}
