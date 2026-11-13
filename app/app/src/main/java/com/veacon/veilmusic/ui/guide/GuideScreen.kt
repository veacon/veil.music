package com.veacon.veilmusic.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.veacon.veilmusic.R
import com.veacon.veilmusic.data.AudioQuality
import com.veacon.veilmusic.data.CrossfadePreferences
import com.veacon.veilmusic.data.DecoderMode
import com.veacon.veilmusic.i18n.AppLanguage
import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.ui.components.MiniPlayerCapsule
import com.veacon.veilmusic.ui.components.TopNavBar
import com.veacon.veilmusic.ui.navigation.TopNavTab
import com.veacon.veilmusic.ui.screens.HomeScreen
import com.veacon.veilmusic.ui.screens.NowPlayingScreen
import com.veacon.veilmusic.ui.screens.SettingDivider
import com.veacon.veilmusic.ui.screens.SettingsSection
import com.veacon.veilmusic.ui.screens.Stepper
import com.veacon.veilmusic.ui.screens.Toggle
import com.veacon.veilmusic.ui.theme.AppPalette
import com.veacon.veilmusic.ui.viewmodel.DevMenuState
import com.veacon.veilmusic.ui.viewmodel.DevMenuViewModel
import com.veacon.veilmusic.ui.viewmodel.HomeViewModel
import com.veacon.veilmusic.ui.viewmodel.NavigationViewModel
import com.veacon.veilmusic.ui.viewmodel.PlayerState
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel

/** 引导页设置文案与设置页同款逻辑：英文界面用英文，其余用中文。 */
private fun tx(zh: String, en: String) = if (AppLanguage.code == "en") en else zh

/** 电视壳子的机身色 / 外缘高光色（深浅主题共用，模拟屏幕外的塑料边框）。 */
private val BezelBody = Color(0xFF101014)
private val BezelEdge = Color(0xFF2E2E36)

