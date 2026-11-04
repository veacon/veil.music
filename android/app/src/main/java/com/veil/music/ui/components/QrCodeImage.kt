package com.veil.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.veil.music.util.QrCode

/**
 * 把一段文本画成二维码。
 *
 * 没有引第三方二维码库：`util/QrCode.kt` 里已经有一个字节模式、纠错等级 M、版本 1–6 的编码器，
 * 而这里只编一个 `http://192.168.x.x:8080`，25–30 字节，版本 3 就够。
 * 渲染走一个 Canvas 一次画完，不生成 Bitmap —— 电视上少一次 512×512 的位图分配。
 */
@Composable
fun QrCodeImage(
    text: String,
    size: Dp,
    modifier: Modifier = Modifier,
    quietZone: Int = 2,
) {
    // 矩阵只依赖文本，重新组合不必重算 RS 纠错和掩码搜索。
    val matrix = remember(text) { runCatching { QrCode.encode(text) }.getOrNull() }

    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (matrix == null) {
            Text("无法生成二维码", color = Color(0xFF6E6E73), fontSize = 12.sp)
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) {
            val n = matrix.size
            val total = n + quietZone * 2
            val cell = this.size.minDimension / total
            // 居中：整数倍取整后右边/下边可能差不到 1px，把偏移补上，避免边缘出现半格白线。
            val originX = (this.size.width - cell * total) / 2f
            val originY = (this.size.height - cell * total) / 2f
            for (r in 0 until n) {
                for (c in 0 until n) {
                    if (!matrix[r][c]) continue
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(originX + cell * (c + quietZone), originY + cell * (r + quietZone)),
                        size = Size(cell + 0.6f, cell + 0.6f),
                    )
                }
            }
        }
    }
}
