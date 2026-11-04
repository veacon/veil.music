package com.veil.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.veil.music.BuildConfig
import com.veil.music.data.VeilPreferences
import com.veil.music.data.model.Quality
import com.veil.music.data.model.SourceID
import com.veil.music.data.model.UserScript
import com.veil.music.ui.components.QrDialog
import com.veil.music.ui.navigation.LocalNavBarFocus
import com.veil.music.ui.viewmodel.LYRIC_FONT_MAX_SP
import com.veil.music.ui.viewmodel.LYRIC_FONT_MIN_SP
import com.veil.music.ui.viewmodel.LYRIC_FONT_STEP_SP
import com.veil.music.ui.viewmodel.LYRIC_GAP_MAX_PCT
import com.veil.music.ui.viewmodel.LYRIC_GAP_MIN_PCT
import com.veil.music.ui.viewmodel.LYRIC_GAP_STEP_PCT
import com.veil.music.ui.viewmodel.FS_FONT_MAX_SP
import com.veil.music.ui.viewmodel.FS_FONT_MIN_SP
import com.veil.music.ui.viewmodel.FS_FONT_STEP_SP
import com.veil.music.ui.viewmodel.FS_GAP_MAX_PCT
import com.veil.music.ui.viewmodel.FS_GAP_MIN_PCT
import com.veil.music.ui.viewmodel.FS_GAP_STEP_PCT
import com.veil.music.ui.viewmodel.FS_TRACKING_MAX
import com.veil.music.ui.viewmodel.FS_TRACKING_MIN
import com.veil.music.ui.viewmodel.FS_TRACKING_STEP
import com.veil.music.ui.viewmodel.FsStyle
import com.veil.music.ui.viewmodel.FsStylePrefs
import com.veil.music.ui.viewmodel.LyricFontChoice
import com.veil.music.ui.viewmodel.PlayerState
import com.veil.music.ui.theme.MingMeiSpring
import com.veil.music.ui.theme.PingFang
import com.veil.music.ui.viewmodel.NowPlayingBackground
import com.veil.music.ui.viewmodel.PlayerViewModel
import com.veil.music.ui.viewmodel.ScriptCoverage
import com.veil.music.ui.viewmodel.SettingsUiState
import com.veil.music.ui.viewmodel.SettingsViewModel

// ── iOS 设置页的配色（取 iOS 深色模式的实际值） ──────────────────────────────
private val IosBg        = Color(0xFF000000)
private val IosCard      = Color(0xFF1C1C1E)
private val IosCardFocus = Color(0xFF3A3A3C)
private val IosSeparator = Color(0xFF38383A)
private val IosLabel     = Color(0xFFFFFFFF)
private val IosValue     = Color(0xFF8E8E93)
private val IosChevron   = Color(0xFF5A5A5F)
private val IosRed       = Color(0xFFFA233B)
private val IosGreen     = Color(0xFF34C759)
private val IosSwitchOff = Color(0xFF39393D)

/**
 * 设置。
 *
 * 版式照搬 iOS「设置」：纯黑底 + 一条一条的 inset grouped 列表。一栏一级菜单在左、
 * 二级内容在右 —— 电视是 16:9，单列长列表在 3 米视距下要一直按方向下，双栏省一半按键。
 *
 * 与上一版的区别（上一版是「每行一张卡 + 红点 + 红边」）：
 *  - 一个分组只有**一张**卡片，行与行之间是一条 0.5dp 的分隔线，不再是一堆独立小卡；
 *  - 焦点是整行底色变亮（#3A3A3C），不是外围一圈描边；
 *  - 状态用 iOS 的控件表达：开关是绿色胶囊，单选是红色对勾，可进入的是灰色 chevron，
 *    不再写「开 / 关」「使用中」这种字。
 */
