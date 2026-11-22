package com.veacon.veilmusic.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import kotlinx.coroutines.delay

/** 手机扫码登录的状态机：授权在手机浏览器完成，电视只镜像进度。 */
internal enum class PhoneSignInPhase { Starting, Waiting, Authorizing, Complete, Expired, Failed }

/**
 * 扫码登录弹窗：电视显示配对二维码，手机完成 Apple 授权。
 * 失败/过期态可重试，成功态短暂停留后自动关闭。
 */
@Composable
internal fun PhoneSignInDialog(
    url: String?,
    phase: PhoneSignInPhase,
    failReason: String? = null,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    val retryable = phase == PhoneSignInPhase.Expired || phase == PhoneSignInPhase.Failed
    var autoClosed by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(phase) { runCatching { primaryFocus.requestFocus() } }
    LaunchedEffect(phase) {
        if (phase == PhoneSignInPhase.Complete && !autoClosed) {
            delay(1_600L)
            autoClosed = true
            onDismiss()
        }
    }

    fun tx(zh: String, en: String) = if (com.veacon.veilmusic.i18n.AppLanguage.code == "en") en else zh
    MotionDialog(onDismissRequest = onDismiss) {
        Row(
            Modifier.width(820.dp).clip(RoundedCornerShape(24.dp))
                .border(0.5.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(24.dp))
                .background(Color(0xFF1C1C1E)).padding(horizontal = 30.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(226.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (url != null) Color.White else Color.White.copy(alpha = 0.06f))
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !url.isNullOrBlank() && !retryable && phase != PhoneSignInPhase.Complete ->
                        QrCode(content = url, size = 206.dp, cornerRadius = 8.dp)
                    phase == PhoneSignInPhase.Complete -> Text("✓", fontSize = 56.sp, color = Color(0xFF9BE3A2))
                    retryable -> Text("📱", fontSize = 48.sp, color = Color.White.copy(alpha = 0.4f))
                    else -> Text("…", fontSize = 32.sp, color = Color.White.copy(alpha = 0.5f))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    tx("手机扫码登录 Apple Music", "Sign in to Apple Music on your phone"),
                    color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    tx("用手机扫描二维码，在浏览器里完成 Apple 授权；凭证只留在手机，播放仍走落雪音源。",
                        "Scan the QR with your phone and authorize in the browser. Credentials stay on the phone; playback still uses Luoxue."),
                    color = Color.White.copy(alpha = 0.66f), fontSize = 14.sp,
                )
                val status = when (phase) {
                    PhoneSignInPhase.Starting -> tx("正在开启配对服务…", "Starting pairing…")
                    PhoneSignInPhase.Waiting -> tx("等待手机连接…", "Waiting for your phone…")
                    PhoneSignInPhase.Authorizing -> tx("已连接，正在获取账号信息…", "Connected, reading account…")
                    PhoneSignInPhase.Complete -> tx("登录成功", "Signed in")
                    PhoneSignInPhase.Expired -> tx("二维码已过期，请重试", "Code expired — retry")
                    PhoneSignInPhase.Failed -> tx("登录失败，请重试", "Sign-in failed — retry")
                }
                Text(status, color = if (retryable) Color(0xFFFFB0BA) else Color(0xFF9BE3A2),
                    fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (retryable && !failReason.isNullOrBlank() && failReason != "error") {
                    Text(failReason, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, lineHeight = 17.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (retryable) ActionButton(tx("重试", "Retry"), onRetry,
                        Modifier.weight(1f).then(if (retryable) Modifier.focusRequester(primaryFocus) else Modifier))
                    ActionButton(
                        tx(if (phase == PhoneSignInPhase.Complete) "完成" else "取消",
                            if (phase == PhoneSignInPhase.Complete) "Done" else "Cancel"),
                        onDismiss,
                        Modifier.weight(1f).then(if (!retryable) Modifier.focusRequester(primaryFocus) else Modifier),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(23.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            focusedContainerColor = Color.White.copy(alpha = 0.28f),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
