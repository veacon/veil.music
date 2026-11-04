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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

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

/** 全屏歌词的两种沉浸样式（上游项目移植）。 */
enum class FullscreenLyricMode(val label: String) {
    INK_SCROLL("墨卷"),
    TRACK_BED("声轨"),
}

/** 全屏歌词可选字体。[label] 直接显示在设置页。 */
enum class LyricFontChoice(val label: String) {
    SYSTEM("系统字体"),
    PINGFANG("苹方"),
    MINGMEI("Aa祝你我明媚向春天"),
    ;

    companion object {
        fun fromName(name: String?): LyricFontChoice =
            entries.firstOrNull { it.name == name } ?: SYSTEM
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

/** 一套全屏歌词排版参数。字号/行距/字间距都是数值，不做「紧凑/标准/宽松」三档。 */
data class FsStylePrefs(
    val font: LyricFontChoice = LyricFontChoice.SYSTEM,
    val fontSp: Int = DEFAULT_FS_FONT_SP,
    val lineGapPct: Int = DEFAULT_FS_GAP_PCT,
    val letterSpacing: Int = DEFAULT_FS_TRACKING,
    val light: Boolean = false,
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
    val screensaverTimeoutMin: Int = 10,
    val nowPlayingBackground: NowPlayingBackground = NowPlayingBackground.DYNAMIC,
    val screensaverKeepBackground: Boolean = false,
    val showNowPlayingInfo: Boolean = true,
    val motionArtworkEnabled: Boolean = false,
    val orbSpeed: Float = 1.0f,
    /** 歌词基准字号（sp）。设置页直接暴露这个数字，不再用小/中/大这种模糊档位。 */
    val lyricFontSp: Int = DEFAULT_LYRIC_FONT_SP,
    /** 歌词行距，百分比（100 = 原设计）。同样是数值，不做「紧凑/标准/宽松」三档。 */
    val lyricLineGapPct: Int = DEFAULT_LYRIC_GAP_PCT,
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
) {
    /**
     * 相对缩放。播放页的历史排版全部按「1.0 = 24sp」写死，这里换算一次，
     * 省得把 NowPlayingScreen 里几十处 fontSize 都改成绝对值。
     */
    val lyricsScale: Float get() = lyricFontSp.toFloat() / DEFAULT_LYRIC_FONT_SP

    /** 行距倍数。播放页把它乘到每行的 lineHeight 上，字不会变形、行距真的变开。 */
    val lyricLineGap: Float get() = lyricLineGapPct / 100f

    /** 取某个全屏样式的排版参数；缺省时回落到该样式的默认值。 */
    fun fsPrefs(style: FsStyle): FsStylePrefs = fsStyles[style] ?: FsStylePrefs.default(style)
}

/** 歌词基准字号：24sp 时 [PlayerState.lyricsScale] == 1.0，与播放页原设计一致。 */
const val DEFAULT_LYRIC_FONT_SP = 24
const val LYRIC_FONT_MIN_SP = 16
const val LYRIC_FONT_MAX_SP = 40
/** 每次点 +/- 的步进。2sp 在电视上看得出变化，又不至于要按很多次。 */
const val LYRIC_FONT_STEP_SP = 2

/** 行距：100% 是播放页原来的排版，上下各放 40 个百分点够覆盖「贴着」到「很松」。 */
const val DEFAULT_LYRIC_GAP_PCT = 100
const val LYRIC_GAP_MIN_PCT = 80
const val LYRIC_GAP_MAX_PCT = 180
const val LYRIC_GAP_STEP_PCT = 10

/** 全屏歌词（墨卷 / 声轨）：默认 40sp —— 电视 3 米视距下正文字号。 */
const val DEFAULT_FS_FONT_SP = 40
const val FS_FONT_MIN_SP = 24
const val FS_FONT_MAX_SP = 64
const val FS_FONT_STEP_SP = 2

/** 全屏歌词行距：100% 为默认排版。 */
const val DEFAULT_FS_GAP_PCT = 100
const val FS_GAP_MIN_PCT = 70
const val FS_GAP_MAX_PCT = 180
const val FS_GAP_STEP_PCT = 5

/** 全屏歌词字间距：10 = 0.10em，0 = 完全贴紧。 */
const val DEFAULT_FS_TRACKING = 10
const val FS_TRACKING_MIN = 0
const val FS_TRACKING_MAX = 30
const val FS_TRACKING_STEP = 2

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
                fsStyles = readFsStyles(),
                useSoftwareDecoder = prefs.getBoolean("software_decoder", DEFAULT_SOFTWARE_DECODER),
                orbSpeed = prefs.getFloat("orb_speed", 1.0f),
                artworkRounded = prefs.getBoolean("artwork_rounded", true),
                reduceMotion = prefs.getBoolean("reduce_motion", false),
                lowPowerMode = prefs.getBoolean("low_power", false),
                volumeLeveling = GainProcessor.enabled,
                motionArtworkEnabled = prefs.getBoolean("motion_artwork", false),
                backgroundPlayEnabled = prefs.getBoolean("background_play", true),
                screensaverTimeoutMin = prefs.getInt("screensaver_timeout", 10),
                screensaverKeepBackground = prefs.getBoolean("screensaver_keep_bg", false),
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
            _state.update { it.copy(isPlaying = false, progressMs = 0) }
            return
        }
        val nextIndex = if (s.queueIndex < s.queue.lastIndex) s.queueIndex + 1 else 0
        playQueueItem(nextIndex)
    }

    fun prev() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if ((exo?.currentPosition ?: 0L) > 3_000 && s.repeatMode != RepeatMode.One) {
            exo?.seekTo(0)
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

    fun stepOrbSpeed() {
        val steps = listOf(0.6f, 1.0f, 1.6f)
        val idx = steps.indexOfFirst { kotlin.math.abs(it - _state.value.orbSpeed) < 0.05f }
        val next = steps[(if (idx < 0) 1 else idx + 1) % steps.size]
        _state.update { it.copy(orbSpeed = next) }
        prefs.edit().putFloat("orb_speed", next).apply()
    }

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

    // ── 全屏歌词（墨卷 / 声轨）的独立排版设置 ───────────────────────────────────
    // 与上面的普通歌词字号 / 行距分开存：全屏是「一张画面」，字号动辄 40sp，
    // 和播放页右栏那套 24sp 的排版共用一个值只会两边都不合适。

    /** 全屏样式全部走这一个入口：夹取范围 + 写盘 + 只换那一套，不会互相串味。 */
    fun updateFsStyle(style: FsStyle, block: (FsStylePrefs) -> FsStylePrefs) {
        val next = block(_state.value.fsPrefs(style)).let {
            it.copy(
                fontSp = it.fontSp.coerceIn(FS_FONT_MIN_SP, FS_FONT_MAX_SP),
                lineGapPct = it.lineGapPct.coerceIn(FS_GAP_MIN_PCT, FS_GAP_MAX_PCT),
                letterSpacing = it.letterSpacing.coerceIn(FS_TRACKING_MIN, FS_TRACKING_MAX),
            )
        }
        _state.update { it.copy(fsStyles = it.fsStyles + (style to next)) }
        prefs.edit()
            .putString(fsKey(style, "font"), next.font.name)
            .putInt(fsKey(style, "font_sp"), next.fontSp)
            .putInt(fsKey(style, "gap_pct"), next.lineGapPct)
            .putInt(fsKey(style, "tracking"), next.letterSpacing)
            .putBoolean(fsKey(style, "light"), next.light)
            .apply()
    }

    fun setFsFont(style: FsStyle, choice: LyricFontChoice) =
        updateFsStyle(style) { it.copy(font = choice) }

    fun nudgeFsFontSp(style: FsStyle, delta: Int) =
        updateFsStyle(style) { it.copy(fontSp = it.fontSp + delta) }

    fun nudgeFsLineGapPct(style: FsStyle, delta: Int) =
        updateFsStyle(style) { it.copy(lineGapPct = it.lineGapPct + delta) }

    fun nudgeFsLetterSpacing(style: FsStyle, delta: Int) =
        updateFsStyle(style) { it.copy(letterSpacing = it.letterSpacing + delta) }

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
                letterSpacing = prefs.getInt(fsKey(style, "tracking"), base.letterSpacing)
                    .coerceIn(FS_TRACKING_MIN, FS_TRACKING_MAX),
                light = prefs.getBoolean(fsKey(style, "light"), base.light),
            )
        }
    }

    private fun readLyricLineGapPct(): Int =
        prefs.getInt("lyric_line_gap_pct", DEFAULT_LYRIC_GAP_PCT)
            .coerceIn(LYRIC_GAP_MIN_PCT, LYRIC_GAP_MAX_PCT)

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

    fun stepScreensaverTimeout() {
        val steps = listOf(0, 5, 10, 20, 30)
        val idx = steps.indexOf(_state.value.screensaverTimeoutMin)
        val next = steps[(if (idx < 0) 2 else idx + 1) % steps.size]
        _state.update { it.copy(screensaverTimeoutMin = next) }
        prefs.edit().putInt("screensaver_timeout", next).apply()
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

    fun toggleScreensaverKeepBackground() {
        val next = !_state.value.screensaverKeepBackground
        _state.update { it.copy(screensaverKeepBackground = next) }
        putBool("screensaver_keep_bg", next)
    }

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
