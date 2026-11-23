package com.veacon.veilmusic.ui.screens

import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.R

import com.veacon.veilmusic.ui.components.TouchSurface as Surface

import com.veacon.veilmusic.ui.components.pageHorizontalInset
import com.veacon.veilmusic.ui.components.ContentActionShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.veacon.veilmusic.ui.navigation.ReturnLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veacon.veilmusic.ui.viewmodel.settingsUiState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.veacon.veilmusic.ui.components.returnToTabOnUp
import com.veacon.veilmusic.ui.components.menuInitialFocus
import com.veacon.veilmusic.data.updates.DownloadSource
import com.veacon.veilmusic.ui.viewmodel.DevMenuViewModel
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DevMenuScreen(
    playerVm: PlayerViewModel,
    onDataRefresh: () -> Unit = {},
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val vm: DevMenuViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val wordByWordLyrics by vm.wordByWordLyrics.collectAsState()
    val lyricElastic by vm.lyricElastic.collectAsState()
    val update by vm.update.collectAsState()
    val selectedSource by vm.downloadSource.collectAsState()
    val pstate by remember(playerVm) {
        playerVm.state.map { it.settingsUiState() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(playerVm.state.value.settingsUiState())
    val ctx = LocalContext.current
    // 开发者选项的展开状态持久化：切换界面后回来仍保持上次的开关。
    val devPrefs = remember(ctx) { ctx.getSharedPreferences("dev_menu_settings", android.content.Context.MODE_PRIVATE) }
        var showDev by remember { mutableStateOf(devPrefs.getBoolean("show_dev", true)) }
        // 「发现页」（新手引导页）的入口开关状态。
    // 播放设置整区默认折叠，展开后才显示各播放项（需求：设置项规整）。
    var showPlayback by remember { mutableStateOf(devPrefs.getBoolean("show_playback", true)) }
    // 界面设置、应用设置像「开发者选项」一样由一个开关控制展开。
        var showUiSettings by remember { mutableStateOf(devPrefs.getBoolean("show_ui_settings", true)) }
        var showGeneral by remember { mutableStateOf(devPrefs.getBoolean("show_general", true)) }
        // 定时切换深浅色：开关 + 深色时段起止小时（0–23）。
        var autoDark by remember { mutableStateOf(com.veacon.veilmusic.data.AppearanceSchedule.enabled(ctx)) }
        var autoDarkStart by remember { mutableStateOf(com.veacon.veilmusic.data.AppearanceSchedule.startMin(ctx)) }
        var autoDarkEnd by remember { mutableStateOf(com.veacon.veilmusic.data.AppearanceSchedule.endMin(ctx)) }
    var confirmReset by remember { mutableStateOf(false) }
    var showSourceManager by remember { mutableStateOf(false) }
    // 音源清单快照：停用/删除后要刷新，否则弹窗里的状态是旧的（看起来"点了没反应/全停用"）。
    var sourceScripts by remember { mutableStateOf(emptyList<com.veacon.veilmusic.luoxue.LxPlayback.ScriptView>()) }
    LaunchedEffect(showSourceManager) { if (showSourceManager) sourceScripts = vm.sourceScripts() }
    val english = com.veacon.veilmusic.i18n.AppLanguage.code == "en"
    fun tx(zh: String, en: String) = if (english) en else zh
    LaunchedEffect(Unit) { vm.checkUpdates() }

    // Live network activity — polled from the shared NetworkLog buffer.
    var netLogs by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(showDev) {
        while (showDev) { netLogs = vm.networkLogs(); kotlinx.coroutines.delay(1000) }
    }

    LaunchedEffect(vm) {
        while (true) { vm.refreshCacheUsage(); kotlinx.coroutines.delay(2_000) }
    }

    // 设置页分区：一次只渲染一个组，保证每组一页放得下。
    var section by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scrollState = rememberScrollState()
    val returning = com.veacon.veilmusic.ui.navigation.LocalNavigationReturn.current?.restoring == true
    LaunchedEffect(tabBarFocused, returning) { if (tabBarFocused && !returning) scrollState.animateScrollTo(0) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val inset = pageHorizontalInset
        // 与资料库 SectionSidebar 同一套宽度算法
        val sidebarWidth = 208.dp.coerceAtMost((maxWidth - inset * 2) * 0.32f)
        Box(Modifier.fillMaxSize().background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012)))) {
        Row(Modifier.fillMaxSize().padding(horizontal = inset)) {
            // 左列：与资料库一致的分区导航
            SettingsSidebar(
                labels = settingsSectionLabels(english),
                selected = section,
                onSelect = { section = it },
                onReturnToTab = onReturnToTab,
                modifier = Modifier.width(sidebarWidth),
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    // 与左列同一起点：左列 top=96 且首个子项是标题，这里用同高透明占位对齐
                    .padding(top = 96.dp, bottom = 20.dp)
                    .verticalScroll(scrollState)
                    .padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 占位：与左列「设置」标题完全同高（透明），保证右侧第一行与「账号外观」齐平
                Text(
                    "设置",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Transparent,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                val displayAuth = remember(ctx) { com.veacon.veilmusic.data.AppleDisplayAuth.get(ctx) }
                val displayState by displayAuth.state.collectAsState()
                    when (section) {
                        0 -> {   // 账号外观
            SettingsSection(tx("账号外观", "Account & appearance")) {
                // 扫码登录会话状态机（epoch 防重入；离开设置页即关服）。
                var phoneSignInVisible by remember { mutableStateOf(false) }
                var phoneUrl by remember { mutableStateOf<String?>(null) }
                var phonePhase by remember { mutableStateOf(com.veacon.veilmusic.ui.components.PhoneSignInPhase.Starting) }
                var phoneFailReason by remember { mutableStateOf<String?>(null) }
                val phoneSession = remember { mutableStateOf<com.veacon.veilmusic.data.auth.PhoneMusicAuthorization?>(null) }
                val phoneJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                val phoneEpoch = remember { java.util.concurrent.atomic.AtomicInteger(0) }
                val phoneScope = rememberCoroutineScope()
                fun closePhoneSignIn() {
                    phoneEpoch.incrementAndGet()
                    phoneJob.value?.cancel(); phoneJob.value = null
                    phoneSession.value?.close(); phoneSession.value = null
                    phoneSignInVisible = false
                }
                fun startPhoneSignIn() {
                    if (phoneSignInVisible) return
                    // 展示方案默认关闭（不发任何网络请求）：用户主动扫码时再打开。
                    if (!displayAuth.isEnabled()) displayAuth.setEnabled(true)
                    val epoch = phoneEpoch.incrementAndGet()
                    phoneSignInVisible = true
                    phonePhase = com.veacon.veilmusic.ui.components.PhoneSignInPhase.Starting
                    phoneFailReason = null
                    phoneJob.value = phoneScope.launch {
                        var session: com.veacon.veilmusic.data.auth.PhoneMusicAuthorization? = null
                        var statusJob: kotlinx.coroutines.Job? = null
                        try {
                            val active = displayAuth.createPhoneSession(if (english) "en" else "zh-CN")
                            if (epoch != phoneEpoch.get()) return@launch
                            session = active
                            phoneSession.value = active
                            phoneUrl = active.pairing.url
                            phonePhase = com.veacon.veilmusic.ui.components.PhoneSignInPhase.Waiting
                            statusJob = launch {
                                active.status.collect { status ->
                                    if (epoch == phoneEpoch.get()) when (status) {
                                        com.veacon.veilmusic.data.auth.PhoneMusicAuthStatus.PhoneConnected,
                                        com.veacon.veilmusic.data.auth.PhoneMusicAuthStatus.Authorized ->
                                            phonePhase = com.veacon.veilmusic.ui.components.PhoneSignInPhase.Authorizing
                                        com.veacon.veilmusic.data.auth.PhoneMusicAuthStatus.Expired ->
                                            phonePhase = com.veacon.veilmusic.ui.components.PhoneSignInPhase.Expired
                                        else -> Unit
                                    }
                                }
                            }
                            val token = active.awaitToken()
                            if (epoch != phoneEpoch.get()) return@launch
                            phonePhase = com.veacon.veilmusic.ui.components.PhoneSignInPhase.Authorizing
                            // 对齐 am_atv：signIn 只做 storefront 校验+落盘（1-3 秒），
                            // 资料库歌单随后异步加载，状态区显示「正在获取资料库信息」。
                            val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { displayAuth.signIn(token) }
                            if (epoch != phoneEpoch.get()) return@launch
                            phoneFailReason = outcome.takeIf { it != "ok" }
                            phonePhase = if (outcome == "ok")
                                com.veacon.veilmusic.ui.components.PhoneSignInPhase.Complete
                            else com.veacon.veilmusic.ui.components.PhoneSignInPhase.Failed
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: com.veacon.veilmusic.data.auth.PhoneMusicAuthorizationExpired) {
                            if (epoch == phoneEpoch.get()) {
                                phoneFailReason = null
                                phonePhase = com.veacon.veilmusic.ui.components.PhoneSignInPhase.Expired
                            }
                        } catch (_: Exception) {
                            if (epoch == phoneEpoch.get()) {
                                phoneFailReason = "error"
                                phonePhase = com.veacon.veilmusic.ui.components.PhoneSignInPhase.Failed
                            }
                        } finally {
                            statusJob?.cancel()
                            session?.close()
                        }
                    }
                }
                val statusLine = when (displayState.status) {
                    "ready", "checking" -> tx("已登录", "Signed in") +
                        displayState.accountName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() +
                        displayState.storefront.takeIf { it.isNotBlank() }?.let { " · ${it.uppercase()}" }.orEmpty()
                    "invalid" -> tx("登录已失效，请重新扫码登录", "Sign-in expired — scan the QR code again")
                    "unavailable" -> tx("暂时连不上 Apple Music，稍后重试", "Can't reach Apple Music right now — try again later")
                    else -> tx("未登录", "Not signed in")
                }
                // 「手机扫码登录」整行可点：点开就是二维码流程；登录后云端歌单也铺在这一行里。
                // 这一行是本区块第一行，按「上」挂回 Tab 栏入口；其余行按「上」只在列表内移动。
                SignInRow(
                    statusLine = statusLine,
                    syncedCount = displayState.playlists.size,
                    loadingLibrary = displayState.loadingLibrary,
                    modifier = Modifier.returnToTabOnUp(onReturnToTab),
                    onClick = { if (phoneSignInVisible) closePhoneSignIn() else startPhoneSignIn() },
                )
                SettingDivider()
                ActionSettingRow(
                    tx("刷新同步歌单及推荐页", "Refresh synced playlists and recommendations"),
                    tx("重新拉取云端歌单与首页推荐内容",
                       "Re-fetch cloud playlists and the Home recommendations"),
                ) {
                    vm.refreshSyncedContent()
                    android.widget.Toast.makeText(ctx, "已刷新", android.widget.Toast.LENGTH_SHORT).show()
                }
                if (phoneSignInVisible) {
                    com.veacon.veilmusic.ui.components.PhoneSignInDialog(
                        url = phoneUrl, phase = phonePhase, failReason = phoneFailReason,
                        onRetry = { closePhoneSignIn(); startPhoneSignIn() },
                        onDismiss = ::closePhoneSignIn,
                    )
                }
                SettingDivider()
                Toggle(tr(R.string.msg_351), pstate.navTitleAsSong, tr(R.string.msg_352)) {
                    playerVm.setNavTitleAsSong(!pstate.navTitleAsSong)
                }
                SettingDivider()
                Toggle(tx("迷你播放胶囊", "Mini player capsule"), pstate.miniCapsuleEnabled,
                    tx("底部常驻胶囊播放条，带主题自适应背景", "Always-on bottom bar with a theme-aware background")) {
                    playerVm.setMiniCapsuleEnabled(!pstate.miniCapsuleEnabled)
                }
                if (pstate.miniCapsuleEnabled) {
                    SettingDivider()
                    Stepper(tx("高度", "Height"), "${pstate.miniCapsuleHeightPct}%",
                        tx("胶囊高度占可用区域的百分比", "Capsule height as a percentage of the available area"),
                        { playerVm.setMiniCapsuleHeightPct((pstate.miniCapsuleHeightPct - 5).coerceAtLeast(60)) },
                        { playerVm.setMiniCapsuleHeightPct((pstate.miniCapsuleHeightPct + 5).coerceAtMost(140)) },
                        decEnabled = pstate.miniCapsuleHeightPct > 60,
                        incEnabled = pstate.miniCapsuleHeightPct < 140)
                    SettingDivider()
                    Stepper(tx("宽度", "Width"), "${pstate.miniCapsuleWidthPct}%",
                        tx("胶囊宽度占可用区域的百分比", "Capsule width as a percentage of the available area"),
                        { playerVm.setMiniCapsuleWidthPct((pstate.miniCapsuleWidthPct - 5).coerceAtLeast(60)) },
                        { playerVm.setMiniCapsuleWidthPct((pstate.miniCapsuleWidthPct + 5).coerceAtMost(100)) },
                        decEnabled = pstate.miniCapsuleWidthPct > 60,
                        incEnabled = pstate.miniCapsuleWidthPct < 100)
                    SettingDivider()
                    Toggle(tx("背景跟随专辑取色", "Album colour background"), pstate.miniCapsuleAlbumTint,
                        tx("用专辑主色给胶囊上色", "Tint the capsule with the album artwork colour")) {
                        playerVm.setMiniCapsuleAlbumTint(!pstate.miniCapsuleAlbumTint)
                    }
                    SettingDivider()
                    Toggle(tx("显示播放进度线", "Playback progress line"), pstate.miniCapsuleProgressLine,
                        tx("胶囊底边的一条细线；关掉更干净", "The thin line along the capsule bottom edge")) {
                        playerVm.setMiniCapsuleProgressLine(!pstate.miniCapsuleProgressLine)
                    }
                }
                SettingDivider()
                val dark = com.veacon.veilmusic.ui.theme.AppPalette.isDark
                Toggle(tx("深色模式", "Dark mode"), dark,
                    tx("关闭则使用浅色界面", "Off = light appearance")) {
                    com.veacon.veilmusic.ui.theme.AppPalette.setDark(ctx, !dark)
                }
                SettingDivider()
                Toggle(tx("定时切换深浅色", "Scheduled dark mode"), autoDark,
                    tx("在设定时段自动切到深色，其余时间用浅色", "Go dark during the set hours")) {
                    val next = !autoDark
                    com.veacon.veilmusic.data.AppearanceSchedule.setEnabled(ctx, next)
                    autoDark = next
                    com.veacon.veilmusic.data.AppearanceSchedule.apply(ctx)
                }
                if (autoDark) {
                    SettingDivider()
                    Stepper(tx("深色开始", "Dark from"),
                        com.veacon.veilmusic.data.AppearanceSchedule.label(autoDarkStart),
                        tx("按半小时选择", "Half-hour steps"),
                        {
                            autoDarkStart = com.veacon.veilmusic.data.AppearanceSchedule.step(autoDarkStart, -1)
                            com.veacon.veilmusic.data.AppearanceSchedule.setStartMin(ctx, autoDarkStart)
                            com.veacon.veilmusic.data.AppearanceSchedule.apply(ctx)
                        },
                        {
                            autoDarkStart = com.veacon.veilmusic.data.AppearanceSchedule.step(autoDarkStart, 1)
                            com.veacon.veilmusic.data.AppearanceSchedule.setStartMin(ctx, autoDarkStart)
                            com.veacon.veilmusic.data.AppearanceSchedule.apply(ctx)
                        })
                    SettingDivider()
                    Stepper(tx("深色结束", "Dark until"),
                        com.veacon.veilmusic.data.AppearanceSchedule.label(autoDarkEnd),
                        tx("到点自动切回浅色", "Switch back to light at this time"),
                        {
                            autoDarkEnd = com.veacon.veilmusic.data.AppearanceSchedule.step(autoDarkEnd, -1)
                            com.veacon.veilmusic.data.AppearanceSchedule.setEndMin(ctx, autoDarkEnd)
                            com.veacon.veilmusic.data.AppearanceSchedule.apply(ctx)
                        },
                        {
                            autoDarkEnd = com.veacon.veilmusic.data.AppearanceSchedule.step(autoDarkEnd, 1)
                            com.veacon.veilmusic.data.AppearanceSchedule.setEndMin(ctx, autoDarkEnd)
                            com.veacon.veilmusic.data.AppearanceSchedule.apply(ctx)
                        })
                }
            }
                        }
                        1 -> {   // 背景动效
            SettingsSection(tx("背景动效", "Background")) {
                val bgStyle by playerVm.backgroundStyle.collectAsState()
                Stepper(tx("背景风格", "Background style"),
                    if (bgStyle == com.veacon.veilmusic.data.BackgroundStylePreferences.LAVA)
                        tx("熔岩", "Lava") else tx("流体", "Fluid"),
                    tx("播放页背景的呈现风格（画质统一为高）",
                       "How the player background is rendered (quality is always High)"),
                    { playerVm.setBackgroundStyle(com.veacon.veilmusic.data.BackgroundStylePreferences.FLUID) },
                    { playerVm.setBackgroundStyle(com.veacon.veilmusic.data.BackgroundStylePreferences.LAVA) },
                    decEnabled = bgStyle == com.veacon.veilmusic.data.BackgroundStylePreferences.LAVA,
                    incEnabled = bgStyle == com.veacon.veilmusic.data.BackgroundStylePreferences.FLUID)
                SettingDivider()
                val bgFps by playerVm.backgroundFps.collectAsState()
                Stepper(tx("背景帧率", "Background frame rate"), "$bgFps fps",
                    tx("背景动画每秒渲染帧数；卡顿明显时切 30",
                       "Frames per second for the background animation. Switch to 30 if it stutters."),
                    { playerVm.setBackgroundFps(30) }, { playerVm.setBackgroundFps(60) },
                    decEnabled = bgFps != 30, incEnabled = bgFps != 60)
                SettingDivider()
                val pulseIndex by playerVm.pulseSpeedIndex.collectAsState()
                Stepper(tx("脉动速度", "Pulse speed"),
                    com.veacon.veilmusic.ui.background.LavaPulseSpeed.label(pulseIndex),
                    tx("背景随节拍脉动的速度倍率", "Beat-reactive speed multiplier"),
                    { playerVm.stepPulseSpeed(-1) }, { playerVm.stepPulseSpeed(1) },
                    decEnabled = pulseIndex > 0,
                    incEnabled = pulseIndex < com.veacon.veilmusic.ui.background.LavaPulseSpeed.STEPS.lastIndex)
                SettingDivider()
                    // 背景律动：一个设置项同时管理律动开关与麦克风授权（Visualizer 只读本应用频谱）。
                    var spectrumOn by remember { mutableStateOf(playerVm.spectrumEnabled()) }
                    var permissionTick by remember { mutableIntStateOf(0) }
                    val spectrumPermState = remember(permissionTick) {
                        com.veacon.veilmusic.data.microphonePermissionState(ctx, playerVm.spectrumPrefs)
                    }
                    val spectrumLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        playerVm.spectrumPrefs.recordPermissionResult(granted)
                        if (granted) playerVm.setSpectrumEnabled(true)
                        spectrumOn = playerVm.spectrumEnabled()
                        permissionTick++
                    }
                    val spectrumSubtitle = when (spectrumPermState) {
                        com.veacon.veilmusic.data.SpectrumPermissionState.Granted ->
                            tx("随音乐节拍让播放页背景呼吸，只读取本应用频谱",
                                "Breathe with the music; reads only this app's spectrum")
                        com.veacon.veilmusic.data.SpectrumPermissionState.Denied ->
                            tx("麦克风权限被拒绝，请到系统设置中允许后重试",
                                "Microphone denied — allow it in system settings, then retry")
                        com.veacon.veilmusic.data.SpectrumPermissionState.Unavailable ->
                            tx("此设备不支持频谱采集，律动不可用",
                                "Spectrum capture unavailable on this device")
                        com.veacon.veilmusic.data.SpectrumPermissionState.NotRequested ->
                            tx("开启时请求麦克风权限，只读取本应用频谱",
                                "Requests mic permission when enabled; reads only this app's spectrum")
                    }
                    Toggle(tx("背景节拍律动", "Beat-reactive backdrop"), spectrumOn, spectrumSubtitle) {
                        if (spectrumOn) {
                            playerVm.setSpectrumEnabled(false)
                            spectrumOn = false
                        } else {
                            when (spectrumPermState) {
                                com.veacon.veilmusic.data.SpectrumPermissionState.Granted -> {
                                    playerVm.setSpectrumEnabled(true); spectrumOn = true
                                }
                                com.veacon.veilmusic.data.SpectrumPermissionState.Denied,
                                com.veacon.veilmusic.data.SpectrumPermissionState.NotRequested ->
                                    spectrumLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                com.veacon.veilmusic.data.SpectrumPermissionState.Unavailable -> Unit
                            }
                        }
                    }
            }
                        }
                        2 -> {   // 封面动效
            SettingsSection(tx("封面动效", "Artwork")) {
                Toggle(tr(R.string.msg_202), pstate.motionArtworkEnabled, tr(R.string.msg_203)) {
                    playerVm.toggleMotionArtwork()
                }
                SettingDivider()
                Toggle(tr(R.string.msg_204), pstate.artworkRounded, tr(R.string.msg_205)) {
                    playerVm.toggleArtworkRounded()
                }
                SettingDivider()
                Toggle(tr(R.string.msg_344), pstate.renderCompat, when {
                    pstate.renderCompat -> tr(R.string.msg_349)
                    pstate.renderCompatManual -> tr(R.string.msg_350)
                    else -> tr(R.string.msg_348)
                }) { playerVm.setRenderCompat(!pstate.renderCompat) }
            }
                        }
                        3 -> {   // 播放音质
            SettingsSection(tx("播放音质", "Audio")) {
                val quality = state.audioQuality
                Stepper(tx("音质", "Audio quality"), quality.label, quality.hint,
                    { vm.stepAudioQuality(-1) }, { vm.stepAudioQuality(1) },
                    decEnabled = quality.ordinal > 0,
                    incEnabled = quality.ordinal < com.veacon.veilmusic.data.AudioQuality.entries.lastIndex)
                SettingDivider()
                val decoder = pstate.decoderMode
                val hw = decoder == com.veacon.veilmusic.data.DecoderMode.HARDWARE
                Stepper(tx("解码方式", "Audio decoder"),
                    if (hw) tx("硬解", "Hardware") else tx("软解", "Software"),
                    tx("系统解码更省电；FFmpeg 软解更稳定",
                       "System decoder is lighter; FFmpeg is more consistent"),
                    { playerVm.setDecoderMode(com.veacon.veilmusic.data.DecoderMode.SOFTWARE) },
                    { playerVm.setDecoderMode(com.veacon.veilmusic.data.DecoderMode.HARDWARE) },
                    decEnabled = !hw, incEnabled = hw)
                SettingDivider()
                Toggle(tr(R.string.msg_193), pstate.volumeLeveling, tr(R.string.msg_194)) {
                    playerVm.toggleVolumeLeveling()
                }
                SettingDivider()
                val crossfadeS = (state.crossfadeMs / 1000L).toInt()
                Stepper(tr(R.string.msg_187),
                    tr(R.string.msg_188).format(com.veacon.veilmusic.i18n.AppLanguage.locale, state.crossfadeMs / 1000f),
                    tr(R.string.msg_189),
                    { vm.setCrossfade(((crossfadeS - 1).coerceAtLeast(0) * 1000L)) },
                    { vm.setCrossfade(((crossfadeS + 1).coerceAtMost(12) * 1000L)) },
                    decEnabled = crossfadeS > 0, incEnabled = crossfadeS < 12)
                SettingDivider()
                Toggle(tr(R.string.msg_190), state.standaloneOn, if (state.standaloneOn) tr(R.string.msg_191) else tr(R.string.msg_192)) {
                    vm.toggleStandalone()
                }
                SettingDivider()
                Toggle(tx("统一输出采样率", "Unified output rate"), pstate.unifySampleRate,
                    tx("把输出统一到设备原生采样率，减少重建", "Resample to the device rate to avoid sink rebuilds")) {
                    playerVm.setUnifySampleRate(!pstate.unifySampleRate)
                }
                SettingDivider()
                Toggle(tx("音频直通（offload）", "Audio offload"), pstate.audioOffload,
                    tx("让 DSP 直接吃压缩流，降低 CPU 占用", "Feed the DSP the compressed stream to cut CPU use")) {
                    playerVm.setAudioOffload(!pstate.audioOffload)
                }
            }
                        }
                        4 -> {   // 歌词设置
            SettingsSection(tx("歌词设置", "Lyrics")) {
                val scaleLabels = listOf(0.8f, 1.0f, 1.25f, 1.6f, 2.0f).map { lyricsScaleLabel(it) }
                val scaleIdx = listOf(0.8f, 1.0f, 1.25f, 1.6f, 2.0f)
                    .indexOfFirst { kotlin.math.abs(it - pstate.lyricsScale) < 0.05f }.let { if (it < 0) 2 else it }
                Stepper(tr(R.string.msg_200), lyricsScaleLabel(pstate.lyricsScale), tr(R.string.msg_201),
                    { playerVm.setLyricsScale(listOf(0.8f, 1.0f, 1.25f, 1.6f, 2.0f)[(scaleIdx - 1).coerceAtLeast(0)]) },
                    { playerVm.setLyricsScale(listOf(0.8f, 1.0f, 1.25f, 1.6f, 2.0f)[(scaleIdx + 1).coerceAtMost(4)]) },
                    decEnabled = scaleIdx > 0, incEnabled = scaleIdx < 4)
                SettingDivider()
                // 播放页专辑下方歌名/歌手字号（小/标准/大/超大 四档）。
                val infoSteps = listOf(0.85f, 1.0f, 1.25f, 1.5f)
                val infoIdx = infoSteps.indexOfFirst { kotlin.math.abs(it - pstate.songInfoScale) < 0.05f }.let { if (it < 0) 1 else it }
                Stepper(tx("歌曲信息字号", "Song info text size"),
                    songInfoScaleLabel(pstate.songInfoScale),
                    tx("专辑下方歌名与歌手名的大小", "Size of the title and artist under the artwork"),
                    { playerVm.setSongInfoScale(infoSteps[(infoIdx - 1).coerceAtLeast(0)]) },
                    { playerVm.setSongInfoScale(infoSteps[(infoIdx + 1).coerceAtMost(infoSteps.lastIndex)]) },
                    decEnabled = infoIdx > 0, incEnabled = infoIdx < infoSteps.lastIndex)
                SettingDivider()
                Toggle(tx("逐字歌词", "Word-by-word lyrics"), wordByWordLyrics,
                    tx("歌词按字逐个点亮，随演唱节奏显示", "Light up lyrics per character as they are sung")) {
                    vm.setWordByWordLyrics(!wordByWordLyrics)
                }
                SettingDivider()
                Toggle(tx("歌词缓动回弹", "Lyric ease & rebound"), lyricElastic,
                    tx("当前行平滑放大、已唱行收暗，切换行有缓动效果", "The current line eases in while others dim")) {
                    vm.setLyricElastic(!lyricElastic)
                }
                SettingDivider()
                val offsets = (-20..20).map { it * 50L }
                Stepper(tr(R.string.msg_213),
                    tr(R.string.msg_214, state.lyricsOffsetMs), tr(R.string.msg_215),
                    { vm.setLyricsOffset((state.lyricsOffsetMs - 50L).coerceAtLeast(-1000L)) },
                    { vm.setLyricsOffset((state.lyricsOffsetMs + 50L).coerceAtMost(1000L)) },
                    decEnabled = state.lyricsOffsetMs > -1000L,
                    incEnabled = state.lyricsOffsetMs < 1000L)
                SettingDivider()
                Stepper(tx("歌词字形", "Lyrics script"),
                    if (pstate.lyricsScript == com.veacon.veilmusic.data.LyricsScript.TRADITIONAL)
                        tx("繁体", "Traditional") else tx("简体", "Simplified"),
                    tx("歌词使用简体还是繁体显示", "Show lyrics in Simplified or Traditional Chinese"),
                    { playerVm.setLyricsScript(com.veacon.veilmusic.data.LyricsScript.SIMPLIFIED) },
                    { playerVm.setLyricsScript(com.veacon.veilmusic.data.LyricsScript.TRADITIONAL) },
                    decEnabled = pstate.lyricsScript == com.veacon.veilmusic.data.LyricsScript.TRADITIONAL,
                    incEnabled = pstate.lyricsScript != com.veacon.veilmusic.data.LyricsScript.TRADITIONAL)
            }
                        }
                        5 -> {   // 通用设置
            SettingsSection(tx("通用设置", "General")) {
                LanguageStepper()
                SettingDivider()
                val details = when (update.phase) {
                    "checking" -> tx("正在检查更新…", "Checking for updates…")
                    "available" -> tx("有新版本 ${update.info?.version}", "Update available: ${update.info?.version}")
                    "latest" -> tx("当前已是最新版本", "You are up to date")
                    "downloading" -> tx("正在下载 ${update.progress}%", "Downloading ${update.progress}%")
                    "ready", "permission" -> tx("安装包已下载，点击继续安装", "Download complete; select to install")
                    "incompatible" -> tx("新版本不支持当前系统", "The update requires a newer Android version")
                    "error", "downloadError", "installError" -> tx("更新失败，点击重试", "Update failed; select to retry")
                    else -> tx("点击检查更新", "Select to check for updates")
                }
                ActionSettingRow(tx("当前版本 ${com.veacon.veilmusic.BuildConfig.VERSION_NAME}", "Version ${com.veacon.veilmusic.BuildConfig.VERSION_NAME}"), details) {
                    if (update.phase == "ready" || update.phase == "permission") vm.installUpdate()
                    else vm.checkUpdates(manual = true)
                }
                SettingDivider()
                ActionSettingRow(tr(R.string.msg_232), tr(R.string.msg_233), danger = true) { confirmReset = true }
            }
                        }
                        6 -> {   // 诊断工具
            SettingsSection(tx("诊断工具", "Diagnostics")) {
                ActionSettingRow(tr(R.string.msg_223), tr(R.string.msg_224)) { vm.clearLogs() }
                SettingDivider()
                LazyColumn(Modifier.fillMaxWidth().height(260.dp).padding(20.dp), reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (netLogs.isEmpty() && state.logs.isEmpty()) item {
                        Text(tr(R.string.msg_225), fontSize = 12.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                    }
                    items(netLogs.reversed()) { line -> Text(line, fontSize = 12.sp, lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8))) }
                    items(state.logs.reversed()) { log -> Text(com.veacon.veilmusic.ui.viewmodel.LogStage.localize(log.message), fontSize = 12.sp, lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace, color = when (log.level) {
                            "ERROR" -> Color(0xFFB22222); "WARN" -> Color(0xFF8A6500)
                            else -> com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8))
                        }) }
                }
                SettingDivider()
                SettingsSection(tr(R.string.msg_226)) { BugReportRow(state.webServerUrl) }
                SettingDivider()
                ActionSettingRow(tx("配置音源", "Configure sources"), tx("停用、删除内置音源", "Disable or delete built-in sources")) {
                    showSourceManager = true
                }
                SettingDivider()
                ActionSettingRow(tr(R.string.msg_230), tr(R.string.msg_231)) {
                    playerVm.stopPlayback(); android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
                        }
                    }
                com.veacon.veilmusic.ui.components.AppVersionFooter()
            }
        }
    }
        }

    if (confirmReset) {
        ResetAppDialog(onDismiss = { confirmReset = false }) {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.clearApplicationUserData()
        }
    }
    if (showSourceManager) {
        SourceManagerDialog(
            scripts = sourceScripts,
            onDismiss = { showSourceManager = false },
            onToggle = { file, disabled -> vm.setScriptDisabled(file, disabled); sourceScripts = vm.sourceScripts() },
            onDelete = { file -> vm.deleteScript(file); sourceScripts = vm.sourceScripts() },
        )
    }
    if (update.manualResult) {
        val title = when (update.phase) {
            "available" -> tx("发现新版本", "Update available")
            "latest" -> tx("当前已是最新版本", "You're up to date")
            "permission" -> tx("允许安装应用", "Allow app installation")
            "incompatible" -> tx("暂无法更新", "Update unavailable")
            else -> tx("更新失败", "Update failed")
        }
        val message = when (update.phase) {
            "available" -> "${update.info?.version}\n${update.info?.notes.orEmpty()}"
            "latest" -> tx("当前版本已是最新版本。", "You have the latest version.")
            "permission" -> tx("请允许从此来源安装，返回后点击「确定」以继续安装。", "Allow installation from this source, then return and select OK to continue installation.")
            "incompatible" -> tx("新版本需要更新的 Android 系统。", "The new version requires a newer Android version.")
            else -> updateFailureMessage(english)
        }
        com.veacon.veilmusic.ui.components.ConfirmationDialog(title = title, message = message,
            confirmLabel = if (update.phase == "available") tx("更新", "Update") else tx("确定", "OK"),
            onDismiss = vm::dismissUpdateResult,
            onConfirm = { when (update.phase) { "available" -> vm.promptDownloadSource(); "permission" -> { vm.dismissUpdateResult(); vm.installUpdate() }; else -> vm.dismissUpdateResult() } },
            // 发现新版本时默认停在「更新」上，遥控器直接按 OK 就能进线路选择，不会误触取消。
            focusConfirm = update.phase == "available")
    }
    if (update.sourcePicker) {
        DownloadSourceDialog(
            english = english,
            sources = vm.downloadSources,
            selected = selectedSource,
            downloading = update.phase == "downloading",
            progress = update.progress,
            onSelect = vm::selectDownloadSource,
            onCancelDownload = vm::cancelDownload,
            onDismiss = { if (update.phase == "downloading") vm.cancelDownload() else vm.dismissDownloadSource() },
        )
    }
}

