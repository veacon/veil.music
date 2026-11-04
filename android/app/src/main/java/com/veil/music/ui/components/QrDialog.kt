package com.veil.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * 手机扫码进管理页。
 *
 * 结构参照 upstream 的 QrDialog：标题 + 一句话说明 + 白底二维码 + 地址 + 关闭。
 * 二维码由 [QrCodeImage] 直接画，不 ahead-of-time 生成 Bitmap，所以弹窗一出来就是成品，
 * 不会先闪一个「正在生成」的空白框。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QrDialog(
    url: String,
    title: String = "手机管理音源",
    subtitle: String = "用手机相机 / 微信扫一下，在浏览器里导入、启停或删除音源脚本",
    onDismiss: () -> Unit,
) {
    // 电视上没有触摸，焦点必须先落在「关闭」上，否则遥控器按确认没有任何反应，用户会以为卡住。
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { closeFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1B1B1F))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = Color(0xFF8E8E93),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(14.dp))
            QrCodeImage(text = url, size = 212.dp)
            Spacer(Modifier.height(12.dp))
            Text("或在手机浏览器里直接打开：", color = Color(0xFF6E6E73), fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0E0E10))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(url, color = Color(0xFF30D158), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeFocus),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF2C2C32),
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                ) {
                    Text(
                        "关闭",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 34.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}
