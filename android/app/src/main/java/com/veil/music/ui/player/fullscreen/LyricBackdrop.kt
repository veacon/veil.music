package com.veil.music.ui.player.fullscreen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.veil.music.data.store.LyricLookStore
import com.veil.music.ui.viewmodel.FsStyle
import com.veil.music.ui.viewmodel.LyricBgMode
import kotlin.math.max

/** 这个界面用哪一份背景图素材（墨卷 / 声轨 / 全屏歌词各一份）。 */
val FsStyle.bgSlot: LyricLookStore.BgSlot
    get() = when (this) {
        FsStyle.PLAIN -> LyricLookStore.BgSlot.PLAIN
        FsStyle.INK -> LyricLookStore.BgSlot.INK
        FsStyle.TRACK -> LyricLookStore.BgSlot.TRACK
    }

/**
 * 实际生效的背景模式：选了「自定义图片」但没传过图时回落默认底，
 * 免得用户点错一下就把整屏变成黑的。
 */
fun LyricBgMode.effective(hasImage: Boolean): LyricBgMode =
    if (this == LyricBgMode.CUSTOM && !hasImage) LyricBgMode.DEFAULT else this

/**
 * 把自定义背景图按 Crop 铺满画布（等价 ContentScale.Crop），再压一层暗色。
 *
 * 压暗是必须的：用户传的图可能是白底、亮色照片，直接垫在歌词下面会彻底读不清。
 */
fun DrawScope.drawCustomBackdrop(image: ImageBitmap, scrim: Float = 0.58f) {
    val iw = image.width.toFloat()
    val ih = image.height.toFloat()
    if (iw <= 0f || ih <= 0f || size.width <= 0f || size.height <= 0f) return
    val scale = max(size.width / iw, size.height / ih)
    val w = iw * scale
    val h = ih * scale
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(((size.width - w) / 2f).toInt(), ((size.height - h) / 2f).toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
    )
    drawRect(Color.Black.copy(alpha = scrim))
}
