package com.veacon.veilmusic.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.veacon.veilmusic.i18n.AppLanguage
import com.veacon.veilmusic.i18n.LanguageChoice
import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.R
import com.veacon.veilmusic.data.AudioQuality
import com.veacon.veilmusic.data.CrossfadePreferences
import com.veacon.veilmusic.data.DecoderMode
import com.veacon.veilmusic.ui.components.AlbumCard
import com.veacon.veilmusic.ui.components.Glyph
import com.veacon.veilmusic.ui.components.Icon
import com.veacon.veilmusic.ui.components.MiniPlayerCapsule
import com.veacon.veilmusic.ui.components.artworkCorners
import com.veacon.veilmusic.ui.screens.ActionSettingRow
import com.veacon.veilmusic.ui.screens.LyricLineRow
import com.veacon.veilmusic.ui.screens.Stepper
import com.veacon.veilmusic.ui.screens.Toggle
import com.veacon.veilmusic.ui.theme.AppPalette
import com.veacon.veilmusic.ui.viewmodel.DevMenuState
import com.veacon.veilmusic.ui.viewmodel.DevMenuViewModel
import com.veacon.veilmusic.ui.viewmodel.PlayerState
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import android.os.SystemClock

/** 引导页设置文案与设置页同款逻辑：英文界面用英文，其余用中文。 */
private fun tx(zh: String, en: String) = if (AppLanguage.code == "en") en else zh