/**
 * 更新下载弹窗：先选线路（3 个加速前缀 + 直连），选定后**不关闭**，
 * 同一界面切换为下载进度视图，可随时取消。与「配置音源」弹窗同一套 MotionDialog / TV 焦点风格。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DownloadSourceDialog(
    english: Boolean,
    sources: List<DownloadSource>,
    selected: DownloadSource,
    downloading: Boolean,
    progress: Int,
    onSelect: (DownloadSource) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    val palette = com.veacon.veilmusic.ui.theme.AppPalette
    com.veacon.veilmusic.ui.components.MotionDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.menuInitialFocus(initialFocus)
                    .fillMaxWidth(0.62f)
                    .background(
                        palette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)),
                        RoundedCornerShape(22.dp),
                    ).padding(horizontal = 30.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (downloading) {
                    Text(tx_dialog("正在下载更新", "Downloading update"), fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                    Text(tx_dialog("下载完成后会自动打开系统安装界面，安装前会校验签名。",
                            "The system installer opens automatically when done; the signature is verified first."),
                        fontSize = 13.sp, lineHeight = 19.sp,
                        color = palette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                    Spacer(Modifier.height(6.dp))
                    // 进度条用 Box 手绘，避免依赖不同 TV material3 版本的进度组件 API。
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))
                        .background(palette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0, 100) / 100f).fillMaxHeight()
                            .background(palette.primary))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(tx_dialog("请保持应用开启", "Keep the app open"), fontSize = 13.sp,
                            color = palette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                        Text("${progress.coerceIn(0, 100)}%", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = palette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = onCancelDownload,
                        modifier = Modifier.align(Alignment.End).focusRequester(initialFocus),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = palette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                            focusedContainerColor = palette.adaptive(Color(0xFFD9D9DE), Color(0xFF4A4A50)),
                        ),
                    ) {
                        Box(Modifier.width(120.dp).height(42.dp), contentAlignment = Alignment.Center) {
                            Text(tx_dialog("取消下载", "Cancel download"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = palette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                        }
                    }
                } else {
                    Text(tx_dialog("选择下载线路", "Choose download source"), fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                    Text(tx_dialog("单线程下载；加速线路失败会自动回退直连。",
                            "Single-connection download. If a mirror fails, downloading falls back to the official direct link."),
                        fontSize = 13.sp, lineHeight = 19.sp,
                        color = palette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                    Spacer(Modifier.height(6.dp))
                    sources.forEachIndexed { index, source ->
                        val isSelected = source == selected
                        Surface(
                            onClick = { onSelect(source) },
                            modifier = Modifier.fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) Color(0x33FA233B) else Color.Transparent,
                                focusedContainerColor = palette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                            ),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (english) source.en else source.zh, fontSize = 16.sp,
                                    color = palette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                                if (isSelected) Text(tx_dialog("当前", "Current"), fontSize = 13.sp,
                                    color = palette.primary)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = palette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                            focusedContainerColor = palette.adaptive(Color(0xFFD9D9DE), Color(0xFF4A4A50)),
                        ),
                    ) {
                        Box(Modifier.width(96.dp).height(42.dp), contentAlignment = Alignment.Center) {
                            Text(tx_dialog("取消", "Cancel"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = palette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ResetAppDialog(onDismiss: () -> Unit, onReset: () -> Unit) {
    com.veacon.veilmusic.ui.components.ConfirmationDialog(
        title = tr(R.string.msg_238), message = tr(R.string.msg_239),
        confirmLabel = tr(R.string.msg_240), confirmColor = Color(0xFFB22222), onDismiss = onDismiss, onConfirm = onReset,
    )
}

@Composable
private fun PhoneServerRow(webServerUrl: String) {
    var showQr by remember { mutableStateOf(false) }
    Column {
        ActionSettingRow(
            if (webServerUrl.isNotEmpty()) webServerUrl else tr(R.string.msg_243),
            tr(R.string.msg_244),
            enabled = webServerUrl.isNotEmpty(),
            onClick = { if (webServerUrl.isNotEmpty()) showQr = true },
        )
    }
    if (showQr && webServerUrl.isNotEmpty()) {
        QrDialog(url = webServerUrl, onDismiss = { showQr = false })
    }
}

@Composable
private fun QrDialog(url: String, onDismiss: () -> Unit) {
    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(24.dp))
                .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF2F2F7), Color(0xFF1C1C1E)))
                .padding(horizontal = 32.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text("手机扫码配置", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
            Spacer(Modifier.height(6.dp))
            Text("用手机扫描二维码，在浏览器里导入歌单、调整播放设置",
                fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                color = com.veacon.veilmusic.ui.theme.AppPalette.secondary)
            Spacer(Modifier.height(24.dp))
            com.veacon.veilmusic.ui.components.QrCode(content = url, size = 236.dp, cornerRadius = 18.dp, padding = 16.dp)
            Spacer(Modifier.height(20.dp))
            Text(url, fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                color = com.veacon.veilmusic.ui.theme.AppPalette.secondary)
            Spacer(Modifier.height(26.dp))
            Surface(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = com.veacon.veilmusic.ui.theme.AppPalette.accent,
                    focusedContainerColor = Color(0xFFFF4757),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            ) {
                Text("关闭", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp))
            }
        }
    }
}

@Composable
private fun BugReportRow(webServerUrl: String) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(R.string.msg_245), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
        Text(tr(R.string.msg_246),
            fontSize = 12.sp, lineHeight = 18.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        Text(if (webServerUrl.isNotEmpty()) "$webServerUrl/report" else tr(R.string.msg_247),
            fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
    }
}

/** Calm / Normal / Strong / Crazy for the beat-reaction multiplier. */
internal fun intensityLabel(f: Float): String = when {
    f < 0.8f -> tr(R.string.msg_248)
    f < 1.5f -> tr(R.string.msg_007)
    f < 2.5f -> tr(R.string.msg_249)
    else     -> tr(R.string.msg_250)
}

