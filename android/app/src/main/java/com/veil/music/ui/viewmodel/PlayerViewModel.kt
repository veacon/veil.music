package com.veil.music.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.veil.music.data.CrossfadePreferences
import com.veil.music.data.LyricsOffsetPreferences
import com.veil.music.data.VeilPreferences
import com.veil.music.data.lx.LxMapper
import com.veil.music.data.lx.TrackRegistry
import com.veil.music.data.model.LyricLine
import com.veil.music.data.model.Song
import com.veil.music.data.model.Track
import com.veil.music.data.playback.PlaybackResolver
import com.veil.music.data.repository.LxRepository
import com.veil.music.data.store.LibraryState
import com.veil.music.data.store.LibraryStore
import com.veil.music.source.ResolveOrigin
import com.veil.music.media.BeatAnalyzer
import com.veil.music.media.BeatAwareRenderersFactory
import com.veil.music.media.DecoderSelectors
import com.veil.music.media.GainProcessor
import com.veil.music.media.InAppWebServer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.roundToInt

/** 循环模式。 */
enum class RepeatMode { Off, One, All }

/** 播放页背景样式。 */
enum class NowPlayingBackground(val label: String, val desc: String) {
    DYNAMIC("动态封面", "从专辑封面取色，缓慢流动"),
    PROJECTOR("投影光晕", "大面积色块呼吸，对比更强"),
    BLACK("纯黑", "关闭背景动效，最省电"),
    ;

    companion object {
        fun fromName(name: String?): NowPlayingBackground =
            entries.firstOrNull { it.name == name } ?: DYNAMIC
    }
}

/** 全屏歌词的两种沉浸样式。 */
enum class FullscreenLyricMode(val label: String) {
    INK_SCROLL("墨卷"),
    TRACK_BED("声轨"),
}

/** 全屏歌词可选字体。[label] 直接显示在设置页。 */
enum class LyricFontChoice(val label: String) {
    SYSTEM("系统字体"),
    PINGFANG("苹方"),
    MINGMEI("Aa祝你我明媚向春天"),
    /** 用户通过手机扫码上传的字体（.ttf/.otf），落在应用私有目录。 */
    CUSTOM("自定义字体"),
    ;

