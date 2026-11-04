package com.veil.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val AppleRed    = Color(0xFFFA233B)
val DeepBlack   = Color(0xFF000000)
val SurfaceDark = Color(0xFF111111)
val OnSurface   = Color(0xFFE5E5E7)
val SubtleGray  = Color(0xFF8E8E93)

@Composable
fun VeilTheme(content: @Composable () -> Unit) {
    // LocalTextStyle 兜一层：页面里大量 Text 只写了 fontSize / color，没写 style，
    // 这些走的是 LocalTextStyle，不显式提供就还是系统默认字体（不是苹方）。
    CompositionLocalProvider(LocalTextStyle provides VeilTypography.bodyLarge) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary          = AppleRed,
                onPrimary        = Color.White,
                primaryContainer = AppleRed.copy(alpha = 0.15f),
                secondary        = Color(0xFF1D1D1F),
                onSecondary      = OnSurface,
                background       = DeepBlack,
                surface          = SurfaceDark,
                onSurface        = OnSurface,
                onSurfaceVariant = SubtleGray,
                error            = Color(0xFFFF453A),
            ),
            typography = VeilTypography,
            content = content,
        )
    }
}
