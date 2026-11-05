package com.veil.music.ui.player.fullscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.veil.music.ui.viewmodel.FS_COL_GAP_STEP_PCT
import com.veil.music.ui.viewmodel.FS_FONT_STEP_SP
import com.veil.music.ui.viewmodel.FS_GAP_STEP_PCT
import com.veil.music.ui.viewmodel.FS_TRACKING_STEP
import com.veil.music.ui.viewmodel.FsStyle
import com.veil.music.ui.viewmodel.FsStylePrefs
import com.veil.music.ui.viewmodel.LyricBgMode
import com.veil.music.ui.viewmodel.LyricFontChoice
import com.veil.music.ui.viewmodel.NowPlayingBackground
import com.veil.music.ui.viewmodel.ORB_SPEED_STEP

/** 胶囊展开后显示哪一页子面板。 */
enum class CapsulePanel { NONE, FONT, SIZE, ACCENT, BG }

/**
 * 「大小」页里的每一行。
 *
 * 三个界面能调的东西不一样：横排的「全屏歌词」有行距，竖排的「墨卷」只有竖距
 * （列与列的水平间距），声轨去掉行距后只剩字号与字距。
 */
private enum class SizeRow(val label: String) {
    FONT_SIZE("字号"),
    LINE_GAP("行距"),
    COL_GAP("竖距"),
    TRACKING("字距"),
}

private fun sizeRows(style: FsStyle): List<SizeRow> = when (style) {
    FsStyle.PLAIN -> listOf(SizeRow.FONT_SIZE, SizeRow.LINE_GAP, SizeRow.TRACKING)
    FsStyle.INK -> listOf(SizeRow.FONT_SIZE, SizeRow.COL_GAP, SizeRow.TRACKING)
    FsStyle.TRACK -> listOf(SizeRow.FONT_SIZE, SizeRow.TRACKING)
}

private fun SizeRow.valueText(pref: FsStylePrefs): String = when (this) {
    SizeRow.FONT_SIZE -> "${pref.fontSp} sp"
    SizeRow.LINE_GAP -> "${pref.lineGapPct}%"
    SizeRow.COL_GAP -> "${pref.colGapPct}%"
    SizeRow.TRACKING -> String.format("%.2f em", pref.letterSpacing / 100f)
}

/** 背景页的 4 行：默认 / 纯黑 / 上传自定义 / 清除。 */
private const val BG_ROW_DEFAULT = 0
private const val BG_ROW_BLACK = 1
private const val BG_ROW_CUSTOM = 2
private const val BG_ROW_CLEAR = 3
private const val BG_ROW_COUNT = 4

/** 播放页背景页的第 4 行（前 3 行与 [NowPlayingBackground] 的顺序一一对应）。 */
private const val BG_ROW_SPEED = 3

/**
 * 「背景」页显示哪一套东西。
 *
 * 播放页的「背景」说的是页面背景（动态封面 / 投影光晕 / 纯黑 + 摆动速度）；
 * 全屏歌词、墨卷、声轨的「背景」说的是歌词底下那张纸（默认 / 纯黑 / 自定义图）。
 * 同一个按钮，两种对象 —— 之前在播放页还得绕去「⋯ → 播放页背景」才能改，
 * 现在就地调。
 */
enum class CapsuleBgPanel { PLAYER, LYRIC }

private fun fontChoices(hasCustomFont: Boolean): List<LyricFontChoice> =
    LyricFontChoice.entries.filter { it != LyricFontChoice.CUSTOM || hasCustomFont }

/**
 * **调节胶囊**（TuningCapsule）的交互状态机（纯逻辑，不依赖 Compose 的焦点体系）。
 *
 * 三枚浮动胶囊之一：顶栏胶囊（TopNavCapsule，切页）／迷你胶囊（MiniPlayerCapsule，
 * 底部播放条）／调节胶囊（这一枚，播放页长按下键呼出，调字号、字体、背景、换效）。
 * 三者都画在内容层与播放层之上，不参与返回栈 —— 见 AppShell 顶部的层级说明。
 *
 * 为什么不用 Compose 焦点：播放层（播放页 / 全屏歌词 / 墨卷 / 声轨）本身已经在
 * `onPreviewKeyEvent` 里接管按键，再往上叠一层会互相抢焦点。这里干脆把按键全部收进
 * 状态机，胶囊只做「画」，由舞台在预览阶段把事件喂进来 —— 逻辑集中、也不会因为焦点树
 * 变化而失灵。返回键先关胶囊、再退层，正是靠"预览阶段先吃键"实现的。
 */