    companion object {
        fun fromName(name: String?): LyricFontChoice =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * 全屏歌词的背景来源。墨卷 / 声轨各自独立存一份，互不影响。
 *
 * - [DEFAULT]：各界面原来的底（墨卷=夜墨/宣纸，声轨=机房/工程纸）
 * - [BLACK]：纯黑，最省电、也最不抢注意力
 * - [CUSTOM]：手机扫码上传的图片（铺满 + 压暗）
 */
enum class LyricBgMode(val label: String) {
    DEFAULT("默认背景"),
    BLACK("纯黑背景"),
    CUSTOM("自定义图片"),
    ;

    companion object {
        fun fromName(name: String?): LyricBgMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 三类「全屏」歌词界面。设置页按这个维度分三套存排版参数。
 *
 * 为什么不是一套参数走天下：墨卷是竖排手卷（字号还要按屏高等比缩）、声轨是时间轴块、
 * 全屏歌词是普通大字号滚动，三者最优的字号/行距差得很远，共用一个值只会互相将就。
 */
enum class FsStyle(val label: String, val desc: String) {
    PLAIN("全屏歌词", "整屏大字，铺在动态背景上"),
    INK("墨卷", "竖排手卷，逐字落墨"),
    TRACK("声轨", "音轨时间轴，句块即时值"),
    ;

    /** 纸面开关只对墨卷/声轨有意义：全屏歌词背后是动态背景，没有「纸」。 */
    val hasPaper: Boolean get() = this != PLAIN

    companion object {
        fun of(mode: FullscreenLyricMode?): FsStyle = when (mode) {
            FullscreenLyricMode.INK_SCROLL -> INK
            FullscreenLyricMode.TRACK_BED -> TRACK
            null -> PLAIN
        }
    }
}

/** 一套全屏歌词排版参数。字号/行距/竖距/字间距都是数值，不做「紧凑/标准/宽松」三档。 */
data class FsStylePrefs(
    val font: LyricFontChoice = LyricFontChoice.SYSTEM,
    val fontSp: Int = DEFAULT_FS_FONT_SP,
    /** 行距：只对横排的「全屏歌词」有意义（墨卷竖排用 [colGapPct]，声轨已固定）。 */
    val lineGapPct: Int = DEFAULT_FS_GAP_PCT,
    /** 竖距：竖排时两列之间的水平间距（墨卷专用）。 */
    val colGapPct: Int = DEFAULT_FS_COL_GAP_PCT,
    val letterSpacing: Int = DEFAULT_FS_TRACKING,
    val light: Boolean = false,
    /** 背景来源：墨卷 / 声轨各自独立。 */
    val bg: LyricBgMode = LyricBgMode.DEFAULT,
) {
    companion object {
        /** 全屏歌词直接当正文铺满整屏，默认字号比墨卷/声轨小一档。 */
        const val PLAIN_FONT_SP = 32

        fun default(style: FsStyle) = FsStylePrefs(
            fontSp = if (style == FsStyle.PLAIN) PLAIN_FONT_SP else DEFAULT_FS_FONT_SP,
            letterSpacing = if (style == FsStyle.PLAIN) 0 else DEFAULT_FS_TRACKING,
        )
    }
}

data class PlayerState(
    val currentSong: Song? = null,
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = 0,
    val lyrics: List<LyricLine> = emptyList(),
    /** 歌词来源标签（脚本 / 平台名 / 跨平台），用于排障。 */
    val lyricsSource: String? = null,
    val isFullStream: Boolean = true,
    val motionUrl: String? = null,
    val lyricsOffsetMs: Long = 0L,
    val avSyncAuto: Boolean = true,
    val avOnBluetooth: Boolean = false,
    val avLyricsMs: Long = LYRICS_BASELINE_MS,
    val avOutputLabel: String = "HDMI / TV",
    val isShuffled: Boolean = false,
    val originalQueue: List<Song> = emptyList(),
    val repeatMode: RepeatMode = RepeatMode.Off,
    val sleepTimerEndsAt: Long? = null,
    val sleepAfterSong: Boolean = false,
    val mutExpired: Boolean = false,
    val beatIntensity: Float = 1.0f,
    val userQueue: List<Song> = emptyList(),
    val crossfadeEnabled: Boolean = true,
    val nowPlayingBackground: NowPlayingBackground = NowPlayingBackground.DYNAMIC,
    /**
     * 底部迷你播放条（音乐胶囊）的缩放，百分比，100 = 设计稿原始尺寸。
     *
     * 高度和宽度分开调：电视屏幕从 32 寸到 85 寸都有，同一条胶囊小屏上顶到边、大屏上又细得
     * 像根线；只给一个「整体缩放」会连比例一起改，反而更难对齐到喜欢的样子。
     * 高度同时决定内部图标/封面/文字的缩放，宽度只改它占屏幕的比例。
     */
    val miniCapsuleHeightPct: Int = 100,
    val miniCapsuleWidthPct: Int = 100,
    val showNowPlayingInfo: Boolean = true,
    val motionArtworkEnabled: Boolean = false,
    val orbSpeed: Float = DEFAULT_ORB_SPEED,
    /** 歌词基准字号（sp）。设置页直接暴露这个数字，不再用小/中/大这种模糊档位。 */
    val lyricFontSp: Int = DEFAULT_LYRIC_FONT_SP,
    /** 歌词行距，百分比（100 = 原设计）。同样是数值，不做「紧凑/标准/宽松」三档。 */
    val lyricLineGapPct: Int = DEFAULT_LYRIC_GAP_PCT,
    /** 播放页歌词字间距（em×100）。与全屏歌词那套同单位、同范围。 */
    val lyricTracking: Int = DEFAULT_LYRIC_TRACKING,
    /**
     * 三类全屏歌词界面各自的排版参数（全屏歌词 / 墨卷 / 声轨，各存一套）。
     * 与播放页右栏的 [lyricFontSp] / [lyricLineGapPct] 完全独立。
     */
    val fsStyles: Map<FsStyle, FsStylePrefs> = FsStyle.entries.associateWith { FsStylePrefs.default(it) },
    /**
     * 音频解码：false = 硬解（MediaCodec 硬件解码器优先），true = 软解（平台软件解码器优先）。
     *
     * 默认软解。电视盒子上的硬解实现参差不齐 —— 同一个盒子放 AAC 没事、放 FLAC 24bit 就爆音，
     * 或者干脆播不动却不报错。软解多花一点 CPU，但换来的是「装上去就能放」，对以听歌为目的的
     * 应用这笔账是划算的；解码器有问题的用户再去设置里切回硬解即可，选择会记在本地。
     */
    val useSoftwareDecoder: Boolean = DEFAULT_SOFTWARE_DECODER,
    val artworkRounded: Boolean = true,
    val reduceMotion: Boolean = false,
    val lowPowerMode: Boolean = false,
    val volumeLeveling: Boolean = false,
    /** 正在解析播放地址。解析要走脚本 + 网络，UI 必须转圈而不是假装在放。 */
    val isLoading: Boolean = false,
    val standaloneActive: Boolean = false,
    val isLiveRadio: Boolean = false,
    val radioStationArt: String? = null,
    val backgroundPlayEnabled: Boolean = true,
    val isInPip: Boolean = false,
    /** 「已换源 / 已降级」提示，显示在播放页角落。 */
    val resolveHint: String? = null,
    /** 当前曲目的实际播放音质，可能低于设置里的期望值。 */
    val qualityLabel: String? = null,
    /** 实际拿到的那条流是什么容器（FLAC / MP3 / M4A…）。从地址后缀读，读不出就是 null。 */
    val qualityFormat: String? = null,
    /**
     * 这条流是谁给的：脚本名 / 内置直连 / 换源到的平台 / 本地文件。
     *
     * 一台电视上通常装了好几个音源脚本，同一首歌可能由任意一个解析成功，出了杂音、播不动
     * 时第一件事就是确认「现在用的是哪个源」。以前这条信息只在换源提示里一闪而过，
     * 现在和音质一起常驻播放页右上角。
     */
    val sourceLabel: String? = null,
) {
    /**
     * 相对缩放。播放页的历史排版全部按「1.0 = 24sp」写死，这里换算一次，
     * 省得把 NowPlayingScreen 里几十处 fontSize 都改成绝对值。
     */
    val lyricsScale: Float get() = lyricFontSp.toFloat() / DEFAULT_LYRIC_FONT_SP

    /** 行距倍数。播放页把它乘到每行的 lineHeight 上，字不会变形、行距真的变开。 */
    val lyricLineGap: Float get() = lyricLineGapPct / 100f

    /** 字间距（em）。直接交给 Text 的 letterSpacing，负数收字。 */
    val lyricTrackingEm: Float get() = lyricTracking / 100f

    /** 取某个全屏样式的排版参数；缺省时回落到该样式的默认值。 */
    fun fsPrefs(style: FsStyle): FsStylePrefs = fsStyles[style] ?: FsStylePrefs.default(style)
}

/**
 * 歌词基准字号：24sp 时 [PlayerState.lyricsScale] == 1.0，与播放页原设计一致。
 *
 * 范围 20–80sp（旧版遗留的一套数值；全屏那三套已经放开到 1–80sp）。
 * 播放页右栏与全屏歌词现在共用 [FsStylePrefs] 里的 PLAIN 那套，这个值只用于迁移旧存档。
 */
const val DEFAULT_LYRIC_FONT_SP = 24
const val LYRIC_FONT_MIN_SP = 20
const val LYRIC_FONT_MAX_SP = 80
/** 每次点 +/- 的步进。2sp 在电视上看得出变化，又不至于要按很多次。 */
const val LYRIC_FONT_STEP_SP = 2

/**
 * 行距：100% 是播放页原来的排版。
 *
 * 40% = 行贴在一起（一屏尽量多读几句），300% = 一句一屏（跟唱）。
 */
const val DEFAULT_LYRIC_GAP_PCT = 100
const val LYRIC_GAP_MIN_PCT = 40
const val LYRIC_GAP_MAX_PCT = 300
const val LYRIC_GAP_STEP_PCT = 10

/**
 * 播放页歌词字间距，单位 em×100：0 = 原设计的紧缩排版，-100 = -1.00em，100 = +1.00em。
 * 与「全屏歌词」的字间距同单位、同范围，两边读起来一致。
 */
const val DEFAULT_LYRIC_TRACKING = 0
const val LYRIC_TRACKING_MIN = -100
const val LYRIC_TRACKING_MAX = 100
const val LYRIC_TRACKING_STEP = 5

/**
 * 全屏歌词（全屏歌词 / 墨卷 / 声轨，三套共用同一组范围）的字号。
 *
 * 1–80sp 全开：1sp 是「只想看一眼还有几行」的极限小字（实际几乎看不清，但这是用户
 * 明确要的下限），80sp 是「坐在沙发上只看一句」的大字。渲染侧不再二次夹取 ——
 * 早先墨卷/声轨各自夹在 24–64，调出这个区间就看不出变化，等于白给了滑杆。
 */
const val DEFAULT_FS_FONT_SP = 40
const val FS_FONT_MIN_SP = 1
const val FS_FONT_MAX_SP = 80
const val FS_FONT_STEP_SP = 1

/**
 * 间距类参数（行距 / 竖距）的统一范围：-100% ~ 300%，100% 为设计稿原样。
 *
 * 百分比走 [fsGapScale] 换算，语意见那个函数：0% ≈ 贴住，负数 = 压到重叠。
 */
const val DEFAULT_FS_GAP_PCT = 100
const val FS_GAP_MIN_PCT = -100
const val FS_GAP_MAX_PCT = 300
const val FS_GAP_STEP_PCT = 10

/**
 * 墨卷竖距：竖排列与列之间的水平间距，100% 为原设计（1.85 倍字宽）。
 *
 * 竖排没有「行距」这个概念 —— 字是一列一列往下走的，能调的间距只有两处：
 * 列内字与字的垂直间隔（字距）与相邻两列之间的水平间距（竖距）。
 */
const val DEFAULT_FS_COL_GAP_PCT = 100
const val FS_COL_GAP_MIN_PCT = -100
const val FS_COL_GAP_MAX_PCT = 300
const val FS_COL_GAP_STEP_PCT = 10

/**
 * 字间距，单位 em×100：10 = 0.10em，0 = 完全贴紧，负数 = 收字。
 * 允许 -1.00em ~ +3.00em —— 负数那边是「字挤在一起」的排法，正数那边是散排。
 */
const val DEFAULT_FS_TRACKING = 10
const val FS_TRACKING_MIN = -100
const val FS_TRACKING_MAX = 300
const val FS_TRACKING_STEP = 5

/**
 * 把「行距 / 竖距」的百分比换成渲染用的倍数。
 *
 * 不能直接 `pct / 100` 当倍数用：-100% 会变成 -1，行高、列距都成了负数，Compose 的
 * 行高直接崩、列距则把整卷倒着排。这里改用两段折线：
 *
 *   -100% → 0.5 倍（压到设计稿一半，字会明显叠在一起）
 *      0% → 0.75 倍（基本贴住，正好等于「字高 = 行高」的观感）
 *    100% → 1.00 倍（设计稿原样）
 *    300% → 3.00 倍（一路散排）
 *
 * 在 100% 处两段相接，所以来回调不会有跳变；负区间仍保持单调 —— 越往左越紧，
 * 而不是一到 0 就卡住不动。
 */
fun fsGapScale(pct: Int): Float {
    val p = pct / 100f
    return if (p >= 1f) p else 0.5f + 0.5f * (p + 1f) / 2f
}

/**
 * 背景摆动速度：倍率，1.0 是现在这套「慢呼吸」的基准。
 *
 * 同时驱动色块漂移的周期和跟拍起伏的弹簧刚度 —— 调慢时两件事一起慢，不会出现
 * 「色块不动了、还在一抽一抽」。下限 0.1 是几乎静止，上限 2.0 是明显流动。
 */
const val DEFAULT_ORB_SPEED = 1.0f
const val ORB_SPEED_MIN = 0.1f
const val ORB_SPEED_MAX = 2.0f
const val ORB_SPEED_STEP = 0.1f

/**
 * 迷你播放条缩放：百分比。100 是设计稿尺寸（76dp 高、屏宽 94%）。
 *
 * 高度 60–140：往下能压到原来六成，往上留给 4K 大屏；宽度只允许 60–100，
 * 因为 100% 已经是「几乎顶满屏宽」，再宽就会压到内容区边缘。
 */
const val MINI_HEIGHT_MIN_PCT = 60
const val MINI_HEIGHT_MAX_PCT = 140
const val MINI_HEIGHT_STEP_PCT = 5
const val MINI_WIDTH_MIN_PCT = 60
const val MINI_WIDTH_MAX_PCT = 100
const val MINI_WIDTH_STEP_PCT = 5

/**
 * 默认解码方式：软解。理由见 [PlayerState.useSoftwareDecoder]。
 * 用户改过之后以本地记录为准，这里只是「没有记录时」的初值。
 */
const val DEFAULT_SOFTWARE_DECODER = true

private const val LYRICS_BASELINE_MS = 200L
private const val SLEEP_FADE_MS = 5_000L
private const val POLL_MS = 200L

/** 启动续播最多等音源脚本多久。脚本是异步加载的，等它比抢跑要划算得多。 */
private const val RESUME_SOURCE_WAIT_MS = 12_000L

/**
 * 从播放地址里读容器格式。
 *
 * 脚本返回的地址后缀通常就是真实容器（`.flac` / `.mp3` / `.m4a`）。有些平台的 CDN 地址
 * 不带后缀，这时返回 null —— 播放页只显示音质档位，不瞎猜一个格式出来。
 */
private fun audioFormatOf(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    val segment = path.substringAfterLast('/')
    val ext = segment.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty() || ext.length > 5) return null
    return when (ext) {
        "flac" -> "FLAC"
        "mp3" -> "MP3"
        "m4a", "mp4" -> "M4A"
        "aac" -> "AAC"
        "wav" -> "WAV"
        "ogg" -> "OGG"
        "opus" -> "OPUS"
        "ape" -> "APE"
        "dsf" -> "DSF"
        else -> null
    }
}

/**
 * 把解析来源翻成播放页要显示的那几个字。
 *
 * 不用 [ResolveOrigin.label] 原样输出：「脚本: xx」里的冒号前缀是给日志看的。
 * 电视右上角那一行要回答的是「现在到底是哪个音源在放」——
 *  - 脚本解析成功：平台 + 脚本名（「网易云 · 六音」），平台和脚本都可能被换掉，两个都得有；
 *  - 自动换源：直接写换到的平台（此时原平台的 track 已经没意义了）；
 *  - 内置直连 / 本地文件：它们本身就是音源，不需要平台前缀。
 */
private fun sourceNameOf(origin: ResolveOrigin, platform: String): String = when (origin) {
    is ResolveOrigin.Script -> if (origin.name.isBlank()) platform else "$platform · ${origin.name}"
    is ResolveOrigin.OtherSource -> origin.source.displayName
    ResolveOrigin.DirectFallback -> "内置直连"
    ResolveOrigin.LocalFile -> "本地文件"
}

/**
 * 播放核心。
 *
 * 与原 Apple Music 版的结构差异：
 *  - 没有 Widevine / CENC / 代理服务器分支；取流统一走 [PlaybackResolver]
 *    （脚本 → 换源 → 内置直连兜底），播放的是平台直链；
 *  - 队列仍是「一首一个 MediaItem + 自己轮询 STATE_ENDED」推进 —— 这条经验来自原项目：
 *    ExoPlayer 多项队列在蓝牙 MediaSession 下会重启播放；
 *  - 歌词在切歌时异步拉取，失败不影响播放。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: LxRepository,
    private val registry: TrackRegistry,
    private val resolver: PlaybackResolver,
    private val veilPrefs: VeilPreferences,
    private val lyricsOffsetPrefs: LyricsOffsetPreferences,
    private val crossfadePrefs: CrossfadePreferences,
    private val webServer: InAppWebServer,
    private val library: LibraryStore,
    private val sourceManager: com.veil.music.source.SourceManager,
    val beatAnalyzer: BeatAnalyzer,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** 手机管理页地址。全屏歌词的「上传字体 / 背景」二维码用它。 */
    fun webServerUrl(): String = webServer.serverUrl()

    /**
     * 非播放页订阅的低频状态：只忽略 [PlayerState.progressMs] / [durationMs] 这两个
     * 每 900 ms 变化的进度字段。发现/我的/搜索/设置/歌单详情原本 collect 整个 state，
     * 会被进度刷新带着整页（含列表）重组；改用这个派生流后，只有曲目、播放状态、
     * 设置项真的变化才刷新。
     *
     * 纯派生：不写回 _state、不新增线程、不加锁，播放状态机与进度轮询完全不变；
     * 播放页仍订阅 [state]，进度条与逐字歌词照旧实时刷新。
     */
    val stableState: StateFlow<PlayerState> = _state
        .distinctUntilChanged { old, new ->
            old.copy(progressMs = 0L, durationMs = 0L) ==
                new.copy(progressMs = 0L, durationMs = 0L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _state.value)

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    var nowPlayingVisible: Boolean = false

    private val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
    private val statePrefs = context.getSharedPreferences("player_state", Context.MODE_PRIVATE)

    private val gain = GainProcessor()
    private var exo: ExoPlayer? = null
    val player: ExoPlayer get() = ensurePlayer()

    private var pollJob: Job? = null
    private var playJob: Job? = null
    private var lyricsJob: Job? = null
    private var resumeJob: Job? = null
    /** 冷启动恢复只做一次（Activity 重建会重复调用 [restoreState]）。 */
    private var restoredOnce = false
    private val lyricsCache = ConcurrentHashMap<String, List<LyricLine>>()
    private var lastEndedSongId: String? = null
    private var retriedSongId: String? = null
    /** 重建播放器后要回到的位置（-1 = 不需要）。 */
    private var pendingSeekMs: Long = -1L
    /** 重建播放器前后如果本来是暂停的，加载完保持暂停。 */
    private var pendingPause: Boolean = false

    init {
        GainProcessor.enabled = prefs.getBoolean("volume_leveling", false)
        GainProcessor.logger = { tag, msg -> webServer.addLog(tag, msg) }
        _state.update {
            it.copy(
                lyricsOffsetMs = lyricsOffsetPrefs.getOffset(),
                lyricFontSp = readLyricFontSp(),
                lyricLineGapPct = readLyricLineGapPct(),
                lyricTracking = readLyricTracking(),
                fsStyles = readFsStyles(),
                useSoftwareDecoder = prefs.getBoolean("software_decoder", DEFAULT_SOFTWARE_DECODER),
                orbSpeed = prefs.getFloat("orb_speed", DEFAULT_ORB_SPEED)
                    .coerceIn(ORB_SPEED_MIN, ORB_SPEED_MAX),
                artworkRounded = prefs.getBoolean("artwork_rounded", true),
                reduceMotion = prefs.getBoolean("reduce_motion", false),
                lowPowerMode = prefs.getBoolean("low_power", false),
                volumeLeveling = GainProcessor.enabled,
                motionArtworkEnabled = prefs.getBoolean("motion_artwork", false),
                backgroundPlayEnabled = prefs.getBoolean("background_play", true),
                miniCapsuleHeightPct = prefs.getInt("mini_height_pct", 100)
                    .coerceIn(MINI_HEIGHT_MIN_PCT, MINI_HEIGHT_MAX_PCT),
                miniCapsuleWidthPct = prefs.getInt("mini_width_pct", 100)
                    .coerceIn(MINI_WIDTH_MIN_PCT, MINI_WIDTH_MAX_PCT),
                showNowPlayingInfo = prefs.getBoolean("show_np_info", true),
                nowPlayingBackground = NowPlayingBackground.fromName(prefs.getString("np_background", null)),
                beatIntensity = prefs.getFloat("beat_intensity", 1.0f),
            )
        }
        viewModelScope.launch {
            lyricsOffsetPrefs.offsetMs.collect { ms -> _state.update { it.copy(lyricsOffsetMs = ms) } }
        }
        startPolling()
    }

    // ── ExoPlayer ──────────────────────────────────────────────────────────

    private fun ensurePlayer(): ExoPlayer {
        exo?.let { return it }
        val beatProcessor = beatAnalyzer.newProcessor()
        beatAnalyzer.activate(beatProcessor)
        val selector = if (_state.value.useSoftwareDecoder) {
            DecoderSelectors.softwareFirst
        } else {
            DecoderSelectors.hardwareFirst
        }
        val renderers = BeatAwareRenderersFactory(context, beatProcessor, gain)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            // 硬解 / 软解：同一个候选集合，只改排序（见 DecoderSelectors）。
            .setMediaCodecSelector(selector)
        val built = ExoPlayer.Builder(context, renderers)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 60_000, 1_000, 2_000)
                    .build()
            )
            .build()
        built.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _state.update {
                        it.copy(isLoading = false, durationMs = built.duration.coerceAtLeast(0L))
                    }
                    // 换解码器会重建播放器：把重建前的位置接上，暂停状态也还原。
                    if (pendingSeekMs >= 0L) {
                        val ms = pendingSeekMs
                        pendingSeekMs = -1L
                        runCatching { built.seekTo(ms) }
                    }
                    if (pendingPause) {
                        pendingPause = false
                        runCatching { built.pause() }
                    }
                    prefetchNext()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }
        })
        exo = built
        return built
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val song = _state.value.currentSong ?: return
        registry.get(song.id)?.let { resolver.invalidate(it) }
        // 直链带时效，过期会 403；重解析一次，仍失败就跳下一首。
        if (retriedSongId != song.id) {
            retriedSongId = song.id
            _state.update { it.copy(isLoading = true) }
            playQueueItem(_state.value.queueIndex, forceResolve = true)
            return
        }
        retriedSongId = null
        _toasts.tryEmit("播放失败（${error.errorCodeName}），已跳过")
        next()
    }

    // ── 队列 ───────────────────────────────────────────────────────────────

    fun playAlbum(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val ordered = if (shuffle) songs.shuffled() else songs
        val index = if (shuffle) 0 else startIndex.coerceIn(0, ordered.lastIndex)
        _state.update {
            it.copy(
                queue = ordered,
                originalQueue = songs,
                queueIndex = index,
                isShuffled = shuffle,
                userQueue = emptyList(),
            )
        }
        playQueueItem(index)
    }

    fun shufflePlayPlaylist(songs: List<Song>) = playAlbum(songs, 0, shuffle = true)

    fun playSong(song: Song, useFullStream: Boolean = true) = playAlbum(listOf(song))

    fun playFromQueue(index: Int) = playQueueItem(index)

    fun playFromUserQueue(index: Int) {
        val s = _state.value
        val song = s.userQueue.getOrNull(index) ?: return
        val merged = s.queue.toMutableList().apply { add(s.queueIndex + 1, song) }
        val userQueue = s.userQueue.toMutableList().apply { removeAt(index) }
        _state.update { it.copy(queue = merged, userQueue = userQueue) }
        playQueueItem(s.queueIndex + 1)
    }

    fun playQueueItem(index: Int, forceResolve: Boolean = false) {
        val s = _state.value
        val song = s.queue.getOrNull(index) ?: return
        _state.update {
            it.copy(
                currentSong = song,
                song = song,
                queueIndex = index,
                progressMs = 0L,
                durationMs = 0L,
                isLoading = true,
                lyrics = lyricsCache[song.id].orEmpty(),
                lyricsSource = null,
                resolveHint = null,
                motionUrl = null,
                qualityLabel = null,
                qualityFormat = null,
                sourceLabel = null,
            )
        }
        retriedSongId = null
        viewModelScope.launch { library.addHistory(song) }
        playJob?.cancel()
        playJob = viewModelScope.launch {
            val track = resolveTrack(song)
            if (track == null) {
                _toasts.tryEmit("无法识别该曲目的来源")
                next()
                return@launch
            }
            loadLyricsAsync(track, song.id)
            val resolved = runCatching { resolver.resolve(track, refresh = forceResolve) }.getOrElse {
                _state.update { st -> st.copy(isLoading = false) }
                _toasts.tryEmit(it.message ?: "无法获取播放地址")
                next()
                return@launch
            }
            _state.update {
                it.copy(
                    isLoading = true,
                    resolveHint = resolved.warning,
                    qualityLabel = resolved.quality.displayName,
                    qualityFormat = audioFormatOf(resolved.url),
                    sourceLabel = sourceNameOf(resolved.origin, track.source.displayName),
                )
            }
            resolved.warning?.let { _toasts.tryEmit(it) }
            val p = ensurePlayer()
            p.setMediaItem(MediaItem.fromUri(resolved.url))
            p.prepare()
            p.playWhenReady = true
            saveState()
        }
    }

    private fun resolveTrack(song: Song): Track? {
        registry.get(song.id)?.let { return it }
        val rebuilt = LxMapper.rebuildTrack(song) ?: return null
        registry.put(rebuilt)
        return rebuilt
    }

    fun next() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (s.repeatMode == RepeatMode.Off && s.queueIndex == s.queue.lastIndex && s.userQueue.isEmpty()) {
            exo?.pause()
            exo?.seekTo(0)
            // 进度归零并同步到 state，否则之后按「上一首」会用残留的进度误判。
            _state.update { it.copy(isPlaying = false, progressMs = 0L) }
            return
        }
        val nextIndex = if (s.queueIndex < s.queue.lastIndex) s.queueIndex + 1 else 0
        playQueueItem(nextIndex)
    }

    fun prev() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        // 用 state 里的 progressMs 判断，而不是 exo.currentPosition：
        // 切歌后 ExoPlayer 还没 READY 时 currentPosition 仍残留上一首的进度值，
        // 会把「回到上一首」误判成「重播当前曲」。progressMs 在 playQueueItem 里
        // 会立刻归零，才是「当前这一首」的真实进度。
        if (s.progressMs > 3_000 && s.repeatMode != RepeatMode.One) {
            exo?.seekTo(0)
            _state.update { it.copy(progressMs = 0L) }
            return
        }
        val target = if (s.queueIndex > 0) s.queueIndex - 1 else s.queue.lastIndex
        playQueueItem(target)
    }

    fun togglePlayPause() {
        val p = ensurePlayer()
        if (p.isPlaying) p.pause() else p.play()
    }

    fun pause() {
        exo?.pause()
    }

    fun stopPlayback() {
        exo?.stop()
        _state.update { it.copy(isPlaying = false, progressMs = 0L) }
    }

    fun addToQueue(song: Song) {
        _state.update { it.copy(userQueue = it.userQueue + song) }
        _toasts.tryEmit("已加入播放队列")
    }

    fun playNext(song: Song) {
        val s = _state.value
        val merged = s.queue.toMutableList().apply { add(s.queueIndex + 1, song) }
        _state.update { it.copy(queue = merged) }
        _toasts.tryEmit("下一首播放：${song.title}")
    }

    fun moveQueueItem(from: Int, to: Int) {
        val s = _state.value
        if (from !in s.userQueue.indices || to !in s.userQueue.indices) return
        val list = s.userQueue.toMutableList()
        list.add(to, list.removeAt(from))
        _state.update { it.copy(userQueue = list) }
    }

    fun toggleShuffle() {
        val s = _state.value
        val shuffled = !s.isShuffled
        val currentId = s.currentSong?.id
        val queue = if (shuffled) s.originalQueue.shuffled() else s.originalQueue
        val index = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        _state.update { it.copy(isShuffled = shuffled, queue = queue, queueIndex = index) }
    }

    fun toggleRepeat() {
        val next = when (_state.value.repeatMode) {
            RepeatMode.Off -> RepeatMode.One
            RepeatMode.One -> RepeatMode.All
            RepeatMode.All -> RepeatMode.Off
        }
        _state.update { it.copy(repeatMode = next) }
        _toasts.tryEmit("循环：${next.name}")
    }

    // ── 轮询 ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var lastPush = 0L
            while (isActive) {
                delay(POLL_MS)
                val p = exo ?: continue
                val s = _state.value
                val song = s.currentSong ?: continue

                val pos = p.currentPosition.coerceAtLeast(0)
                val now = System.currentTimeMillis()
                if (now - lastPush > 900) {
                    lastPush = now
                    _state.update { it.copy(progressMs = pos, durationMs = p.duration.coerceAtLeast(0L)) }
                }

                if (p.playbackState == Player.STATE_ENDED && lastEndedSongId != song.id) {
                    lastEndedSongId = song.id
                    if (s.sleepAfterSong) {
                        cancelSleepTimer()
                        p.pause()
                        _toasts.tryEmit("已播完当前歌曲，停止播放")
                    } else {
                        next()
                    }
                }

                val timerEnd = s.sleepTimerEndsAt
                if (timerEnd != null) {
                    val remain = timerEnd - now
                    if (remain in 1 until SLEEP_FADE_MS) {
                        p.volume = (remain.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
                    } else if (remain <= 0) {
                        p.pause()
                        p.volume = 1f
                        _state.update { it.copy(sleepTimerEndsAt = null) }
                        _toasts.tryEmit("睡眠定时已结束")
                    }
                }
            }
        }
    }

    // ── 歌词 ───────────────────────────────────────────────────────────────

    private fun loadLyricsAsync(track: Track, songId: String) {
        val cached = lyricsCache[songId]
        if (cached != null) {
            _state.update { it.copy(lyrics = cached) }
            return
        }
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val payload = runCatching { repo.lyrics(track) }.getOrNull() ?: return@launch
            if (payload.lines.isEmpty()) return@launch
            lyricsCache[songId] = payload.lines
            if (_state.value.currentSong?.id == songId) {
                _state.update { it.copy(lyrics = payload.lines, lyricsSource = payload.source) }
            }
        }
    }

    // ── 预取 ───────────────────────────────────────────────────────────────

    /** 提前解析下一首的地址（进 [PlaybackResolver] 的内存缓存），切歌时不用等。 */
    private fun prefetchNext() {
        val s = _state.value
        val nextIndex = if (s.queueIndex < s.queue.lastIndex) s.queueIndex + 1 else 0
        val nextSong = s.queue.getOrNull(nextIndex) ?: return
        if (nextSong.id == s.currentSong?.id) return
        viewModelScope.launch {
            registry.get(nextSong.id)?.let { runCatching { resolver.resolve(it) } }
        }
    }

    fun prefetchAudio(song: Song) {
        viewModelScope.launch {
            registry.get(song.id)?.let { runCatching { resolver.resolve(it) } }
        }
    }

    // ── 状态持久化 ─────────────────────────────────────────────────────────

    fun saveState() {
        val s = _state.value
        val song = s.currentSong ?: return
        runCatching {
            val arr = JSONArray()
            s.queue.forEach { arr.put(songToJson(it)) }
            val obj = JSONObject()
                .put("queue", arr)
                .put("index", s.queueIndex)
                .put("position", exo?.currentPosition ?: 0L)
            statePrefs.edit().putString("snapshot", obj.toString()).apply()
        }
        // 队列里所有歌曲的 Track 一起落盘，重启后还能取流
        registry.persistNow()
    }

    private fun songToJson(song: Song): JSONObject = JSONObject()
        .put("id", song.id)
        .put("title", song.title)
        .put("artistName", song.artistName)
        .put("albumName", song.albumName)
        .put("durationMs", song.durationMs)
        .put("artworkUrl", song.artworkUrl ?: "")

    private fun songFromJson(o: JSONObject): Song = Song(
        id = o.optString("id"),
        title = o.optString("title"),
        artistName = o.optString("artistName"),
        albumName = o.optString("albumName"),
        durationMs = o.optLong("durationMs"),
        artworkUrl = o.optString("artworkUrl").ifBlank { null },
    )

    /**
     * 冷启动：恢复上次的队列，并**自动续播**上次那一首。
     *
     * 以前只恢复队列不出声，要用户自己按播放。电视上退出应用基本等于待机，回来接着听是常态，
     * 每次都得摸遥控器点一下很别扭，所以改成默认续播，并且接着上次的进度走。
     *
     * 两个必须注意的点：
     * 1. 必须等音源脚本就绪。脚本是应用启动时异步加载的（QuickJS 初始化 + 各源握手），
     *    抢在前面解析拿不到地址，会被当成「这首播不了」直接跳下一首 —— 一开电视就听到
     *    列表在乱跳。这里等 [SourceManager.loaded] 有内容再解析，最多等 [RESUME_SOURCE_WAIT_MS]；
     *    一个源都没起来就干脆不续播，避免连续报错。
     * 2. 用户抢先操作就让路：等待期间用户自己点了歌或按了播放，这里就不再插手。
     */
    fun restoreState() {
        // Activity 重建（改分辨率、切 HDR）会再调一次，恢复只做第一遍。
        if (restoredOnce) return
        restoredOnce = true
        val raw = statePrefs.getString("snapshot", null) ?: return
        val restored = runCatching {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("queue") ?: return@runCatching null
            val queue = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { songFromJson(it) } }
            if (queue.isEmpty()) return@runCatching null
            val index = obj.optInt("index", 0).coerceIn(0, queue.lastIndex)
            val position = obj.optLong("position", 0L).coerceAtLeast(0L)
            _state.update {
                it.copy(
                    queue = queue,
                    originalQueue = queue,
                    queueIndex = index,
                    currentSong = queue[index],
                    song = queue[index],
                    progressMs = position,
                )
            }
            index to position
        }.getOrNull() ?: return

        val (index, position) = restored
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            withTimeoutOrNull(RESUME_SOURCE_WAIT_MS) {
                sourceManager.loaded.first { it.isNotEmpty() }
            }
            if (sourceManager.loaded.value.isEmpty()) return@launch
            if (_state.value.isPlaying || _state.value.isLoading) return@launch
            // 重建/首播都会在 STATE_READY 时消费这个值，把进度接上。
            pendingSeekMs = position
            playQueueItem(index)
        }
    }

    override fun onCleared() {
        saveState()
        pollJob?.cancel()
        playJob?.cancel()
        lyricsJob?.cancel()
        resumeJob?.cancel()
        exo?.release()
        exo = null
        super.onCleared()
    }

    // ── 设置（落到 SharedPreferences，UI 立即生效）────────────────────────

    private fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    fun stepBeatIntensity() {
        val next = when {
            _state.value.beatIntensity < 1.5f -> 2.0f
            _state.value.beatIntensity < 2.5f -> 3.5f
            else -> 1.0f
        }
        _state.update { it.copy(beatIntensity = next) }
        prefs.edit().putFloat("beat_intensity", next).apply()
    }

    /**
     * 播放背景：直接指定，不再「点一次换个档」。
     *
     * 循环切换的问题是用户看不到总共有几个选项、也退不回去；设置页现在把
     * [NowPlayingBackground] 全部平铺成单选，这里只负责落一个确定值。
     */
    fun setNowPlayingBackground(mode: NowPlayingBackground) {
        _state.update { it.copy(nowPlayingBackground = mode) }
        prefs.edit().putString("np_background", mode.name).apply()
    }

    /**
     * 背景摆动速度（倍率）。同时作用于两件事：色块漂移的周期，和跟拍起伏的弹簧刚度。
     *
     * 0.1 倍 = 几乎静止的呼吸，2 倍 = 明显在流动。设置页一格 0.1，直接给数值，
     * 不再用「慢/中/快」这种看不出差别的档位。
     */
    fun setOrbSpeed(speed: Float) {
        val clamped = (speed.coerceIn(ORB_SPEED_MIN, ORB_SPEED_MAX) * 10f)
            .roundToInt() / 10f
        _state.update { it.copy(orbSpeed = clamped) }
        prefs.edit().putFloat("orb_speed", clamped).apply()
    }

    /** 步进 +/-，供设置页的减号/加号用。 */
    fun nudgeOrbSpeed(delta: Float) = setOrbSpeed(_state.value.orbSpeed + delta)

    /**
     * 歌词字号：直接设数值，范围 [LYRIC_FONT_MIN_SP]..[LYRIC_FONT_MAX_SP]。
     *
     * 以前是「小/标准/大」三档循环，用户看不出自己到底调了多少，也没法微调；
     * 现在设置页显示真实 sp 值，这里是它的唯一写入口。
     */
    fun setLyricFontSp(sp: Int) {
        val clamped = sp.coerceIn(LYRIC_FONT_MIN_SP, LYRIC_FONT_MAX_SP)
        _state.update { it.copy(lyricFontSp = clamped) }
        prefs.edit().putInt("lyric_font_sp", clamped).apply()
    }

    /** 步进 +/-，供设置页的减号/加号用。 */
    fun nudgeLyricFontSp(delta: Int) = setLyricFontSp(_state.value.lyricFontSp + delta)

    /**
     * 歌词行距：百分比，[LYRIC_GAP_MIN_PCT]..[LYRIC_GAP_MAX_PCT]。
     *
     * 行距不是字号的一部分：放大字号会把字撑大，但行与行还是挤在一起；
     * 这里乘到每行的 lineHeight 上，字本身的字号不变。
     */
    fun setLyricLineGapPct(pct: Int) {
        val clamped = pct.coerceIn(LYRIC_GAP_MIN_PCT, LYRIC_GAP_MAX_PCT)
        _state.update { it.copy(lyricLineGapPct = clamped) }
        prefs.edit().putInt("lyric_line_gap_pct", clamped).apply()
    }

    fun nudgeLyricLineGapPct(delta: Int) = setLyricLineGapPct(_state.value.lyricLineGapPct + delta)

    /**
     * 播放页歌词字间距（em×100），范围 [LYRIC_TRACKING_MIN]..[LYRIC_TRACKING_MAX]。
     *
     * 和全屏歌词那套一样允许负数：负值收字，长句能在有限宽度里多放几个字。
     */
    fun setLyricTracking(v: Int) {
        val clamped = v.coerceIn(LYRIC_TRACKING_MIN, LYRIC_TRACKING_MAX)
        _state.update { it.copy(lyricTracking = clamped) }
        prefs.edit().putInt("lyric_tracking", clamped).apply()
    }

    fun nudgeLyricTracking(delta: Int) = setLyricTracking(_state.value.lyricTracking + delta)

    // ── 全屏歌词（墨卷 / 声轨）的独立排版设置 ───────────────────────────────────
    // 与上面的普通歌词字号 / 行距分开存：全屏是「一张画面」，字号动辄 40sp，
    // 和播放页右栏那套 24sp 的排版共用一个值只会两边都不合适。

    /** 全屏样式全部走这一个入口：夹取范围 + 写盘 + 只换那一套，不会互相串味。 */
    fun updateFsStyle(style: FsStyle, block: (FsStylePrefs) -> FsStylePrefs) {
        val next = block(_state.value.fsPrefs(style)).let {
            it.copy(
                fontSp = it.fontSp.coerceIn(FS_FONT_MIN_SP, FS_FONT_MAX_SP),
                lineGapPct = it.lineGapPct.coerceIn(FS_GAP_MIN_PCT, FS_GAP_MAX_PCT),
                colGapPct = it.colGapPct.coerceIn(FS_COL_GAP_MIN_PCT, FS_COL_GAP_MAX_PCT),
                letterSpacing = it.letterSpacing.coerceIn(FS_TRACKING_MIN, FS_TRACKING_MAX),
            )
        }
        _state.update { it.copy(fsStyles = it.fsStyles + (style to next)) }
        prefs.edit()
            .putString(fsKey(style, "font"), next.font.name)
            .putInt(fsKey(style, "font_sp"), next.fontSp)
            .putInt(fsKey(style, "gap_pct"), next.lineGapPct)
            .putInt(fsKey(style, "col_gap_pct"), next.colGapPct)
            .putInt(fsKey(style, "tracking"), next.letterSpacing)
            .putBoolean(fsKey(style, "light"), next.light)
            .putString(fsKey(style, "bg"), next.bg.name)
            .apply()
    }

    fun setFsFont(style: FsStyle, choice: LyricFontChoice) =
        updateFsStyle(style) { it.copy(font = choice) }

    fun nudgeFsFontSp(style: FsStyle, delta: Int) =
        updateFsStyle(style) { it.copy(fontSp = it.fontSp + delta) }

    fun nudgeFsLineGapPct(style: FsStyle, delta: Int) =
        updateFsStyle(style) { it.copy(lineGapPct = it.lineGapPct + delta) }

    /** 墨卷专用：两竖列之间的水平间距。 */
    fun nudgeFsColGapPct(style: FsStyle, delta: Int) =
        updateFsStyle(style) { it.copy(colGapPct = it.colGapPct + delta) }

    fun nudgeFsLetterSpacing(style: FsStyle, delta: Int) =
        updateFsStyle(style) { it.copy(letterSpacing = it.letterSpacing + delta) }

    fun setFsBg(style: FsStyle, mode: LyricBgMode) =
        updateFsStyle(style) { it.copy(bg = mode) }

    fun setFsLight(style: FsStyle, enabled: Boolean) =
        updateFsStyle(style) { it.copy(light = enabled) }

    private fun fsKey(style: FsStyle, field: String) =
        "fs_${style.name.lowercase()}_$field"

    /**
     * 读三套参数。旧版只有一套（fs_lyric_*），迁移时当成墨卷，声轨也继承一份 ——
     * 升级后进「墨卷 / 声轨」看到的还是自己调过的样子，不会突然回到出厂值。
     */
    private fun readFsStyles(): Map<FsStyle, FsStylePrefs> {
        val legacy = if (prefs.contains("fs_lyric_font") || prefs.contains("fs_lyric_font_sp")) {
            FsStylePrefs(
                font = LyricFontChoice.fromName(prefs.getString("fs_lyric_font", null)),
                fontSp = prefs.getInt("fs_lyric_font_sp", DEFAULT_FS_FONT_SP),
                lineGapPct = prefs.getInt("fs_lyric_gap_pct", DEFAULT_FS_GAP_PCT),
                colGapPct = prefs.getInt("fs_lyric_col_gap_pct", DEFAULT_FS_COL_GAP_PCT),
                letterSpacing = prefs.getInt("fs_lyric_tracking", DEFAULT_FS_TRACKING),
                light = prefs.getBoolean("fs_lyric_light", false),
            )
        } else null
        return FsStyle.entries.associateWith { style ->
            val def = FsStylePrefs.default(style)
            val base = if (style == FsStyle.PLAIN) def else (legacy ?: def)
            FsStylePrefs(
                font = LyricFontChoice.fromName(prefs.getString(fsKey(style, "font"), base.font.name)),
                fontSp = prefs.getInt(fsKey(style, "font_sp"), base.fontSp)
                    .coerceIn(FS_FONT_MIN_SP, FS_FONT_MAX_SP),
                lineGapPct = prefs.getInt(fsKey(style, "gap_pct"), base.lineGapPct)
                    .coerceIn(FS_GAP_MIN_PCT, FS_GAP_MAX_PCT),
                colGapPct = prefs.getInt(fsKey(style, "col_gap_pct"), base.colGapPct)
                    .coerceIn(FS_COL_GAP_MIN_PCT, FS_COL_GAP_MAX_PCT),
                letterSpacing = prefs.getInt(fsKey(style, "tracking"), base.letterSpacing)
                    .coerceIn(FS_TRACKING_MIN, FS_TRACKING_MAX),
                light = prefs.getBoolean(fsKey(style, "light"), base.light),
                bg = LyricBgMode.fromName(prefs.getString(fsKey(style, "bg"), base.bg.name)),
            )
        }
    }

    private fun readLyricLineGapPct(): Int =
        prefs.getInt("lyric_line_gap_pct", DEFAULT_LYRIC_GAP_PCT)
            .coerceIn(LYRIC_GAP_MIN_PCT, LYRIC_GAP_MAX_PCT)

    private fun readLyricTracking(): Int =
        prefs.getInt("lyric_tracking", DEFAULT_LYRIC_TRACKING)
            .coerceIn(LYRIC_TRACKING_MIN, LYRIC_TRACKING_MAX)

    /**
     * 硬解 / 软解。
     *
     * 解码器是 ExoPlayer 构造期定死的，所以这里必须重建播放器 —— 重建时记下位置与播放状态，
     * 新实例 READY 之后接回去，听感上只是卡一下（和换源差不多）。播放地址走
     * [PlaybackResolver] 的 10 分钟缓存，重建不会重新联网解析。
     */
    fun setUseSoftwareDecoder(enabled: Boolean) {
        if (_state.value.useSoftwareDecoder == enabled) return
        prefs.edit().putBoolean("software_decoder", enabled).apply()
        _state.update { it.copy(useSoftwareDecoder = enabled) }
        rebuildPlayer()
    }

    private fun rebuildPlayer() {
        val old = exo
        pendingSeekMs = old?.currentPosition?.coerceAtLeast(0L) ?: -1L
        pendingPause = !_state.value.isPlaying
        runCatching { old?.release() }
        exo = null
        val song = _state.value.currentSong
        if (song == null) {
            pendingSeekMs = -1L
            pendingPause = false
            return
        }
        playQueueItem(_state.value.queueIndex)
    }

    /**
     * 旧版本用 lyrics_scale（0.85 / 1.0 / 1.2）三档缩放，迁移到绝对字号，
     * 保证老用户升级后视觉上不会突变。
     */
    private fun readLyricFontSp(): Int {
        if (!prefs.contains("lyric_font_sp") && prefs.contains("lyrics_scale")) {
            val legacy = prefs.getFloat("lyrics_scale", 1.0f)
            return (DEFAULT_LYRIC_FONT_SP * legacy).toInt().coerceIn(LYRIC_FONT_MIN_SP, LYRIC_FONT_MAX_SP)
        }
        return prefs.getInt("lyric_font_sp", DEFAULT_LYRIC_FONT_SP)
            .coerceIn(LYRIC_FONT_MIN_SP, LYRIC_FONT_MAX_SP)
    }

    fun toggleArtworkRounded() {
        val next = !_state.value.artworkRounded
        _state.update { it.copy(artworkRounded = next) }
        putBool("artwork_rounded", next)
    }

    fun toggleMotionArtwork() {
        val next = !_state.value.motionArtworkEnabled
        _state.update { it.copy(motionArtworkEnabled = next) }
        putBool("motion_artwork", next)
    }

    fun toggleReduceMotion() {
        val next = !_state.value.reduceMotion
        _state.update { it.copy(reduceMotion = next) }
        putBool("reduce_motion", next)
    }

    fun toggleLowPowerMode() {
        val next = !_state.value.lowPowerMode
        _state.update { it.copy(lowPowerMode = next) }
        putBool("low_power", next)
    }

    fun toggleBackgroundPlay() {
        val next = !_state.value.backgroundPlayEnabled
        _state.update { it.copy(backgroundPlayEnabled = next) }
        putBool("background_play", next)
    }

    /**
     * 迷你播放条高度（百分比）。整条胶囊连同图标、封面、文字一起等比缩放 ——
     * 只把外框改高不改内容，60% 时会露出一圈空白边。
     */
    fun setMiniCapsuleHeightPct(pct: Int) {
        val clamped = pct.coerceIn(MINI_HEIGHT_MIN_PCT, MINI_HEIGHT_MAX_PCT)
        _state.update { it.copy(miniCapsuleHeightPct = clamped) }
        prefs.edit().putInt("mini_height_pct", clamped).apply()
    }

    fun nudgeMiniCapsuleHeightPct(delta: Int) =
        setMiniCapsuleHeightPct(_state.value.miniCapsuleHeightPct + delta)

    /** 迷你播放条宽度（百分比，相对屏宽的 94% 基准）。只改长度，不改内容大小。 */
    fun setMiniCapsuleWidthPct(pct: Int) {
        val clamped = pct.coerceIn(MINI_WIDTH_MIN_PCT, MINI_WIDTH_MAX_PCT)
        _state.update { it.copy(miniCapsuleWidthPct = clamped) }
        prefs.edit().putInt("mini_width_pct", clamped).apply()
    }

    fun nudgeMiniCapsuleWidthPct(delta: Int) =
        setMiniCapsuleWidthPct(_state.value.miniCapsuleWidthPct + delta)

    fun toggleNowPlayingInfo() {
        val next = !_state.value.showNowPlayingInfo
        _state.update { it.copy(showNowPlayingInfo = next) }
        putBool("show_np_info", next)
    }

    fun toggleVolumeLeveling() {
        val next = !_state.value.volumeLeveling
        GainProcessor.enabled = next
        _state.update { it.copy(volumeLeveling = next) }
        putBool("volume_leveling", next)
    }

    fun setSleepTimer(minutes: Int) {
        _state.update {
            it.copy(sleepTimerEndsAt = System.currentTimeMillis() + minutes * 60_000L, sleepAfterSong = false)
        }
        _toasts.tryEmit("${minutes} 分钟后停止播放")
    }

    fun setSleepAfterSong() {
        _state.update { it.copy(sleepAfterSong = true, sleepTimerEndsAt = null) }
        _toasts.tryEmit("播完当前歌曲后停止")
    }

    fun cancelSleepTimer() {
        _state.update { it.copy(sleepTimerEndsAt = null, sleepAfterSong = false) }
        exo?.volume = 1f
    }

    fun setLyricsOffset(ms: Long) {
        lyricsOffsetPrefs.setOffset(ms)
        _state.update { it.copy(lyricsOffsetMs = ms) }
    }

    fun setAvSyncAuto(auto: Boolean) {
        _state.update { it.copy(avSyncAuto = auto) }
        putBool("av_sync_auto", auto)
    }

    fun remoteOverride(): String = prefs.getString("remote_override", "auto") ?: "auto"

    fun setRemoteOverride(choice: String) {
        prefs.edit().putString("remote_override", choice).apply()
    }

    fun setPipMode(inPip: Boolean) = _state.update { it.copy(isInPip = inPip) }

    fun webServerEnabled(): Boolean = webServer.isEnabled

    fun setWebServerEnabled(enabled: Boolean) {
        webServer.applyEnabled(enabled)
        if (enabled) webServer.boot(viewModelScope)
    }

    /** 换源后丢掉当前曲目的 URL 缓存。 */
    fun invalidateCurrentUrl() {
        _state.value.currentSong?.let { song -> registry.get(song.id)?.let { resolver.invalidate(it) } }
    }

    // ---- 「添加到歌单」-------------------------------------------------------------------------
    // 播放页的 AddToDialog 直接读这份 state 渲染歌单列表，不再单独开一个 ViewModel。

    val libraryState: StateFlow<LibraryState> get() = library.state

    fun addSongToList(listId: String, song: Song) {
        viewModelScope.launch {
            library.addTo(listId, listOf(song))
            _toasts.tryEmit("已添加到歌单")
        }
    }

    fun createListWithSong(song: Song, name: String) {
        viewModelScope.launch {
            val id = library.createList(name)
            library.addTo(id, listOf(song))
            _toasts.tryEmit("已新建「$name」并加入")
        }
    }
}