@Composable
fun SettingsScreen(
    playerVm: PlayerViewModel,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val update by vm.update.collectAsState()
    val playerState by playerVm.state.collectAsState()
    var section by rememberSaveable { mutableStateOf(SettingsSection.AUDIO) }
    // 全屏歌词页里当前在调哪一个界面（全屏歌词 / 墨卷 / 声轨）。放在这里而不是 when 分支里：
    // LazyColumn 的 builder 不是 @Composable，拿不到 rememberSaveable。
    var fsStyle by rememberSaveable { mutableStateOf(FsStyle.PLAIN) }
    var showQr by remember { mutableStateOf(false) }
    // 左栏在第一屏的最左边，和顶栏居中的胶囊横向不重叠 —— 不加这条，按上键会卡在左栏。
    val navBarFocus = LocalNavBarFocus.current

    Box(modifier.fillMaxSize().background(IosBg)) {
        Row(Modifier.fillMaxSize().padding(top = 12.dp)) {
            // ── 左：一级菜单 ──────────────────────────────────────────────
            Column(Modifier.width(300.dp).fillMaxHeight().padding(start = 52.dp)) {
                Text(
                    "设置", color = IosLabel, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 18.dp),
                )
                IOSGroup {
                    SettingsSection.entries.forEachIndexed { i, s ->
                        NavRow(
                            label = s.label,
                            value = s.summary(state, playerState.lyricFontSp, playerState.lyricLineGapPct),
                            selected = section == s,
                            chevron = false,
                            upFocus = if (i == 0) navBarFocus else null,
                            onClick = { section = s },
                        )
                        if (i != SettingsSection.entries.lastIndex) IOSDivider()
                    }
                }
            }

            Spacer(Modifier.width(28.dp))

            // ── 右：二级内容 ──────────────────────────────────────────────
            val listState = rememberLazyListState()
            // 换栏目回到顶部：否则从「关于」的长度列表切到「音质」时，滚动位置会带过去，
            // 一进来就停在中间，看起来像内容没加载出来。
            LaunchedEffect(section) { listState.scrollToItem(0) }

            LazyColumn(
                state = listState,
                // 限宽而不是铺满：iOS 设置的列表本来就窄，1920 宽下铺满会读得很累。
                modifier = Modifier.widthIn(max = 900.dp).fillMaxHeight(),
                contentPadding = PaddingValues(end = 56.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item("header") {
                    Text(section.label, color = IosLabel, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(section.desc, color = IosValue, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                }

                when (section) {
                    SettingsSection.AUDIO -> {
                        item("quality") {
                            IOSGroup(title = "默认音质") {
                                Quality.entries.forEachIndexed { i, q ->
                                    CheckRow(q.displayName, selected = state.quality == q) { vm.setQuality(q) }
                                    if (i != Quality.entries.lastIndex) IOSDivider()
                                }
                            }
                        }
                        item("quality-tip") {
                            Footer("解析时按此优先级向脚本请求；平台没有该档位会自动降级，实际音质显示在播放页。")
                        }

                        item("decoder") {
                            IOSGroup(title = "解码方式") {
                                CheckRow("硬解", sub = "设备硬件解码器，功耗低、CPU 占用小", selected = !playerState.useSoftwareDecoder) {
                                    playerVm.setUseSoftwareDecoder(false)
                                }
                                IOSDivider()
                                CheckRow("软解", sub = "硬解有杂音、爆音或播不动时切这里", selected = playerState.useSoftwareDecoder) {
                                    playerVm.setUseSoftwareDecoder(true)
                                }
                            }
                        }
                        item("decoder-tip") {
                            Footer("切换会重建播放器，当前位置会自动接上；本机解码器情况见「关于」。")
                        }

                        item("source") {
                            IOSGroup(title = "换源") {
                                SwitchRow(
                                    "自动换源",
                                    sub = "当前源解析失败时，按歌名 + 歌手 + 时长去别的平台找同一首",
                                    checked = state.toggleSource,
                                ) { vm.setToggleSource(!state.toggleSource) }
                                IOSDivider()
                                SwitchRow(
                                    "内置直连兜底",
                                    sub = "脚本和换源都失败时，用酷我 / 网易云公开接口直连取流",
                                    checked = state.builtinFallback,
                                ) { vm.setBuiltinFallback(!state.builtinFallback) }
                                IOSDivider()
                                SwitchRow(
                                    "换源提示",
                                    sub = "发生换源或音质降级时在播放页角落提示",
                                    checked = state.showResolveHint,
                                ) { vm.setShowResolveHint(!state.showResolveHint) }
                            }
                        }
                    }

                    SettingsSection.SOURCE -> {
                        item("home-source") {
                            IOSGroup(
                                title = "首页数据源",
                                footer = "决定发现页的歌单与榜单来自哪个平台。切换后首页立即重新拉取，不需要重启。",
                            ) {
                                VeilPreferences.HOME_SOURCES.forEachIndexed { i, src ->
                                    CheckRow(src.displayName, selected = state.homeSource == src) { vm.setHomeSource(src) }
                                    if (i != VeilPreferences.HOME_SOURCES.lastIndex) IOSDivider()
                                }
                            }
                        }

                        item("scripts-head") {
                            val failed = state.scripts.count { it.loadError != null }
                            val platforms = state.coverage.values.flatMapTo(mutableSetOf()) { it.sources }
                            IOSGroupHeader(
                                title = "音源脚本（${state.scripts.size}）",
                                sub = buildString {
                                    append("内置 ${state.scripts.count { it.builtin }} 个")
                                    append(" · 已启用 ${state.scripts.count { it.enabled && it.loadError == null }} 个")
                                    if (platforms.isNotEmpty()) append(" · 覆盖 ${platforms.size} 个平台")
                                    if (failed > 0) append(" · 加载失败 $failed 个")
                                },
                                action = "恢复内置",
                            ) { vm.restoreBuiltIns() }
                        }
                        item("scripts-tip") {
                            Footer("某个源失效时直接删掉即可，删掉的内置源不会在重启后自己回来。")
                        }
                        // 所有脚本装在同一个分组里：一行一个卡片会退化回「一堆独立小卡」，
                        // 正是这一版要甩掉的观感。LazyColumn 允许单个 item 很高，照常滚动。
                        item("scripts") {
                            IOSGroup {
                                state.scripts.forEachIndexed { i, script ->
                                    ScriptRow(script, state.coverage[script.id], vm)
                                    if (i != state.scripts.lastIndex) IOSDivider()
                                }
                            }
                        }

                        item("mobile") {
                            IOSGroup(
                                title = "手机管理",
                                footer = "电视遥控器上没法粘贴大段脚本，所以音源用手机浏览器管理。",
                            ) {
                                SwitchRow(
                                    "手机管理页",
                                    sub = if (state.webServerEnabled) state.serverUrl else "关闭中，打开后可扫码进入",
                                    checked = state.webServerEnabled,
                                ) { vm.setWebServerEnabled(!state.webServerEnabled) }
                                IOSDivider()
                                NavRow("打开二维码") {
                                    // 先确认服务在跑，再取地址：关着的状态下扫码只会看到连接失败。
                                    if (!state.webServerEnabled) vm.setWebServerEnabled(true)
                                    vm.refreshServerUrl()
                                    showQr = true
                                }
                            }
                        }
                    }

                    SettingsSection.PLAYBACK -> {
                        item("lyric") {
                            IOSGroup(
                                title = "歌词",
                                footer = "字号 $LYRIC_FONT_MIN_SP–$LYRIC_FONT_MAX_SP sp，行距 $LYRIC_GAP_MIN_PCT–$LYRIC_GAP_MAX_PCT%。行距只拉开行与行，不改字号。",
                            ) {
                                StepperRow(
                                    label = "字号",
                                    value = "${playerState.lyricFontSp} sp",
                                    onMinus = { playerVm.nudgeLyricFontSp(-LYRIC_FONT_STEP_SP) },
                                    onPlus = { playerVm.nudgeLyricFontSp(LYRIC_FONT_STEP_SP) },
                                )
                                IOSDivider()
                                StepperRow(
                                    label = "行距",
                                    value = "${playerState.lyricLineGapPct}%",
                                    onMinus = { playerVm.nudgeLyricLineGapPct(-LYRIC_GAP_STEP_PCT) },
                                    onPlus = { playerVm.nudgeLyricLineGapPct(LYRIC_GAP_STEP_PCT) },
                                )
                            }
                        }

                        item("background") {
                            IOSGroup(title = "播放页背景") {
                                NowPlayingBackground.entries.forEachIndexed { i, mode ->
                                    CheckRow(mode.label, sub = mode.desc, selected = playerState.nowPlayingBackground == mode) {
                                        playerVm.setNowPlayingBackground(mode)
                                    }
                                    if (i != NowPlayingBackground.entries.lastIndex) IOSDivider()
                                }
                            }
                        }

                        item("power") {
                            IOSGroup(title = "播放与功耗") {
                                SwitchRow("减少动效", sub = "关闭封面动效与背景漂移，老设备更流畅", checked = playerState.reduceMotion) {
                                    playerVm.toggleReduceMotion()
                                }
                                IOSDivider()
                                SwitchRow("低功耗模式", sub = "降级背景渲染，长时间待机更省电", checked = playerState.lowPowerMode) {
                                    playerVm.toggleLowPowerMode()
                                }
                                IOSDivider()
                                SwitchRow("音量均衡", sub = "对齐不同曲目的响度（实验性）", checked = playerState.volumeLeveling) {
                                    playerVm.toggleVolumeLeveling()
                                }
                                IOSDivider()
                                SwitchRow("后台播放", sub = "离开应用后继续播放", checked = playerState.backgroundPlayEnabled) {
                                    playerVm.toggleBackgroundPlay()
                                }
                            }
                        }
                    }

                    SettingsSection.FS_LYRICS -> {
                        val pref = playerState.fsPrefs(fsStyle)

                        item("fs-style") {
                            IOSGroup(
                                title = "界面",
                                footer = "三个界面各存一套排版：调墨卷不会影响声轨，也不会影响播放页右栏的歌词。",
                            ) {
                                FsStyle.entries.forEachIndexed { i, st ->
                                    CheckRow(st.label, sub = st.desc, selected = fsStyle == st) { fsStyle = st }
                                    if (i != FsStyle.entries.lastIndex) IOSDivider()
                                }
                            }
                        }

                        item("fs-preview") {
                            FullscreenLyricPreview(fsStyle, pref)
                        }

                        item("fs-font") {
                            IOSGroup(
                                title = "字体",
                                footer = "只作用于这个界面，不影响界面字体与播放页右栏。",
                            ) {
                                LyricFontChoice.entries.forEachIndexed { i, choice ->
                                    CheckRow(
                                        choice.label,
                                        sub = when (choice) {
                                            LyricFontChoice.SYSTEM -> "跟随界面（苹方）"
                                            LyricFontChoice.PINGFANG -> "界面的默认字体，最稳、最清晰"
                                            LyricFontChoice.MINGMEI -> "上游项目内置的标题字体，笔画更软"
                                        },
                                        selected = pref.font == choice,
                                    ) { playerVm.setFsFont(fsStyle, choice) }
                                    if (i != LyricFontChoice.entries.lastIndex) IOSDivider()
                                }
                            }
                        }

                        item("fs-type") {
                            IOSGroup(
                                title = "排版",
                                footer = "字号 $FS_FONT_MIN_SP–$FS_FONT_MAX_SP sp；行距 $FS_GAP_MIN_PCT–$FS_GAP_MAX_PCT%；" +
                                    "字间距 ${FS_TRACKING_MIN / 100f}–${FS_TRACKING_MAX / 100f} em。改动即时反映在上方预览。",
                            ) {
                                StepperRow(
                                    label = "字号",
                                    value = "${pref.fontSp} sp",
                                    onMinus = { playerVm.nudgeFsFontSp(fsStyle, -FS_FONT_STEP_SP) },
                                    onPlus = { playerVm.nudgeFsFontSp(fsStyle, FS_FONT_STEP_SP) },
                                )
                                IOSDivider()
                                StepperRow(
                                    label = "行距",
                                    value = "${pref.lineGapPct}%",
                                    onMinus = { playerVm.nudgeFsLineGapPct(fsStyle, -FS_GAP_STEP_PCT) },
                                    onPlus = { playerVm.nudgeFsLineGapPct(fsStyle, FS_GAP_STEP_PCT) },
                                )
                                IOSDivider()
                                StepperRow(
                                    label = "字间距",
                                    value = String.format("%.2f em", pref.letterSpacing / 100f),
                                    onMinus = { playerVm.nudgeFsLetterSpacing(fsStyle, -FS_TRACKING_STEP) },
                                    onPlus = { playerVm.nudgeFsLetterSpacing(fsStyle, FS_TRACKING_STEP) },
                                )
                            }
                        }

                        if (fsStyle.hasPaper) item("fs-light") {
                            IOSGroup(title = "纸面") {
                                SwitchRow(
                                    "浅色纸面",
                                    sub = if (fsStyle == FsStyle.INK) "宣纸底、墨色字" else "工程纸底、深色字",
                                    checked = pref.light,
                                ) { playerVm.setFsLight(fsStyle, !pref.light) }
                            }
                        }

                        item("fs-tip") {
                            Footer(
                                when (fsStyle) {
                                    FsStyle.PLAIN -> "播放页按 ⋯ →「全屏歌词」进入；返回键退出。"
                                    FsStyle.INK -> "播放页按 ⋯ →「墨卷（全屏）」进入；返回键退出。"
                                    FsStyle.TRACK -> "播放页按 ⋯ →「声轨（全屏）」进入；返回键退出。"
                                },
                            )
                        }
                    }

                    SettingsSection.ABOUT -> {
                        val device = state.device
                        item("version") {
                            IOSGroup {
                                InfoRow("应用", "薄音 Veil Music", BuildConfig.VERSION_NAME)
                                IOSDivider()
                                InfoRow("音源协议", "lx-music v4 用户脚本", null)
                                IOSDivider()
                                InfoRow("最低系统", "Android 6.0（API 23）", null)
                            }
                        }
                        item("device") {
                            IOSGroup(title = "本机信息") {
                                InfoRow("设备", device?.model ?: "读取中…", null)
                                IOSDivider()
                                InfoRow("系统", device?.android ?: "—", null)
                                IOSDivider()
                                InfoRow("架构", device?.abi ?: "—", null)
                                IOSDivider()
                                InfoRow("屏幕", device?.screen ?: "—", null)
                                IOSDivider()
                                InfoRow("内存", device?.ram ?: "—", null)
                            }
                        }
                        item("audio") {
                            IOSGroup(
                                title = "音频解码",
                                footer = "「平台解码器」是 MediaCodec 里音频解码器的数量分布；切到软解后数字没变，说明这台设备的音频解码器本来就只有一种实现。",
                            ) {
                                InfoRow(
                                    "当前解码",
                                    if (playerState.useSoftwareDecoder) "软解 · 软件解码器优先" else "硬解 · 硬件解码器优先",
                                    null,
                                )
                                IOSDivider()
                                InfoRow("音频输出", device?.output ?: "—", null)
                                IOSDivider()
                                InfoRow("多声道直通", device?.passthrough ?: "—", null)
                                IOSDivider()
                                InfoRow("平台解码器", device?.decoders ?: "—", null)
                            }
                        }
                        item("update") {
                            IOSGroup(
                                title = "更新",
                                footer = "应用不提供、不存储任何音频内容；播放地址由脚本与平台公开接口即时解析。",
                            ) {
                                ActionRow(
                                    label = "检查更新",
                                    value = update.message ?: "当前 v${BuildConfig.VERSION_NAME}",
                                    action = if (update.checking) "检查中…" else "检查",
                                    enabled = !update.checking,
                                ) { vm.checkUpdate() }
                                if (update.available != null) {
                                    IOSDivider()
                                    InfoRow("新版本", "已发布，可在发布页下载 APK 覆盖安装", null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQr) QrDialog(url = state.serverUrl, onDismiss = { showQr = false })
}

// ── 一级菜单 ────────────────────────────────────────────────────────────────

private enum class SettingsSection(val label: String, val desc: String) {
    AUDIO("音质", "默认音质、解码方式与换源"),
    SOURCE("音源", "首页数据源、音源脚本与手机管理"),
    PLAYBACK("播放与外观", "歌词字号与行距、播放背景、动效与功耗"),
    FS_LYRICS("全屏歌词", "全屏歌词 / 墨卷 / 声轨，各一套字体与排版"),
    ABOUT("关于", "本机信息、解码参数与更新检查"),
    ;

    fun summary(state: SettingsUiState, lyricFontSp: Int, lineGapPct: Int): String = when (this) {
        AUDIO -> state.quality.displayName.substringBefore(' ')
        SOURCE -> state.homeSource.displayName
        PLAYBACK -> "${lyricFontSp}sp"
        FS_LYRICS -> "3 种"
        ABOUT -> "v${BuildConfig.VERSION_NAME}"
    }
}

// ── iOS 组件 ────────────────────────────────────────────────────────────────

/** 一个分组：可选的小标题 + 一张圆角卡片 + 可选的脚注。 */
@Composable
private fun IOSGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title, color = IosValue, fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 7.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(IosCard),
            content = content,
        )
        if (footer != null) Footer(footer)
    }
}

/** 分组标题行：标题 + 副标题 + 右侧一个小号文字动作。 */
@Composable
private fun IOSGroupHeader(title: String, sub: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = IosValue, fontSize = 12.sp)
            Text(sub, color = Color(0xFF5A5A5F), fontSize = 11.sp)
        }
        TextAction(action, color = IosRed, onClick = onAction)
    }
}

/**
 * 「全屏歌词 / 墨卷 / 声轨」设置的实时预览。
 *
 * 不是放一张截图，而是用**当前正在编辑的参数**直接排版一段示例歌词：改字号、行距、字间距、
 * 换字体、切纸面都会立刻反映在这块画面里；三个界面还各自带上自己那点特征（墨卷的朱砂章、
 * 声轨的标尺与时间码），所以调的时候知道自己在调哪一张画面。
 */
@Composable
private fun FullscreenLyricPreview(style: FsStyle, pref: FsStylePrefs) {
    val font: FontFamily? = when (pref.font) {
        LyricFontChoice.SYSTEM, LyricFontChoice.PINGFANG -> PingFang
        LyricFontChoice.MINGMEI -> MingMeiSpring
    }
    // 预览按 0.5 倍缩：设置页只有几百 dp 宽，1:1 会撑破卡片。
    val baseSp = (pref.fontSp * 0.5f).coerceIn(11f, 32f)
    val gap = (baseSp * (pref.lineGapPct / 100f) * 0.36f).dp.coerceIn(1.dp, 34.dp)
    val tracking = (pref.letterSpacing / 100f).em

    val paper = when (style) {
        FsStyle.PLAIN -> Color(0xFF0B0B0F)
        FsStyle.INK -> if (pref.light) Color(0xFFF3EADA) else Color(0xFF121110)
        FsStyle.TRACK -> if (pref.light) Color(0xFFF5F6F8) else Color(0xFF0E1013)
    }
    val ink = when (style) {
        FsStyle.PLAIN -> Color.White
        FsStyle.INK -> if (pref.light) Color(0xFF1B1A17) else Color(0xFFEDE7DA)
        FsStyle.TRACK -> if (pref.light) Color(0xFF12151A) else Color(0xFFF2F4F7)
    }
    val accent = when (style) {
        FsStyle.PLAIN -> Color(0xFFFA233B)
        FsStyle.INK -> Color(0xFFB23A2E)
        FsStyle.TRACK -> if (pref.light) Color(0xFF185FA5) else Color(0xFF85B7EB)
    }
    val sample = listOf(
        "夜色沿着窗台慢慢落下来",
        "我把没说完的话交给风",
        "等一个春天替我们回答",
    )
    // 恒定「第二行是正在唱的那句」，看起来像全屏歌词的中间态。
    val active = 1

    Column(Modifier.fillMaxWidth()) {
        Text(
            "实时预览 · ${style.label}",
            color = IosValue, fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, bottom = 7.dp),
        )
        Box(
            Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(14.dp)).background(paper),
        ) {
            // 全屏歌词：背后是动态背景，预览给一层很淡的专辑色光晕，免得看成纯黑卡片。
            if (style == FsStyle.PLAIN) Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        0.6f to Color(0x33FA233B), 1f to Color.Transparent,
                    ),
                ),
            )
            if (style == FsStyle.TRACK) TrackRuler(pref.light)
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = if (style == FsStyle.TRACK) Alignment.Start else Alignment.CenterHorizontally,
            ) {
                sample.forEachIndexed { i, line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (style == FsStyle.TRACK) {
                            Text(
                                "0:${(12 + i * 6).toString().padStart(2, '0')}",
                                color = accent.copy(alpha = if (i == active) 1f else 0.45f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(38.dp),
                            )
                        }
                        Text(
                            text = line,
                            color = when {
                                style == FsStyle.TRACK && i == active -> ink
                                i == active -> ink
                                i < active -> ink.copy(alpha = 0.45f)
                                else -> ink.copy(alpha = 0.26f)
                            },
                            fontSize = baseSp.sp,
                            fontFamily = font,
                            fontWeight = if (i == active) FontWeight.SemiBold else FontWeight.Normal,
                            letterSpacing = tracking,
                            maxLines = 1,
                        )
                    }
                    if (i != sample.lastIndex) Spacer(Modifier.height(gap))
                }
            }
            if (style == FsStyle.INK) {
                // 朱砂章
                Box(
                    Modifier.align(Alignment.TopEnd).padding(18.dp).size(26.dp)
                        .clip(RoundedCornerShape(3.dp)).background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("墨", color = if (pref.light) Color(0xFFFFF6E8) else Color(0xFFF7EADA), fontSize = 13.sp)
                }
            }
            Text(
                "${style.label} · ${pref.font.label} · ${pref.fontSp}sp · 行距 ${pref.lineGapPct}% · 字距 " +
                    String.format("%.2f", pref.letterSpacing / 100f) + "em",
                color = ink.copy(alpha = 0.4f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

/** 声轨底部的刻度尺：只是给个「这是时间轴」的信号，不参与排版。 */
@Composable
private fun BoxScope.TrackRuler(light: Boolean) {
    val line = if (light) Color(0xFF12151A).copy(alpha = 0.18f) else Color.White.copy(alpha = 0.14f)
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(18.dp).align(Alignment.BottomStart).padding(horizontal = 16.dp),
    ) {
        drawLine(line, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1f)
        var x = 0f
        var i = 0
        while (x <= size.width) {
            val h = if (i % 4 == 0) size.height * 0.7f else size.height * 0.35f
            drawLine(line, androidx.compose.ui.geometry.Offset(x, size.height - h), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
            x += size.width / 28f
            i++
        }
    }
}

@Composable
private fun Footer(text: String) {    Text(
        text, color = IosValue, fontSize = 11.sp, lineHeight = 16.sp,
        modifier = Modifier.padding(start = 16.dp, top = 7.dp, end = 8.dp),
    )
}

/** 0.5dp 分隔线，左侧留 16dp —— iOS 的分隔线是从文字左边开始的。 */
@Composable
private fun IOSDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(IosSeparator))
}

/** 可点行：整行高亮 + 点击。所有行共用同一个「聚焦即整行变亮」的规则。 */
@Composable
private fun FocusRow(
    modifier: Modifier = Modifier,
    upFocus: FocusRequester? = null,
    minHeight: Dp = 54.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) IosCardFocus else Color.Transparent, tween(120), label = "rowBg")
    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .onFocusChanged { focused = it.hasFocus }
            .focusProperties { up = upFocus ?: FocusRequester.Default }
            // 只读行（如「关于」的本机信息）也必须可聚焦：它们不可点击，但要是拿不到焦点，
            // 右栏就只剩底部的「检查更新」一个落点，一进栏 LazyColumn 会直接把列表滚到底，
            // 上方信息被滚出视口，按上键也没有任何可去的目标 —— 表现为"回不到顶部"。
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier.focusable())
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 单选行：右侧红色对勾。 */
@Composable
private fun CheckRow(label: String, sub: String? = null, selected: Boolean, onClick: () -> Unit) {
    FocusRow(onClick = onClick) {
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(label, color = IosLabel, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, color = IosValue, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (selected) {
            Text("✓", color = IosRed, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 开关行：iOS 绿色胶囊开关。 */
@Composable
private fun SwitchRow(label: String, sub: String? = null, checked: Boolean, onToggle: () -> Unit) {
    FocusRow(onClick = onToggle) {
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(label, color = IosLabel, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, color = IosValue, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        IOSSwitch(checked)
    }
}

/** 可进入行：右侧灰色 chevron。侧栏导航不需要 chevron（没有"进入"的方向感）。 */
@Composable
private fun NavRow(
    label: String,
    value: String? = null,
    selected: Boolean = false,
    chevron: Boolean = true,
    upFocus: FocusRequester? = null,
    onClick: () -> Unit,
) {
    FocusRow(upFocus = upFocus, onClick = onClick) {
        Text(
            label,
            color = if (selected) IosRed else IosLabel,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(value, color = IosValue, fontSize = 12.sp, maxLines = 1)
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

/** 数值行：右侧数值 + 两个减/加按钮（各自可聚焦，左右键在两者间移动）。 */
@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) IosCardFocus else Color.Transparent, tween(120), label = "stepBg")
    Row(
        Modifier.fillMaxWidth().background(bg).onFocusChanged { focused = it.hasFocus }
            .heightIn(min = 54.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = IosLabel, fontSize = 15.sp, modifier = Modifier.weight(1f))
        StepButton("−", onMinus)
        Text(
            value, color = IosLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 66.dp).padding(horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("+", onPlus)
    }
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color(0xFF636366) else Color(0xFF2C2C2E))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 动作行：左侧标题，右侧一个文字动作（检查更新用）。 */
@Composable
private fun ActionRow(
    label: String,
    value: String?,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    // 整行可点：OK 落在行的任意位置都能触发，不必精确移到右侧的小字上。
    FocusRow(onClick = onAction) {
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(label, color = IosLabel, fontSize = 15.sp)
            if (value != null) {
                Spacer(Modifier.height(2.dp))
                Text(value, color = IosValue, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        TextAction(action, color = if (enabled) IosRed else IosChevron, onClick = onAction)
    }
}

/** 只读信息行：左标题，右值。不可点击，但**可聚焦**（见 FocusRow 注释）。 */
@Composable
private fun InfoRow(label: String, value: String?, trailing: String?) {
    // 走 FocusRow：视觉上仍是一行只读信息（不可点），但能拿焦点，右栏因此可以靠方向键
    // 上下滚动，"关于"顶部的版本/本机信息随时能回到。
    FocusRow(minHeight = 48.dp) {
        Text(label, color = IosLabel, fontSize = 15.sp, modifier = Modifier.width(96.dp))
        Text(
            value ?: "—",
            color = IosValue,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, color = IosValue, fontSize = 13.sp)
        }
    }
}

/** iOS 文字按钮（红色）。 */
@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (focused) Color(0xFF48484A) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Chevron() {
    Text("›", color = IosChevron, fontSize = 20.sp, fontWeight = FontWeight.Light)
}

/** iOS 开关：51×31 的胶囊，开启时绿色，圆点为白色 27dp。 */
@Composable
private fun IOSSwitch(checked: Boolean) {
    val track by animateColorAsState(if (checked) IosGreen else IosSwitchOff, tween(180), label = "swTrack")
    val knob by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "swKnob",
    )
    Box(Modifier.width(51.dp).height(31.dp).clip(RoundedCornerShape(50)).background(track)) {
        Box(
            Modifier.offset(x = knob, y = 2.dp).size(27.dp)
                .clip(RoundedCornerShape(50)).background(Color.White)
        )
    }
}

/**
 * 一行音源脚本：名称 / 版本 / 作者 / 状态，右侧「停用 / 删除」。
 *
 * 删除仍要两步：电视遥控器上按错一格很常见，一步直删会很难受。
 *
 * 加载失败的源照样在这里，状态位标红写「加载失败」——内置源失效是常态，用户需要
 * 看到它才能决定是停用还是删掉。
 */
@Composable
private fun ScriptRow(script: UserScript, coverage: ScriptCoverage?, vm: SettingsViewModel) {
    var confirming by remember(script.id) { mutableStateOf(false) }
    val failed = script.loadError != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                script.name + if (script.builtin) " · 内置" else "",
                color = if (script.enabled) IosLabel else IosValue,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    script.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    script.author.takeIf { it.isNotBlank() },
                    when {
                        failed -> "加载失败"
                        script.enabled -> "已启用"
                        else -> "已停用"
                    },
                ).joinToString(" · "),
                color = if (failed) Color(0xFFFF453A) else IosValue,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (failed) {
                Spacer(Modifier.height(2.dp))
                Text(
                    script.loadError.orEmpty(),
                    color = Color(0xFFFF453A).copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (script.enabled && coverage != null && coverage.sources.isNotEmpty()) {
                // 「这个源到底能用在哪」——脚本头部的名字和版本说明不了这件事，只有跑起来
                // 上报的能力表才算数。一行放下平台与最高音质，比较几个源时不用逐个去试。
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(coverage.sources.joinToString("·") { it.displayName })
                        coverage.topQuality?.let { append(" ｜ 最高 ${it.displayName}") }
                    },
                    color = IosValue.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (confirming) {
            TextAction("删除", color = Color(0xFFFF453A)) {
                confirming = false
                vm.removeScript(script.id)
            }
            TextAction("取消", color = IosValue) { confirming = false }
        } else {
            TextAction(if (script.enabled) "停用" else "启用", color = IosRed) {
                vm.setScriptEnabled(script.id, !script.enabled)
            }
            TextAction("删除", color = Color(0xFFFF453A)) { confirming = true }
        }
    }
}
