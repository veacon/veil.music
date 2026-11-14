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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.veacon.veilmusic.R
import com.veacon.veilmusic.data.AudioQuality
import com.veacon.veilmusic.data.CrossfadePreferences

import com.veacon.veilmusic.i18n.AppLanguage
import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.ui.components.AlbumCard
import com.veacon.veilmusic.ui.components.MiniPlayerCapsule
import com.veacon.veilmusic.ui.components.TopNavBar
import com.veacon.veilmusic.ui.components.artworkCorners
import com.veacon.veilmusic.ui.navigation.TopNavTab
import com.veacon.veilmusic.ui.screens.LyricLineRow
import com.veacon.veilmusic.ui.screens.SettingDivider
import com.veacon.veilmusic.ui.screens.SettingsSection
import com.veacon.veilmusic.ui.screens.Stepper
import com.veacon.veilmusic.ui.screens.Toggle
import com.veacon.veilmusic.ui.theme.AppPalette
import com.veacon.veilmusic.ui.viewmodel.DevMenuState
import com.veacon.veilmusic.ui.viewmodel.DevMenuViewModel
import com.veacon.veilmusic.ui.viewmodel.PlayerState
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

/** 引导页设置文案与设置页同款逻辑：英文界面用英文，其余用中文。 */
private fun tx(zh: String, en: String) = if (AppLanguage.code == "en") en else zh

/** 电视壳子的机身色 / 外缘高光色。 */
private val BezelBody = Color(0xFF101014)
private val BezelEdge = Color(0xFF2E2E36)

/**
 * **新手引导页** —— 左右分屏：左侧 60% 预览区，右侧 40% 控制台。
 *
 * 性能原则（重要）：预览区**只复用真实组件**（[LyricLineRow] / [MiniPlayerCapsule] /
 * [AlbumCard] / [artworkCorners]）+ 内置演示数据，**不挂载整页** [com.veacon.veilmusic.ui.screens.NowPlayingScreen]
 * 或 [com.veacon.veilmusic.ui.screens.HomeScreen] —— 后者会让整棵 UI 树在 AppShell 里被组合两遍，
 * 两套歌词滚动 / 动画 / 焦点逻辑同时跑，全 App 掉帧。
 *
 * - 预览区完全冻结：不可聚焦、吞掉全部按键与触摸；
 * - 焦点只锁在右侧设置卡片与右下角翻页按钮之间；
 * - 右侧设置项就是**真实设置**，拨动立即生效（下次进真实页面自然一致）。
 *
 * 页面：0 = 播放与界面设置（预览播放页）；1 = 实验性功能（预览首页）。
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

    var page by rememberSaveable { mutableIntStateOf(0) }

    // 预览用的模拟进度：20fps 足够驱动歌词扫光（比逐帧省一大截），
    // 且随引导页离开组合而自动取消，不会在后台空转。
    var previewProgressMs by remember { mutableLongStateOf(GuideDemoData.LOOP_START_MS) }
    LaunchedEffect(Unit) {
        val started = android.os.SystemClock.elapsedRealtime()
        val loop = GuideDemoData.LOOP_DURATION_MS.coerceAtLeast(1L)
        while (true) {
            previewProgressMs = GuideDemoData.LOOP_START_MS +
                ((android.os.SystemClock.elapsedRealtime() - started) % loop)
            delay(50)
        }
    }
    val progress: () -> Long = { previewProgressMs }

    // 切页后焦点重置到当前页第一个设置项。
    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(page) {
        withFrameNanos { }
        runCatching { firstItemFocus.requestFocus() }
    }

    // 引导中吞掉返回键：防止误触退出导致引导半途而废。
    BackHandler(enabled = true) { }

    Row(modifier.fillMaxSize().background(AppPalette.background)) {
        // ─────────── 左侧 60%：预览（完全冻结） ───────────
        Box(
            Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .focusProperties { canFocus = false }
                .onPreviewKeyEvent { true }
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
                if (page == 0) PlayerPreview(pstate, wordByWordLyrics, progress, Modifier.fillMaxSize())
                else HomePreview(playerVm, pstate, progress, Modifier.fillMaxSize())
            }
        }

        // ─────────── 右侧 40%：控制台（对齐设置页） ───────────
        Column(
            Modifier.weight(0.4f).fillMaxHeight()
                .padding(start = 20.dp, end = 34.dp, top = 36.dp, bottom = 20.dp),
        ) {
            Text(
                if (page == 0) tx("播放与界面设置", "Playback & appearance") else tx("实验性功能", "Experimental"),
                fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AppPalette.primary,
            )
            Text(
                tx("左侧预览区不可操作，只会跟着这里的开关变化。",
                   "The preview is frozen — it only follows the switches below."),
                fontSize = 13.sp, color = AppPalette.secondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
            )

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
            ) {
                Column(Modifier.fillMaxWidth().focusGroup(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (page == 0) {
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
                            Toggle(tr(R.string.msg_335), wordByWordLyrics, "",
                                onToggle = { settingsVm.setWordByWordLyrics(!wordByWordLyrics) })
                        }
                    } else {
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

            // 翻页按钮：固定在右下角，永远可见（触摸与遥控器都能用）。
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
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

// ─────────────────── 电视壳子（16:9，两层，扁平） ───────────────────

/**
 * 把 [content] 放进一个居中的 16:9「电视壳子」。
 * 只有两层：机身（圆角 + 描边 + 内边距即边框厚度）→ 内容（一次圆角裁切），
 * 避免多层 clip 造成离屏合成开销。
 */
