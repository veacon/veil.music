package com.veil.music.ui.player.fullscreen

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/**
 * 胶囊 5 个圆钮的功能位。
 *
 * 图标全部**手绘**（24×24 网格上的矢量线条），不用字体字符 —— 字符图标在电视上会
 * 被系统字体的字形差异带偏，而且「字 / 大 / 背 / 码」这种缩写也不够直观。
 */
enum class CapsuleTab(val label: String) {
    CYCLE("换效"),
    FONT("字体"),
    SIZE("大小"),
    ACCENT("字色"),
    BG("背景"),
    QR("上传"),
}

/** 画一个 24dp 基准的图标，按实际尺寸等比缩放。 */
@Composable
fun CapsuleIcon(tab: CapsuleTab, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        if (u <= 0f) return@Canvas
        withTransform({ scale(u, u, Offset.Zero) }) {
            when (tab) {
                CapsuleTab.CYCLE -> drawCycle(tint)
                CapsuleTab.FONT -> drawTypeface(tint)
                CapsuleTab.SIZE -> drawScale(tint)
                CapsuleTab.ACCENT -> drawPalette(tint)
                CapsuleTab.BG -> drawPicture(tint)
                CapsuleTab.QR -> drawQr(tint)
            }
        }
    }
}

/** 调色盘：一个圆环 + 三个色点（当前句字色）。 */
private fun DrawScope.drawPalette(tint: Color) {
    drawCircle(tint, radius = 8.6f, center = Offset(12f, 12f), style = Stroke(width = 1.7f))
    drawCircle(tint, radius = 1.9f, center = Offset(12f, 7.6f))
    drawCircle(tint, radius = 1.9f, center = Offset(7.8f, 14.4f))
    drawCircle(tint, radius = 1.9f, center = Offset(16.2f, 14.4f))
}

/** 加减号徽标（数值调节行用）。 */
@Composable
fun StepBadge(plus: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        if (u <= 0f) return@Canvas
        withTransform({ scale(u, u, Offset.Zero) }) {
            drawCircle(tint, radius = 8.6f, center = Offset(12f, 12f), style = Stroke(width = 1.9f))
            drawLine(tint, Offset(7.4f, 12f), Offset(16.6f, 12f), strokeWidth = 1.9f, cap = StrokeCap.Round)
            if (plus) {
                drawLine(tint, Offset(12f, 7.4f), Offset(12f, 16.6f), strokeWidth = 1.9f, cap = StrokeCap.Round)
            }
        }
    }
}

