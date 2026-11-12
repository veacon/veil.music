package com.veacon.veilmusic.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * 封面圆角裁剪：用画布裁剪代替 `Modifier.clip(RoundedCornerShape(...))`。
 *
 * 两者像素结果一致，但合成路径不同：
 *  - `Modifier.clip(shape)` 会把被裁剪的节点渲染进**一张离屏图层**（RenderNode + clip）；
 *  - `drawWithContent { clipPath { drawContent() } }` 只是当前画布上的一个裁剪指令，不额外开图层。
 *
 * 在部分电视 GPU（Realtek RTD2886 等）上，「封面圆角离屏层」与背景的 GL 层合成时会残留未刷新的
 * 缓冲区内容，表现为封面区域出现马赛克/彩色块。改为画布裁剪后，开圆角与关圆角的合成路径一致，
 * 观感不变但不再触发该驱动行为。
 *
 * @param radiusPx 圆角半径（像素）。<= 0 时原样返回，不做任何绘制包装。
 */
fun Modifier.artworkCornerClip(radiusPx: Float): Modifier {
    if (radiusPx <= 0f) return this
    return this.drawWithContent {
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    Rect(Offset.Zero, size),
                    CornerRadius(radiusPx),
                ),
            )
        }
        clipPath(path) { this@drawWithContent.drawContent() }
    }
}

/**
 * 封面圆角（受「封面马赛克修复」开关控制）：
 *
 *  - [compat] = false：保持框架的 `Modifier.clip`（**与 2.2.1 完全一致的渲染路径**，
 *    正常机型不受本次修复影响）；
 *  - [compat] = true：改用画布裁剪 [artworkCornerClip]，不再为封面开离屏图层。
 *
 * @param radius 圆角半径；<= 0 时不做任何裁剪（与「封面圆角」开关关闭时一致）。
 * @param compat 是否启用马赛克修复路径，来自 `RenderCompat.isActive(context)`。
 */
@Composable
fun Modifier.artworkCorners(radius: Dp, compat: Boolean): Modifier {
    if (radius.value <= 0f) return this
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    return if (compat) artworkCornerClip(radiusPx) else clip(RoundedCornerShape(radius))
}