/**
 * **新手引导页** —— 全屏上下分屏：上方 55% 高保真预览区，下方 45% 双列设置区。
 *
 * 核心原则：预览区**直接调用现有真实组件**（歌词行 [LyricLineRow]、迷你胶囊
 * [MiniPlayerCapsule]、专辑卡 [AlbumCard]、封面圆角 [artworkCorners]），数据用
 * [GuideDemoData] 的本地假数据 + 模拟进度时钟驱动 —— 断网秒显，零网络请求。
 *
 * 下方的设置项**就是真实设置**：复用设置页同款 [Toggle] / [Stepper] / [ActionSettingRow]
 * 与同一批文案（只留标题、说明文字全部隐藏），拨动开关立即改变真实设置与上方预览。
 *
 * 焦点规则（遥控器）：
 * - 预览区所有组件不可聚焦，光标绝不进入；
 * - 方向键只在 2×2 设置网格内移动，**不能切页**；
 * - 翻页只能通过右下角「上一页 / 下一页 / 完成」按钮按 OK 触发，
 *   切页后焦点自动重置到当前页第一个设置项。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GuideScreen(
    playerVm: PlayerViewModel,
    settingsVm: DevMenuViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pstate by playerVm.state.collectAsState()
    val vmState by settingsVm.state.collectAsState()
    val wordByWordLyrics by settingsVm.wordByWordLyrics.collectAsState()

    // 页面：0=界面外观（联动播放页）1=音频与歌词（联动歌词）2=实验性功能（联动首页 UI）
    var page by rememberSaveable { mutableIntStateOf(0) }

    // 歌词模拟进度时钟：「逐字播放」开着时推进（扫光动画），关掉即静止。
    var previewProgress by remember { mutableLongStateOf(GuideDemoData.LOOP_START_MS) }
    LaunchedEffect(wordByWordLyrics) {
        var last = SystemClock.elapsedRealtime()
        while (true) {
            delay(16)
            val now = SystemClock.elapsedRealtime()
            if (wordByWordLyrics) {
                previewProgress += now - last
                if (previewProgress >= GuideDemoData.LOOP_START_MS + GuideDemoData.LOOP_DURATION_MS) {
                    previewProgress = GuideDemoData.LOOP_START_MS
                }
            }
            last = now
        }
    }

    // 切页后焦点重置到当前页第一个设置项（进入引导页时同样生效）。
    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(page) {
        withFrameNanos { }
        runCatching { firstItemFocus.requestFocus() }
    }

    // 引导中吞掉返回键：防止误触退出导致引导半途而废。
    BackHandler(enabled = true) { }

    val displayAuth = remember(context) { com.veacon.veilmusic.data.AppleDisplayAuth.get(context) }
    val displayState by displayAuth.state.collectAsState()

    Column(modifier.fillMaxSize().background(AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012)))) {
        // ─────────── 上半部分：高保真预览区（55%） ───────────
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.55f)
                .background(AppPalette.adaptive(Color(0xFFF2F2F7), Color(0xFF17171A))),
        ) {
            when (page) {
                2 -> HomePreview(playerVm, pstate, { previewProgress }, Modifier.fillMaxSize())
                else -> PlayerPreview(pstate, wordByWordLyrics, { previewProgress }, Modifier.fillMaxSize())
            }
            Text(
                "${page + 1} / 3",
                fontSize = 12.sp, color = AppPalette.secondary,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 20.dp),
            )
        }

        // ─────────── 下半部分：2×2 设置区（45%） ───────────
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 44.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    Modifier.weight(1f).focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (page) {
                        0 -> {
                            LanguageItem(Modifier.focusRequester(firstItemFocus))
                            MiniCapsuleItem(pstate) { playerVm.setMiniCapsuleEnabled(!pstate.miniCapsuleEnabled) }
                        }
                        1 -> {
                            QualityItem(vmState,
                                onDec = { settingsVm.stepAudioQuality(-1) },
                                onInc = { settingsVm.stepAudioQuality(1) },
                                modifier = Modifier.focusRequester(firstItemFocus))
                            WordByWordItem(wordByWordLyrics) { settingsVm.setWordByWordLyrics(!wordByWordLyrics) }
                        }
                        else -> {
                            CrossfadeItem(vmState,
                                onDec = { settingsVm.setCrossfade(CrossfadePreferences.step(vmState.crossfadeMs, -1)) },
                                onInc = { settingsVm.setCrossfade(CrossfadePreferences.step(vmState.crossfadeMs, 1)) },
                                modifier = Modifier.focusRequester(firstItemFocus))
                            AppleDisplayItem(displayState) { displayAuth.setEnabled(!displayState.enabled) }
                        }
                    }
                }
                Column(
                    Modifier.weight(1f).focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (page) {
                        0 -> {
                            AppearanceItem(pstate)
                            RoundedItem(pstate) { playerVm.toggleArtworkRounded() }
                        }
                        1 -> {
                            DecoderItem(pstate,
                                onDec = { playerVm.setDecoderMode(DecoderMode.SOFTWARE) },
                                onInc = { playerVm.setDecoderMode(DecoderMode.HARDWARE) })
                            // 简繁切换不在引导页提供设置：这里只提示去哪里改。
                            ActionSettingRow(tr(R.string.msg_357), "可在「设置 · 实验室」中修改", onClick = { })
                        }
                        else -> {
                            NavTitleItem(pstate) { playerVm.setNavTitleAsSong(!pstate.navTitleAsSong) }
                            MotionItem(pstate) { playerVm.toggleMotionArtwork() }
                        }
                    }
                }
            }

            // 第三页附加提示：实验性功能风险声明。
            if (page == 2) {
                Text(
                    "实验性功能可能会不稳定和使用出现问题，按需开启",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = AppPalette.accent, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }

            // 翻页按钮：唯一的切页入口（方向键永远切不了页）。
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page > 0) {
                    GuidePageButton("上一页") { page-- }
                    Spacer(Modifier.width(14.dp))
                }
                GuidePageButton(if (page < 2) "下一页" else "完成") {
                    if (page < 2) page++ else { GuideState.markCompleted(context); onDone() }
                }
            }
        }
    }
}

// ─────────────────── 预览区：播放页（页 1、2） ───────────────────

/** 播放页预览：左列封面（Ken Burns + 圆角联动）+ 右列真实歌词组件（逐字扫光联动）。 */
@Composable
private fun PlayerPreview(
    pstate: PlayerState,
    wordByWord: Boolean,
    progress: () -> Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左：封面 + 歌名 + 艺人（比例对齐真实播放页的 controls 列）
        Column(
            Modifier.weight(0.4f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val radius = if (pstate.artworkRounded) 10.dp else 0.dp
            Box(
                Modifier.weight(1f)
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .artworkCorners(radius, pstate.renderCompat)
                    .background(Color(0xFF181818)),
            ) {
                // Ken Burns：动态封面开启时 1.0→1.1 缓慢往复缩放，关闭时静止。
                val kb = rememberInfiniteTransition(label = "guideKenBurns")
                val kbScale by kb.animateFloat(
                    initialValue = 1f, targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse),
                    label = "guideKenBurnsScale",
                )
                AsyncImage(
                    model = GuideDemoData.COVER_URI,
                    contentDescription = GuideDemoData.song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val s = if (pstate.motionArtworkEnabled && !pstate.lowPowerMode) kbScale else 1f
                        scaleX = s; scaleY = s
                    },
                )
            }
            Text(GuideDemoData.song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
            Text(GuideDemoData.song.artistName, fontSize = 14.sp,
                color = AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        }

        // 右：真实歌词组件（逐字扫光由模拟进度驱动；字形跟随设置页的简繁状态）
        val lyrics = GuideDemoData.lyrics(pstate.lyricsScript)
        val progressMs = progress()
        val activeIdx = lyrics.indexOfLast { it.startMs <= progressMs }
        Column(
            Modifier.weight(0.6f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val from = (activeIdx - 2).coerceAtLeast(0)
            val to = (activeIdx + 2).coerceAtMost(lyrics.lastIndex)
            for (i in from..to) {
                LyricLineRow(
                    line = lyrics[i],
                    isActive = i == activeIdx,
                    isPast = i < activeIdx,
                    progress = progress,
                    onSeek = { },
                    wordByWord = wordByWord,
                    fontScale = 0.85f,
                    focusable = false,
                )
            }
        }
    }
}

// ─────────────────── 预览区：首页（页 3） ───────────────────

/** 首页预览：顶部歌名条（开关联动）+ 真实专辑卡流 + 真实迷你胶囊（悬浮底部）。 */
@Composable
private fun HomePreview(
    playerVm: PlayerViewModel,
    pstate: PlayerState,
    progress: () -> Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 20.dp, bottom = 96.dp)) {
            // 顶部歌名条：开启时顶栏显示当前歌名，关闭时显示页签本名。
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.widthIn(max = 640.dp).height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF1C1C1E)))
                        .border(1.dp, AppPalette.adaptive(Color(0x14000000), Color(0x14FFFFFF)), RoundedCornerShape(50))
                        .padding(horizontal = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Glyph.MUSIC_NOTE, size = 16.dp, color = AppPalette.accent)
                    Text(
                        if (pstate.navTitleAsSong) "爱人错过 - 告五人" else tr(R.string.msg_087),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            // 卡片流：真实 AlbumCard（不可聚焦），预览「卡片铺满页面」的浏览形态。
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) {
                    AlbumCard(
                        album = GuideDemoData.album,
                        size = 170,
                        onClick = { },
                        motionEnabled = false,
                        focusable = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 底部悬浮：真实迷你胶囊（预览模式：假歌 + 模拟进度，整条不可聚焦、点击空操作）。
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
            MiniPlayerCapsule(
                playerVm = playerVm,
                onOpen = { },
                onOpenQueue = { },
                previewSong = GuideDemoData.song,
                previewProgressMs = progress(),
                heightPct = pstate.miniCapsuleHeightPct,
                widthPct = pstate.miniCapsuleWidthPct,
                albumTint = pstate.miniCapsuleAlbumTint,
                progressLine = pstate.miniCapsuleProgressLine,
            )
        }
    }
}

