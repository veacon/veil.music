package com.veacon.veilmusic.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.veacon.veilmusic.R

/** Render just the Apple symbol from the original supplied wordmark; the source image is unchanged. */
@Composable
fun AppleSymbol(modifier: Modifier = Modifier, color: Color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7))) {
    val mark = ImageBitmap.imageResource(R.drawable.apple_music_wordmark)
    Canvas(modifier) {
        drawImage(mark, srcOffset = IntOffset.Zero, srcSize = IntSize(230, 300),
            dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            colorFilter = ColorFilter.tint(color))
    }
}

@Composable
fun ProfileAvatar(url: String?, modifier: Modifier = Modifier, onLoadError: () -> Unit = {}) {
    var failed by remember(url) { mutableStateOf(false) }
    // 圆底也要跟随主题：深色界面下若是纯白底，浅色的苹果符号会白底白图标、完全看不见。
    Box(modifier.size(44.dp).clip(CircleShape)
        .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color.White, Color(0xFF3A3A3F)))
        .border(0.5.dp, com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFD9D9DE), Color(0xFF5A5A60)), CircleShape), Alignment.Center) {
        AppleSymbol(Modifier.size(width = 23.dp, height = 30.dp))
        if (url != null && !failed) AsyncImage(model = url, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            onError = { failed = true; onLoadError() })
    }
}