@Composable
private fun TvFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val bezel = 14.dp
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val innerWidth = minOf(maxWidth - bezel * 2, (maxHeight - bezel * 2) * 16f / 9f)
        val innerHeight = innerWidth * 9f / 16f
        Box(
            Modifier
                .size(innerWidth + bezel * 2, innerHeight + bezel * 2)
                .clip(RoundedCornerShape(22.dp))
                .background(BezelBody)
                .border(2.dp, BezelEdge, RoundedCornerShape(22.dp))
                .padding(bezel)
                .clip(RoundedCornerShape(10.dp)),
            content = { content() },
        )
    }
}

// ─────────────────── 预览区：播放页 ───────────────────

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
                        transformOrigin = TransformOrigin.Center
                    },
                )
            }
            Text(GuideDemoData.song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
            Text(GuideDemoData.song.artistName, fontSize = 14.sp,
                color = AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        }

        // 右：真实歌词组件（逐字扫光由模拟进度驱动；字形跟随简繁设置）。
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

// ─────────────────── 预览区：首页 ───────────────────

/** 首页预览：顶部歌名条（开关联动）+ 真实专辑卡流 + 真实迷你胶囊（开关关闭则整条不组合）。 */
@Composable
private fun HomePreview(
    playerVm: PlayerViewModel,
    pstate: PlayerState,
    progress: () -> Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 20.dp, bottom = 96.dp)) {
            // 顶栏直接用**真实 TopNavBar**（不可聚焦）：页签、头像、位置全部与真实界面一致。
            // 「顶部栏显示歌名」开启时，最左侧的「正在播放」胶囊替换成当前歌名 —— 只显示歌名，
            // 不带歌手（与真实顶栏行为完全相同）。
            TopNavBar(
                selected = TopNavTab.ListenNow,
                onSelect = { },
                isPlaying = true,
                nowPlayingTitle = if (pstate.navTitleAsSong) "爱人错过" else null,
                focusEnabled = false,
            )
            Spacer(Modifier.height(18.dp))
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

        // 底部悬浮真实胶囊（预览模式：假歌 + 模拟进度）。开关关掉 → 整条不组合。
        if (pstate.miniCapsuleEnabled) {
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
}

// ─────────────────── 设置项（复用设置页组件与文案） ───────────────────

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
        quality.label,
        "",
        onDec, onInc,
        decEnabled = quality.ordinal > 0,
        incEnabled = quality.ordinal < AudioQuality.entries.lastIndex,
        modifier = modifier,
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

// ─────────────────── 翻页按钮（遥控器 + 触摸） ───────────────────

/**
 * 圆角胶囊翻页钮。电视端 [Surface] 只认遥控器 OK，这里显式补触摸点击，
 * 并用 350ms 去抖避免「触摸 + OK」重复触发导致一次跳两页。
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
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
        )
    }
}