/**
 * **新手引导页** —— 左右分屏：左侧 60% 电视预览区，右侧 40% 控制台。
 *
 * - **左侧**：把真实的 [NowPlayingScreen]（第 1 页）/ [HomeScreen] + [TopNavBar] +
 *   [MiniPlayerCapsule]（第 2 页）**按 16:9 缩放**塞进一个"电视壳子"里居中展示；
 *   壳子以外的留白与右侧控制台同底色（页面背景），不会出现刺眼的色块拼接。
 * - **左侧完全冻结**：整块 `focusProperties { canFocus = false }` + 吞掉全部按键与触摸；
 *   万一被真实屏幕内部的 FocusRequester 抢走焦点，focus jail 会立刻弹回右侧。
 * - **右侧完全对齐设置页**：同款页面标题（32sp Bold）/ 副标题 / [SettingsSection] 分区卡片
 *   （同底色 + 14dp 圆角）/ [Toggle] · [Stepper] 行（≥84dp 行高，控件右对齐）。
 *
 * 页面：0 = 播放与界面设置（左：播放页）；1 = 实验性功能（左：首页）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GuideScreen(
    playerVm: PlayerViewModel,
    settingsVm: DevMenuViewModel,
    homeVm: HomeViewModel,
    navVm: NavigationViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    guideVm: GuideViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val pstate by playerVm.state.collectAsState()
    val vmState by settingsVm.state.collectAsState()
    val wordByWordLyrics by settingsVm.wordByWordLyrics.collectAsState()

    // 页面：0=播放与界面设置（左侧播放页）1=实验性功能（左侧首页）
    var page by rememberSaveable { mutableIntStateOf(0) }

    // 切页后焦点重置到当前页第一个设置项（进入引导页时同样生效）。
    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(page) {
        withFrameNanos { }
        runCatching { firstItemFocus.requestFocus() }
    }

    // 引导中吞掉返回键：防止误触退出导致引导半途而废。
    BackHandler(enabled = true) { }

    // 进入引导页自动播放演示曲目《爱人错过》：让左侧真实播放页不是空态。
    // 已有曲目在播时不打断 —— 只有当前没有歌的时候才接管。
    LaunchedEffect(Unit) {
        if (playerVm.state.value.currentSong != null) return@LaunchedEffect
        guideVm.demoSong()?.let { playerVm.playSong(it) }
    }

    // focus jail：左侧真实屏幕自带 FocusRequester，可能把光标抢进预览区。
    // 一旦发现焦点落到左侧，立刻弹回右侧第一个设置项 —— 保证"焦点只锁在右侧"。
    var leftStoleFocus by remember { mutableStateOf(false) }
    LaunchedEffect(leftStoleFocus) {
        if (leftStoleFocus) {
            leftStoleFocus = false
            runCatching { firstItemFocus.requestFocus() }
        }
    }

    Row(modifier.fillMaxSize().background(AppPalette.background)) {
        // ─────────── 左侧 60%：16:9 电视预览（完全冻结） ───────────
        Box(
            Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .clipToBounds()
                .focusProperties { canFocus = false }
                .onFocusChanged { if (it.hasFocus) leftStoleFocus = true }
                // 预览（preview）阶段吞掉全部按键：方向键不会在预览区里移动/触发任何控件。
                .onPreviewKeyEvent { true }
                // 吞掉触摸：真实屏幕的 onClick 不会被点到。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            TvFrame(Modifier.fillMaxSize().padding(20.dp)) {
                when (page) {
                    0 -> NowPlayingScreen(
                        playerVm = playerVm,
                        navVm = navVm,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> HomePreview(playerVm, homeVm, pstate, Modifier.fillMaxSize())
                }
            }
        }

        // ─────────── 右侧 40%：控制台（对齐设置页） ───────────
        Column(
            Modifier.weight(0.4f).fillMaxHeight()
                .padding(start = 20.dp, end = 34.dp, top = 36.dp, bottom = 20.dp),
        ) {
            // 页面标题：与设置页同款字号与颜色。
            Text(
                if (page == 0) tx("播放与界面设置", "Playback & appearance") else tx("实验性功能", "Experimental"),
                fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AppPalette.primary,
            )
            Text(
                tx("左侧预览区不可操作，只会跟着这里的开关变化。",
                   "The TV preview is frozen — it only follows the switches below."),
                fontSize = 13.sp,
                color = AppPalette.secondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
            )

            // 设置项可滚动；翻页按钮固定在下方，永远看得见、点得到（触摸 / 遥控器都行）。
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
            ) {
                Column(Modifier.fillMaxWidth().focusGroup(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (page == 0) {
                    // 第 1 页：5 项，单列。
                    SettingsSection(tx("播放与界面", "Playback & interface")) {
                        Toggle(tr(R.string.msg_202), pstate.motionArtworkEnabled, "",
                            modifier = Modifier.focusRequester(firstItemFocus),
                            onToggle = { playerVm.toggleMotionArtwork() })
                        SettingDivider()
                        Toggle(tr(R.string.msg_204), pstate.artworkRounded, "",
                            onToggle = { playerVm.toggleArtworkRounded() })
                        SettingDivider()
                        QualityItem(vmState,
                            onDec = { settingsVm.stepAudioQuality(-1) },
                            onInc = { settingsVm.stepAudioQuality(1) })
                        SettingDivider()
                        DecoderItem(pstate,
                            onDec = { playerVm.setDecoderMode(DecoderMode.SOFTWARE) },
                            onInc = { playerVm.setDecoderMode(DecoderMode.HARDWARE) })
                        SettingDivider()
                        Toggle(tr(R.string.msg_335), wordByWordLyrics, "",
                            onToggle = { settingsVm.setWordByWordLyrics(!wordByWordLyrics) })
                    }
                } else {
                    // 第 2 页：3 项，单列。
                    SettingsSection(tx("实验性功能", "Experimental")) {
                        CrossfadeItem(vmState,
                            onDec = { settingsVm.setCrossfade(CrossfadePreferences.step(vmState.crossfadeMs, -1)) },
                            onInc = { settingsVm.setCrossfade(CrossfadePreferences.step(vmState.crossfadeMs, 1)) },
                            modifier = Modifier.focusRequester(firstItemFocus))
                        SettingDivider()
                        Toggle(tr(R.string.msg_351), pstate.navTitleAsSong, "",
                            onToggle = { playerVm.setNavTitleAsSong(!pstate.navTitleAsSong) })
                        SettingDivider()
                        Toggle(tx("迷你播放胶囊", "Mini player capsule"), pstate.miniCapsuleEnabled, "",
                            onToggle = { playerVm.setMiniCapsuleEnabled(!pstate.miniCapsuleEnabled) })
                    }
                    Text(
                        tx("实验性功能可能会不稳定和使用出现问题，按需开启",
                           "Experimental features can be unstable — enable only if you need them."),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppPalette.accent,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            }

            // 翻页按钮：唯一的切页入口（方向键永远切不了页）。
            Row(
                Modifier.fillMaxWidth().padding(top = 26.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page > 0) {
                    GuidePageButton(tx("上一页", "Back")) { page-- }
                    Spacer(Modifier.width(14.dp))
                }
                GuidePageButton(if (page < 1) tx("下一页", "Next") else tx("完成", "Done")) {
                    if (page < 1) page++ else { GuideState.markCompleted(context); onDone() }
                }
            }
        }
    }
}

// ─────────────────── 左侧：电视壳子（16:9 居中） ───────────────────

/**
 * 把 [content] 按 **16:9** 等比缩放进一个居中的"电视壳子"里：
 * 先按真实电视上的布局尺寸（1080p / density 2 ≈ 960×540dp）排版，再用 [graphicsLayer]
 * 整体缩小，这样预览与真机是**同一套比例**，而不是在小盒子里重新排版（后者会让控件显得巨大）。
 * 壳子之外由调用方保持页面底色，所以留白与右侧控制台同色。
 */
