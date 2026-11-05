package com.veil.music.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.veil.music.R

/**
 * 苹方（PingFang SC）。
 *
 * Apple Music 中文界面用的就是这一套，所以整体换成苹方后字形骨架与系统级留白天然对齐，
 * 比思源黑体更"苹果"。资源里只放了一个 Regular 字重：Android 在请求更粗的字重时会用
 * Typeface 的合成加粗（fake bold），效果对 TV 上 2 米外观看的场景足够，还能省下每个字重
 * 十几 MB 的体积。
 *
 * 注意别把同一个 resId 按多个 FontWeight 重复声明 —— 那样 Compose 会认为"字重已命中"，
 * 反而不做合成加粗，标题会变成一片 Regular。
 */
val PingFang = FontFamily(Font(R.font.pingfang, FontWeight.Normal))

/**
 * 「Aa祝你我明媚向春天」—— 标题/歌词字体。
 *
 * 原字体 14.4 MB（2.2 万字形），这里做了 GB2312 + 拉丁/标点子集（4.1 MB / 7.4 千字形）。
 * 只用于全屏歌词，不参与界面排版：装饰字体在小字号下笔画会糊，界面还是苹方。
 */
val MingMeiSpring = FontFamily(Font(R.font.aa_ming_mei_spring, FontWeight.Normal))

/** 把任意 TextStyle 的字体族换成苹方，其余（字号 / 行高 / 字距）保持设计稿原样。 */
private fun TextStyle.pf(): TextStyle = copy(fontFamily = PingFang)

/**
 * Apple Music 的排版特征：标题极粗、字距略紧、正文行高偏松。
 * 这里只调整字重与字距，字号沿用 Material3 的档位，避免影响已经按 dp 排好的布局。
 */
val VeilTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.pf().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        displayMedium = displayMedium.pf().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        displaySmall = displaySmall.pf().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineLarge = headlineLarge.pf().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineMedium = headlineMedium.pf().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = headlineSmall.pf().copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.pf().copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.pf().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.pf().copy(fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.pf(),
        bodyMedium = bodyMedium.pf(),
        bodySmall = bodySmall.pf(),
        labelLarge = labelLarge.pf().copy(fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.pf().copy(fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.pf(),
    )
}