// ─────────────────── 设置项（文案与设置页强绑定，只留标题） ───────────────────

/** 界面语言：与设置页同一份 Stepper 行为（跟随系统 / 简体中文 / English）。 */
@Composable
private fun LanguageItem(modifier: Modifier = Modifier) {
    val choice = AppLanguage.choice
    val choices = LanguageChoice.entries
    val label = when (choice) {
        LanguageChoice.System -> tr(R.string.msg_311)
        LanguageChoice.Chinese -> "简体中文"
        LanguageChoice.English -> "English"
    }
    Stepper(
        tr(R.string.msg_313), label, "",
        { AppLanguage.select(choices[(choice.ordinal - 1 + choices.size) % choices.size]) },
        { AppLanguage.select(choices[(choice.ordinal + 1) % choices.size]) },
        modifier = modifier,
    )
}

/** 界面模式：深色 / 浅色。 */
@Composable
private fun AppearanceItem(pstate: PlayerState) {
    val ctx = LocalContext.current
    val dark = AppPalette.isDark
    Stepper(
        tx("界面模式", "Appearance"),
        if (dark) tx("深色", "Dark") else tx("浅色", "Light"),
        "",
        { AppPalette.setDark(ctx, false) },
        { AppPalette.setDark(ctx, true) },
        decEnabled = dark, incEnabled = !dark,
    )
}