@Stable
class TuningCapsuleController(
    private val onFont: (LyricFontChoice) -> Unit,
    private val onNudgeSize: (Int) -> Unit,
    private val onNudgeLine: (Int) -> Unit,
    private val onNudgeColGap: (Int) -> Unit,
    private val onNudgeTrack: (Int) -> Unit,
    private val onSetBg: (LyricBgMode) -> Unit,
    private val onClearBg: () -> Unit,
    private val onOpenQr: () -> Unit,
    private val onCycleEffect: () -> Unit,
    /** 「背景」页选页面背景（动态封面 / 投影光晕 / 纯黑）时回调；只在 [bgPanel] = PLAYER 时用。 */
    private val onSetPlayerBg: (NowPlayingBackground) -> Unit = {},
    /** 「背景」页第 4 行的摆动速度步进，正负一个 [ORB_SPEED_STEP]。 */
    private val onNudgeOrbSpeed: (Float) -> Unit = {},
    /** 「字色」页：选中第几档（0 = 默认，1–7 = 彩虹）。墨卷 / 声轨用。 */
    private val onSetAccent: (Int) -> Unit = {},
    /** 「背景」页讲的是页面背景还是歌词底图 —— 播放页与三个歌词界面共用同一枚胶囊，靠它区分。 */
    val bgPanel: CapsuleBgPanel = CapsuleBgPanel.LYRIC,
) {
    var visible by mutableStateOf(false)
        private set
    var tab by mutableStateOf(CapsuleTab.SIZE)
        private set
    var panel by mutableStateOf(CapsulePanel.NONE)
        private set
    /** 子面板里当前选中的行。 */
    var item by mutableIntStateOf(0)
        private set

    /** 长按触发后，同一次按住产生的重复 DOWN 事件要吞掉，否则会立刻又把面板收起。 */
    private var suppressDownUntilUp = false

    /**
     * 当前界面能用的页签。
     *
     * 「字色」只对墨卷 / 声轨有意义（全屏歌词是整屏正文，彩虹色会破坏可读性），
     * 所以在 PLAIN 上空不出现，左右切换时也不会停在一页调不动的面板上。
     */
    private fun tabsFor(style: FsStyle): List<CapsuleTab> =
        CapsuleTab.entries.filter { it != CapsuleTab.ACCENT || style != FsStyle.PLAIN }

    private fun applyVisible(v: Boolean) {
        visible = v
        if (!v) panel = CapsulePanel.NONE
    }

    fun close() = applyVisible(false)

    /**
     * 喂一个按键事件。返回 true 表示已被胶囊消费（舞台不要再处理）。
     *
     * [style] 决定「大小」页里有几行（墨卷有竖距、声轨没有行距）。
     */
    fun onKey(
        e: KeyEvent,
        pref: FsStylePrefs,
        hasCustomFont: Boolean,
        style: FsStyle,
    ): Boolean {
        val k = e.key
        if (e.type == KeyEventType.KeyUp) {
            if (k == Key.DirectionDown && suppressDownUntilUp) {
                suppressDownUntilUp = false
                return visible
            }
            // 胶囊展开时，导航键 / OK 的「抬起」也必须吃掉。
            //
            // 原因：Compose 的 clickable 是在 KeyUp 上触发点击的。只吞 KeyDown 时，
            // 底下仍然持有焦点的播放键会收到这次 KeyUp —— 于是「长按下键呼出胶囊」
            // 之后按一次 OK，胶囊打开面板的同时歌被暂停了。返回 [visible] 让它
            // 只在胶囊开着时生效，收起来之后按键照常落到控件上。
            return visible && (
                k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter ||
                    k == Key.DirectionLeft || k == Key.DirectionRight || k == Key.DirectionUp ||
                    k == Key.Back
                )
        }
        if (e.type != KeyEventType.KeyDown) return false

        // 未显示：只有当「下键」被按住（系统开始重复）时才呼出。
        if (!visible) {
            if (k == Key.DirectionDown && e.nativeKeyEvent.repeatCount >= 1) {
                applyVisible(true)
                tab = CapsuleTab.SIZE
                panel = CapsulePanel.NONE
                item = 0
                suppressDownUntilUp = true
                return true
            }
            return false
        }

        if (k == Key.DirectionDown && suppressDownUntilUp) return true

        val tabs = tabsFor(style)
        return when (panel) {
            CapsulePanel.NONE -> when (k) {
                Key.DirectionLeft -> {
                    tab = tabs[(tab.ordinal + tabs.size - 1) % tabs.size]; true
                }
                Key.DirectionRight -> {
                    tab = tabs[(tab.ordinal + 1) % tabs.size]; true
                }
                Key.DirectionUp, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    activateTab(pref)
                    true
                }
                Key.DirectionDown, Key.Back -> {
                    applyVisible(false); true
                }
                else -> false
            }

            CapsulePanel.FONT -> {
                val list = fontChoices(hasCustomFont)
                val n = list.size.coerceAtLeast(1)
                when (k) {
                    Key.DirectionUp -> { item = (item + n - 1) % n; true }
                    Key.DirectionDown -> { item = (item + 1) % n; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        list.getOrNull(item)?.let(onFont)
                        panel = CapsulePanel.NONE
                        true
                    }
                    Key.Back, Key.DirectionLeft, Key.DirectionRight -> { panel = CapsulePanel.NONE; true }
                    else -> false
                }
            }

            CapsulePanel.SIZE -> {
                val rows = sizeRows(style)
                when (k) {
                    Key.DirectionUp -> { item = (item + rows.size - 1) % rows.size; true }
                    Key.DirectionDown -> { item = (item + 1) % rows.size; true }
                    Key.DirectionLeft -> { adjustSize(style, item, -1); true }
                    Key.DirectionRight -> { adjustSize(style, item, 1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back -> { panel = CapsulePanel.NONE; true }
                    else -> false
                }
            }

            CapsulePanel.ACCENT -> {
                val n = LyricAccent.entries.size
                when (k) {
                    Key.DirectionUp, Key.DirectionLeft -> {
                        item = (item + n - 1) % n
                        onSetAccent(item)   // 边选边预览，不用按确定才知道什么颜色
                        true
                    }
                    Key.DirectionDown, Key.DirectionRight -> {
                        item = (item + 1) % n
                        onSetAccent(item)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onSetAccent(item)
                        panel = CapsulePanel.NONE
                        true
                    }
                    Key.Back -> { panel = CapsulePanel.NONE; true }
                    else -> false
                }
            }

            CapsulePanel.BG -> when (k) {
                Key.DirectionUp -> { item = (item + BG_ROW_COUNT - 1) % BG_ROW_COUNT; true }
                Key.DirectionDown -> { item = (item + 1) % BG_ROW_COUNT; true }
                Key.DirectionLeft -> {
                    when {
                        bgPanel == CapsuleBgPanel.PLAYER && item == BG_ROW_SPEED ->
                            onNudgeOrbSpeed(-ORB_SPEED_STEP)
                        bgPanel == CapsuleBgPanel.LYRIC -> panel = CapsulePanel.NONE
                    }
                    true
                }
                Key.DirectionRight -> {
                    if (bgPanel == CapsuleBgPanel.PLAYER && item == BG_ROW_SPEED) {
                        onNudgeOrbSpeed(ORB_SPEED_STEP)
                    }
                    true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (bgPanel == CapsuleBgPanel.PLAYER) {
                        // 前 3 行是三种页面背景；第 4 行是速度，确定键不做事、面板留着继续调。
                        NowPlayingBackground.entries.getOrNull(item)?.let {
                            onSetPlayerBg(it)
                            panel = CapsulePanel.NONE
                        }
                    } else when (item) {
                        BG_ROW_DEFAULT -> { onSetBg(LyricBgMode.DEFAULT); panel = CapsulePanel.NONE }
                        BG_ROW_BLACK -> { onSetBg(LyricBgMode.BLACK); panel = CapsulePanel.NONE }
                        BG_ROW_CUSTOM -> {
                            // 上传即启用；图还没传时渲染层会回落默认底，不会突然全黑。
                            onSetBg(LyricBgMode.CUSTOM)
                            applyVisible(false)
                            onOpenQr()
                        }
                        BG_ROW_CLEAR -> { onClearBg(); panel = CapsulePanel.NONE }
                    }
                    true
                }
                Key.Back -> { panel = CapsulePanel.NONE; true }
                else -> false
            }
        }
    }

    private fun activateTab(pref: FsStylePrefs) {
        when (tab) {
            CapsuleTab.CYCLE -> { applyVisible(false); onCycleEffect() }
            CapsuleTab.FONT -> { panel = CapsulePanel.FONT; item = 0 }
            CapsuleTab.SIZE -> { panel = CapsulePanel.SIZE; item = 0 }
            CapsuleTab.ACCENT -> { panel = CapsulePanel.ACCENT; item = pref.accent }
            CapsuleTab.BG -> { panel = CapsulePanel.BG; item = 0 }
            CapsuleTab.QR -> { applyVisible(false); onOpenQr() }
        }
    }

    private fun adjustSize(style: FsStyle, index: Int, sign: Int) {
        when (sizeRows(style).getOrNull(index)) {
            // 字号 1–80sp 全开之后步进降到 1：范围变宽了还按 2 走，掐不到 1 也掐不准 27 这种值。
            SizeRow.FONT_SIZE -> onNudgeSize(sign * FS_FONT_STEP_SP)
            SizeRow.LINE_GAP -> onNudgeLine(sign * FS_GAP_STEP_PCT)
            SizeRow.COL_GAP -> onNudgeColGap(sign * FS_COL_GAP_STEP_PCT)
            SizeRow.TRACKING -> onNudgeTrack(sign * FS_TRACKING_STEP)
            null -> Unit
        }
    }
}

/**
 * 悬浮胶囊的绘制。纯展示，按键由 [TuningCapsuleController] 处理。
 *
 * 布局对齐用户草图：底部一条圆角胶囊，内含 5 个圆形按钮；选中项展开上方子面板。
 * 胶囊底下**不写任何说明文字** —— 图标与展开的面板本身已经说明了一切，
 * 多一行绿字在深色画面上比不写更吵。
 *
 * [playerBg] / [orbSpeed] 只在 [TuningCapsuleController.bgPanel] 为
 * [CapsuleBgPanel.PLAYER]（播放页那枚胶囊）时用得上。
 */
@Composable
fun TuningCapsule(
    controller: TuningCapsuleController,
    style: FsStyle,
    pref: FsStylePrefs,
    hasCustomFont: Boolean,
    hasCustomBg: Boolean,
    modifier: Modifier = Modifier,
    playerBg: NowPlayingBackground = NowPlayingBackground.DYNAMIC,
    orbSpeed: Float = 1f,
) {
    if (!controller.visible) return
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (controller.panel != CapsulePanel.NONE) {
                CapsulePanelCard(controller, style, pref, hasCustomFont, hasCustomBg, playerBg, orbSpeed)
                Spacer(Modifier.height(12.dp))
            }
            CapsulePill(controller.tab, style)
        }
    }
}

