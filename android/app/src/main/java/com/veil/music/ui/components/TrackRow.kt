package com.veil.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.veil.music.data.model.Song

/**
 * 曲目行：序号 / 封面 / 歌名 + 歌手·专辑 / 时长。
 *
 * 歌单详情、搜索结果、我的歌单共用同一个控件，避免三处样式漂移
 * （原项目每个页面各写一份列表行，改一次颜色要改三遍）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TrackRow(
    song: Song,
    index: Int? = null,
    isCurrent: Boolean = false,
    showArtwork: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color(0xFF2C2C2E),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f, pressedScale = 0.99f),
    ) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (index != null) {
                Text(
                    "$index",
                    color = if (isCurrent) Color(0xFFFA233B) else Color(0xFF6E6E73),
                    fontSize = 13.sp,
                    modifier = Modifier.width(26.dp),
                )
            }
            if (showArtwork) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))) {
                    if (song.artworkUrl != null) {
                        AsyncImage(
                            model = song.artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = if (isCurrent) Color(0xFFFA233B) else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(song.artistName, song.albumName).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Color(0xFF8E8E93),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                song.durationFormatted,
                color = Color(0xFF6E6E73),
                fontSize = 12.sp,
            )
        }
    }
}