/** 动态封面（Ken Burns 预览联动）。 */
@Composable
private fun MotionItem(pstate: PlayerState, onToggle: () -> Unit) {
    Toggle(tr(R.string.msg_202), pstate.motionArtworkEnabled, "", onToggle = onToggle)
}

/** 封面圆角（沿用布尔：10dp 圆角 ↔ 直角）。 */
@Composable
private fun RoundedItem(pstate: PlayerState, onToggle: () -> Unit) {
    Toggle(tr(R.string.msg_204), pstate.artworkRounded, "", onToggle = onToggle)
}

/** 音质（与设置页同一份枚举与步进）。 */
@Composable
private fun QualityItem(
    vmState: DevMenuState,
    onDec: () -> Unit,
    onInc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val quality = vmState.audioQuality
    Stepper(
        tx("音质", "Audio quality"),
        tx(quality.label, quality.label),
        "",
        onDec, onInc,
        decEnabled = quality.ordinal > 0,
        incEnabled = quality.ordinal < AudioQuality.entries.lastIndex,
        modifier = modifier,
    )
}

/** 解码方式：硬解 / 软解。 */
@Composable
private fun DecoderItem(pstate: PlayerState, onDec: () -> Unit, onInc: () -> Unit) {
    val hw = pstate.decoderMode == DecoderMode.HARDWARE
    Stepper(
        tx("解码方式", "Audio decoder"),
        if (hw) tx("硬解", "Hardware") else tx("软解", "Software"),
        "",
        onDec, onInc,
        decEnabled = hw, incEnabled = !hw,
    )
}

/** 歌词逐字播放（预览扫光联动）。 */
@Composable
private fun WordByWordItem(on: Boolean, onToggle: () -> Unit) {
    Toggle(tr(R.string.msg_335), on, "", onToggle = onToggle)
}

/** 交叉渐变。 */
@Composable
private fun CrossfadeItem(
    vmState: DevMenuState,
    onDec: () -> Unit,
    onInc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Stepper(
        tr(R.string.msg_187),
        tr(R.string.msg_188).format(AppLanguage.locale, vmState.crossfadeMs / 1000f),
        "",
        onDec, onInc,
        modifier = modifier,
    )
}

/** 顶部栏显示歌名（预览顶栏联动）。 */
@Composable
private fun NavTitleItem(pstate: PlayerState, onToggle: () -> Unit) {
    Toggle(tr(R.string.msg_351), pstate.navTitleAsSong, "", onToggle = onToggle)
}

/** 迷你播放胶囊（预览胶囊联动）。 */
@Composable
private fun MiniCapsuleItem(pstate: PlayerState, onToggle: () -> Unit) {
    Toggle(tx("迷你播放胶囊", "Mini player capsule"), pstate.miniCapsuleEnabled, "", onToggle = onToggle)
}

/** Apple Music 展示（免登录模式下的「AM 账号」项）。 */
@Composable
private fun AppleDisplayItem(state: com.veacon.veilmusic.data.AppleDisplayAuth.State, onToggle: () -> Unit) {
    Toggle(tx("Apple Music 展示", "Apple Music display"), state.enabled, "", onToggle = onToggle)
}

// ─────────────────── 翻页按钮 ───────────────────

/** 圆角胶囊翻页钮：与设置页同配色语言（聚焦变红）。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuidePageButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
            focusedContainerColor = Color(0xFFFA233B),
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None),
    ) {
        Text(
            label,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = AppPalette.adaptive(Color(0xFF1D1D1F), Color.White),
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
        )
    }
}