@Composable
private fun CapsulePill(selected: CapsuleTab, style: FsStyle) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xB3000000))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsuleTab.entries.filter { it != CapsuleTab.ACCENT || style != FsStyle.PLAIN }.forEach { t ->
            val on = t == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (on) Color.White else Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    CapsuleIcon(
                        tab = t,
                        tint = if (on) Color(0xFF0A0A0C) else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    t.label,
                    color = if (on) Color(0xFF7CFFB2) else Color(0x99FFFFFF),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun CapsulePanelCard(
    controller: TuningCapsuleController,
    style: FsStyle,
    pref: FsStylePrefs,
    hasCustomFont: Boolean,
    hasCustomBg: Boolean,
    playerBg: NowPlayingBackground,
    orbSpeed: Float,
) {
    Column(
        Modifier
            .width(380.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF2101014))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        when (controller.panel) {
            CapsulePanel.FONT -> FontPanel(controller.item, hasCustomFont)
            CapsulePanel.SIZE -> SizePanel(controller.item, pref, style)
            CapsulePanel.ACCENT -> AccentPanel(controller.item)
            CapsulePanel.BG -> when (controller.bgPanel) {
                CapsuleBgPanel.PLAYER -> PlayerBgPanel(controller.item, playerBg, orbSpeed)
                CapsuleBgPanel.LYRIC -> BgPanel(controller.item, pref.bg, hasCustomBg)
            }
            CapsulePanel.NONE -> {}
        }
    }
}

@Composable
private fun AccentPanel(item: Int) {
    Column(Modifier.fillMaxWidth()) {
        PanelTitle("当前句字色")
        LyricAccent.entries.forEachIndexed { i, accent ->
            val on = i == item
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color(0xFF2A2A31) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(
                            if (accent == LyricAccent.DEFAULT) Color(0xFF8E8E93) else accent.color
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(accent.label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (on) ChevronRight(Color(0xFF7CFFB2), Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun FontPanel(item: Int, hasCustomFont: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        PanelTitle("字体")
        fontChoices(hasCustomFont).forEachIndexed { i, choice ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (i == item) Color(0xFF2A2A31) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(choice.label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (i == item) ChevronRight(Color(0xFF7CFFB2), Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SizePanel(item: Int, pref: FsStylePrefs, style: FsStyle) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        sizeRows(style).forEachIndexed { i, row ->
            SizeRowView(row.label, row.valueText(pref), i == item)
        }
    }
}

@Composable
private fun SizeRowView(label: String, value: String, selected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF2A2A31) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xFF9A9AA2), fontSize = 13.sp, modifier = Modifier.width(52.dp))
        Spacer(Modifier.weight(1f))
        StepBadge(
            plus = false,
            tint = if (selected) Color(0xFF7CFFB2) else Color(0x66FFFFFF),
            modifier = Modifier.size(18.dp),
        )
        Text(
            value,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        StepBadge(
            plus = true,
            tint = if (selected) Color(0xFF7CFFB2) else Color(0x66FFFFFF),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun BgPanel(item: Int, current: LyricBgMode, hasCustomBg: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        PanelTitle("背景")
        BgRow(
            "默认背景",
            sub = if (current == LyricBgMode.DEFAULT) "使用中" else "",
            selected = item == BG_ROW_DEFAULT,
        )
        BgRow(
            "纯黑背景",
            sub = if (current == LyricBgMode.BLACK) "使用中" else "",
            selected = item == BG_ROW_BLACK,
        )
        BgRow(
            "扫码上传自定义图",
            sub = if (current == LyricBgMode.CUSTOM) "使用中" else if (hasCustomBg) "已上传" else "未上传",
            selected = item == BG_ROW_CUSTOM,
        )
        BgRow(
            "清除自定义图",
            sub = if (hasCustomBg) "" else "无",
            selected = item == BG_ROW_CLEAR,
        )
    }
}

@Composable
private fun BgRow(label: String, sub: String, selected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF2A2A31) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (sub.isNotEmpty()) {
            Text(
                sub,
                color = if (sub == "使用中") Color(0xFF7CFFB2) else Color(0x8AFFFFFF),
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 播放页的「背景」页：三种页面背景 + 摆动速度，和设置里原来那一组完全对应。
 *
 * 前 3 行确定即切换，第 4 行左右键步进 0.1。选中行高亮 + 右侧「使用中」，
 * 不必再回设置里确认现在用的是哪一种。
 */
@Composable
private fun PlayerBgPanel(item: Int, current: NowPlayingBackground, orbSpeed: Float) {
    Column(Modifier.fillMaxWidth()) {
        PanelTitle("播放页背景")
        NowPlayingBackground.entries.forEachIndexed { i, mode ->
            BgOptionRow(
                label = mode.label,
                desc = mode.desc,
                selected = item == i,
                current = current == mode,
            )
        }
        BgSpeedRow(
            speed = orbSpeed,
            selected = item == BG_ROW_SPEED,
        )
    }
}

/** 一行背景选项：名字 + 一句说明，正在用的那行右侧标绿。 */
@Composable
private fun BgOptionRow(label: String, desc: String, selected: Boolean, current: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF2A2A31) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text(desc, color = Color(0x8AFFFFFF), fontSize = 10.sp, maxLines = 1)
        }
        if (current) {
            Text("使用中", color = Color(0xFF7CFFB2), fontSize = 11.sp)
        }
    }
}

/** 速度行：选中时两侧亮起 − / +，提示这一行是左右键调的。 */
@Composable
private fun BgSpeedRow(speed: Float, selected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF2A2A31) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("摆动速度", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (selected) {
            StepBadge(
                plus = false,
                tint = Color(0xFF7CFFB2),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            String.format("%.1f×", speed),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        if (selected) {
            StepBadge(
                plus = true,
                tint = Color(0xFF7CFFB2),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text,
        color = Color(0xFF9A9AA2),
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
    )
}