@Composable
private fun TvFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val bezel = 14.dp
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // 可用区域内能放下的最大 16:9 屏幕；真实界面直接按 16:9 自适应铺满（不做等比缩放）。
        val innerWidth = minOf(maxWidth - bezel * 2, (maxHeight - bezel * 2) * 16f / 9f)
        val innerHeight = innerWidth * 9f / 16f
        Box(
            Modifier
                .size(innerWidth + bezel * 2, innerHeight + bezel * 2)
                // 机身
                .clip(RoundedCornerShape(22.dp))
                .background(BezelBody)
                .border(2.dp, BezelEdge, RoundedCornerShape(22.dp))
                .padding(bezel),
        ) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).clipToBounds()) { content() }
        }
    }
}

// ─────────────────── 左侧第 2 页：真实首页（顶栏 + 首页 + 胶囊） ───────────────────

/**
 * 首页预览：按 AppShell 的层级原样拼装**真实**组件 —— 顶栏 [TopNavBar] + 首页 [HomeScreen]
 * + 底部 [MiniPlayerCapsule]，全部使用真实数据。
 *
 * 因此「顶部歌名」开关（`navTitleAsSong`）与「迷你胶囊」开关（`miniCapsuleEnabled`）
 * 一关，画面上对应的元素就**彻底消失**。
 */
@Composable
private fun HomePreview(
    playerVm: PlayerViewModel,
    homeVm: HomeViewModel,
    pstate: PlayerState,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：不可聚焦（focusEnabled=false），仍是真实组件与真实歌名逻辑。
            TopNavBar(
                selected = TopNavTab.ListenNow,
                onSelect = { },
                isPlaying = pstate.isPlaying,
                beatAnalyzer = playerVm.beatAnalyzer,
                nowPlayingTitle = if (pstate.navTitleAsSong) pstate.currentSong?.title else null,
                focusEnabled = false,
            )
            HomeScreen(
                playerVm = playerVm,
                vm = homeVm,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 底部真实迷你胶囊：开关关闭就整条不组合。
        if (pstate.miniCapsuleEnabled) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp)) {
                MiniPlayerCapsule(
                    playerVm = playerVm,
                    onOpen = { },
                    onOpenQueue = { },
                    heightPct = pstate.miniCapsuleHeightPct,
                    widthPct = pstate.miniCapsuleWidthPct,
                    albumTint = pstate.miniCapsuleAlbumTint,
                    progressLine = pstate.miniCapsuleProgressLine,
                )
            }
        }
    }
}

// ─────────────────── 设置项（文案与设置页强绑定，只留标题） ───────────────────

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

// ─────────────────── 翻页按钮 ───────────────────

/**
 * 圆角胶囊翻页钮：与设置页同配色语言（聚焦变红）。
 *
 * 电视端的 [Surface] 只认遥控器 OK，触摸点不动 —— 这里显式补一个 [detectTapGestures]，
 * 并用 350ms 去抖，避免"触摸 + OK"重复触发导致一次跳两页。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuidePageButton(label: String, onClick: () -> Unit) {
    var lastFire by remember { mutableStateOf(0L) }
    val fire: () -> Unit = {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFire > 350L) {
            lastFire = now
            onClick()
        }
    }
    Surface(
        onClick = fire,
        modifier = Modifier.pointerInput(Unit) { detectTapGestures { fire() } },
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