internal fun orbSpeedLabel(f: Float): String = when {
    f < 0.85f -> tr(R.string.msg_251)
    f < 1.3f  -> tr(R.string.msg_007)
    else      -> tr(R.string.msg_252)
}

internal fun lyricsScaleLabel(f: Float): String = when {
    f < 0.9f  -> tr(R.string.msg_253)
    f < 1.1f  -> tr(R.string.msg_254)
    f < 1.4f  -> tr(R.string.msg_007)
    f < 1.8f  -> tr(R.string.msg_255)
    else      -> tr(R.string.msg_256)
}

/** 专辑下方歌名/歌手字号档位标签：0.85 小 · 1.0 标准 · 1.25 大 · 1.5 超大。 */
internal fun songInfoScaleLabel(f: Float): String = when {
    f < 0.9f  -> tr(R.string.msg_254)
    f < 1.1f  -> tr(R.string.msg_007)
    f < 1.35f -> tr(R.string.msg_255)
    else      -> tr(R.string.msg_256)
}

/** 「正在播放」行最多显示几个字（超出直接丢弃，不加省略号）。 */
internal const val NOW_PLAYING_MAX_CHARS = 7

/**
 * 「正在播放」行显示的歌名：只显示歌名，**最多 7 个字**，超出直接丢弃（不加省略号）。
 *
 * 截断放在数据侧而不是 `TextOverflow.Ellipsis`：状态行宽度是固定的，靠默认的字符截断会出现
 * 「半个字 + …」的别扭效果，也不好在单测里断言。按码点截断，emoji 不会被劈开。
 *
 * @param title 当前播放的曲目标题；null / 空白 时回退到 [fallback]（「未在播放」）。
 */