/** 右向箭头（列表「已选中」提示用）。 */
@Composable
fun ChevronRight(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        if (u <= 0f) return@Canvas
        withTransform({ scale(u, u, Offset.Zero) }) {
            val p = Path().apply {
                moveTo(9f, 5f)
                lineTo(16f, 12f)
                lineTo(9f, 19f)
            }
            drawPath(p, tint, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// ============== 图形本体 =====================================================================
//
// 下面的几何与 design/capsule-icons/svg/*.svg 逐点对应 —— 直接解析同一串 path 数据，
// 以后改图只需同步那份 SVG，不会出现「代码里画的和设计稿不一样」。

/** 按 SVG path 数据描边（[opacity] 对应 SVG 的 opacity）。 */
private fun DrawScope.strokePath(
    data: String,
    tint: Color,
    width: Float,
    opacity: Float = 1f,
) {
    drawPath(
        path = PathParser().parsePathString(data).toPath(),
        color = tint.copy(alpha = tint.alpha * opacity),
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** 按 SVG path 数据填充（[opacity] 对应 SVG 的 opacity）。 */
private fun DrawScope.fillPath(data: String, tint: Color, opacity: Float = 1f) {
    drawPath(
        path = PathParser().parsePathString(data).toPath(),
        color = tint.copy(alpha = tint.alpha * opacity),
    )
}

/**
 * 换效：中心竖直双向箭头 + 外圈顺时针旋转弧（缺口在左下，弧端箭头贴合切线）。
 * 「换一个界面」= 双向箭头；「转一圈」= 旋转弧，两者叠在一起语义最完整。
 */
private fun DrawScope.drawCycle(tint: Color) {
    strokePath("M 12 7.6 L 12 16.4", tint, 2f)
    fillPath("M 12 5.70 L 14.50 8.70 L 9.50 8.70 Z", tint)
    fillPath("M 12 18.30 L 9.50 15.30 L 14.50 15.30 Z", tint)
    strokePath("M 3.84 8.20 A 9.0 9.0 0 1 1 5.11 17.79", tint, 1.7f)
    fillPath("M 3.88 16.33 L 8.37 17.79 L 4.54 21 Z", tint)
}

/**
 * 字体：同一个「A」的两副字形 —— 粗体在左下、细体（50%）退到右上，
 * 表达的是「字形在变」，不是干巴巴一个字母。
 */
private fun DrawScope.drawTypeface(tint: Color) {
    strokePath(
        "M 10.90 10.40 L 14.20 2.60 L 17.50 10.40 M 12.52 8.36 L 15.88 8.36",
        tint, 1.25f, opacity = 0.5f,
    )
    strokePath(
        "M 3 18.24 L 7.84 6.80 L 12.68 18.24 M 5.38 15.25 L 10.30 15.25",
        tint, 2.5f,
    )
}

/** 大小：中心方框（取景范围）+ 四角对角箭头 + 框内大小两个 A。 */
private fun DrawScope.drawScale(tint: Color) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(7.2f, 7.2f),
        size = Size(9.6f, 9.6f),
        cornerRadius = CornerRadius(1.9f),
        style = Stroke(width = 1.6f),
    )
    strokePath("M 13.40 10.78 L 14.36 8.50 L 15.32 10.78 M 13.87 10.18 L 14.85 10.18", tint, 0.85f, opacity = 0.5f)
    strokePath("M 8.30 15.32 L 10.17 10.90 L 12.04 15.32 M 9.22 14.16 L 11.12 14.16", tint, 1.8f)
    // 四个方向的对角短杆 + 实心箭头
    strokePath("M 6.50 6.50 L 4.90 4.90", tint, 1.8f)
    fillPath("M 4.30 4.30 L 6.99 4.87 L 4.87 6.99 Z", tint)
    strokePath("M 17.50 6.50 L 19.10 4.90", tint, 1.8f)
    fillPath("M 19.70 4.30 L 19.13 6.99 L 17.01 4.87 Z", tint)
    strokePath("M 6.50 17.50 L 4.90 19.10", tint, 1.8f)
    fillPath("M 4.30 19.70 L 4.87 17.01 L 6.99 19.13 Z", tint)
    strokePath("M 17.50 17.50 L 19.10 19.10", tint, 1.8f)
    fillPath("M 19.70 19.70 L 17.01 19.13 L 19.13 17.01 Z", tint)
}

/** 背景：画框 + 太阳 + 远山脊线（55%）+ 近山实心剪影。 */
private fun DrawScope.drawPicture(tint: Color) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(3f, 4.4f),
        size = Size(18f, 15.2f),
        cornerRadius = CornerRadius(2.4f),
        style = Stroke(width = 1.8f),
    )
    drawCircle(tint, radius = 1.5f, center = Offset(7.6f, 8.8f))
    strokePath("M 4.8 17.2 L 8.9 11.9 L 13 17.2", tint, 1.6f, opacity = 0.55f)
    fillPath("M 9.8 17.8 L 14.4 12.4 L 19 17.8 Z", tint)
}

/** 上传：二维码（三个定位角 + 点阵），一眼看出「手机扫码」。 */
private fun DrawScope.drawQr(tint: Color) {
    fun eye(x: Float, y: Float) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(x, y),
            size = Size(6.2f, 6.2f),
            cornerRadius = CornerRadius(1.5f),
            style = Stroke(width = 1.7f),
        )
    }
    eye(3f, 3f)
    eye(14.8f, 3f)
    eye(3f, 14.8f)
    // 定位角中心块
    listOf(
        Offset(4.85f, 4.85f),
        Offset(16.65f, 4.85f),
        Offset(4.85f, 16.65f),
    ).forEach { c ->
        drawRoundRect(tint, c, Size(2.5f, 2.5f), CornerRadius(0.6f))
    }
    // 右下数据点阵（3×3，中心留空 = 真实二维码的「时钟/对齐」留白）
    val dots = listOf(
        14.6f to 14.6f, 16.8f to 14.6f, 19.0f to 14.6f,
        14.6f to 16.8f, 19.0f to 16.8f,
        14.6f to 19.0f, 16.8f to 19.0f, 19.0f to 19.0f,
        // 中缝两个点，把左右两片连成一张码，避免看成四个孤立方块
        10.4f to 10.4f, 12.6f to 10.4f, 10.4f to 12.6f, 12.6f to 12.6f,
    )
    dots.forEach { (x, y) ->
        drawRoundRect(tint, Offset(x, y), Size(1.6f, 1.6f), CornerRadius(0.42f))
    }
}