internal fun nowPlayingLabel(title: String?, fallback: String): String {
    val name = title?.trim().orEmpty()
    if (name.isEmpty()) return fallback
    val count = name.codePointCount(0, name.length)
    if (count <= NOW_PLAYING_MAX_CHARS) return name
    return name.substring(0, name.offsetByCodePoints(0, NOW_PLAYING_MAX_CHARS))
}


/** 设置页左列分区（与资料库一致的两列式）。 */
@Composable
internal fun settingsSectionLabels(english: Boolean): List<String> = listOf(
    if (english) "Account" else "账号外观",
    if (english) "Backdrop" else "背景动效",
    if (english) "Artwork" else "封面动效",
    if (english) "Audio" else "播放音质",
    if (english) "Lyrics" else "歌词设置",
    if (english) "General" else "通用设置",
    if (english) "Diagnostics" else "诊断工具",
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsSidebar(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onReturnToTab: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 与资料库 SectionSidebar 同一套视觉：208dp 宽、12dp 圆角、选中红底、键盘模式下焦点即选中。
    val inputModeManager = androidx.compose.ui.platform.LocalInputModeManager.current
    Column(
        modifier = modifier.fillMaxHeight()
            .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012)))
            .padding(end = 16.dp, top = 96.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "设置",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        labels.forEachIndexed { index, label ->
            val isSel = index == selected
            Surface(
                onClick = { onSelect(index) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSel) Color(0x33FA233B) else Color.Transparent,
                    focusedContainerColor = if (isSel) Color(0x4DFA233B)
                        else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged {
                        if (it.isFocused && inputModeManager.inputMode == androidx.compose.ui.input.InputMode.Keyboard) onSelect(index)
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key == androidx.compose.ui.input.key.Key.DirectionUp && index == 0) {
                            onReturnToTab?.invoke(); true
                        } else false
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 选中项左侧的红色竖条（与资料库 SectionSidebar 完全一致）
                    Box(
                        Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (isSel) Color(0xFFFA233B) else Color.Transparent)
                    )
                    Text(
                        text = label,
                        fontSize = 16.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSel) com.veacon.veilmusic.ui.theme.AppPalette.primary
                            else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        Column(Modifier.fillMaxWidth().background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)), RoundedCornerShape(14.dp)), content = content)
    }
}

@Composable
internal fun SettingDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(0.75.dp).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE0E0E5), Color(0xFF414146))))
}

@Composable
internal fun RowScope.SettingText(label: String, sub: String) {
    Column(Modifier.weight(1f).padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
        Text(sub, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
    }
}

/** 选择型设置行：点开弹一个小卡片选值（替代原来的 -+ 步进器）。 */
/** 选择型设置行：点开弹一个贴着本行的白色小气泡（与播放页歌词菜单同一套）。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ChoiceRow(
    label: String,
    value: String,
    sub: String,
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    val visibility = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    val firstFocus = remember { FocusRequester() }
    Box {
        Surface(
            onClick = { visibility.targetState = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingText(label, sub)
                Text(
                    value, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF3C3C43), Color(0xFFE3E3E8)),
                )
            }
        }
        PlayerBubbleMenu(
            visibility = visibility,
            onDismiss = { visibility.targetState = false },
            initialFocus = firstFocus,
        ) {
            Column(
                Modifier.width(220.dp).heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEachIndexed { index, option ->
                    val isSel = index == selected
                    Surface(
                        onClick = { onPick(index); visibility.targetState = false },
                        modifier = Modifier.fillMaxWidth().then(
                            if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                        ),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSel) Color(0x33FA233B) else Color.Transparent,
                            focusedContainerColor = Color(0xFFE8E8ED),
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None),
                    ) {
                        Text(
                            option, fontSize = 15.sp, maxLines = 1,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) Color(0xFFFA233B) else Color(0xFF1D1D1F),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun Stepper(label: String, value: String, sub: String, onDec: () -> Unit, onInc: () -> Unit, modifier: Modifier = Modifier, decEnabled: Boolean = true, incEnabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SettingText(label, sub)
        Row(Modifier.width(190.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            // 到边界时只调淡 + 标记 disabled，仍可聚焦：否则点完一步焦点就被顶栏接走。
            StepBtn("−", onDec, enabled = true, dimmed = !decEnabled, disabledSemantics = !decEnabled)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF3C3C43), Color(0xFFE3E3E8)),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            StepBtn("+", onInc, enabled = true, dimmed = !incEnabled, disabledSemantics = !incEnabled)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun StepBtn(symbol: String, onClick: () -> Unit, enabled: Boolean = true, dimmed: Boolean = false,
                     disabledSemantics: Boolean = false) {
    Surface(onClick = onClick, enabled = enabled,
        // 到边界时只做两件事：符号调淡 + 语义上标记 disabled（读屏/测试照旧）。
        // 但不能真的禁用：禁用的按钮不可聚焦，点完一步焦点会掉出本行、被顶栏接走。
        // 点到边界的那一下由各 step 函数自己夹取，等于空操作。
        modifier = Modifier.width(46.dp).height(30.dp).then(
            if (disabledSemantics) Modifier.semantics { disabled() } else Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)), focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.accent,
            focusedContentColor = Color.White, contentColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF3C3C43), Color(0xFFE3E3E8))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            // 颜色不写死：聚焦时跟随 focusedContentColor（白字）。
            Text(symbol, fontSize = 20.sp, modifier = Modifier.alpha(if (dimmed) 0.45f else 1f))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun Toggle(label: String, on: Boolean, sub: String, modifier: Modifier = Modifier, enabled: Boolean = true, onToggle: () -> Unit) {
    Surface(onClick = onToggle, enabled = enabled, modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SettingText(label, sub)
            Box(Modifier.width(48.dp).height(28.dp)
                .background(if (on) Color(0xFFFA233B) else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFD2D2D7), Color(0xFF55555C)), RoundedCornerShape(50)),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.padding(3.dp).size(22.dp).background(Color.White, RoundedCornerShape(50)))
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, fontSize = 18.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)), fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp))
}

/**
 * 只读状态行：左边是标签，右边是当前值（例如「正在播放 → 小星星」）。
 * 与 [Toggle] / [Stepper] 同一套高度与内边距，列在一起不会高低不齐。
 */
@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SettingText(label, "")
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 320.dp),
            textAlign = TextAlign.End,
            color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean, sub: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SettingText(label, sub.orEmpty())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(if (ok) Color(0xFF248A3D) else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), RoundedCornerShape(50)))
            Text(if (ok) tr(R.string.msg_257) else tr(R.string.msg_054), fontSize = 13.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ActionSettingRow(label: String, description: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = if (danger) Color(0xFFB22222) else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                Text(description, fontSize = 12.sp, lineHeight = 18.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
            Text("›", fontSize = 22.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
        }
    }
}

/**
 * 「手机扫码登录」入口行：整行可点、右侧只有箭头，**没有开关**——开关对"扫码"这种一次性动作没有意义，
 * 而且原来那个假开关（永远为 false）看起来像"登录没生效"。点进去直接开二维码流程。
 *
 * 登录后的状态与云端歌单也铺在这一行里（.cn 区域的歌单同样列出），
 * 设置页不再需要另一块单独的状态区，减少一处"看起来重复"的信息。
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SignInRow(
    statusLine: String,
    syncedCount: Int,
    loadingLibrary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 只显示「登录情况 + 同步了几个歌单」，不再铺歌单名。
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
            focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("点击扫码登录 Apple Music", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                Text(statusLine, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                val syncText = when {
                    loadingLibrary -> if (com.veacon.veilmusic.i18n.AppLanguage.code == "en") "Syncing playlists…" else "正在同步歌单…"
                    syncedCount > 0 -> if (com.veacon.veilmusic.i18n.AppLanguage.code == "en") "$syncedCount playlists synced" else "已同步 $syncedCount 个歌单"
                    else -> if (com.veacon.veilmusic.i18n.AppLanguage.code == "en") "No cloud playlists synced" else "暂无已同步歌单"
                }
                Text(syncText, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable

internal fun cacheBytesLabel(bytes: Long): String = if (bytes < 1_000_000_000L)
    (if (com.veacon.veilmusic.i18n.AppLanguage.code == "en") "%.0f MB" else "%.0fM").format(java.util.Locale.ROOT, bytes.coerceAtLeast(0) / 1_000_000.0)
else (if (com.veacon.veilmusic.i18n.AppLanguage.code == "en") "%.2f GB" else "%.2fG").format(java.util.Locale.ROOT, bytes / 1_000_000_000.0)

@Composable
internal fun CacheSettingsContent(capacityGb: Int, usedBytes: Long, busy: Boolean, message: String,
    onCapacity: (Int) -> Unit, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val progress = (usedBytes.toDouble() / (capacityGb * 1_000_000_000L)).toFloat().coerceIn(0f, 1f)
    Stepper(tr(R.string.msg_259), if (com.veacon.veilmusic.i18n.AppLanguage.code == "en") "$capacityGb GB" else "${capacityGb}G", tr(R.string.msg_260),
            { onCapacity((capacityGb - 1).coerceAtLeast(1)) },
            { onCapacity((capacityGb + 1).coerceAtMost(10)) },
            decEnabled = !busy && capacityGb > 1, incEnabled = !busy && capacityGb < 10)
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr(R.string.msg_261, cacheBytesLabel(usedBytes), capacityGb), fontSize = 12.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                Text("${(progress * 100).toInt()}%", fontSize = 12.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
                .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE0E0E5), Color(0xFF414146))).semantics {
                    contentDescription = tr(R.string.msg_262)
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                }) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(com.veacon.veilmusic.ui.theme.AppPalette.accent))
            }
            if (message.isNotEmpty()) Text(com.veacon.veilmusic.i18n.AppLanguage.message(message).orEmpty(), fontSize = 12.sp, lineHeight = 18.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        }
        SettingDivider()
        ActionSettingRow(if (busy) tr(R.string.msg_263) else tr(R.string.msg_264), tr(R.string.msg_265), enabled = !busy, onClick = { confirmClear = true })
    if (confirmClear) {
        ClearCacheDialog(onDismiss = { confirmClear = false }) {
            confirmClear = false
            if (!busy) onClear()
        }
    }
}

@Composable
internal fun ClearCacheDialog(onDismiss: () -> Unit, onClear: () -> Unit) {
    com.veacon.veilmusic.ui.components.ConfirmationDialog(
        title = tr(R.string.msg_266), message = tr(R.string.msg_267),
        confirmLabel = tr(R.string.msg_268), onDismiss = onDismiss, onConfirm = onClear,
    )
}

@Composable
internal fun LanguageStepper() {
    val choice = com.veacon.veilmusic.i18n.AppLanguage.choice
    val choices = com.veacon.veilmusic.i18n.LanguageChoice.entries
    val label = when (choice) {
        com.veacon.veilmusic.i18n.LanguageChoice.System -> tr(R.string.msg_311)
        com.veacon.veilmusic.i18n.LanguageChoice.Chinese -> "简体中文"
        com.veacon.veilmusic.i18n.LanguageChoice.English -> "English"
    }
        fun changeLanguage(direction: Int) {
            com.veacon.veilmusic.i18n.AppLanguage.select(choices[(com.veacon.veilmusic.i18n.AppLanguage.choice.ordinal + direction + choices.size) % choices.size])
        }
    Stepper(tr(R.string.msg_313), label, tr(R.string.msg_314),
        { changeLanguage(-1) }, { changeLanguage(1) })
}

/**
 * 开发者模式「配置音源」二级菜单：列出内置音源，支持停用/启用、删除。
 * 用 MotionDialog 弹出，与 App 其余弹窗风格一致；列表项用方向键上下移动、确认执行。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceManagerDialog(
    scripts: List<com.veacon.veilmusic.luoxue.LxPlayback.ScriptView>,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val initialFocus = remember { FocusRequester() }

    // usePlatformDefaultWidth=false 才能拿到全屏窗口，再由内容自己缩到 80%。
    com.veacon.veilmusic.ui.components.MotionDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.menuInitialFocus(initialFocus)
                    .fillMaxWidth(0.8f).fillMaxHeight(0.8f)
                    .background(
                        com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)),
                        RoundedCornerShape(22.dp),
                    ).padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(tx_dialog("配置音源", "Configure sources"), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                    Text("${scripts.size} 个音源", fontSize = 13.sp,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                }
                // 列表吃掉剩余高度（80% 屏幕里的大部分），不再用固定 480dp 上限。
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(scripts, key = { it.file }) { s ->
                        SourceManagerRow(
                            script = s,
                            onToggle = { onToggle(s.file, !s.disabled) },
                            onDelete = { onDelete(s.file) },
                        )
                    }
                }
                Surface(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).focusRequester(initialFocus),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                        focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFD9D9DE), Color(0xFF4A4A50)),
                    ),
                ) {
                    Box(Modifier.width(96.dp).height(42.dp), contentAlignment = Alignment.Center) {
                        Text(tx_dialog("关闭", "Close"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                    }
                }
            }
        }
    }
}

private fun tx_dialog(zh: String, en: String) =
    if (com.veacon.veilmusic.i18n.AppLanguage.code == "en") en else zh

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceManagerRow(
    script: com.veacon.veilmusic.luoxue.LxPlayback.ScriptView,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val display = script.displayName.ifBlank {
        script.file.removeSuffix(".js").removePrefix("lx-")
    }
    val sources = script.sources.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "—"
    Row(
        Modifier.fillMaxWidth()
            .background(
                com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF2A2A2E)),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 左侧：名称 + 状态 + 平台列表（放同一列，长平台名不会把按钮顶出卡片）。
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(display, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                if (script.disabled) {
                    Text("已停用", fontSize = 11.sp, color = Color(0xFFB22222))
                } else if (script.loaded) {
                    Text("已加载", fontSize = 11.sp, color = Color(0xFF2E9E44))
                }
            }
            Text("源 $sources", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
        }
        // 右侧：停用 / 删除，同高同宽、文字居中。
        SourceActionButton(
            label = tx_dialog(if (script.disabled) "启用" else "停用", if (script.disabled) "Enable" else "Disable"),
            role = SourceActionRole.Secondary,
        ) { onToggle() }
        SourceActionButton(
            label = tx_dialog("删除", "Delete"),
            role = SourceActionRole.Destructive,
        ) { onDelete() }
    }
}

/** 音源行的操作按钮层级。 */
private enum class SourceActionRole { Secondary, Destructive }

/**
 * 音源行的操作按钮：固定尺寸 + 文字水平垂直居中。
 *
 * 统一 92×38dp、19dp 圆角，两个按钮同高同宽，视觉重量一致；
 * 次要操作（停用）用中性描边，危险操作（删除）用红色 —— 层级一眼可辨；
 * 描边克制（1dp），聚焦时加深到 1.5dp；按下时轻微缩小并加深底色。
 */
@Composable
private fun SourceActionButton(
    label: String,
    role: SourceActionRole,
    onClick: () -> Unit,
) {
    val danger = Color(0xFFB22222)
    val ink = when (role) {
        SourceActionRole.Secondary -> com.veacon.veilmusic.ui.theme.AppPalette.primary
        SourceActionRole.Destructive -> danger
    }
    val borderColor = when (role) {
        SourceActionRole.Secondary -> com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFD2D2D7), Color(0xFF48484E))
        SourceActionRole.Destructive -> danger.copy(alpha = 0.45f)
    }
    val focusedBorderColor = when (role) {
        SourceActionRole.Secondary -> com.veacon.veilmusic.ui.theme.AppPalette.primary
        SourceActionRole.Destructive -> danger
    }
    val shape = RoundedCornerShape(19.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape, shape, shape, shape, shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = ink,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = ink,
            pressedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0x14000000), Color(0x1AFFFFFF)),
            pressedContentColor = ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f, pressedScale = 0.96f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, borderColor), shape = shape),
            focusedBorder = Border(BorderStroke(1.5.dp, focusedBorderColor), inset = 2.dp, shape = shape),
        ),
        modifier = Modifier.width(92.dp).height(38.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ink,
                textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

