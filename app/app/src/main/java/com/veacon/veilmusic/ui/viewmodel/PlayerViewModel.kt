package com.veacon.veilmusic.ui.viewmodel

import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.R
import com.veacon.veilmusic.data.VideoPlaybackPolicy

import android.app.NotificationChannel
import android.app.NotificationManager
import com.veacon.veilmusic.data.cache.AppDiskCache
import com.veacon.veilmusic.data.cache.CacheLimits
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.veacon.veilmusic.data.LyricsOffsetPreferences
import com.veacon.veilmusic.data.MutPreferences
import com.veacon.veilmusic.data.ServerPreferences
import com.veacon.veilmusic.media.InAppWebServer
import com.veacon.veilmusic.data.model.Album
import com.veacon.veilmusic.data.model.Song
import com.veacon.veilmusic.data.network.LyricLine
import com.veacon.veilmusic.data.repository.MusicRepository
import com.veacon.veilmusic.ui.background.unifiedFluidQuality
import com.veacon.veilmusic.ui.background.FluidQuality
import com.veacon.veilmusic.media.AppleDirectClient
import com.veacon.veilmusic.media.AppleMusicDrmCallback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.veacon.veilmusic.media.BeatAnalyzer
import com.veacon.veilmusic.media.BeatAwareRenderersFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RepeatMode { Off, One, All }

/** Rebuffers on one song before standalone is abandoned for it. */
private const val STUTTER_LIMIT = 3
/** Sleep timer eases the volume down over this window before pausing. */
private const val SLEEP_FADE_MS = 5_000L

/** Dump schm/tenc/pssh of the standalone init segment. Costs a full segment download. */
private const val PROBE_INIT_SEGMENT = false

/** Path 1 milestone: run our own software Widevine CDM and log the raw content key
 *  (AMKEY). Compare against server/get_key.py for the same track to prove the on-device
 *  CDM derives the correct key before building the in-app decrypt. */
private const val PROBE_CDM_KEY = false

/** Path 1: decrypt segments in-app with our own CDM and play CLEAR AAC (no MediaDrm),
 *  which is what kills the 0x4004 chop. Falls back to the MediaDrm path on failure. */
private const val DECRYPT_IN_APP = true

/** DIAGNOSTIC: capture decoded PCM of the first track to inspect the chop offline. */
private const val CAPTURE_PCM = false

/**
 * What the Now Playing screen draws behind the card.
 *
 * - [DYNAMIC]: the drifting, album-coloured beat blobs (default).
 * - [PROJECTOR]: the same blobs pulled to the centre on true black, dissolved to black on every
 *   edge, so a projected image has no visible boundary. Halo brightness stays constant with the
 *   beat — only size moves — so the black never pumps.
 * - [BLACK]: plain black, no blobs, no beat.
 */
enum class NowPlayingBackground(private val labelId: Int) {
    DYNAMIC(R.string.msg_012),
    PROJECTOR(R.string.msg_013),
    BLACK(R.string.msg_014),
    ;
    val label: String get() = tr(labelId)


    companion object {
        fun fromName(name: String?): NowPlayingBackground =
            entries.firstOrNull { it.name == name } ?: DYNAMIC
    }
}

/**
 * 一首歌实际命中的音频流（解析之后才知道）。
 *
 * 只给「播放信息」浮层用：音源脚本名、平台、容器格式、请求档位、真实码率。
 * [songId] 跟着歌曲走，切歌瞬间旧信息绝不会挂在新歌上。
 */
data class ResolvedStream(
    val songId: String,
    val sourceName: String,
    val platform: String,
    val format: String,
    val quality: String,
    val kbps: Int,
    /** 命中的脚本文件名（如 lx-01-kulao.js），换源时据此跳过当前源。 */
    val script: String = "",
) {
    /** 「FLAC · 1024 kbps」这类展示串；码率未知时只留格式。 */
    val qualityLabel: String
        get() {
            val name = when {
                format.contains("flac") && quality.contains("24") -> "FLAC 24bit"
                format.contains("flac") -> "FLAC"
                format.contains("aac") || format.contains("m4a") || format.contains("mp4") -> "AAC"
                format.contains("mp3") -> "MP3"
                format.contains("ape") -> "APE"
                format.contains("wavpack") -> "WavPack"
                format.contains("opus") -> "OPUS"
                format.isBlank() -> ""
                else -> format.substringAfter('/').uppercase()
            }
            return when {
                kbps > 0 && name.isNotEmpty() -> "$name · $kbps kbps"
                name.isNotEmpty() -> name
                kbps > 0 -> "$kbps kbps"
                else -> "—"
            }
        }
}

data class PlayerState(
    val currentSong:      Song?           = null,
    val song:             Song?           = null,
    val isPlaying:        Boolean         = false,
    val progressMs:       Long            = 0L,
    val queue:            List<Song>      = emptyList(),
    val queueIndex:       Int             = 0,
    val lyrics:           List<LyricLine> = emptyList(),
    val lyricsNotice: LyricsNoticePhase = LyricsNoticePhase.Idle,
    val lyricsNoticeSongId: String? = null,
    val lyricsNoticeGeneration: Long = 0L,
    val emptyLyricsNoticeCompletedFor: String? = null,
    val exhaustedEmptyLyricsFor: String? = null,
    /** 当前歌词命中的来源标签（网易云/QQ音乐/酷狗/酷我/咪咕/lrclib），供播放信息框展示。 */
    val lyricsSource: String? = null,
    /** 播放一律按"完整曲目"处理：音源只来自落雪，不需要 MUT，也不再有试听片段。 */
    val isFullStream:     Boolean         = true,
    val motionUrl:        String?         = null,
    /** A/V-sync EXTRA the user dials on top of everything. 0 normally; the only value shown in the UI. */
    val lyricsOffsetMs:   Long            = 0L,
    /** Automatic A/V sync: add the Bluetooth delta when a BT output is live. Factory default OFF. */
    val avSyncAuto:       Boolean         = false,
    /** A BT output is currently active. */
    val avOnBluetooth:    Boolean         = false,
    /** Total delay applied to the LYRIC clock: hidden 200 ms baseline (+ BT delta) + extra. */
    val avLyricsMs:       Long            = LYRICS_BASELINE_MS,
    /** Human label for the current audio output. */
    val avOutputLabel:    String          = tr(R.string.msg_015),
    val isShuffled:       Boolean         = false,
    val originalQueue:    List<Song>      = emptyList(),
    val repeatMode:       RepeatMode      = RepeatMode.Off,
    val sleepTimerEndsAt: Long?           = null,
    val sleepAfterSong:   Boolean         = false,
    val mutExpired:       Boolean         = false,
    val beatIntensity:    Float           = 1.0f,
    val userQueue:        List<Song>      = emptyList(),
    val crossfadeEnabled: Boolean         = true,
    /** Idle minutes before the ambient screensaver; 0 = off. */
    val screensaverTimeoutMin: Int        = 10,
    /** What's drawn behind the Now Playing card. */
    val nowPlayingBackground: NowPlayingBackground = NowPlayingBackground.DYNAMIC,
    val fluidQuality: FluidQuality = FluidQuality.STANDARD,
    /**
     * When true, the beat blobs keep running once the screensaver kicks in. Off by default: the
     * screensaver drops to plain black so the extra motion doesn't linger overnight.
     */
    val screensaverKeepBackground: Boolean = false,
    /** Show the clock and the queue/lyrics panel hint on Now Playing. Off = a cleaner, art-only view. */
    val showNowPlayingInfo: Boolean = true,
    /** Animated album art is enabled by default; one bounded, muted decoder at a time. */
    val motionArtworkEnabled: Boolean = false,
    /** Projector orb drift speed: 0.6 slow · 1.0 normal · 1.6 fast. */
    val orbSpeed: Float = 1.0f,
    /** Lyrics font scale: 0.8 extra small · 1.0 small · 1.25 standard · 1.6 large · 2.0 extra large. */
    val lyricsScale: Float = 1.0f,
    /** Rounded (true) vs square (false) album-art corners. */
    val artworkRounded: Boolean = true,
    /** Accessibility: freeze/minimise background motion. */
    val reduceMotion: Boolean = false,
    /** Low Power Mode: cheaper background rendering (also forces motion art off). */
    val lowPowerMode: Boolean = false,
    /** Volume leveling (loudness AGC) on the audio path. */
    val volumeLeveling: Boolean = false,
    /** 硬解 / 软解 — which decoder path the player is built with. Factory default: hardware. */
    val decoderMode: com.veacon.veilmusic.data.DecoderMode = com.veacon.veilmusic.data.DecoderMode.DEFAULT,
    /** 歌词字形（简/繁）；Apple 源歌词默认是繁体，这里统一到用户选择的字形。 */
    val lyricsScript: com.veacon.veilmusic.data.LyricsScript = com.veacon.veilmusic.data.LyricsScript.SIMPLIFIED,
    /**
     * 封面渲染兼容模式是否生效：开启时播放页封面不再挂载动态封面的 TextureView，
     * 避免个别电视 GPU（Realtek RTD2886 等）在封面区域合成出马赛克。
     */
    val renderCompat: Boolean = false,
    /** 渲染兼容开关是否被手动改过（false = 仍在按机型自动判定）。仅用于设置页文案。 */
    val renderCompatManual: Boolean = false,
    /** 顶部交互栏用当前歌名替换「正在播放」四个字（实验室开关）。 */
    val navTitleAsSong: Boolean = false,
    /** 当前曲目实际命中的音频流（音源/格式/码率），播放页「菜单键」信息框用；未解析完成时为 null。 */
    val resolvedStream: ResolvedStream? = null,
    /** 统一输出采样率（P2），改档要重建播放器。 */
    val unifySampleRate: Boolean = true,
    /** 音频 offload（P2）；设备不支持时恒为 false。 */
    val audioOffload: Boolean = false,
    /** Decrypt/buffer in flight — a cold track takes 15-20s, so the UI must say so. */
    val isLoading:        Boolean         = false,
    /** True while the current track is playing via on-device Widevine. */
    val standaloneActive: Boolean         = false,
    /** True while the activity is in Picture-in-Picture (renders the minimal PiP view). */
    val isInPip:          Boolean         = false,
    /** 实验室：底部迷你播放胶囊（移植自 v1.1.2）。默认关。 */
    val miniCapsuleEnabled: Boolean = false,
    /** 迷你胶囊高度百分比（60–140），同时缩放图标/封面/文字。 */
    val miniCapsuleHeightPct: Int = 100,
    /** 迷你胶囊宽度百分比（60–100），只改占屏宽比例。 */
    val miniCapsuleWidthPct: Int = 100,
)

/** Hidden LYRICS baseline: the Fire TV shows the lyric line ~200 ms before you hear the word even over
 *  HDMI, so the lyric clock is always shifted by this. Internal — never surfaced in the UI, so the
 *  menu's "extra" reads 0 normally. The BEAT does NOT get this baseline (it's already aligned on HDMI). */
private const val LYRICS_BASELINE_MS = 200L
/** Added to BOTH the beat and the lyrics when a Bluetooth output is live (Automatic mode). Bluetooth
 *  delays the actual sound by roughly this much; an estimate, since Android exposes no exact figure. */
private const val BT_DELTA_MS = 350L
/** 启动续播的等待上限：脚本冷启动（十来秒）+ 一次完整解析（十几秒）。超时则保留待播状态。 */
private const val LX_RESTORE_TIMEOUT_MS = 60_000L
/** 看门狗采样间隔与判定：READY 且在播时连续 N 个 tick 位置不动，就认定音频通路卡死。 */
private const val WATCHDOG_TICK_MS = 2_000L
private const val WATCHDOG_STALL_TICKS = 2
private const val WATCHDOG_MAX_RECOVERIES = 2

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MusicRepository,
    private val moshi: Moshi,
    private val mutPrefs: com.veacon.veilmusic.data.MutPreferences,
    private val serverPrefs: ServerPreferences,
    private val appleClient: AppleDirectClient,
    private val lyricsOffsetPrefs: LyricsOffsetPreferences,
    private val lyricsMotionPrefs: com.veacon.veilmusic.data.LyricsMotionPreferences,
    private val lyricsScriptPrefs: com.veacon.veilmusic.data.LyricsScriptPreferences,
    private val crossfadePrefs: com.veacon.veilmusic.data.CrossfadePreferences,
    private val onboardingPrefs: com.veacon.veilmusic.data.OnboardingPreferences,
    private val standalonePrefs: com.veacon.veilmusic.data.StandalonePreferences,
    private val webServer: InAppWebServer,
    private val lx: com.veacon.veilmusic.luoxue.LxPlayback,
    private val localLibrary: com.veacon.veilmusic.data.local.LocalLibraryStore,
    private val playbackCache: com.veacon.veilmusic.data.cache.PlaybackCache,
    val beatAnalyzer: BeatAnalyzer,
) : ViewModel() {

    /** 歌词逐字播放开关：设置页改，播放页通过 StateFlow 即时生效。 */
    val wordByWordLyrics: kotlinx.coroutines.flow.StateFlow<Boolean> = lyricsMotionPrefs.wordByWord

    private var albumWallPrefetch: kotlinx.coroutines.Deferred<List<com.veacon.veilmusic.ui.wall.CachedWallCard>>? = null

    /** One stable selection per player ViewModel lifetime, shared across player re-entry. */
    private var albumWallCapacity = 100
    private var currentWallBoard: com.veacon.veilmusic.ui.wall.WallBoard? = null

    fun albumWallBoard(pool: List<com.veacon.veilmusic.ui.wall.CachedWallCard>, capacity: Int): com.veacon.veilmusic.ui.wall.WallBoard {
        val previous = currentWallBoard
        if (previous != null && previous.pool === pool && previous.capacity == capacity) return previous
        return com.veacon.veilmusic.ui.wall.WallBoard(pool, capacity,
            previous?.takeIf { it.pool === pool }?.cards.orEmpty()).also { currentWallBoard = it }
    }
    fun prefetchAlbumWall(capacity: Int = 100): kotlinx.coroutines.Deferred<List<com.veacon.veilmusic.ui.wall.CachedWallCard>> {
        albumWallCapacity = capacity.coerceIn(1, 100)
        return albumWallPrefetch ?: viewModelScope.async { albumWallArtwork() }.also { albumWallPrefetch = it }
    }

    private var nextAlbumWall: List<com.veacon.veilmusic.ui.wall.CachedWallCard>? = null
    private var albumWallRefresh: kotlinx.coroutines.Job? = null

    /** Only prepare a new selection after the wall is no longer visible. */
    fun prepareNextAlbumWall() {
        if (nextAlbumWall != null || albumWallRefresh?.isActive == true) return
        albumWallRefresh = viewModelScope.launch {
            val prepared = albumWallArtwork()
            if (prepared.isNotEmpty()) nextAlbumWall = prepared
        }
    }

    /** Called only while the wall is hidden; never replace a visible wall. */
    fun takePreparedAlbumWall(): List<com.veacon.veilmusic.ui.wall.CachedWallCard>? {
        val prepared = nextAlbumWall ?: return null
        nextAlbumWall = null
        albumWallPrefetch = kotlinx.coroutines.CompletableDeferred(prepared)
        return prepared
    }

    /** Metadata from all three page caches, joined to existing local image snapshots. */
    suspend fun albumWallArtwork(): List<com.veacon.veilmusic.ui.wall.CachedWallCard> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val account = mutPrefs.getMUT()
            val cards = com.veacon.veilmusic.ui.wall.CachedWallCards(context, moshi).load(account, (albumWallCapacity + 40).coerceAtMost(160))
            if (account == mutPrefs.getMUT()) cards else emptyList()
        }

    private fun hasMUT() = mutPrefs.hasMUT()
    private fun isStandalone() = !serverPrefs.hasPcServer()

    /** 落雪音源：当前正在播放的歌曲 id（直链过期时据此重解析）。 */
    private var lxSongId: String? = null
    /** 每首歌只做一次"过期重解析"，避免死循环。 */
    private var lxRetriedSongId: String? = null

    private fun lxEligible(song: Song, full: Boolean) =
        full && !song.isMusicVideo && lx.isEnabled()

    private fun buildLxMediaItem(song: Song, stream: com.veacon.veilmusic.luoxue.AudioStream) =
        buildMediaItem(song, stream.url).buildUpon()
            .setMimeType(stream.mimeType.takeIf { it.isNotBlank() })
            .build()

    /** Resolve a card's animated-artwork loop lazily (on focus). Null = stay static.
     *  Honors the "动态封面" setting so cards don't spin up video decoders when it's off. */
    suspend fun cardMotion(album: Album): String? =
        if (!_state.value.motionArtworkEnabled) null else repo.cardMotion(album.id, album.type)


    private val prefs: SharedPreferences =
        context.getSharedPreferences("player_state", Context.MODE_PRIVATE)

    /**
     * 硬解 / 软解. Read once here (before any player is built) and owned by [setDecoderMode] after
     * that, so every rebuild — start, crossfade, radio, switch — agrees on one decoder path.
     */
    private var decoderMode = com.veacon.veilmusic.data.DecoderMode.fromKey(prefs.getString("decoder_mode", null))

    /**
     * P2 输出策略，和 [decoderMode] 一样在构造期读一次、之后只由 setter 改：
     *  - [outputSampleRate]：设备原生采样率，0 表示取不到（不重采样）。
     *  - [unifySampleRate]：把每首歌统一到这个采样率，避免 44.1k/48k 来回切重建 sink。
     *  - [offloadEnabled]：音频 offload。设备探测不到支持就永远是 false。
     */
    private val outputSampleRate = com.veacon.veilmusic.media.OutputTuning.deviceSampleRate(context)
    private var unifySampleRate = prefs.getBoolean("unify_sample_rate", true)
    private var offloadEnabled = com.veacon.veilmusic.media.OutputTuning.offloadSupported() &&
        prefs.getBoolean("audio_offload", false)

    private val bookmarkWriterDelegate = lazy {
        PlaybackStateWriter(prefs, moshi, onError = { error ->
            webServer.addLog("SAVE", "bookmark failed (${error.javaClass.simpleName})")
        })
    }
    private val bookmarkWriter by bookmarkWriterDelegate

    private val _state = MutableStateFlow(PlayerState(decoderMode = decoderMode,
        unifySampleRate = unifySampleRate, audioOffload = offloadEnabled))
    val state: StateFlow<PlayerState> = _state
    val artworkCentered: StateFlow<Boolean> = _state.stablePlayerLayout()
        .onEach { webServer.addLog("LAYOUT", "lyrics layout=${if (it) "centered" else "split"}") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val lyricsFailureNotice = LyricsFailureNotice(viewModelScope)
    private val diskCache = AppDiskCache.get(context)
    private val systemAudio by lazy {
        com.veacon.veilmusic.media.SystemAudioLoader(viewModelScope,
            com.veacon.veilmusic.data.cache.EncryptedAudioStore(diskCache.store), mutPrefs::getMUT,
            appleClient::getBearer, appleClient::getWebPlayback,
            { webServer.addLog("AUDIOCACHE", it) })
    }
    private val systemCacheSources = mutableSetOf<String>()
    private var systemCacheRetryFor: String? = null
    private var api23SoftwareFallbackSong: String? = null

    init {
        viewModelScope.launch { lyricsFailureNotice.status.collect { notice ->
            _state.update { current ->
                if (notice.songId != current.currentSong?.id || notice.generation < current.lyricsNoticeGeneration) current
                else current.copy(lyricsNotice=notice.phase,lyricsNoticeSongId=notice.songId,
                    lyricsNoticeGeneration=notice.generation,emptyLyricsNoticeCompletedFor=notice.completedEmptySong)
            }
        } }

        diskCache.protect("audio") {
            val s = _state.value
            AppDiskCache.audioNames(listOfNotNull(s.currentSong?.id,
                s.queue.getOrNull(s.queueIndex - 1)?.id,
                s.queue.getOrNull(s.queueIndex + 1)?.id,
                s.userQueue.firstOrNull()?.id))
        }
    }

    // Integrated queue: the queue may hold music videos. When the current item is a video,
    // the audio engine idles and this emits the video so the UI hands it to the video player.
    // Null means the current item is a normal song (any active video should be dismissed).
    // autoOpen=true only when the user EXPLICITLY picked the video (tapped it in a list) — that
    // jumps to Now Playing. Auto-advance / skip keep it false so the video plays without yanking
    // the user off whatever page they're browsing; they just see it when they visit Now Playing.
    data class VideoRequest(val song: Song, val autoOpen: Boolean, val startPaused: Boolean = false)
    private val _videoRequest = MutableStateFlow<VideoRequest?>(null)
    val videoRequest: StateFlow<VideoRequest?> = _videoRequest
    private val _userPlaybackRequests = MutableStateFlow(0L)
    val userPlaybackRequests: StateFlow<Long> = _userPlaybackRequests
    fun clearVideoRequest() { _videoRequest.value = null }

    /**
     * One-off user-facing messages. extraBufferCapacity so an emit from the polling
     * thread never suspends, and replay=0 so a message doesn't re-fire on rotation.
     */
    private val _toasts = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts
    fun toast(msg: String) { _toasts.tryEmit(msg) }

    /**
     * 落雪命中后记下这条流的信息（音源 / 格式 / 码率），供播放页「菜单键」信息框读取。
     *
     * 以前这里会弹一条「准备播放 xxx 来源 yyy」的 toast —— 每首歌都弹、还挡字幕，
     * 现在改成按需查看：不打扰，但信息一条不少。
     */
    private fun recordResolvedStream(song: Song, stream: com.veacon.veilmusic.luoxue.AudioStream) {
        val sourceName = stream.sourceName.ifBlank {
            stream.script.removeSuffix(".js").removePrefix("lx-")
        }
        _state.update {
            it.copy(resolvedStream = ResolvedStream(
                songId = song.id,
                sourceName = sourceName,
                platform = stream.source,
                format = stream.kind,
                quality = stream.quality,
                kbps = stream.kbps,
                script = stream.script,
            ))
        }
    }

    private var lastErrorKey: String? = null
    private var hasPlayedSomething = false
    var nowPlayingVisible = false

    /**
     * 播放信息框里的「歌曲换源」：落雪直链过期 / 源端 403 / 音质不对时，用户手动触发一次强制重解析。
     * 保持当前进度，重新解析当前歌并换到新的直链；解析不到就提示并跳过下一首（和自动重解析一致）。
     */
    fun retryCurrentSource() {
        val song = _state.value.currentSong ?: return
        if (song.isMusicVideo) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = _state.value.isPlaying
        // 换源语义：跳过「当前命中的脚本」，让解析级联落到下一个源；否则 force 重解析
        // 仍会按同一优先级返回同一个源，用户点了等于没点。
        val currentScript = _state.value.resolvedStream?.takeIf { it.songId == song.id }?.script
            ?.takeIf { it.isNotBlank() }
        lxRetriedSongId = song.id   // 复用防死循环标记：手动换源也最多这一次
        lx.invalidate(song.id)
        val myGen = ++playGen
        sourcePreparation.begin(myGen, wasPlaying)
        attachedSourceGeneration = -1
        webServer.addLog("LX", "手动换源 ${song.title}（@${pos}ms，跳过 $currentScript）")
        toast(tr(R.string.msg_334))   // 「正在换源…」
        viewModelScope.launch {
            val stream = lx.resolve(song, force = true, excludeScript = currentScript)
            if (myGen != playGen) return@launch
            val shouldPlay = sourcePreparation.finish(myGen) ?: return@launch
            if (stream != null) {
                attachedSourceGeneration = myGen
                lxSongId = song.id
                recordResolvedStream(song, stream)
                player.setMediaItem(buildLxMediaItem(song, stream), pos)
                player.prepare(); player.volume = 1f; player.playWhenReady = shouldPlay
                webServer.addLog("LX", "手动换源成功 ${song.title} · ${stream.kind} ${stream.kbps}kbps · ${stream.source}/${stream.script}")
            } else {
                lxSongId = null
                webServer.addLog("LX", "手动换源失败 ${song.title} — 跳过")
                toast(tr(R.string.msg_019, song.title))
                advanceQueue()
            }
        }
    }

    /**
     * 播放信息框里的「歌词换源」：歌词拿错/没匹配到歌词时，清掉本歌缓存并**跳过当前命中的源**
     * 再走一遍歌词级联（网易云 → QQ → 酷狗 → 酷我 → 咪咕 → lrclib），等于手动换一个来源。
     */
    fun retryCurrentLyrics() {
        val song = _state.value.currentSong ?: return
        if (song.isMusicVideo) return
        lyricsCache.remove(song.id)
        lyricsInFlight.remove(song.id)
        // 关键：直连歌词源（DirectLyricsSource）内部还有一层 24h 缓存，只清 ViewModel 的
        // lyricsCache 没用，必须把它也失效掉，否则「换源」永远命中原歌词。
        repo.invalidateLyricsCache(song.id)
        // 「换源」核心：把当前命中的源（如「网易云」）加入排除集，让级联从下一个源开始，
        // 否则 force 重走一遍级联仍会命中同一个排第一的源，用户点了等于没点。
        val currentSource = _state.value.lyricsSource
        if (currentSource.isNullOrBlank()) {
            lyricsExcludedSources.remove(song.id)
        } else {
            val set = lyricsExcludedSources[song.id].orEmpty() + currentSource
            lyricsExcludedSources[song.id] = set
        }
        _state.update { it.copy(lyrics = emptyList(), lyricsSource = null) }
        webServer.addLog("LYRICS", "手动歌词换源 ${song.title} — 排除「${currentSource ?: "无"}」后重试")
        loadLyrics(song.id)
    }

    private var mediaSession: androidx.media3.session.MediaSession? = null
    fun showEmptyLyricsNotice(songId: String, generation: Long) = lyricsFailureNotice.showWhenReady(songId,generation)
    private var lyricsJob: kotlinx.coroutines.Job? = null
    private var lyricsLoadGeneration = 0L
    // Small cache of already-fetched lyrics, keyed by song id. Lets a track change show its lyrics
    // instantly (from the N+1 prefetch) instead of blanking to empty and re-fetching — which, in
    // full-screen lyrics, flashed the view back to the player for a beat.
    private val lyricsCache = java.util.concurrent.ConcurrentHashMap<String, List<LyricLine>>()
    private var motionJob: kotlinx.coroutines.Job? = null
    private var fadeJob: kotlinx.coroutines.Job? = null
    private var crossfadeInProgress = false
    /** Mirrors [crossfadePrefs] — read on the polling thread, so keep it a plain field. */
    @Volatile private var crossfadeDurationMs = com.veacon.veilmusic.data.CrossfadePreferences.DEFAULT_MS
    private var crossfadeExo: ExoPlayer? = null
    private var cfExoErrListener: Player.Listener? = null  // stored so STATE_ENDED snap can remove it
    private var prefetchRequestedForSongId: String? = null
    private var crossfadeSkipSongId: String? = null
    /** Song whose crossfade-window decision has already been logged (see pollProgress). */
    private var cfWindowLoggedForSongId: String? = null
    /** Title of the song whose standalone attempt already failed — retry proxy once, then give up. */
    private var standaloneFailedSongId: String? = null
    /** True while the sleep timer's final-5s volume ramp is active (so a cancel can restore volume). */
    private var sleepFadeActive = false
    /** Library song id whose catalog (not-in-library) on-device retry already ran — try once. */
    private var catalogRetriedSongId: String? = null
    /** Songs Apple 404s on (pulled from the catalogue). Skipped without a retry. */
    private val unavailableSongIds = mutableSetOf<String>()
    /** Avoids re-logging the audio format on every READY (seeks re-enter it). */
    private var lastFormatLoggedFor: String? = null
    /** Rebuffers seen on the current song, and which song they belong to. */
    private var stutterSongId: String? = null
    private var stutterCount = 0
    /** Songs whose standalone encode stutters — permanently routed via the proxy. */
    private val proxyOnlySongIds = mutableSetOf<String>()
    private var standaloneFailures = 0
    /** Pending N+1 prefetch, cancelled whenever a new song starts loading. */
    private var prefetchJob: kotlinx.coroutines.Job? = null
    /** Bumped on every playQueueItem — a slow async standalone build checks it before
     *  starting playback so rapidly-skipped songs don't each play for a moment. */
    private var playGen = 0
    private val playbackRequests = PlaybackRequestGate()
    private var relatedQueueJob: kotlinx.coroutines.Job? = null
    private var relatedRequestedGeneration = -1
    private val sourcePreparation = SourcePreparation()
    private var attachedSourceGeneration = -1

    /** Reserve ownership before fetching a playlist outside this ViewModel. */
    fun reservePlaybackRequest(): Long = beginPlaybackSelection()
    fun playAlbumIfCurrent(request: Long, songs: List<Song>) {
        if (playbackRequests.isCurrent(request)) playAlbum(songs)
    }

    private fun beginPlaybackSelection(keepDecryptFor: String? = null): Long {
        if (sourcePreparation.isPending) {
            ++playGen
            sourcePreparation.cancel()
            awaitingSongStart = null
            _state.update { it.copy(isLoading = false) }
        }
        prefetchJob?.cancel()
        cancelOtherDecrypts(keepDecryptFor)
        return playbackRequests.select()
    }

    private fun currentSourceReady(generation: Int): Boolean =
        generation == playGen && attachedSourceGeneration == generation &&
            player.playbackState == Player.STATE_READY

    // True while the on-device (Widevine) path is driving playback, so the
    // error handler doesn't bounce back to the proxy in a loop.
    private var usingStandalone: Boolean = false
    private var lastCheckedProxy: String? = null
    private var proxyHadSuccessfulCheck = false


    /** Held so onCleared can unregister it — AudioManager outlives this ViewModel. */
    private var audioDeviceCallback: AudioDeviceCallback? = null



    /**
     * MediaCodec selection. The candidate list is always logged — that line is how we tell which
     * decoder a box actually picked. In 软解 the older OMX AAC implementation is additionally
     * preferred: it is the one that survives HE-AAC segment boundaries cleanly. 硬解 keeps the
     * platform order so the real hardware decoder wins.
     */
    @OptIn(UnstableApi::class)
    /** 解码器排序 + 设备级黑名单（`DecoderPolicy`：硬解优先/软解优先、失败拉黑）。 */
    private val decoderPolicy = com.veacon.veilmusic.media.DecoderPolicy(
        context.getSharedPreferences("decoder_policy", Context.MODE_PRIVATE),
    )

    private val audioCodecSelector =
        androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, secure, tunneling ->
            val infos = androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                .getDecoderInfos(mimeType, secure, tunneling)
            // 每个 mime 都记一行：切到硬解后靠这行确认平台真的挑到了解码器。
            webServer.addLog("CODEC", "decoder=${decoderMode.key} $mimeType (secure=$secure): ${infos.joinToString { it.name }}")
            decoderPolicy.select(
                infos = infos,
                mimeType = mimeType,
                preferHardware = decoderMode == com.veacon.veilmusic.data.DecoderMode.HARDWARE,
            ) { msg -> webServer.addLog("CODEC", msg) }
        }

    /**
     * 软解 lets the bundled FFmpeg renderer claim the audio (MediaCodec stays as the secure/DRM
     * fallback); 硬解 drops that renderer entirely so only the platform decoders can run.
     */
    @OptIn(UnstableApi::class)
    private fun extensionRendererMode(): Int =
        if (decoderMode == com.veacon.veilmusic.data.DecoderMode.HARDWARE)
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER

    /**
     * Every player this ViewModel builds is pinned to 1.0x with silence-skipping off.
     *
     * A stale playback speed is the classic "occasionally plays too fast" failure: the speed can
     * survive a sink rebuild (or arrive from a platform AudioTrack that kept the parameters it was
     * handed earlier), and once it is 1.5x/2x it never drifts back on its own. Pinning it at build
     * time and re-checking it once playback is live (see [enforceNormalSpeed]) keeps it honest.
     */
    @OptIn(UnstableApi::class)
    private fun ExoPlayer.pinNormalSpeed() {
        playbackParameters = androidx.media3.common.PlaybackParameters(1f, 1f)
        skipSilenceEnabled = false
        repeatMode = Player.REPEAT_MODE_OFF
    }

    /** Reset a drifted playback speed back to 1.0x, and say so in the log. */
    private fun enforceNormalSpeed(where: String) {
        val p = player.playbackParameters
        if (p.speed != 1f || p.pitch != 1f) {
            webServer.addLog("SPD", "$where speed=${p.speed} pitch=${p.pitch} → 1.0")
            player.playbackParameters = androidx.media3.common.PlaybackParameters(1f, 1f)
        }
    }

    @OptIn(UnstableApi::class)
    /** 统一采样率生效时的目标输出率；关掉或拿不到设备采样率时返回 0（不重采样）。 */
    private fun outputTargetSampleRate(): Int = if (unifySampleRate) outputSampleRate else 0

    /** offload 的轨道选择参数：要求可 offload，且不要求 gapless / 变速支持（本应用都锁在 1.0x）。 */
    @OptIn(UnstableApi::class)
    private fun offloadParameters(): androidx.media3.common.TrackSelectionParameters =
        androidx.media3.common.TrackSelectionParameters.Builder(context)
            .setAudioOffloadPreferences(
                androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(false)
                    .setIsSpeedChangeSupportRequired(false)
                    .build()
            ).build()

    private fun buildExoPlayer(): ExoPlayer = run {
        // The proxy holds the HTTP connection open for a few seconds while it
        // decrypts (no bytes until mp4decrypt finishes), so give ExoPlayer
        // generous connect/read timeouts — otherwise the first play of a fresh
        // (uncached) song times out and silently fails, which looks like "some
        // songs don't play."
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(60_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        // 先查本地播放缓存（同一首歌重播不用再走网络），未命中再透传给上游。
        val dataSourceFactory = playbackCache.wrap(
            androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory),
        )
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        // 纯音频按「扛住一次网络抖动」定标：20s 起读 / 60s 封顶 / 8MB 硬上限 + 15s 回退缓冲。
        // 原来的 50s/180s/32MB 是按视频流媒体写的，对 3 分钟的歌等于整首预载（也是之前 OOM 的主因）。
        val loadControl = com.veacon.veilmusic.media.PlaybackTuning.loadControl()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setRenderersFactory(
                // offload 时整条处理器链让路，律动分析拿不到 PCM，就别再挂一个空转的处理器。
                BeatAwareRenderersFactory(context, beatAnalyzer.newProcessor().also { p ->
                    mainProc = p; if (!offloadEnabled) beatAnalyzer.activate(p)
                }, com.veacon.veilmusic.media.GapConcealProcessor(),
                    targetSampleRate = outputTargetSampleRate(),
                    offload = offloadEnabled)
                    .setExtensionRendererMode(extensionRendererMode())
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(audioCodecSelector)
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), false, // don't yield to Fire TV Alexa audio focus steals
            )
            .setHandleAudioBecomingNoisy(false)
            .build().also {
                it.pinNormalSpeed()
                // offload 的轨道参数只能在实例上设（Builder 上没有这个方法）。
                if (offloadEnabled) it.trackSelectionParameters = offloadParameters()
            }
    }

    /** Processor feeding the beat bus for [player]; [cfProc] for the crossfade player. */
    private var mainProc: com.veacon.veilmusic.media.BeatProcessor? = null
    private var cfProc: com.veacon.veilmusic.media.BeatProcessor? = null

    private data class PreparedNext(val songId:String,val generation:Int,val exo:ExoPlayer,
        val processor:com.veacon.veilmusic.media.BeatProcessor?,val listener:Player.Listener)
    private var preparedNext:PreparedNext?=null
    private var preparedNextJob:kotlinx.coroutines.Job?=null

    private fun clearPreparedNext(keep:String?=null) {
        if(keep!=null && preparedNext?.songId==keep) return
        preparedNextJob?.cancel();preparedNextJob=null
        preparedNext?.let {it.exo.removeListener(it.listener);it.exo.stop();it.exo.release()}
        preparedNext=null
    }
    private fun takePreparedNext(id:String):PreparedNext? {
        val held=preparedNext
        val usable=held?.takeIf {it.songId==id && it.generation==playGen && it.exo.playbackState==Player.STATE_READY}
        if(usable!=null) {preparedNext=null;preparedNextJob?.cancel();preparedNextJob=null;usable.exo.removeListener(usable.listener)}
        else clearPreparedNext()
        return usable
    }
    /** Check live settings both before and after the asynchronous source preparation. */
    private fun canPrepareNextWithoutFade(song:Song,generation:Int):Boolean {
        val st=_state.value
        val current=st.currentSong ?: return false
        val next=st.userQueue.firstOrNull() ?: st.queue.getOrNull(st.queueIndex+1)
            ?: st.queue.firstOrNull().takeIf {st.repeatMode==RepeatMode.All && st.queue.size>1}
        val sameAlbum=if(current.albumId!=null && song.albumId!=null) current.albumId==song.albumId
            else current.albumName.isNotBlank() && current.albumName==song.albumName
        val consecutive=current.trackNumber!=null && song.trackNumber==current.trackNumber+1
        val noFade=com.veacon.veilmusic.data.CrossfadePreferences.needsPreparedNext(
            st.crossfadeEnabled,crossfadeDurationMs,sameAlbum && consecutive && st.userQueue.isEmpty())
        return generation==playGen && next?.id==song.id && noFade && !song.isMusicVideo && st.repeatMode!=RepeatMode.One
    }

    /** Same-album/no-fade transitions still need a prepared decoder and DRM session before the boundary. */
    private fun prepareNextWithoutFade(song:Song,generation:Int) {
        if(!canPrepareNextWithoutFade(song,generation)) return
        clearPreparedNext()
        preparedNextJob=viewModelScope.launch {
            val source=buildStandaloneSource(song) ?: return@launch
            if(!canPrepareNextWithoutFade(song,generation)) return@launch
            val previousProc=cfProc
            val exo=buildCrossfadeExo()
            val proc=cfProc;cfProc=previousProc
            val listener=object:Player.Listener {
                override fun onPlaybackStateChanged(state:Int) {
                    if(state==Player.STATE_READY) webServer.addLog("PRE","player ready song=${song.id}")
                }
                override fun onPlayerError(error:androidx.media3.common.PlaybackException) {
                    webServer.addLog("PRE","player failed song=${song.id} code=${error.errorCodeName}")
                    if(preparedNext?.exo===exo) clearPreparedNext()
                }
            }
            preparedNext=PreparedNext(song.id,generation,exo,proc,listener)
            exo.addListener(listener);exo.volume=1f;exo.playWhenReady=false
            exo.setMediaSource(source);exo.prepare()
        }
    }

    /** Hand the beat bus over to the crossfade player once it becomes the one we hear. */
    private fun promoteCrossfadeBeat() {
        cfProc?.let { beatAnalyzer.activate(it); mainProc = it }
        cfProc = null
    }

    /** Crossfade-only player. Own BeatProcessor so it can drive the visuals after the swap. */
    @OptIn(UnstableApi::class)
    private fun buildCrossfadeExo(): ExoPlayer {
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(60_000).setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        // 交叉淡入的下一首同样走缓存：淡入前它已经被 prepare 过一遍，命中缓存时能省一次完整下载。
        val dataSourceFactory = playbackCache.wrap(
            androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory),
        )
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        val loadControl = com.veacon.veilmusic.media.PlaybackTuning.loadControl()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .setRenderersFactory(
                BeatAwareRenderersFactory(context, beatAnalyzer.newProcessor().also { cfProc = it }, com.veacon.veilmusic.media.GapConcealProcessor(),
                    targetSampleRate = outputTargetSampleRate())
                    .setExtensionRendererMode(extensionRendererMode())
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(audioCodecSelector)
            )
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .setHandleAudioBecomingNoisy(false)
            .build().also { it.pinNormalSpeed() }
    }

    // ── 播放看门狗 ────────────────────────────────────────────────────────────
    // 「播着播着没声音 / 进度卡住」在电视盒子上多半是音频 HAL 卡死或 codec 被系统回收：
    // ExoPlayer 不报错，只是停在同一个位置。这里主动发现并自愈（重建播放器 → 换解码器 → 跳下一首）。

    private var watchdogJob: kotlinx.coroutines.Job? = null
    private var watchdogSongId: String? = null
    private var watchdogRecoveries = 0

    /**
     * 把「解码/输出」类失败回灌给策略层：
     *  - 解码失败 → 把这个 mime 上次选中的 codec 拉黑（落盘，下次构建播放器生效）；
     *  - AudioTrack 初始化/写入失败 → 关掉 float 输出，之后新建的播放器走 16bit。
     * 网络/DRM/文件类失败与解码器无关，不在这里处理。
     */
    private fun reportPipelineFailure(error: androidx.media3.common.PlaybackException) {
        val mime = player.audioFormat?.sampleMimeType
        when (error.errorCode) {
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> {
                if (mime != null) {
                    decoderPolicy.reportFailure(mime)
                    webServer.addLog("DEC", "decode failed mime=$mime — codec 已拉黑，下次构建生效 blacklist=${decoderPolicy.snapshot()}")
                }
            }
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> {
                if (com.veacon.veilmusic.media.BeatAwareRenderersFactory.floatOutputAllowed) {
                    com.veacon.veilmusic.media.BeatAwareRenderersFactory.floatOutputAllowed = false
                    webServer.addLog("DEC", "audio track failed (${error.errorCodeName}) — 后续播放器退回 16bit 输出")
                }
            }
        }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = viewModelScope.launch {
            var lastPos = -1L
            var stallTicks = 0
            // delay() 在取消时抛 CancellationException，不需要再判断 isActive。
            while (true) {
                kotlinx.coroutines.delay(WATCHDOG_TICK_MS)
                if (!_state.value.isPlaying) break
                val p = player
                // 缓冲中位置本来就不动；切歌/淡入期间也会重排，都不算卡死。
                if (p.playbackState != Player.STATE_READY || !p.playWhenReady || crossfadeInProgress) {
                    lastPos = -1L; stallTicks = 0; continue
                }
                val pos = p.currentPosition
                if (pos == lastPos) stallTicks++ else { stallTicks = 0; lastPos = pos }
                if (stallTicks >= WATCHDOG_STALL_TICKS) { recoverFromStall(pos); break }
            }
        }
    }

    private fun stopWatchdog() { watchdogJob?.cancel(); watchdogJob = null }

    private fun recoverFromStall(atMs: Long) {
        val song = _state.value.currentSong ?: return
        if (watchdogSongId != song.id) { watchdogSongId = song.id; watchdogRecoveries = 0 }
        watchdogRecoveries++
        webServer.addLog("WDOG", "pos stuck at ${atMs}ms on ${song.title} (recovery #$watchdogRecoveries)")
        if (watchdogRecoveries > WATCHDOG_MAX_RECOVERIES) {
            webServer.addLog("WDOG", "giving up on ${song.title} — skipping")
            watchdogRecoveries = 0
            advanceQueue()
            return
        }
        // 重建播放器（等价于重建 sink + 重新协商解码器），位置保留。
        pendingRestore = RestoreInfo(song, atMs.coerceAtLeast(0L), _state.value.isFullStream)
        startPendingRestore(autoPlay = true)
    }

    /** Swap the shared [player] for a fresh instance built by [factory], carrying over the listener
     *  and MediaSession. Mirrors the crossfade snap swap. */
    @OptIn(UnstableApi::class)
    private fun swapPlayer(factory: () -> ExoPlayer) {
        val oldP = player
        try { oldP.removeListener(playerListener); oldP.stop(); oldP.release() } catch (_: Exception) {}
        player = factory().also { it.addListener(playerListener) }
        mediaSession?.release(); mediaSession = buildMediaSession(player)
    }

    @OptIn(UnstableApi::class)
    var player: ExoPlayer = buildExoPlayer()
        private set

    init {
        webServer.addLog(
            "DEC",
            "pipeline ${com.veacon.veilmusic.media.PlaybackTuning.summary()} | " +
                "${com.veacon.veilmusic.media.FfmpegSupport.describe()} | " +
                "${com.veacon.veilmusic.media.OutputTuning.summary(unifySampleRate, offloadEnabled, outputSampleRate)} | " +
                "cache=256MB blacklist=${decoderPolicy.snapshot()}",
        )
        // 设备/渲染快照：花屏这类问题只看错误日志是查不出来的（老版本只有一行 Device MODEL），
        // 这里把 SoC / 进程位数 / 内存 / GPU / 屏幕一次性打进日志，并异步探测一次 GPU 型号。
        webServer.addLog("DEV", com.veacon.veilmusic.util.RenderDiagnostics.summaryLine(context))
        com.veacon.veilmusic.util.RenderDiagnostics.probeGpuAsync { gpu ->
            webServer.addLog(
                "DEV",
                "gpu probed=${gpu.probed} vendor=${gpu.vendor.ifBlank { "?" }} " +
                    "renderer=${gpu.renderer.ifBlank { "?" }} gl=${gpu.version.ifBlank { "?" }} es=${gpu.glEsVersion.ifBlank { "?" }}",
            )
        }
        val renderCompatNow = com.veacon.veilmusic.util.RenderCompat.isActive(context)
        val motionPref = prefs.getBoolean("motion_art", false)
        webServer.addLog(
            "RENDER",
            "compat=${com.veacon.veilmusic.util.RenderCompat.describe(context)} " +
                "reasons=${com.veacon.veilmusic.util.RenderCompat.riskReasons(
                    com.veacon.veilmusic.util.RenderDiagnostics.facts(context)).joinToString(",").ifEmpty { "none" }} " +
                // 下面两个字段是**实际生效**的渲染路径，不是偏好值：排查花屏时对齐的是它们。
                "rounded=${if (renderCompatNow) "canvas-clip" else "clip-layer"} " +
                "coverMotion=${if (motionPref && !renderCompatNow) "on" else "off"} " +
                "motionPref=${if (motionPref) "on" else "off"}",
        )
        // 歌词字形也可以在手机配置页改（写同一个 prefs）：跟着走，并让当前这首重取歌词。
        // 用 drop(1) 跳过 StateFlow 的当前值：构造期间不该触发任何重载（否则会碰到
        // 尚未初始化、声明在后面的歌词缓存字段）。
        viewModelScope.launch {
            lyricsScriptPrefs.script.drop(1).collect { script ->
                if (_state.value.lyricsScript != script) applyLyricsScript(script, reload = true)
            }
        }
        // 封面马赛克修复同样可以从手机配置页改（写同一个 prefs）：跟着走，让运行中的界面
        // 即时切换渲染路径。drop(1) 的用意同上：构造期间不触发任何状态写入。
        viewModelScope.launch {
            com.veacon.veilmusic.util.RenderCompat.revision.drop(1).collect {
                _state.update { s ->
                    s.copy(
                        renderCompat = com.veacon.veilmusic.util.RenderCompat.isActive(context),
                        renderCompatManual = com.veacon.veilmusic.util.RenderCompat.hasOverride(context),
                    )
                }
            }
        }
        // Offset can also be changed from the phone web server — follow it live.
        viewModelScope.launch {
            lyricsOffsetPrefs.offsetMs.collect { ms ->
                _state.update { it.copy(lyricsOffsetMs = ms) }
                updateOutputLatency()
            }
        }
        // Same for crossfade length. Takes effect at the next song boundary — an
        // in-flight fade keeps the length it started with.
        viewModelScope.launch {
            crossfadePrefs.durationMs.collect { ms ->
                val wasEnabled=com.veacon.veilmusic.data.CrossfadePreferences.isEnabled(_state.value.crossfadeEnabled,crossfadeDurationMs)
                crossfadeDurationMs=ms
                val enabled=com.veacon.veilmusic.data.CrossfadePreferences.isEnabled(_state.value.crossfadeEnabled,ms)
                if(wasEnabled!=enabled && !crossfadeInProgress) {
                    clearPreparedNext()
                    // A previous prefetch may already have finished while fading was enabled.
                    // Reuse the warmed cache to prepare the no-fade handoff after switching off.
                    val s=_state.value
                    val next=s.userQueue.firstOrNull() ?: s.queue.getOrNull(s.queueIndex+1)
                        ?: s.queue.firstOrNull().takeIf {s.repeatMode==RepeatMode.All && s.queue.size>1}
                    if(!enabled && s.currentSong!=null && next!=null) prefetchSong(next)
                }
            }
        }
    }

    fun setLyricsOffset(ms: Long) {
        lyricsOffsetPrefs.setOffset(ms)
        _state.update { it.copy(lyricsOffsetMs = ms) }
        updateOutputLatency()
    }


    fun setAvSyncAuto(auto: Boolean) {
        prefs.edit().putBoolean("av_auto", auto).apply()
        _state.update { it.copy(avSyncAuto = auto) }
        updateOutputLatency()
    }

    /**
     * Recompute the single output-latency figure that drives BOTH the beat visuals and the lyric clock:
     * the embedded [AV_BASELINE_MS] baseline, plus [AV_BT_ESTIMATE_MS] when a Bluetooth output is live
     * and Automatic mode is on, plus the user's [PlayerState.lyricsOffsetMs] extra. Called on init, on
     * every audio-device change, and when either the mode or the extra is edited.
     */
    private fun updateOutputLatency() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val btTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        val btDev = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in btTypes }
        }.getOrNull()
        val onBt = btDev != null
        val extra = _state.value.lyricsOffsetMs
        val auto = _state.value.avSyncAuto
        val btDelta = if (onBt && auto) BT_DELTA_MS else 0L
        // Beat has NO HDMI baseline (it's aligned already), it only gains the Bluetooth delta.
        val beatMs = btDelta + extra
        // Lyrics carry the hidden 200 ms display baseline on every output, plus the same BT delta.
        val lyricsMs = LYRICS_BASELINE_MS + btDelta + extra
        beatAnalyzer.latencyMs = beatMs
        val label = if (onBt) tr(R.string.msg_017) + (btDev?.productName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    else tr(R.string.msg_015)
        _state.update { it.copy(avOnBluetooth = onBt, avLyricsMs = lyricsMs, avOutputLabel = label) }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val pos = player.currentPosition
            val song = _state.value.currentSong?.title ?: "?"
            webServer.addLog("EXO", "isPlaying=$isPlaying pos=${pos}ms song=$song source=${player.currentMediaItem?.mediaId} gen=$attachedSourceGeneration/$playGen preparing=${sourcePreparation.isPending} cfade=$crossfadeInProgress")
            if (isPlaying) { enforceNormalSpeed("PLAY"); startWatchdog() } else stopWatchdog()
            _state.update { it.copy(isPlaying = isPlaying) }
        }
        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($state)"
            }
            val pos = player.currentPosition
            val dur = player.duration
            val song = _state.value.currentSong?.title ?: "?"
            webServer.addLog("EXO", "state=$name pos=${pos}ms dur=${dur}ms song=$song source=${player.currentMediaItem?.mediaId} gen=$attachedSourceGeneration/$playGen preparing=${sourcePreparation.isPending} cfade=$crossfadeInProgress")
            // One-line audio diagnostic per track: what actually got decoded, and by
            // which path. Guessing at codec/rate from the server side isn't possible.
            // Some encodes stream badly on the standalone path — the proxy's remux
            // repairs segment gaps that ExoPlayer just plays through. Rather than
            // guessing which tracks those are, watch for repeated rebuffering and
            // move that song to the proxy for good.
            val curSong = _state.value.queue.getOrNull(_state.value.queueIndex)
            if (state == Player.STATE_BUFFERING && usingStandalone && player.playWhenReady) {
                if (stutterSongId != curSong?.id) { stutterSongId = curSong?.id; stutterCount = 0 }
                stutterCount++
                // Only worth falling back to the proxy if the proxy is actually
                // reachable. With in-app decrypt the file plays fine and a brief
                // seek-induced rebuffer must NOT bounce us to a dead server.
                if (stutterCount >= STUTTER_LIMIT && curSong != null && serverPrefs.serverReachable &&
                    curSong.id !in proxyOnlySongIds && player.currentPosition > 3_000
                ) {
                    proxyOnlySongIds.add(curSong.id)
                    usingStandalone = false
                    val at = player.currentPosition
                    webServer.addLog("PLR", "standalone stuttering on ${curSong.title} " +
                        "($stutterCount rebuffers) — switching to proxy at ${at}ms")
                    player.setMediaItem(buildMediaItem(curSong, repo.streamUrl(curSong.id)), at)
                    player.prepare(); player.play()
                }
            }
            // 去重键带上解码方式：切硬解/软解后要再记一行，否则无法确认新解码路径真的在工作。
            val formatKey = "$song|${decoderMode.key}"
            if (state == Player.STATE_READY && lastFormatLoggedFor != formatKey) {
                lastFormatLoggedFor = formatKey
                enforceNormalSpeed("READY")
                val f = player.audioFormat
                webServer.addLog(
                    "FMT",
                    "codec=${f?.sampleMimeType ?: "?"} " +
                        "bitrate=${f?.bitrate ?: -1} rate=${f?.sampleRate ?: -1}Hz " +
                        "ch=${f?.channelCount ?: -1} enc=${f?.pcmEncoding ?: -1} " +
                        "dec=${decoderMode.key} speed=${player.playbackParameters.speed} " +
                        "path=${if (usingStandalone) "standalone" else "proxy"} " +
                        "buffered=${player.bufferedPercentage}%",
                )
            }
            if(state==Player.STATE_ENDED && !crossfadeInProgress &&
                preparedNext?.generation==playGen && preparedNext?.exo?.playbackState==Player.STATE_READY) {
                val generation=playGen
                viewModelScope.launch {
                    kotlinx.coroutines.yield()
                    if(generation==playGen && player.playbackState==Player.STATE_ENDED) advanceQueue()
                }
            }
            // Old player ended naturally during crossfade — snap cfExo in immediately
            if (state == Player.STATE_ENDED && crossfadeInProgress) {
                webServer.addLog("CFXO", "STATE_ENDED mid-fade cfExo=${crossfadeExo != null} cfExoState=${crossfadeExo?.playbackState} fadeJob=${fadeJob?.isActive}")
                try {
                    fadeJob?.cancel()
                    val cfExo = crossfadeExo
                    crossfadeExo = null
                    crossfadeInProgress = false
                    val oldP = player
                    oldP.removeListener(this)
                    oldP.volume = 0f; oldP.stop(); oldP.release()
                    if (cfExo != null && (cfExo.playbackState == Player.STATE_READY || cfExo.playbackState == Player.STATE_BUFFERING)) {
                        cfExo.volume = 1f
                        player = cfExo
                        promoteCrossfadeBeat()
                        cfExoErrListener?.let { cfExo.removeListener(it) }; cfExoErrListener = null
                        cfExo.addListener(this)
                        webServer.addLog("CFXO", "snap done cfExo.isPlaying=${cfExo.isPlaying} state=${cfExo.playbackState}")
                        val sNow = _state.value
                        val nextUp = sNow.userQueue.firstOrNull() ?: sNow.queue.getOrNull(sNow.queueIndex + 1)
                        if (nextUp != null && prefetchRequestedForSongId != nextUp.id) {
                            prefetchRequestedForSongId = nextUp.id; prefetchSong(nextUp)
                        }
                        _state.update { it.copy(isPlaying = cfExo.isPlaying) }
                        // Rebuilding the MediaSession reinits the audio output pipeline —
                        // defer it so it doesn't gap the audio right at the swap.
                        viewModelScope.launch {
                            delay(500)
                            mediaSession?.release()
                            mediaSession = buildMediaSession(player)
                        }
                    } else {
                        // cfExo bad or null — need a fresh player, then advance
                        webServer.addLog("CFXO", "snap: cfExo unusable (state=${cfExo?.playbackState}) — rebuilding player and advancing")
                        try { cfExo?.stop(); cfExo?.release() } catch (_: Exception) {}
                        player = buildExoPlayer().also { it.addListener(this) }
                        mediaSession?.release()
                        mediaSession = buildMediaSession(player)
                        advanceQueue()
                    }
                } catch (e: Exception) {
                    webServer.addLog("CFXO", "snap exception: ${e.message}")
                    crossfadeInProgress = false
                    advanceQueue()
                }
            }
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            // During crossfade, REPEAT_MODE_ONE is intentionally set on old player — don't reset it
            if (repeatMode != Player.REPEAT_MODE_OFF && !crossfadeInProgress) player.repeatMode = Player.REPEAT_MODE_OFF
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val pos = player.currentPosition
            // Preserve the user's play/pause intent across error recovery — a restored-paused track
            // that fails standalone and retries via proxy must NOT auto-start playing.
            val wasPlaying = player.playWhenReady
            val song = _state.value.currentSong?.title ?: "?"
            webServer.addLog("ERR", "${error.errorCodeName} pos=${pos}ms song=$song cfade=$crossfadeInProgress cause=${error.cause?.message}")
            reportPipelineFailure(error)
            val current=_state.value.currentSong
            // 落雪直链 TTL ≈21 分钟：过期/源端 403 时重解析一次并保持进度。
            val lxCurrent = _state.value.currentSong?.takeIf { it.id == lxSongId }
            if (lxCurrent != null && !crossfadeInProgress && lxRetriedSongId != lxCurrent.id) {
                lxRetriedSongId = lxCurrent.id
                lx.invalidate(lxCurrent.id)
                val myGen = ++playGen
                sourcePreparation.begin(myGen, wasPlaying)
                attachedSourceGeneration = -1
                webServer.addLog("LX", "重解析 ${lxCurrent.title}（${error.errorCodeName} @${pos}ms）")
                viewModelScope.launch {
                    val stream = lx.resolve(lxCurrent, force = true)
                    if (myGen != playGen) return@launch
                    val shouldPlay = sourcePreparation.finish(myGen) ?: return@launch
                    if (stream != null) {
                        attachedSourceGeneration = myGen
                        lxSongId = lxCurrent.id
                        player.setMediaItem(buildLxMediaItem(lxCurrent, stream), pos.coerceAtLeast(0L))
                        player.prepare(); player.volume = 1f; player.playWhenReady = shouldPlay
                    } else {
                        lxSongId = null
                        webServer.addLog("LX", "重解析失败 ${lxCurrent.title} — 跳过")
                        toast(tr(R.string.msg_019, song))
                        advanceQueue()
                    }
                }
                return
            }
            val isCurrentApi23Song = android.os.Build.VERSION.SDK_INT == 23 && usingStandalone &&
                current != null && !current.isMusicVideo && !crossfadeInProgress &&
                player.currentMediaItem?.mediaId == current.id && attachedSourceGeneration == playGen
            if (isCurrentApi23Song && api23SoftwareFallbackSong == current!!.id) {
                webServer.addLog("SOURCE", "api23 software fallback failed")
                stopFailedPreparation(current)
                return
            }
            val unsupportedDrm = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ||
                generateSequence(error as Throwable?) { it.cause }.take(12).any {
                    it is android.media.MediaDrmException && it.message?.contains("dummy ExoMediaDrm") == true
                }
            if (isCurrentApi23Song && unsupportedDrm && api23SoftwareFallbackSong == null) {
                val target = current!!
                api23SoftwareFallbackSong = target.id
                clearPreparedNext()
                val generation = ++playGen
                sourcePreparation.begin(generation, wasPlaying)
                attachedSourceGeneration = -1
                player.stop(); player.clearMediaItems()
                _state.update { it.copy(isLoading = wasPlaying, isPlaying = false) }
                webServer.addLog("SOURCE", "api23 system drm unavailable; trying software once")
                viewModelScope.launch {
                    val fallback = buildDecryptedStandaloneSource(target)
                    if (generation != playGen) return@launch
                    if (fallback == null) { stopFailedPreparation(target); return@launch }
                    val play = sourcePreparation.finish(generation) ?: return@launch
                    attachedSourceGeneration = generation
                    player.setMediaSource(fallback, pos.coerceAtLeast(0L)); player.prepare(); player.playWhenReady = play
                }
                return
            }
            if (current!=null && current.id in systemCacheSources && systemCacheRetryFor!=current.id &&
                player.currentMediaItem?.mediaId==current.id && !crossfadeInProgress) {
                systemCacheRetryFor=current.id
                systemCacheSources-=current.id
                systemAudio.invalidate(current.id)
                clearPreparedNext()
                systemAudio.cancelExcept(current.id)
                val generation=++playGen
                sourcePreparation.begin(generation,wasPlaying)
                attachedSourceGeneration=-1
                player.stop();player.clearMediaItems()
                _state.update {it.copy(isLoading=wasPlaying,isPlaying=false)}
                webServer.addLog("SOURCE","refresh cached source song=${current.id}")
                viewModelScope.launch {
                    val refreshed=buildSystemStandaloneSource(current)
                    if(generation!=playGen) return@launch
                    if(refreshed==null) {stopFailedPreparation(current);return@launch}
                    val play=sourcePreparation.finish(generation) ?: return@launch
                    attachedSourceGeneration=generation
                    player.setMediaSource(refreshed,pos.coerceAtLeast(0L));player.prepare();player.playWhenReady=play
                }
                return
            }
            // NOTE: each branch below owns its own toast — the generic "skipping" one lives on the
            // final give-up path so a single failure never fires two toasts (404 / proxy-retry did).
            val gone = error.errorCode ==
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                (error.cause?.message?.contains("404") == true)
            if (gone) {
                val cur = _state.value.queue.getOrNull(_state.value.queueIndex)
                if (cur != null) unavailableSongIds.add(cur.id)
                webServer.addLog("ERR", "unavailable (404) song=$song — skipping, won't retry")
                toast(tr(R.string.msg_019, song))
                advanceQueue()
                return
            }

            // Retry a stale release once, after validating a same-song catalog replacement.
            run {
                val cur = _state.value.queue.getOrNull(_state.value.queueIndex)
                if (usingStandalone && !crossfadeInProgress && cur != null &&
                    !cur.isMusicVideo && catalogRetriedSongId != cur.id) {
                    val myGen = ++playGen
                    sourcePreparation.begin(myGen, wasPlaying)
                    viewModelScope.launch {
                        val src = tryCatalogRecoverySource(cur, myGen)
                        if (myGen != playGen) return@launch
                        val shouldPlay = sourcePreparation.finish(myGen) ?: return@launch
                        if (src != null) {
                            usingStandalone = true
                            attachedSourceGeneration = myGen
                            player.setMediaSource(src, pos); player.prepare()
                            player.volume = 1f; player.playWhenReady = shouldPlay
                            _state.update { it.copy(standaloneActive = true) }
                        } else {
                            retryStandaloneOrProxy(cur, pos, shouldPlay, song)
                        }
                    }
                    return
                }
            }
            // A standalone failure must not advance — it'd fail identically on the next
            // song and rip through the whole queue. Retry this one through the proxy.
            if (usingStandalone && !crossfadeInProgress && standaloneFailedSongId != song) {
                val cur = _state.value.queue.getOrNull(_state.value.queueIndex)
                if (cur != null) {
                    retryStandaloneOrProxy(cur, pos, wasPlaying, song)
                    return
                }
            }
            if (crossfadeInProgress && crossfadeExo != null) {
                // Old player died mid-fade — snap cfExo to full volume immediately
                crossfadeExo!!.volume = 1f
                // cfExo is audible; keep UI showing playing so it doesn't flash paused
                _state.update { it.copy(isPlaying = true) }
            } else {
                // Final give-up: neither standalone retry nor a 404 owned this — say why we skipped.
                toast(if (serverPrefs.serverReachable) tr(R.string.msg_020, song)
                      else tr(R.string.msg_021))
                advanceQueue()
            }
        }
    }

    /** Last-resort fallback for a song that failed on-device (both its own and, for
     *  library ids, its catalog copy): route it through the PC proxy. No-op if there's
     *  no current queue item. */
    private fun retryStandaloneOrProxy(cur: Song, pos: Long, wasPlaying: Boolean, songTitle: String) {
        standaloneFailedSongId = songTitle
        usingStandalone = false
        standaloneFailures++
        _state.update { it.copy(standaloneActive = false) }
        if (!serverPrefs.serverReachable) {
            stopFailedPreparation(cur)
            return
        }
        webServer.addLog("PLR", "on-device exhausted for $songTitle — retrying via proxy (#$standaloneFailures)")
        if (standaloneFailures >= 3 && standalonePrefs.isEnabled()) {
            standalonePrefs.setEnabled(false)
            webServer.addLog("PLR", "standalone disabled after $standaloneFailures failures")
            toast(tr(R.string.msg_022))
        } else {
            toast(tr(R.string.msg_023))
        }
        attachedSourceGeneration = playGen
        player.setMediaItem(buildMediaItem(cur, repo.streamUrl(cur.id)))
        player.prepare()
        if (pos > 3_000) player.seekTo(pos)
        player.volume = 1f; player.playWhenReady = wasPlaying
    }

    private fun buildMediaSession(exo: ExoPlayer): androidx.media3.session.MediaSession =
        androidx.media3.session.MediaSession.Builder(context, exo)
            .setCallback(object : androidx.media3.session.MediaSession.Callback {
                override fun onConnect(
                    session: androidx.media3.session.MediaSession,
                    controller: androidx.media3.session.MediaSession.ControllerInfo,
                ): androidx.media3.session.MediaSession.ConnectionResult {
                    val pkg = controller.packageName
                    val allowed = pkg == context.packageName
                    return if (allowed) super.onConnect(session, controller)
                    else androidx.media3.session.MediaSession.ConnectionResult.reject()
                }
            }).build()

    init {
        // Apply the requested defaults once to existing installations as well as new ones.
        // Later user changes are preserved. Account, library and playback history are untouched.
        if (!prefs.getBoolean("lab3_defaults_applied", false)) {
            prefs.edit {
                putFloat("lyrics_scale", 1.0f)
                if (!prefs.contains("motion_art")) putBoolean("motion_art", false)
                putBoolean("lab3_defaults_applied", true)
            }
        }
        // Load the lightweight display settings up front so they apply even on a cold launch with
        // no track to restore (restoreState, which reads the rest, only runs when a song was saved).
        val initBg = NowPlayingBackground.fromName(prefs.getString("np_background", null))
        com.veacon.veilmusic.media.GainProcessor.enabled = prefs.getBoolean("volume_leveling", false)
        // Volume-leveling per-track gain memory. The cache is a tiny prefs store (one float per track id),
        // capped so it can't grow without bound. (The VOL live logger is wired in AppleMusicApp so it goes
        // to the APP log, not the network log.)
        run {
            val gainCache = context.getSharedPreferences("gain_cache", Context.MODE_PRIVATE)
            com.veacon.veilmusic.media.GainProcessor.cacheGet = { key -> if (gainCache.contains(key)) gainCache.getFloat(key, 1f) else null }
            com.veacon.veilmusic.media.GainProcessor.cachePut = { key, g ->
                if (gainCache.all.size > 500) gainCache.edit().clear().apply()
                gainCache.edit().putFloat(key, g).apply()
            }
        }
        // The 200 ms lyrics latency is now embedded (LYRICS_BASELINE_MS) and hidden, so the user-facing
        // "extra" starts at 0. Force it to 0 once so nobody carries a pre-existing manual offset on top
        // of the new baseline (which would double-count).
        if (!prefs.getBoolean("av_migrated_v2", false)) {
            lyricsOffsetPrefs.setOffset(0L)
            prefs.edit().putBoolean("av_migrated_v2", true).apply()
        }
        if(!prefs.getBoolean("unified_fluid_controls_v9",false)) {
            val quality=FluidQuality.fromName(prefs.getString("fluid_quality",null))
            val migrated=unifiedFluidQuality(quality,prefs.getBoolean("reduce_motion",false),prefs.getBoolean("low_power",false))
            prefs.edit {
                putString("fluid_quality",migrated.name)
                remove("reduce_motion");remove("low_power")
                putBoolean("unified_fluid_controls_v9",true)
            }
        }
        _state.update { it.copy(
            lyricsOffsetMs = lyricsOffsetPrefs.getOffset(),
            avSyncAuto = prefs.getBoolean("av_auto", false),
            nowPlayingBackground = initBg,
            fluidQuality = FluidQuality.fromName(prefs.getString("fluid_quality", null)),
            screensaverKeepBackground = prefs.getBoolean("screensaver_keep_bg", false),
            showNowPlayingInfo = prefs.getBoolean("np_info", true),
            motionArtworkEnabled = prefs.getBoolean("motion_art", false),
            lyricsScript = lyricsScriptPrefs.current(),
            renderCompat = com.veacon.veilmusic.util.RenderCompat.isActive(context),
            renderCompatManual = com.veacon.veilmusic.util.RenderCompat.hasOverride(context),
            navTitleAsSong = prefs.getBoolean("nav_title_song", false),
            miniCapsuleEnabled = prefs.getBoolean("mini_capsule", false),
            miniCapsuleHeightPct = prefs.getInt("mini_height_pct", 100).coerceIn(60, 140),
            miniCapsuleWidthPct = prefs.getInt("mini_width_pct", 100).coerceIn(60, 100),
            beatIntensity = prefs.getFloat(intensityKey(initBg), 1.0f),
            orbSpeed = prefs.getFloat("orb_speed", 1.0f),
            lyricsScale = prefs.getFloat("lyrics_scale", 1.0f),
            artworkRounded = prefs.getBoolean("artwork_rounded", true),
            reduceMotion = false,
            lowPowerMode = false,
            volumeLeveling = prefs.getBoolean("volume_leveling", false),
        ) }
        player.addListener(playerListener)
        mediaSession = buildMediaSession(player)
        // Confirm the bundled FFmpeg audio decoder loaded — when true, ExoPlayer
        // prefers it over MediaCodec (EXTENSION_RENDERER_MODE_PREFER) and HE-AAC
        // decodes like the proxy, killing the standalone chop.
        webServer.addLog("FFMPEG", "FfmpegLibrary.isAvailable=${androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable()}")
        // DIAGNOSTIC: capture ~25s of decoded PCM (44100Hz stereo s16le) from the
        // active player to inspect the standalone chop offline. Remove after repair.
        if (CAPTURE_PCM) try {
            val f = java.io.File(context.cacheDir, "pcmcap.raw")
            beatAnalyzer.startCapture(f.outputStream().buffered(), 44_100 * 2 * 2 * 25)
            webServer.addLog("CAP", "capturing PCM to ${f.absolutePath}")
        } catch (e: Exception) { webServer.addLog("CAP", "capture start failed: ${e.message}") }
        // EventLogger formats a string on every playback event for the life of the
        // player — pure debug overhead on an underpowered Fire TV. Gate it behind the
        // same probe flag the init-segment dump uses.
        if (PROBE_INIT_SEGMENT) player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())

        updateOutputLatency()
        // AudioManager is a system service and outlives this ViewModel, so hold the
        // callback and unregister it in onCleared — otherwise every future BT connect
        // fires into a dead instance.
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) { updateOutputLatency() }
            override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) { updateOutputLatency() }
        }
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .registerAudioDeviceCallback(audioDeviceCallback, null)

        viewModelScope.launch {
            repo.authIssue.collect { issue ->
                _state.update { it.copy(mutExpired = issue == com.veacon.veilmusic.data.auth.AuthorizationIssue.UserRejected) }
                when (issue) {
                    com.veacon.veilmusic.data.auth.AuthorizationIssue.UserRejected -> showAuthNotification()
                    com.veacon.veilmusic.data.auth.AuthorizationIssue.DeveloperUnavailable -> toast(tr(R.string.msg_024))
                    null -> Unit
                }
            }
        }
        pollProgress()
        // 恢复上次的书签（启动续播就靠这一步）。这里不再看引导状态：引导屏已经不在启动流程里，
        // 而真正的首次启动本来就没有书签可恢复，所以不需要额外的门。
        //
        // ⚠️ 必须比构造函数晚一拍：viewModelScope 用的是 Dispatchers.Main.immediate，
        // 在 init 里直接 launch 会当场同步执行——续播会立刻去重建播放器、取消解密任务，
        // 而那时本类后面声明的字段（OwnedTaskRegistry 等）还没初始化，会 NPE。
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) { restoreState() }
        checkServerReachable()
        viewModelScope.launch { checkAppleServiceStatus() }
    }

    fun onboardingCompleted(): Boolean = onboardingPrefs.completed

    /** Remote type chosen in setup — overrides hardware detection for the toggle button. */
    fun remoteOverride(): String = onboardingPrefs.remoteOverride
    fun setRemoteOverride(choice: String) { onboardingPrefs.remoteOverride = choice }
    fun webServerEnabled(): Boolean = webServer.isEnabled
    fun setWebServerEnabled(on: Boolean) { webServer.setEnabled(on) }

    /** Flips true when the user asks to replay setup, so the shell can show it without a relaunch. */
    private val _replayOnboarding = MutableStateFlow(false)
    val replayOnboarding: StateFlow<Boolean> = _replayOnboarding
    val authorizationRevision: StateFlow<Long> = mutPrefs.authorizationRevision

    /** Dev menu: replay setup now (and on next launch until finished). */
    fun resetOnboarding() {
        onboardingPrefs.reset()
        _replayOnboarding.value = true
    }

    /** Shell consumed the replay signal (setup is now on screen). */
    fun consumeReplayOnboarding() { _replayOnboarding.value = false }

    /** Setup just finished — safe to bring back whatever was playing before. */
    fun onOnboardingFinished() {
        // Setup may have just added the token; the proxy needs its own copy before
        // any library call.
        viewModelScope.launch {
            if (hasMUT()) runCatching { repo.syncMUTToServer(mutPrefs.getMUT()) }
            recheckServer().join()
            _state.update { it.copy(isFullStream = true) }
            restoreState()
        }
    }

    /** Only explicitly configured proxies are checked. No address means local mode. */
    fun recheckServer() = viewModelScope.launch {
        val address = serverPrefs.getPcServerIp()
        if (address.isEmpty()) {
            serverPrefs.serverReachable = false
            lastCheckedProxy = null
            proxyHadSuccessfulCheck = false
            return@launch
        }
        if (lastCheckedProxy != address) {
            lastCheckedProxy = address
            proxyHadSuccessfulCheck = false
        }
        val up = repo.pingServer()
        currentCoroutineContext().ensureActive()
        // A check for an old address must not change the newly selected mode.
        if (address != serverPrefs.getPcServerIp()) return@launch
        val wasUp = serverPrefs.serverReachable
        serverPrefs.serverReachable = up
        Log.i("PlayerVM", if (up) "Server reachable — proxy mode" else "Server DOWN — standalone mode")
        val announce = !standalonePrefs.isEnabled()
        if (!up) {
            if (wasUp && announce) toast(tr(R.string.msg_025))
            if (wasUp) repo.prepareStandalone()
        } else if (!wasUp) {
            usingStandalone = false
            if (!wasUp && proxyHadSuccessfulCheck && announce) toast(tr(R.string.msg_026))
            proxyHadSuccessfulCheck = true
        }
    }

    private fun checkServerReachable() = viewModelScope.launch {
        // Changing/clearing the address cancels its timer; an empty address has no loop.
        serverPrefs.proxyAddress.collectLatest { address ->
            if (address.isEmpty()) {
                serverPrefs.serverReachable = false
                lastCheckedProxy = null
                proxyHadSuccessfulCheck = false
                com.veacon.veilmusic.data.NetworkLog.line("Proxy", "not configured; local mode; health polling stopped")
                repo.prepareStandalone()
                return@collectLatest
            }
            recheckServer().join()
            if (!serverPrefs.serverReachable) repo.prepareStandalone()
            while (true) {
                delay(30_000)
                recheckServer().join()
            }
        }
    }

    /** Never route playback to the placeholder URL when a proxy hasn't been configured. */
    private fun useStandalone() = !serverPrefs.hasPcServer() || standalonePrefs.isEnabled() || !serverPrefs.serverReachable

    /**
     * [playingOverride] 记录的是「续播意图」：用户主动暂停传 false（下次只恢复、不自动播）；
     * 离开应用被动暂停时传暂停前的真实状态，这样下次启动仍能自动接着播。
     */
    private fun saveState(playingOverride: Boolean? = null) {
        val s = _state.value
        val song = s.currentSong ?: return
        // Preserve V12's crossfade and interrupted-source bookmark rules.
        if (crossfadeInProgress) return
        val savedPosition = pendingRestore?.posMs ?: player.currentPosition
        // Preferences remain synchronous and cannot be overwritten by an older IO bookmark.
        if (prefs.getFloat("beat_intensity", 1f) != s.beatIntensity ||
            prefs.getBoolean("crossfade_enabled", true) != s.crossfadeEnabled) {
            prefs.edit {
                putFloat("beat_intensity", s.beatIntensity)
                putBoolean("crossfade_enabled", s.crossfadeEnabled)
            }
        }
        bookmarkWriter.submit(PlaybackBookmark(song, s.queue, s.queueIndex, savedPosition, s.isFullStream, playingOverride ?: s.isPlaying))
    }


    /** Called by the activity when it enters/leaves Picture-in-Picture. */
    fun setPipMode(on: Boolean) { _state.update { it.copy(isInPip = on) } }

    private fun restoreState() = viewModelScope.launch {
        val songJson = prefs.getString("song", null) ?: return@launch
        if (hasPlayedSomething) return@launch
        try {
            val adapter = moshi.adapter(Song::class.java)
            val listType = Types.newParameterizedType(List::class.java, Song::class.java)
            val listAdapter = moshi.adapter<List<Song>>(listType)
            val savedSong = adapter.fromJson(songJson) ?: return@launch
            val savedQueue = listAdapter.fromJson(prefs.getString("queue", "[]") ?: "[]") ?: listOf(savedSong)
            val restored = VideoPlaybackPolicy.restored(savedSong, savedQueue, prefs.getInt("queue_index", 0))
            val song = restored.song
            val queue = restored.songs
            val idx = restored.index
            val posMs = if (song == savedSong) prefs.getLong("position_ms", 0L) else 0L
            val full  = true   // 落雪音源不需要登录态；试听已移除
            val crossfade = prefs.getBoolean("crossfade_enabled", true)
            val screensaverMin = prefs.getInt("screensaver_min", 10)
            val npBg = NowPlayingBackground.fromName(prefs.getString("np_background", null))
            val beat      = prefs.getFloat(intensityKey(npBg), 1.0f)
            val keepBg = prefs.getBoolean("screensaver_keep_bg", false)
            val npInfo = prefs.getBoolean("np_info", true)
            val motionArt = prefs.getBoolean("motion_art", false)
            com.veacon.veilmusic.media.GainProcessor.enabled = prefs.getBoolean("volume_leveling", false)
            _state.update { it.copy(currentSong = song, song = song, queue = queue, queueIndex = idx, isFullStream = full, beatIntensity = beat, crossfadeEnabled = crossfade, screensaverTimeoutMin = screensaverMin, nowPlayingBackground = npBg, screensaverKeepBackground = keepBg, showNowPlayingInfo = npInfo, motionArtworkEnabled = motionArt,
                orbSpeed = prefs.getFloat("orb_speed", 1.0f), lyricsScale = prefs.getFloat("lyrics_scale", 1.0f), artworkRounded = prefs.getBoolean("artwork_rounded", true), reduceMotion = false, lowPowerMode = false, volumeLeveling = prefs.getBoolean("volume_leveling", false), progressMs = posMs) }
            if (song == null) return@launch
            // A restored music video must go to the video player, NOT the audio stream — otherwise
            // it hits /api/stream, 404s ("No playable asset"), and gets skipped as if unavailable.
            if (song.isMusicVideo) {
                webServer.addLog("PLR", "restoreState idx=$idx VIDEO ${song.title}")
                player.pause()
                _videoRequest.value = VideoRequest(song, autoOpen = false, startPaused = true)
                return@launch
            }
            // 续播：上次退出时正在播放就直接从断点接着放；退出时是暂停的，就只恢复出来、等按下播放。
            // （以前这里一律延后加载，是为了躲开 Android 媒体恢复的自动开播；现在自动续播是明确需求，
            //   开播与否改由书签里的 was_playing 决定。）
            pendingRestore = RestoreInfo(song, posMs, full)
            // Lyrics/motion are just display — safe (and nice) to warm now.
            if (full) loadLyrics(song.id)
            loadMotion(song.id)
            if (prefs.getBoolean("was_playing", true)) {
                webServer.addLog("PLR", "restoreState idx=$idx posMs=$posMs song=${song.title} — 续播")
                startPendingRestore(autoPlay = true)
            } else {
                webServer.addLog("PLR", "restoreState idx=$idx posMs=$posMs song=${song.title} — deferred (paused)")
            }
        } catch (e: Exception) {
            // 这里静默吞异常会让「启动没恢复」毫无线索，至少留一行。
            webServer.addLog("PLR", "restoreState failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** A restored track not yet loaded into the player — loaded on the first play press, or straight
     *  away on launch when the previous session was playing (see restoreState). */
    private data class RestoreInfo(val song: Song, val posMs: Long, val full: Boolean)
    private var pendingRestore: RestoreInfo? = null

    /** [autoPlay] = false restores the track but stops at "ready to play" (used when the last
     *  session was paused, so a fresh launch doesn't start audio nobody asked for). */
    private fun startPendingRestore(autoPlay: Boolean = true) {
        val r = pendingRestore ?: return
        pendingRestore = null
        hasPlayedSomething = true
        val lxMode = r.full && lx.isEnabled()
        val standalone = !lxMode && r.full && useStandalone()
        usingStandalone = standalone
        failedPreparationSongId = null
        beginPlaybackSelection()
        val myGen = ++playGen
        sourcePreparation.begin(myGen, shouldPlay = autoPlay)
        attachedSourceGeneration = -1
        player.playWhenReady = false
        player.stop(); player.clearMediaItems()
        _state.update { it.copy(isLoading = true, isPlaying = false) }
        webServer.addLog("PLR", "startPendingRestore song=${r.song.title} pos=${r.posMs}ms autoPlay=$autoPlay lx=$lxMode standalone=$standalone")
        viewModelScope.launch {
            if (lxMode) {
                // 冷启动时音源脚本还在加载（十几秒），这里先等就绪再解析：解析内部虽然也会等，
                // 但那是阻塞等锁，会迟迟不返回。整段再套一个有界超时，卡住时明确退回「待播」状态，
                // 而不是静静地什么也不发生。
                webServer.addLog("LX", "等待音源就绪后解析 ${r.song.title}")
                val stream = if (autoPlay) {
                    kotlinx.coroutines.withTimeoutOrNull(LX_RESTORE_TIMEOUT_MS) {
                        lx.ensureReady()
                        lx.resolve(r.song)
                    }
                } else {
                    lx.ensureReady()
                    lx.resolve(r.song)
                }
                if (myGen != playGen) return@launch
                if (stream == null && autoPlay) {
                    // 超时/未就绪：保留待播状态，按一次播放键还能接着来。
                    pendingRestore = RestoreInfo(r.song, r.posMs, r.full)
                    sourcePreparation.cancel()
                    _state.update { it.copy(isLoading = false) }
                    webServer.addLog("LX", "启动续播未完成（${LX_RESTORE_TIMEOUT_MS}ms 内未取到直链）— 保留待播状态")
                    return@launch
                }
                if (stream != null) {
                    val play = sourcePreparation.finish(myGen) ?: return@launch
                    attachedSourceGeneration = myGen
                    lxSongId = r.song.id; lxRetriedSongId = null
                    webServer.addLog("LX", "恢复播放 ${r.song.title} · ${stream.kind} ${stream.kbps}kbps")
                    recordResolvedStream(r.song, stream)
                    player.setMediaItem(buildLxMediaItem(r.song, stream), r.posMs)
                    player.prepare(); player.playWhenReady = play
                    _state.update { it.copy(standaloneActive = false) }
                    return@launch
                }
                webServer.addLog("LX", "恢复未命中 ${r.song.title} — 无可用音源")
                stopFailedPreparation(r.song)
                return@launch
            }
            val src = if (standalone) buildStandaloneSource(r.song) ?:
                tryCatalogRecoverySource(r.song, myGen) else null
            if (myGen != playGen) return@launch
            if (src == null && !serverPrefs.serverReachable && (r.full || r.song.previewUrl == null)) {
                stopFailedPreparation(r.song)
                return@launch
            }
            _state.update { it.copy(standaloneActive = src != null) }
            val uri = if (r.full) repo.streamUrl(r.song.id) else (r.song.previewUrl ?: repo.streamUrl(r.song.id))
            val shouldPlay = sourcePreparation.finish(myGen) ?: return@launch
            attachedSourceGeneration = myGen
            if (src != null) player.setMediaSource(src, r.posMs) else player.setMediaItem(buildMediaItem(r.song, uri), r.posMs)
            player.prepare(); player.playWhenReady = shouldPlay
            val nextSong = _state.value.queue.getOrNull(_state.value.queueIndex + 1)
            if (r.full && !standalone && nextSong != null && prefetchRequestedForSongId != nextSong.id) {
                prefetchRequestedForSongId = nextSong.id
                prefetchSong(nextSong)
            }
        }
    }

    fun playFromQueue(idx: Int) = playQueueItem(idx)

    private fun advanceQueue() {
        val s = _state.value
        val pos = player.currentPosition
        webServer.addLog("ADV", "advanceQueue pos=${pos}ms from=${s.currentSong?.title} idx=${s.queueIndex}/${s.queue.size}")
        if (s.sleepAfterSong) { _state.update { it.copy(sleepAfterSong = false) }; player.stop(); webServer.addLog("SLEEP", "sleepAfterSong — stopped"); return }
        // Repeat one: replay current
        if (s.repeatMode == RepeatMode.One) { playQueueItem(s.queueIndex, skipFadeIn = true); return }

        // Drain user priority queue first (Play Next / Add to Queue songs)
        if (s.userQueue.isNotEmpty()) {
            val next = s.userQueue.first()
            val insertIdx = (s.queueIndex + 1).coerceIn(0, s.queue.size)
            val newQueue = s.queue.toMutableList().also { it.add(insertIdx, next) }
            _state.update { it.copy(queue = newQueue, userQueue = it.userQueue.drop(1)) }
            playQueueItem(insertIdx)
            return
        }

        // Shuffle already permuted the queue. Consume every entry exactly once.
        val nextIdx = s.queueIndex + 1

        webServer.addLog("PLR", "advance → idx=$nextIdx / ${s.queue.size}")
        if (nextIdx >= s.queue.size) {
            if (s.repeatMode == RepeatMode.All) { playQueueItem(0); return }
            // Queue exhausted — fetch related songs and continue
            val lastSong = s.queue.lastOrNull()
            if (lastSong != null && relatedRequestedGeneration != playGen) {
                relatedRequestedGeneration = playGen
                val request = playbackRequests.revision
                val generation = playGen
                relatedQueueJob = viewModelScope.launch {
                    val related = repo.getRelatedSongs(lastSong.id).getOrDefault(emptyList())
                    currentCoroutineContext().ensureActive()
                    if (!playbackRequests.isCurrent(request) || generation != playGen) return@launch
                    if (related.isNotEmpty()) {
                        relatedQueueJob = null
                        _state.update { it.copy(queue = it.queue + related) }
                        playQueueItem(nextIdx)
                    }
                }
            }
            return
        }
        playQueueItem(nextIdx)
    }

    fun toggleShuffle() {
        _state.update { s ->
            if (s.isShuffled) {
                // Turning off: restore original order, keep current song position
                val (restored, newIdx) = restoreQueueOrder(s.queue, s.originalQueue, s.queueIndex)
                s.copy(isShuffled = false, queue = restored, queueIndex = newIdx, originalQueue = emptyList())
            } else {
                // Turning on: save original, shuffle remaining
                val split = (s.queueIndex + 1).coerceIn(0, s.queue.size)
                val before = s.queue.take(split)
                val after = s.queue.drop(split).shuffled()
                s.copy(isShuffled = true, originalQueue = s.queue, queue = before + after)
            }
        }
    }
    fun cyclePlaybackOrder() {
        _state.update { it.nextPlaybackOrder() }
    }
    fun toggleRepeat() {
        _state.update { it.copy(repeatMode = when (it.repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        })}
    }
    fun setSleepTimer(minutes: Int) { _state.update { it.copy(sleepTimerEndsAt = System.currentTimeMillis() + minutes * 60_000L, sleepAfterSong = false) } }
    fun setSleepAfterSong() { _state.update { it.copy(sleepAfterSong = true, sleepTimerEndsAt = null) } }
    fun cancelSleepTimer() { _state.update { it.copy(sleepTimerEndsAt = null, sleepAfterSong = false) } }
    fun dismissMutExpired() { _state.update { it.copy(mutExpired = false) } }
    // Calm / Normal / Strong / Crazy — the multiplier applied to beat energy AND the orb bands, so it
    // drives how hard both Dynamic and Projector react.
    private val intensitySteps = floatArrayOf(0.55f, 1.0f, 2.0f, 3.5f)

    fun cycleBeatIntensity() = stepBeatIntensity(1)

    fun stepBeatIntensity(dir: Int) {
        val cur = intensitySteps.indexOfFirst { kotlin.math.abs(it - _state.value.beatIntensity) < 0.05f }.let { if (it < 0) 1 else it }
        val next = intensitySteps[(cur + dir).coerceIn(0, intensitySteps.lastIndex)]
        _state.update { it.copy(beatIntensity = next) }
        // Remembered per background mode (Dynamic vs Projector).
        prefs.edit { putFloat(intensityKey(_state.value.nowPlayingBackground), next) }
    }

    fun toggleCrossfade() {
        val next = !_state.value.crossfadeEnabled
        _state.update { it.copy(crossfadeEnabled = next) }
        prefs.edit { putBoolean("crossfade_enabled", next) }
    }

    /**
     * Hard stop for a real app exit (the "退出" confirmation). `moveTaskToBack`/`finish` alone leave
     * this ViewModel — and its ExoPlayer — alive in the background, so the audio kept playing; stop
     * the output explicitly so exiting actually stops the music.
     */
    fun stopPlayback() {
        // A window/posture change can interrupt source preparation or a two-player fade.
        // Keep the displayed track's bookmark before invalidating those asynchronous owners.
        val needsSource = sourcePreparation.isPending || crossfadeInProgress
        val position = if (pendingRestore != null) pendingRestore!!.posMs
            else if (crossfadeInProgress) crossfadeExo?.currentPosition ?: 0L
            else if (sourcePreparation.isPending) _state.value.progressMs else player.currentPosition
        if (needsSource) {
            _state.value.currentSong?.takeUnless { it.isMusicVideo }?.let {
                pendingRestore = RestoreInfo(it, position.coerceAtLeast(0L), _state.value.isFullStream)
            }
        }
        pause()
        beginPlaybackSelection()
        ++playGen
        sourcePreparation.cancel()
        awaitingSongStart = null
        fadeJob?.cancel(); fadeJob = null
        crossfadeInProgress = false
        crossfadeExo?.let { cf ->
            cfExoErrListener?.let { cf.removeListener(it) }
            cf.stop(); cf.release()
        }
        crossfadeExo = null; cfExoErrListener = null
        player.volume = 1f; player.repeatMode = Player.REPEAT_MODE_OFF
        player.stop()
        if (needsSource) player.clearMediaItems()
        _state.update { it.copy(isPlaying = false, isLoading = false, progressMs = position.coerceAtLeast(0L)) }
        saveState()
        beatAnalyzer.resetBeat()
    }

    /** Cycle the Now Playing backdrop: Dynamic → Projector → Black → Dynamic (dir = +1 / -1). */
    /** Intensity is remembered PER background mode (Dynamic vs Projector); Black has no orbs. */
    private fun intensityKey(mode: NowPlayingBackground): String =
        if (mode == NowPlayingBackground.PROJECTOR) "intensity_projector" else "intensity_dynamic"

    fun stepFluidQuality(dir: Int) {
        val qualities = FluidQuality.entries
        val next = qualities[(_state.value.fluidQuality.ordinal + dir).coerceIn(0, qualities.lastIndex)]
        _state.update { it.copy(fluidQuality = next) }
        prefs.edit { putString("fluid_quality", next.name) }
    }

    fun stepNowPlayingBackground(dir: Int) {
        val modes = NowPlayingBackground.entries
        val cur = _state.value.nowPlayingBackground.ordinal
        val next = modes[((cur + dir) % modes.size + modes.size) % modes.size]
        // Swap in the intensity remembered for the mode we're switching to.
        val nextIntensity = prefs.getFloat(intensityKey(next), 1.0f)
        _state.update { it.copy(nowPlayingBackground = next, beatIntensity = nextIntensity) }
        prefs.edit { putString("np_background", next.name) }
    }

    fun stepOrbSpeed(dir: Int) {
        val steps = floatArrayOf(0.6f, 1.0f, 1.6f)
        val cur = steps.indexOfFirst { kotlin.math.abs(it - _state.value.orbSpeed) < 0.05f }.let { if (it < 0) 1 else it }
        val next = steps[(cur + dir).coerceIn(0, steps.lastIndex)]
        _state.update { it.copy(orbSpeed = next) }
        prefs.edit { putFloat("orb_speed", next) }
    }

    fun stepLyricsScale(dir: Int) {
        val steps = floatArrayOf(0.8f, 1.0f, 1.25f, 1.6f, 2.0f)
        val cur = steps.indexOfFirst { kotlin.math.abs(it - _state.value.lyricsScale) < 0.05f }.let { if (it < 0) 2 else it }
        val next = steps[(cur + dir).coerceIn(0, steps.lastIndex)]
        _state.update { it.copy(lyricsScale = next) }
        prefs.edit { putFloat("lyrics_scale", next) }
    }

    fun toggleArtworkRounded() {
        val next = !_state.value.artworkRounded
        _state.update { it.copy(artworkRounded = next) }
        prefs.edit { putBoolean("artwork_rounded", next) }
    }

    /**
     * 歌词字形（简/繁）切换。缓存里存的是转换前的原文，字形变了必须连同
     * [com.veacon.veilmusic.data.datasource.DirectLyricsSource] 的缓存一起作废，
     * 然后把当前这首重取一遍，否则界面还是旧字形。
     */
    fun setLyricsScript(script: com.veacon.veilmusic.data.LyricsScript) {
        lyricsScriptPrefs.setScript(script)
    }

    private fun applyLyricsScript(script: com.veacon.veilmusic.data.LyricsScript, reload: Boolean) {
        _state.update { it.copy(lyricsScript = script) }
        lyricsCache.clear()
        lyricsSourceCache.clear()
        lyricsExcludedSources.clear()
        repo.invalidateLyricsCacheAll()
        webServer.addLog("LYRICS", "script=${script.name.lowercase()} — lyrics cache invalidated, refetching")
        if (reload) _state.value.currentSong?.let { if (!it.isMusicVideo) loadLyrics(it.id) }
    }

    /**
     * 封面马赛克修复开关（实验室功能，默认按机型自动判定）。
     *
     * 开启后：封面圆角走画布裁剪，不再为封面开离屏图层（动态封面照常叠加）。
     * 关闭后代码路径与 2.2.1 完全一致，正常机型不受任何影响。
     */
    fun setRenderCompat(enabled: Boolean) {
        com.veacon.veilmusic.util.RenderCompat.setEnabled(context, enabled)
        _state.update { it.copy(renderCompat = enabled, renderCompatManual = true) }
        webServer.addLog("RENDER", "compat=${com.veacon.veilmusic.util.RenderCompat.describe(context)} active=$enabled")
    }

    /** 顶部交互栏用当前歌名替换「正在播放」四个字（实验室功能）。 */
    fun setNavTitleAsSong(enabled: Boolean) {
        prefs.edit().putBoolean("nav_title_song", enabled).apply()
        _state.update { it.copy(navTitleAsSong = enabled) }
    }

    /** 实验室：底部迷你播放胶囊开关。 */
    fun setMiniCapsuleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("mini_capsule", enabled).apply()
        _state.update { it.copy(miniCapsuleEnabled = enabled) }
    }

    /**
     * 迷你胶囊高度（百分比）。整条胶囊连同图标、封面、文字一起等比缩放 ——
     * 只把外框改高不改内容，60% 时会露出一圈空白边。
     */
    fun setMiniCapsuleHeightPct(pct: Int) {
        val clamped = pct.coerceIn(60, 140)
        prefs.edit().putInt("mini_height_pct", clamped).apply()
        _state.update { it.copy(miniCapsuleHeightPct = clamped) }
    }
    fun nudgeMiniCapsuleHeightPct(delta: Int) =
        setMiniCapsuleHeightPct(_state.value.miniCapsuleHeightPct + delta)

    /** 迷你胶囊宽度（百分比，相对屏宽 94% 基准）。只改长度，不改内容大小。 */
    fun setMiniCapsuleWidthPct(pct: Int) {
        val clamped = pct.coerceIn(60, 100)
        prefs.edit().putInt("mini_width_pct", clamped).apply()
        _state.update { it.copy(miniCapsuleWidthPct = clamped) }
    }
    fun nudgeMiniCapsuleWidthPct(delta: Int) =
        setMiniCapsuleWidthPct(_state.value.miniCapsuleWidthPct + delta)

    fun toggleVolumeLeveling() {
        val next = !_state.value.volumeLeveling
        com.veacon.veilmusic.media.GainProcessor.enabled = next
        _state.update { it.copy(volumeLeveling = next) }
        prefs.edit { putBoolean("volume_leveling", next) }
    }

    /**
     * 硬解 / 软解 switch. The decoder is chosen when a player is built, so the current one is
     * rebuilt in place: position and play/pause intent are captured first, the fresh player is
     * swapped in, and the same track is re-prepared at the same offset on the new decoder path.
     * That makes the switch observable immediately instead of "from the next song".
     */
    fun setDecoderMode(mode: com.veacon.veilmusic.data.DecoderMode) {
        if (mode == decoderMode) return
        decoderMode = mode
        prefs.edit { putString("decoder_mode", mode.key) }
        _state.update { it.copy(decoderMode = mode) }
        webServer.addLog("DEC", "decoder=${mode.key} (${if (mode == com.veacon.veilmusic.data.DecoderMode.HARDWARE) "硬解/MediaCodec" else "软解/FFmpeg"})")
        rebuildPlayerKeepingPosition("decoder=${mode.key}")
    }

    /**
     * 换输出形态（解码器 / 统一采样率 / offload）后必须整只播放器重建 —— 这三样都在
     * `buildExoPlayer` 里落定。当前曲目在同一定位重新起播，切换对用户是"立刻生效"。
     */
    private fun rebuildPlayerKeepingPosition(reason: String) {
        val resumable = _state.value.currentSong
            ?.takeIf { !it.isMusicVideo && player.playbackState != Player.STATE_IDLE }
        val position = if (resumable != null) pendingRestore?.posMs ?: player.currentPosition.coerceAtLeast(0L) else 0L
        val wasPlaying = _state.value.isPlaying
        crossfadeExo?.let { cf -> try { cf.stop(); cf.release() } catch (_: Exception) {} }
        crossfadeExo = null; crossfadeInProgress = false; fadeJob?.cancel(); fadeJob = null
        clearPreparedNext()
        swapPlayer { buildExoPlayer() }
        if (resumable != null) {
            webServer.addLog("OUT", "re-preparing ${resumable.title} at ${position}ms ($reason)")
            pendingRestore = RestoreInfo(resumable, position, _state.value.isFullStream)
            startPendingRestore(autoPlay = wasPlaying)
        }
    }

    /** 统一输出采样率：开启后 44.1k/48k 混排不再反复重建 AudioTrack。 */
    fun setUnifySampleRate(on: Boolean) {
        if (on == unifySampleRate) return
        unifySampleRate = on
        prefs.edit { putBoolean("unify_sample_rate", on) }
        _state.update { it.copy(unifySampleRate = on) }
        webServer.addLog("OUT", com.veacon.veilmusic.media.OutputTuning.summary(on, offloadEnabled, outputSampleRate))
        rebuildPlayerKeepingPosition("unify=${on}")
    }

    /** 音频 offload：解码交给 DSP。设备探测不到支持时这里不会有任何效果。 */
    fun setAudioOffload(on: Boolean) {
        if (on == offloadEnabled) return
        if (on && !com.veacon.veilmusic.media.OutputTuning.offloadSupported()) return
        offloadEnabled = on
        prefs.edit { putBoolean("audio_offload", on) }
        _state.update { it.copy(audioOffload = on) }
        webServer.addLog("OUT", com.veacon.veilmusic.media.OutputTuning.summary(unifySampleRate, on, outputSampleRate))
        rebuildPlayerKeepingPosition("offload=$on")
    }

    /** 设置页据此决定是否显示 offload 开关。 */
    fun audioOffloadSupported(): Boolean = com.veacon.veilmusic.media.OutputTuning.offloadSupported()

    fun toggleScreensaverKeepBackground() {
        val next = !_state.value.screensaverKeepBackground
        _state.update { it.copy(screensaverKeepBackground = next) }
        prefs.edit { putBoolean("screensaver_keep_bg", next) }
    }

    fun toggleNowPlayingInfo() {
        val next = !_state.value.showNowPlayingInfo
        _state.update { it.copy(showNowPlayingInfo = next) }
        prefs.edit { putBoolean("np_info", next) }
    }

    fun toggleMotionArtwork() {
        val next = !_state.value.motionArtworkEnabled
        _state.update { it.copy(motionArtworkEnabled = next, motionUrl = if (next) it.motionUrl else null) }
        prefs.edit { putBoolean("motion_art", next) }
        // Fetch it now if we just turned it on mid-song; clearing above handles turning it off.
        if (next) _state.value.currentSong?.let { loadMotion(it.id) }
    }

    private val screensaverSteps = listOf(0, 1, 2, 5, 10, 20, 30, 60, 120)

    /** Cycle the screensaver idle timeout: Off → 1 → 2 → 5 → 10 → 20 → 30 → Off. */
    fun cycleScreensaverTimeout() = stepScreensaverTimeout(1)

    /** Step the screensaver idle timeout through [screensaverSteps] (dir = +1 / -1). */
    fun stepScreensaverTimeout(dir: Int) {
        val cur = screensaverSteps.indexOf(_state.value.screensaverTimeoutMin).coerceAtLeast(0)
        val next = screensaverSteps[(cur + dir).coerceIn(0, screensaverSteps.lastIndex)]
        _state.update { it.copy(screensaverTimeoutMin = next) }
        prefs.edit { putInt("screensaver_min", next) }
    }

    // Set on every track change; holds the UI clock at 0:00 until the NEW item is actually
    // playing, so the poll loop can't push the outgoing song's elapsed/duration through the
    // window while the standalone source is still building.
    @Volatile private var awaitingSongStart: String? = null
    private var failedPreparationSongId: String? = null

    private fun stopFailedPreparation(song: Song) {
        failedPreparationSongId = song.id
        sourcePreparation.cancel()
        attachedSourceGeneration = -1
        awaitingSongStart = null
        usingStandalone = false
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        _state.update { it.copy(isLoading = false, isPlaying = false, standaloneActive = false) }
        webServer.addLog("PLR", "source preparation failed for ${song.title}; no reachable configured proxy; stopped waiting")
        toast(tr(R.string.msg_027, song.title))
    }

    private fun playQueueItem(idx: Int, skipFadeIn: Boolean = false, userOpened: Boolean = false) {
        pendingRestore = null   // any real queue action supersedes a deferred restore
        val q = _state.value.queue
        if (q.isEmpty() || idx !in q.indices) {
            webServer.addLog("PLR", "playQueueItem idx=$idx out of bounds (size=${q.size}) — stopping")
            return
        }
        val prepared=takePreparedNext(q[idx].id)
        beginPlaybackSelection(keepDecryptFor = q[idx].id)
        val myGen = ++playGen
        sourcePreparation.cancel()
        attachedSourceGeneration = -1
        prefetchJob?.cancel()
        relatedQueueJob?.cancel(); relatedQueueJob = null
        beatAnalyzer.resetBeat(); mainProc?.resetBeat()
        crossfadeSkipSongId = null
        systemCacheRetryFor = null
        api23SoftwareFallbackSong = null
        catalogRetriedSongId = null   // fresh selection → allow a new catalog on-device retry
        // Cancel any in-progress crossfade
        fadeJob?.cancel()
        crossfadeInProgress = false
        crossfadeExo?.let { cf ->
            cfExoErrListener?.let { cf.removeListener(it) }
            cf.stop(); cf.release()
        }
        crossfadeExo = null; cfExoErrListener = null

        val song = q[idx]
        if (!VideoPlaybackPolicy.allows(song)) return
        failedPreparationSongId = null
        if (song.id in unavailableSongIds) {
            prepared?.exo?.release()
            webServer.addLog("PLR", "skipping known-unavailable ${song.title}")
            if (idx + 1 < q.size) playQueueItem(idx + 1, skipFadeIn) 
            return
        }
        // Music video in the queue → hand it to the video player; the audio engine idles.
        if (song.isMusicVideo) {
            prepared?.exo?.release()
            webServer.addLog("PLR", "playQueueItem idx=$idx VIDEO ${song.title}")
            player.stop(); player.clearMediaItems()
            awaitingSongStart = null
            _state.update { it.copy(currentSong = song, song = song, queueIndex = idx, lyrics = emptyList(), motionUrl = null, progressMs = 0L) }
            saveState()
            _videoRequest.value = VideoRequest(song, autoOpen = userOpened)
            return
        }
        _videoRequest.value = null   // audio item → dismiss any active video

        val full = _state.value.isFullStream
        // 落雪优先：只要命中就用直链播放，Apple DRM/代理只作兜底。
        val lxMode = lxEligible(song, full)
        val standalone = !lxMode && full && useStandalone() && (song.id !in proxyOnlySongIds || !serverPrefs.serverReachable)
        lxSongId = null; lxRetriedSongId = null
        val uri = if (full) repo.streamUrl(song.id) else (song.previewUrl ?: repo.streamUrl(song.id))
        webServer.addLog("PLR", "playQueueItem idx=$idx song=${song.title}${if (standalone) " [standalone]" else ""}")
        awaitingSongStart = song.id
        sourcePreparation.begin(myGen)
        com.veacon.veilmusic.media.GainProcessor.currentTrackKey = song.id   // per-track volume-leveling memory
        _state.update { it.copy(currentSong = song, song = song, queueIndex = idx, lyrics = lyricsCache[song.id] ?: emptyList(), motionUrl = null, progressMs = 0L, isLoading = true, isPlaying = false) }
        saveState()
        if (!full && song.previewUrl == null && !serverPrefs.serverReachable) {
            prepared?.exo?.release()
            stopFailedPreparation(song)
            return
        }
        if (!standalone) prepared?.exo?.release()
        player.repeatMode = Player.REPEAT_MODE_OFF
        // Remove the outgoing item, not just its play intent: no controller can resume it
        // underneath the new title while the new source is still being prepared.
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        fun startPlayback(shouldPlay: Boolean) {
            player.volume = 1f
            player.playWhenReady = shouldPlay
        }
        if (prepared!=null && standalone) {
            val old=player
            old.removeListener(playerListener)
            mediaSession?.release();mediaSession=null
            old.release()
            player=prepared.exo
            prepared.processor?.let {mainProc=it;beatAnalyzer.activate(it)}
            player.addListener(playerListener)
            val shouldPlay=sourcePreparation.finish(myGen) ?: false
            attachedSourceGeneration=myGen;awaitingSongStart=null;usingStandalone=true
            mediaSession=buildMediaSession(player)
            player.playWhenReady=shouldPlay
            _state.update {it.copy(isLoading=false,isPlaying=player.isPlaying,standaloneActive=true)}
            webServer.addLog("SOURCE","prepared handoff song=${song.id} gen=$myGen")
        } else if (standalone) {
            prepared?.exo?.release()
            usingStandalone = true
            viewModelScope.launch {
                val original = buildStandaloneSource(song)
                if (myGen != playGen) return@launch
                val src = original ?: tryCatalogRecoverySource(song, myGen)
                // Superseded by a newer selection while we were decrypting — drop it so
                // the skipped-over song never briefly plays.
                if (myGen != playGen) return@launch
                if (src == null && !serverPrefs.serverReachable) {
                    stopFailedPreparation(song)
                    return@launch
                }
                val shouldPlay = sourcePreparation.finish(myGen) ?: return@launch
                attachedSourceGeneration = myGen
                webServer.addLog("PLR", "attach source song=${song.title} id=${song.id} gen=$myGen play=$shouldPlay")
                if (src != null) player.setMediaSource(src)
                else player.setMediaItem(buildMediaItem(song, uri))
                player.prepare()
                if (src != null) standaloneFailures = 0
                _state.update { it.copy(standaloneActive = src != null) }
                startPlayback(shouldPlay)
            }
        } else {
            usingStandalone = false
            _state.update { it.copy(standaloneActive = false) }
            // 这里既覆盖"配了代理"又覆盖"落雪接管"：先解析直链，未命中再降级。
            viewModelScope.launch {
                val stream = if (lxMode) lx.resolve(song) else null
                if (myGen != playGen) return@launch
                if (stream != null) {
                    val shouldPlay = sourcePreparation.finish(myGen) ?: return@launch
                    attachedSourceGeneration = myGen
                    lxSongId = song.id; lxRetriedSongId = null
                    webServer.addLog("LX", "播放 ${song.title} · ${stream.kind} ${stream.kbps}kbps · ${stream.source}/${stream.quality} · ${stream.resolveMs}ms")
                    recordResolvedStream(song, stream)
                    player.setMediaItem(buildLxMediaItem(song, stream))
                    player.prepare()
                    startPlayback(shouldPlay)
                    return@launch
                }
                if (lxMode) {
                    // 落雪是唯一音源：未命中就到此为止，不再回退 Apple 试听/DRM。
                    // 试听片段（previewUrl）与 DRM 都需要登录态，本 app 已不再使用。
                    webServer.addLog("LX", "未命中 ${song.title} — 无可用音源，跳过")
                    stopFailedPreparation(song)
                    return@launch
                }
                // 落雪被用户关闭时才走原有代理/DRM 路径。
                val shouldPlay = sourcePreparation.finish(myGen) ?: return@launch
                attachedSourceGeneration = myGen
                player.setMediaItem(buildMediaItem(song, uri))
                player.prepare()
                startPlayback(shouldPlay)
            }
        }
        prefetchRequestedForSongId = null
        if (full) loadLyrics(song.id)
        loadMotion(song.id)
        if (song.artistId == null || song.albumId == null) enrichSongIds(song.id)
        // Prefetch N+1 immediately so it's cached well before crossfade. Under Repeat
        // All the last track's "next" is index 0, so warm that instead of nothing.
        val nextSong = (_state.value.userQueue.firstOrNull()
            ?: q.getOrNull(idx + 1)
            ?: q.firstOrNull().takeIf { _state.value.repeatMode == RepeatMode.All && q.size > 1 })
        // Wait for THIS song to be playable before warming the next one. Firing both at
        // once splits the WAN download and the song you're waiting on lands last — a
        // skip used to queue up four concurrent decrypts and take 30s+. Cancelled on
        // the next playQueueItem, so hammering skip only ever warms the song you land on.
        // Persist the song change right away rather than waiting up to 10s for the
        // ticker — that window is what let a restore come back on a previous track.
        lastAutoSaveMs = System.currentTimeMillis()
        saveState()
        prefetchJob?.cancel()
        // Prefetch N+1 in BOTH modes. Standalone was excluded before, which is why the
        // crossfade partner was always cold and crossfade/skip felt broken — now
        // prefetchSong warms the in-app decrypt cache for the next song.
        if (full && nextSong != null) {
            prefetchLyrics(nextSong)   // warm lyrics now — cheap, independent of the audio decrypt
            prefetchJob = viewModelScope.launch {
                val deadline = System.currentTimeMillis() + 60_000
                while (myGen == playGen && !currentSourceReady(myGen) &&
                       System.currentTimeMillis() < deadline) {
                    delay(300)
                }
                if (!currentSourceReady(myGen)) {
                    webServer.addLog("PRE", "prefetch N+1 abandoned — ${song.title} not current/ready gen=$myGen/$playGen")
                    return@launch
                }
                prefetchRequestedForSongId = nextSong.id
                webServer.addLog("PRE", "prefetch N+1 song=${nextSong.title}")
                prefetchSong(nextSong)
            }
        } else {
            webServer.addLog("PRE", "prefetch N+1 skip — full=$full nextSong=${nextSong?.title}")
        }
    }

    fun playSong(album: Album) {
        if (!VideoPlaybackPolicy.allows(album.id, album.type)) return
        val song = Song(id = album.id, title = album.title, artistName = album.artistName,
            artworkUrl = album.artworkUrl, artworkBgColor = album.artworkBgColor,
            durationMs = 0, albumName = "", previewUrl = null)
        playSong(song)
    }

    fun playSong(song: Song, useFullStream: Boolean = true) {
        if (!VideoPlaybackPolicy.allows(song)) return
        _userPlaybackRequests.value++
        usingStandalone = false
        lastErrorKey = null
        hasPlayedSomething = true
        _state.update { it.copy(currentSong = song, song = song, queue = listOf(song), queueIndex = 0, lyrics = emptyList(), isFullStream = useFullStream, motionUrl = null) }
        playQueueItem(0, userOpened = true)
    }

    /** Play a shelf of video DTOs (music-videos / interviews) in the video player. */
    fun playVideos(dtos: List<com.veacon.veilmusic.data.network.SongDto>, startIndex: Int = 0) {
        if (!VideoPlaybackPolicy.ENABLED) return
        playAlbum(dtos.map(repo::songFromDto), startIndex)
    }

    fun playAlbum(inputSongs: List<Song>, startIndex: Int = 0, useFullStream: Boolean = true, shuffle: Boolean = false, openPlayer: Boolean = true) {
        val selection = VideoPlaybackPolicy.selection(inputSongs, startIndex)
        val songs = selection.songs
        if (songs.isEmpty()) return
        if (openPlayer) _userPlaybackRequests.value++
        val stack = Thread.currentThread().stackTrace
        val callers = (3..7).mapNotNull { stack.getOrNull(it) }.joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        webServer.addLog("PLR", "playAlbum size=${songs.size} idx=$startIndex shuffle=$shuffle << $callers")
        usingStandalone = false
        lastErrorKey = null
        hasPlayedSomething = true
        val idx = selection.index
        val queue = if (shuffle) {
            val first = songs[idx]
            listOf(first) + songs.filterIndexed { i, _ -> i != idx }.shuffled()
        } else songs
        val queueIdx = if (shuffle) 0 else idx
        _state.update { it.copy(queue = queue, isFullStream = useFullStream, isShuffled = shuffle, originalQueue = if (shuffle) songs else emptyList()) }
        // User explicitly started this list → a video here should open fullscreen Now Playing.
        playQueueItem(queueIdx, userOpened = openPlayer)
    }

    fun shufflePlayPlaylist(playlistId: String): kotlinx.coroutines.Job {
        val request = beginPlaybackSelection()
        return viewModelScope.launch {
            val tracks = repo.getPlaylistTracks(playlistId).getOrDefault(emptyList())
            currentCoroutineContext().ensureActive()
            if (playbackRequests.isCurrent(request) && tracks.isNotEmpty())
                playAlbum(tracks, startIndex = tracks.indices.random(), shuffle = true)
        }
    }

    /** Temp: dump a personalized ra.* station's payload to logcat (tag StationProbe). */
    fun probeStation(id: String) = viewModelScope.launch { repo.probeStation(id) }

    // ── Library writes ──────────────────────────────────────────────────
    /** 收藏到本地资料库（不再写 Apple library）。 */
    fun addToLibrary(song: com.veacon.veilmusic.data.model.Song) {
        val added = localLibrary.toggleFavorite(song)
        toast(if (added) "已收藏「${song.title}」" else "已取消收藏「${song.title}」")
    }

    fun isFavorite(songId: String): Boolean = localLibrary.isFavorite(songId)

    /** 可追加歌曲的本地歌单（供「添加到播放列表」选择器）。 */
    suspend fun editablePlaylists() = localLibrary.playlists.value

    fun addToPlaylist(playlistId: String, playlistName: String, song: com.veacon.veilmusic.data.model.Song) {
        localLibrary.addSongToPlaylist(playlistId, song)
        toast("已加入「$playlistName」")
    }

    /** One metadata recovery per selection; stale network results cannot attach after a song change. */
    private suspend fun tryCatalogRecoverySource(song: Song, generation: Int): androidx.media3.exoplayer.source.MediaSource? {
        if (generation != playGen || song.isMusicVideo || catalogRetriedSongId == song.id) return null
        catalogRetriedSongId = song.id
        webServer.addLog("PLR", "recover catalog release for ${song.title} sf=${repo.catalogStorefront}")
        val replacement = appleClient.findReplacementCatalogSong(song, mutPrefs.getMUT(), repo.catalogStorefront)
        if (generation != playGen) return null
        if (replacement == null) {
            webServer.addLog("PLR", "no verified catalog replacement for ${song.title}")
            return null
        }
        _state.update { it.withRecoveredArtwork(song.id, replacement) }
        saveState()
        val displaySong = _state.value.currentSong?.takeIf { it.id == song.id } ?: song
        webServer.addLog("PLR", "verified catalog replacement id=${replacement.id} for ${song.title}")
        return buildStandaloneSource(displaySong.copy(id = replacement.id))
    }

    /**
     * Widevine media source for on-device playback. Suspends on a network round-trip
     * (bearer + webPlayback), so callers must await it before touching play() —
     * fire-and-forget here meant the PREVIOUS track kept playing and faded back in
     * while the UI already showed the new one. Returns null if standalone can't run;
     * the caller falls back to the proxy URL.
     */
    @OptIn(UnstableApi::class)
    private suspend fun buildStandaloneSource(song: Song): androidx.media3.exoplayer.source.MediaSource? {
        @Suppress("ConstantConditionIf")
        if (DECRYPT_IN_APP && android.os.Build.VERSION.SDK_INT > 23) {
            val clear = buildDecryptedStandaloneSource(song)
            if (clear != null) return clear
            Log.w("AMCENC", "in-app decrypt failed, falling back to MediaDrm path")
        }
        return buildSystemStandaloneSource(song)
    }

    /** Android 6 uses its supported MediaDrm path directly, backed by account-bound encrypted audio. */
    @OptIn(UnstableApi::class)
    private suspend fun buildSystemStandaloneSource(song: Song): androidx.media3.exoplayer.source.MediaSource? = try {
        val asset=systemAudio.prepare(song.id,foreground=true)
        val mut=mutPrefs.getMUT()
        if (mut.isEmpty() || com.veacon.veilmusic.data.cache.EncryptedAudioStore.scope(mut)!=asset.scope) null
        else {
            val bearer=appleClient.getBearer()
            val wb=asset.playback
            val rewritten=appleClient.rewritePlaylistForExo(wb.hlsText,wb.hlsUrl)
            val playlist=if(asset.entry!=null) com.veacon.veilmusic.data.cache.EncryptedAudioStore.localPlaylist(
                rewritten,"amtv-audio://cache/${asset.entry.name}") else rewritten
            val factory=androidx.media3.datasource.DataSource.Factory {
                com.veacon.veilmusic.data.cache.SystemAudioDataSource(diskCache.store,asset.scope,
                    playlist.toByteArray(Charsets.UTF_8),androidx.media3.datasource.DefaultDataSource.Factory(context).createDataSource())
            }
            val drm=DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID,FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false).build(AppleMusicDrmCallback(wb.adamId,wb.keyUri,bearer,mut,http=cdmHttp))
            if(asset.hit) systemCacheSources+=song.id else systemCacheSources-=song.id
            webServer.addLog("SOURCE","song=${song.id} system-drm local=${asset.entry!=null} cacheHit=${asset.hit}")
            DefaultMediaSourceFactory(factory).setDrmSessionManagerProvider {drm}
                .createMediaSource(buildMediaItem(song,"amtv-playlist://audio/track.m3u8")
                    .buildUpon().setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build())
        }
    } catch(e:Exception) {
        if(e is kotlinx.coroutines.CancellationException) throw e
        webServer.addLog("SOURCE","system-drm failed song=${song.id} error=${e.javaClass.simpleName}")
        null
    }

    /** Path 1 milestone probe: derive the raw content key on-device with our own
     *  software Widevine CDM and log it (AMKEY). Non-fatal; wrapped in runCatching. */
    private val cdmHttp by lazy { okhttp3.OkHttpClient.Builder()
        .addInterceptor(com.veacon.veilmusic.data.auth.AppleAuthorizationInterceptor(
            bearer = { kotlinx.coroutines.runBlocking { appleClient.getBearer() } },
            refresh = { rejected -> kotlinx.coroutines.runBlocking { appleClient.refreshBearer(rejected) } },
            userToken = mutPrefs::getMUT,
            state = appleClient.authorization,
        )).addNetworkInterceptor { chain ->
        val request = chain.request()
        chain.proceed(if (com.veacon.veilmusic.data.auth.isAppleAuthorizationHost(request.url.host)) request
            else request.newBuilder().removeHeader("Authorization").removeHeader("Cookie").build())
    }.build() }
    private val decryptCalls = com.veacon.veilmusic.media.DecryptCallRegistry()

    /** Run our software Widevine CDM to get the raw content key (hex) for a track. */
    private fun deriveContentKey(adamId: String, keyUri: String, bearer: String, mut: String, owner: String? = null, ownerJob: kotlinx.coroutines.Job? = null): String? =
        com.veacon.veilmusic.media.widevine.WidevineCdm().getContentKey(keyUri) { challengeB64 ->
            val body = org.json.JSONObject().apply {
                put("challenge", challengeB64)
                put("key-system", "com.widevine.alpha")
                put("uri", keyUri)
                put("adamId", adamId)
                put("isLibrary", false)
                put("user-initiated", true)
            }.toString()
            val call = cdmHttp.newCall(
                okhttp3.Request.Builder()
                    .url("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $bearer")
                    .addHeader("Cookie", "media-user-token=$mut")
                    .addHeader("Origin", "https://music.apple.com")
                    .build()
            )
            val licenseB64 = if (owner != null && ownerJob != null)
                decryptCalls.read(call, owner, ownerJob) { org.json.JSONObject(it.body!!.string()).getString("license") }
            else call.execute().use { org.json.JSONObject(it.body!!.string()).getString("license") }
            android.util.Base64.decode(licenseB64, android.util.Base64.DEFAULT)
        }

    private fun probeCdmKey(adamId: String, keyUri: String, bearer: String, mut: String) {
        runCatching {
            val t0 = System.currentTimeMillis()
            val key = deriveContentKey(adamId, keyUri, bearer, mut)
            Log.i("AMKEY", "adamId=$adamId key=$key (${System.currentTimeMillis() - t0}ms)")
            webServer.addLog("AMKEY", "adamId=$adamId key=$key (${System.currentTimeMillis() - t0}ms)")
        }.onFailure { Log.e("AMKEY", "probe error: ${it.message}", it) }
    }

    /**
     * Path 1 real path: derive key → download init + segments → decrypt in-app to a
     * CLEAR fMP4 on disk → play it with NO DRM, so the AAC decoder gets intact frames.
     * Returns null on any failure so the caller can fall back to the MediaDrm path.
     */
    private suspend fun buildDecryptedStandaloneSource(song: Song): androidx.media3.exoplayer.source.MediaSource? {
        val out = decryptToFile(song) ?: return null
        return DefaultMediaSourceFactory(diskCache.mediaDataSourceFactory())
            .createMediaSource(buildMediaItem(song, android.net.Uri.fromFile(out).toString()))
    }

    private val decryptInFlight = com.veacon.veilmusic.media.OwnedTaskRegistry<String, kotlinx.coroutines.Deferred<java.io.File?>>()
    /** Serializes standalone decrypts — one whole-file download at a time. Running several at once
     *  (fast-skip + prefetch) split the WAN N ways: a single decrypt is ~3-4s, four concurrent
     *  measured 14-29s each, and the foreground song you landed on was the slowest of them. */
    private val decryptMutex = kotlinx.coroutines.sync.Mutex()
    /** Cancel abandoned foreground work as well as prefetch; preserve a promoted N+1 download. */
    private fun cancelOtherDecrypts(keepSongId: String?) {
        clearPreparedNext(keepSongId)
        systemAudio.cancelExcept(keepSongId)
        decryptInFlight.forEach { (id, job) ->
            if (id != keepSongId) { job.cancel(); decryptInFlight.remove(id, job) }
        }
        decryptCalls.cancelExcept(keepSongId)
    }

    /** Derive key → fetch whole fMP4 → decrypt in-app → clear file on disk. Reuses an
     *  already-decrypted file (that's what lets prefetch warm crossfade/skip targets),
     *  and dedupes concurrent requests for the same song (fast-skip fires many).
     *  `foreground` = the song being played right now; it preempts any in-flight prefetch download
     *  and takes the decrypt slot next, so it never waits behind a background warm. */
    private suspend fun decryptToFile(song: Song, foreground: Boolean = true): java.io.File? {
        val out = diskCache.mediaFile("clear_${song.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.mp4")
        diskCache.cachedMedia(out.name)?.let { return it }
        val existing = decryptInFlight[song.id]
        if (existing != null) {
            if (foreground) cancelOtherDecrypts(keepSongId = song.id)   // promote; but never abort THIS song
            return existing.await()
        }
        // Foreground preempts whatever prefetch download is in flight so it gets the slot immediately.
        if (foreground) cancelOtherDecrypts(keepSongId = song.id)
        val job = viewModelScope.async(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                // Serialize: only one whole-file download runs at a time (no bandwidth splitting).
                decryptMutex.lock()
                try {
                    val bearer = appleClient.getBearer()
                    val mut = mutPrefs.getMUT()
                    if (bearer.isEmpty() || mut.isEmpty()) return@async null
                    val wb = appleClient.getWebPlayback(song.id, bearer, mut)
                    val keyUri = wb.keyUri ?: return@async null
                    val t0 = System.currentTimeMillis()
                    val keyHex = deriveContentKey(wb.adamId, keyUri, bearer, mut, song.id, currentCoroutineContext()[kotlinx.coroutines.Job]) ?: run {
                        Log.e("AMCENC", "no key"); return@async null
                    }
                    currentCoroutineContext().ensureActive()
                    // Apple delivers ONE fMP4 file, segmented only by #EXT-X-BYTERANGE.
                    val base = wb.hlsUrl.substring(0, wb.hlsUrl.lastIndexOf('/') + 1)
                    fun abs(u: String) = if (u.startsWith("http")) u else base + u
                    val fileUri = (Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(wb.hlsText)?.groupValues?.get(1)
                        ?: wb.hlsText.lineSequence().map { it.trim() }
                            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?: return@async null)
                        .substringBefore('?').let(::abs)
                    val call = cdmHttp.newCall(
                        okhttp3.Request.Builder().url(fileUri)
                            .addHeader("Authorization", "Bearer $bearer")
                            .addHeader("Cookie", "media-user-token=$mut").build()
                    )
                    val encrypted = decryptCalls.read(call, song.id, currentCoroutineContext()[kotlinx.coroutines.Job]!!) { response ->
                        val body = response.body
                        if (!response.isSuccessful || body == null || body.contentLength() > CacheLimits.MAX_MEDIA_ENTRY_BYTES) null
                        else body.bytes()
                    } ?: return@async null
                    currentCoroutineContext().ensureActive()
                    val currentJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
                    val dec = com.veacon.veilmusic.media.widevine.CencDecryptor(keyHex)
                    val clear = dec.decryptWhole(encrypted) { currentJob.ensureActive() }
                    currentCoroutineContext().ensureActive()
                    val saved = diskCache.putMedia(out.name, clear) ?: return@async null
                    webServer.addLog("AMCENC", "song=${song.id} in=${encrypted.size} out=${out.length()} ${System.currentTimeMillis() - t0}ms ${if (foreground) "fg" else "bg"}")
                    saved
                } finally { decryptMutex.unlock() }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("AMCENC", "decrypt failed for ${song.id}: ${e.message}", e); null
            } finally {
                decryptInFlight.remove(song.id, currentCoroutineContext()[kotlinx.coroutines.Job])
            }
        }
        decryptInFlight[song.id] = job
        return job.await()
    }

    /**
     * [byUser] = true：用户主动按了暂停（下次启动只恢复、不自动播）。
     * [byUser] = false：离开应用的被动暂停 —— 书签保留暂停前的播放状态，否则自动续播永远不生效。
     */
    fun pause(byUser: Boolean = true) {
        val wasPlaying = _state.value.isPlaying
        sourcePreparation.pause()
        player.pause()
        crossfadeExo?.pause()
        _state.update { it.copy(isPlaying = false, isLoading = false) }
        saveState(playingOverride = if (byUser) false else wasPlaying)
    }

    /** Explicit play is idempotent, including stopped/ended players and interrupted loads. */
    fun resume() {
        if (pendingRestore != null) { startPendingRestore(); return }
        if (sourcePreparation.isPending) {
            sourcePreparation.resume()
            _state.update { it.copy(isLoading = true) }
            return
        }
        if (failedPreparationSongId != null && failedPreparationSongId == _state.value.currentSong?.id) {
            playQueueItem(_state.value.queueIndex)
            return
        }
        if (player.mediaItemCount == 0) return
        attachedSourceGeneration = playGen
        resumePreparedPlayer(player)
        crossfadeExo?.takeIf { it.mediaItemCount > 0 }?.let(::resumePreparedPlayer)
    }

    fun togglePlayPause() {
        val wantsPlay = if (sourcePreparation.isPending) sourcePreparation.playWhenReady else player.playWhenReady
        // stop() may retain playWhenReady=true (e.g. a failed resume on V7).
        if (wantsPlay && (sourcePreparation.isPending ||
                player.playbackState !in setOf(Player.STATE_IDLE, Player.STATE_ENDED))) pause()
        else resume()
    }

    /** Fade time advances only while playback is requested, including a buffering partner. */
    private suspend fun awaitFadePlayback() {
        while (!player.playWhenReady) delay(100)
    }

    fun next() {
        val s = _state.value
        webServer.addLog("NAV", "next() pos=${player.currentPosition}ms idx=${s.queueIndex} userQueue=${s.userQueue.size}")
        fadeJob?.cancel(); crossfadeInProgress = false; player.volume = 1f; player.repeatMode = Player.REPEAT_MODE_OFF
        crossfadeExo?.let { cfExoErrListener?.let { l -> it.removeListener(l) }; it.stop(); it.release() }
        crossfadeExo = null; cfExoErrListener = null
        prefetchRequestedForSongId = null
        if (s.userQueue.isNotEmpty()) {
            val nextSong = s.userQueue.first()
            val insertIdx = (s.queueIndex + 1).coerceIn(0, s.queue.size)
            val newQueue = s.queue.toMutableList().also { it.add(insertIdx, nextSong) }
            _state.update { it.copy(queue = newQueue, userQueue = it.userQueue.drop(1)) }
            playQueueItem(insertIdx, skipFadeIn = true)
        } else {
            // Repeat All wraps on a manual skip too — otherwise pressing Next on the
            // last track just stops, which contradicts the mode being on.
            val target = if (s.repeatMode == RepeatMode.All && s.queueIndex + 1 >= s.queue.size) 0
                         else s.queueIndex + 1
            playQueueItem(target, skipFadeIn = true)
        }
    }
    fun prev() {
        val s = _state.value
        val pos = player.currentPosition
        webServer.addLog("NAV", "prev() pos=${pos}ms idx=${s.queueIndex}")
        fadeJob?.cancel(); crossfadeInProgress = false; player.volume = 1f; player.repeatMode = Player.REPEAT_MODE_OFF
        crossfadeExo?.let { cfExoErrListener?.let { l -> it.removeListener(l) }; it.stop(); it.release() }
        crossfadeExo = null; cfExoErrListener = null
        prefetchRequestedForSongId = null
        // Past 10s, Prev restarts the current song (standard player behaviour);
        // only a quick double-press walks back to the previous track.
        if (pos > 10_000L) { player.seekTo(0L); _state.update { it.copy(progressMs = 0L) }; return }
        val prevIdx = s.queueIndex - 1
        if (prevIdx >= 0) playQueueItem(prevIdx, skipFadeIn = true) else player.seekTo(0L)
    }

    fun moveQueueItem(from: Int, to: Int) {
        val q = _state.value.queue.toMutableList()
        if (from !in q.indices || to !in q.indices) return
        val item = q.removeAt(from)
        q.add(to, item)
        val newIdx = when {
            from == _state.value.queueIndex -> to
            from < _state.value.queueIndex && to >= _state.value.queueIndex -> _state.value.queueIndex - 1
            from > _state.value.queueIndex && to <= _state.value.queueIndex -> _state.value.queueIndex + 1
            else -> _state.value.queueIndex
        }
        _state.update { it.copy(queue = q, queueIndex = newIdx) }
    }
    fun seekForward() { webServer.addLog("PLR", "seekForward pos=${player.currentPosition}"); player.seekTo((player.currentPosition + 15_000L).coerceAtMost(player.duration.coerceAtLeast(0L))) }
    fun seekBack()    { webServer.addLog("PLR", "seekBack pos=${player.currentPosition}"); player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L)) }

    /** Public warm-up for the next AUDIO song — used by AppShell while a VIDEO is playing (the
     *  audio engine's own N+1 prefetch is idle then, so a video→song advance would be cold). */
    fun prefetchAudio(song: Song) { if (!song.isMusicVideo) prefetchSong(song) }

    /**
     * 打开专辑 / 歌单详情页时后台预热首曲直链。用户浏览曲目、看封面的这几秒里
     * 「脚本初始化 + 三平台搜索 + 直链校验」已经跑完，点「播放」时通常直接命中
     * 12 分钟缓存，首播不再出现长时间空白等待。与真正播放共用同一个 inflight
     * Deferred，因此即开即点也不会重复解析。
     *
     * 布局类单测用 Unsafe 造空白 ViewModel（字段全为 null），用 runCatching 兜住。
     */
    fun prefetchLead(songs: List<Song>) {
        runCatching {
            val first = songs.firstOrNull { !it.isMusicVideo } ?: return@runCatching
            if (lx.isEnabled()) lx.prefetch(first)
        }
    }

    private fun prefetchSong(song: Song) {
        if (!_state.value.isFullStream) return
        // Music videos are decrypted+played by the video player, not the audio stream route —
        // prefetching one via /api/stream just 404s ("No playable asset"). The video player has
        // its own prefetch (MusicVideoViewModel.prefetch), triggered from AppShell.
        if (song.isMusicVideo) return
        // Warm lyrics for the upcoming song so they render the instant it starts.
        viewModelScope.launch {
            runCatching { repo.prefetchLyrics(song.id, song.title, song.artistName, song.durationMs / 1000) }
        }
        // 落雪：只预解析直链（不建第二个 ExoPlayer），下一首/跨淡直接命中缓存。
        if (lx.isEnabled()) {
            lx.prefetch(song)
            return
        }
        // Standalone: warm the in-app decrypt cache instead of pinging the (often
        // absent) proxy. decryptToFile writes clear_<id>.mp4, which the next play or
        // crossfade then reuses instantly.
        if (useStandalone() && (song.id !in proxyOnlySongIds || !serverPrefs.serverReachable)) {
            val generation=playGen
            viewModelScope.launch {
                repeat(2) { attempt ->
                    if(generation!=playGen) return@launch
                    try {
                        val clear=if(android.os.Build.VERSION.SDK_INT>23) decryptToFile(song,foreground=false) else null
                        val ready=clear!=null || systemAudio.prepare(song.id,foreground=false).entry!=null
                        webServer.addLog("PRE", "audio ${if(ready) "ready" else "not-ready"} song=${song.id} attempt=${attempt+1}")
                        if(ready) {prepareNextWithoutFade(song,generation);return@launch}
                    } catch(e:kotlinx.coroutines.CancellationException) {throw e}
                    catch(e:Exception) {webServer.addLog("PRE","audio failed song=${song.id} error=${e.javaClass.simpleName}")}
                    if(attempt==0) delay(2_000L)
                }
            }
            return
        }
        if (!serverPrefs.serverReachable) return
        val url = repo.prefetchUrl(song.id)
        webServer.addLog("PRE", "prefetch start song=${song.title} url=$url")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("X-Music-User-Token", mutPrefs.getMUT())
                conn.connectTimeout = 5_000
                conn.readTimeout = 10_000
                val code = conn.responseCode
                conn.disconnect()
                webServer.addLog("PRE", "prefetch response=$code song=${song.title}")
            } catch (e: Exception) {
                webServer.addLog("PRE", "prefetch error=${e.message} song=${song.title}")
            }
        }
    }

    // Add to end of user priority queue (plays before rest of playlist)
    fun addToQueue(song: Song) { if (!VideoPlaybackPolicy.allows(song)) return; _state.update { it.copy(userQueue = it.userQueue + song) }; prefetchSong(song) }

    /**
     * Apple 的封面地址是模板（`.../{w}x{h}bb.jpg`），原样存下来 Coil 只会得到一个灰块。
     * 这里按固定尺寸替换掉占位符；已经不是模板的地址原样返回。
     */
    private fun resolveCover(url: String?, size: Int = 600): String? = url
        ?.replace("{w}", "$size")
        ?.replace("{h}", "$size")
        ?.replace("{f}", "jpg")
        ?.takeIf { it.isNotBlank() }

    /**
     * 「添加至列表」on an album / playlist page — 把整张专辑 / 整个歌单存成资料库里的一条
     * **播放列表**（不是「歌曲」）。[originKey] 标记来源，同一张专辑重复点只补齐缺的曲目。
     */
    fun saveAsPlaylist(name: String, originKey: String, coverUrl: String?, songs: List<Song>) {
        val playable = VideoPlaybackPolicy.audio(songs)
        if (playable.isEmpty()) return
        val cover = resolveCover(coverUrl) ?: playable.firstOrNull()?.artworkUrl(600)
        val result = localLibrary.addAsPlaylist(name, originKey, cover, playable)
        webServer.addLog("LIB", "saveAsPlaylist key=$originKey n=${playable.size} -> $result")
        toast(
            when (result) {
                is com.veacon.veilmusic.data.local.LocalLibraryStore.PlaylistUpsert.Created ->
                    tr(R.string.msg_329, result.playlist.name, result.added)
                is com.veacon.veilmusic.data.local.LocalLibraryStore.PlaylistUpsert.Updated ->
                    tr(R.string.msg_330, result.playlist.name, result.added)
                is com.veacon.veilmusic.data.local.LocalLibraryStore.PlaylistUpsert.Unchanged ->
                    tr(R.string.msg_331, result.playlist.name)
            },
        )
    }

    // Insert at front of user priority queue (plays immediately next)
    fun playNext(song: Song) { if (!VideoPlaybackPolicy.allows(song)) return; _state.update { it.copy(userQueue = listOf(song) + it.userQueue) }; prefetchSong(song) }

    // Play a specific userQueue item immediately, removing it from the queue
    fun playFromUserQueue(idx: Int) {
        val s = _state.value
        val song = s.userQueue.getOrNull(idx) ?: return
        val newUserQueue = s.userQueue.toMutableList().also { it.removeAt(idx) }
        _state.update { it.copy(userQueue = newUserQueue) }
        hasPlayedSomething = true
        val insertIdx = (s.queueIndex + 1).coerceIn(0, s.queue.size)
        val newQueue = s.queue.toMutableList().also { it.add(insertIdx, song) }
        _state.update { it.copy(queue = newQueue) }
        playQueueItem(insertIdx, skipFadeIn = true)
    }

    // One shared in-flight fetch per song id: a song is often prefetched (as the next track) and then
    // asked for again via loadLyrics when it becomes current. Without this, both fire a full network
    // fetch for the same id at once (seen as two concurrent getLyrics in logcat). Both paths now await
    // the same Deferred.
    private val lyricsInFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<com.veacon.veilmusic.data.datasource.LyricsBundle>>()

    private fun fetchLyricsShared(id: String, title: String, artist: String, durationSec: Long): kotlinx.coroutines.Deferred<com.veacon.veilmusic.data.datasource.LyricsBundle> {
        lyricsCache[id]?.let { return kotlinx.coroutines.CompletableDeferred(com.veacon.veilmusic.data.datasource.LyricsBundle(it, lyricsSourceCache[id] ?: "")) }
        lyricsInFlight[id]?.let { return it }
        val exclude = lyricsExcludedSources[id].orEmpty()
        // Register before starting, including immediate failures/cache hits; always release the slot.
        val d = viewModelScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val bundle = repo.getLyricsBundle(id, title, artist, durationSec, exclude)
                if (bundle.lines.isNotEmpty()) {
                    lyricsCache[id] = bundle.lines
                    if (bundle.source.isNotBlank()) lyricsSourceCache[id] = bundle.source
                }
                bundle
            } finally {
                if (lyricsInFlight[id] === currentCoroutineContext()[kotlinx.coroutines.Job]) lyricsInFlight.remove(id)
            }
        }
        lyricsInFlight[id] = d
        d.start()
        return d
    }

    /** 每首歌命中的歌词来源（与 lyricsCache 同生命周期）。 */
    private val lyricsSourceCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 「歌词换源」按歌曲累积要跳过的源标签（如 {"网易云"}）。切歌/换源清缓存时重置。 */
    private val lyricsExcludedSources = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private fun loadLyrics(songId: String) {
        lyricsJob?.cancel()
        lyricsFailureNotice.beginSong(songId)
        _state.update { it.copy(lyricsNotice=LyricsNoticePhase.Idle,lyricsNoticeSongId=songId,
            lyricsNoticeGeneration=lyricsFailureNotice.generation,emptyLyricsNoticeCompletedFor=null,
            exhaustedEmptyLyricsFor=null) }
        val generation = ++lyricsLoadGeneration
        lyricsCache[songId]?.let { cached ->
            if (_state.value.currentSong?.id == songId) _state.update { it.copy(lyrics = cached) }
            return
        }
        val song = _state.value.currentSong?.takeIf { it.id == songId } ?: return
        fun stillCurrent() = lyricsLoadGeneration == generation &&
            _state.value.currentSong?.id == songId && !song.isMusicVideo
        lyricsJob = viewModelScope.launch {
            fetchLyricsWithRetries(
                isCurrent = ::stillCurrent,
                fetch = {
                    val request=fetchLyricsShared(songId,song.title,song.artistName,song.durationMs / 1000)
                    try {request.await().lines} catch(e:kotlinx.coroutines.CancellationException) {request.cancel();throw e}
                },
                exhausted = {
                    if(stillCurrent() && _state.value.lyrics.isEmpty()) {
                        _state.update {it.copy(exhaustedEmptyLyricsFor=songId)}
                        lyricsFailureNotice.finishEmpty()
                        webServer.addLog("LYRICS","background retries finished song=$songId; notice eligible")
                    }
                },
                publish = { lines ->
                    _state.update { if (stillCurrent()) it.copy(lyrics = lines, lyricsSource = lyricsSourceCache[songId]) else it }
                },
                report = { attempt, count, error ->
                    lyricsFailureNotice.report(attempt, count)
                    webServer.addLog("LYRICS", "song=$songId attempt=$attempt/3 lines=$count" +
                        (error?.let { " error=${it.javaClass.simpleName}" } ?: "") +
                        if (count == 0 && attempt == 3) " exhausted" else "")
                },
            )
        }
    }

    /** Warm the lyrics cache for an upcoming song so a track change shows them with no blank flash. */
    private fun prefetchLyrics(song: Song) {
        if (lyricsCache.containsKey(song.id) || lyricsInFlight.containsKey(song.id)) return
        fetchLyricsShared(song.id, song.title, song.artistName, song.durationMs / 1000)
    }

    private fun loadMotion(songId: String) {
        motionJob?.cancel()
        _state.update { it.copy(motionUrl = null) }
        if (!_state.value.motionArtworkEnabled) return   // off → don't even fetch the motion URL
        motionJob = viewModelScope.launch {
            repo.getMotion(songId).onSuccess { url ->
                if (_state.value.currentSong?.id == songId && _state.value.motionArtworkEnabled)
                    _state.update { it.copy(motionUrl = url) }
            }
        }
    }

    private fun enrichSongIds(songId: String) = viewModelScope.launch {
        repo.getSong(songId).onSuccess { enriched ->
            if (enriched.artistId == null && enriched.albumId == null) return@onSuccess
            _state.update { s ->
                val updatedQueue = s.queue.map { q -> if (q.id == songId) q.copy(artistId = enriched.artistId ?: q.artistId, albumId = enriched.albumId ?: q.albumId) else q }
                val updatedSong  = s.currentSong?.takeIf { it.id == songId }?.copy(artistId = enriched.artistId ?: s.currentSong.artistId, albumId = enriched.albumId ?: s.currentSong.albumId)
                s.copy(queue = updatedQueue, currentSong = updatedSong ?: s.currentSong, song = updatedSong ?: s.song)
            }
        }
    }

    suspend fun lookupSongIds(songId: String): Pair<String?, String?> =
        repo.getSong(songId).getOrNull()?.let { it.artistId to it.albumId } ?: (null to null)

    private var lastAutoSaveMs = 0L
    private var recentRecordedSongId: String? = null
    private var lastProgressPushMs = 0L

    private fun pollProgress() = viewModelScope.launch {
        while (true) {
            val now = System.currentTimeMillis()
            val playing = player.isPlaying
            val playState = player.playbackState
            // During a crossfade the UI already shows the NEXT song, so track that
            // player's position — otherwise the bar sits frozen on the old song.
            val cf = crossfadeExo
            val progressSource = if (crossfadeInProgress && cf != null && cf.playbackState == Player.STATE_READY) cf else player
            // Re-anchor the UI clock about once a second, not every 200ms. The loop must
            // stay fast to catch STATE_ENDED and the crossfade window promptly, but the
            // UI interpolates between anchors per frame (rememberSmoothProgressMs), so
            // pushing progress 5x/sec just recomposed everything reading PlayerState for
            // no visible gain.
            val sourcePos = pendingRestore?.posMs ?: progressSource.currentPosition
            if (awaitingSongStart != null) {
                // Just switched tracks — pin the clock at 0 until the new item is genuinely
                // attached and READY for this generation, so the old position/duration
                // doesn't linger on the bar during the source build.
                if (currentSourceReady(playGen) && !sourcePreparation.isPending) awaitingSongStart = null
                if (_state.value.progressMs != 0L) _state.update { it.copy(progressMs = 0L) }
            } else {
                // A seek while PAUSED moves the player but not the clock, and the throttle
                // below only runs while playing — so the bar would sit frozen at the old
                // position until playback resumed. Detect the jump and push it through.
                val jumped = kotlin.math.abs(sourcePos - _state.value.progressMs) > 1_000L
                if (playing || progressSource !== player || jumped) {
                    if (jumped || now - lastProgressPushMs >= 900L) {
                        lastProgressPushMs = now
                        _state.update { it.copy(progressMs = sourcePos) }
                    }
                }
            }
            // Buffering only counts as "loading" while we're mid-crossfade or actually
            // trying to play — a paused player sitting at IDLE isn't waiting on anything.
            val loading = (sourcePreparation.isPending && sourcePreparation.playWhenReady) ||
                ((playState == Player.STATE_BUFFERING) && player.playWhenReady)
            if (loading != _state.value.isLoading) _state.update { it.copy(isLoading = loading) }
            // Guard against a double-advance: after advanceQueue() the player keeps
            // reporting STATE_ENDED for a few ticks while the new (standalone) source builds.
            // awaitingSongStart stays set until the next item is genuinely playing, so we
            // don't skip a second time within a couple of seconds.
            if (playState == Player.STATE_ENDED && !crossfadeInProgress && awaitingSongStart == null) advanceQueue()
            // Auto-save position every 10s while playing so restore lands at the right spot
            if (playing && now - lastAutoSaveMs > 10_000L) { lastAutoSaveMs = now; saveState() }
            // 有效播放（>10s）记入「最近播放」：每首歌只记一次，换曲后重新计时。
            if (playing && sourcePos >= 10_000L) {
                val cur = _state.value.currentSong
                if (cur != null && cur.id != recentRecordedSongId && !cur.isMusicVideo) {
                    recentRecordedSongId = cur.id
                    localLibrary.recordRecent(cur)
                    webServer.addLog("RECENT", "记入最近播放 ${cur.title}")
                }
            }
            val timerEnd = _state.value.sleepTimerEndsAt
            if (timerEnd != null) {
                val remaining = timerEnd - System.currentTimeMillis()
                // Ease the volume down over the final 5s instead of a hard cut.
                if (remaining in 1..SLEEP_FADE_MS && playing) {
                    sleepFadeActive = true
                    player.volume = (remaining.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
                }
                if (remaining <= 0L) {
                    webServer.addLog("SLEEP", "timer fired at pos=${player.currentPosition}ms song=${_state.value.currentSong?.title}")
                    pause(); player.volume = 1f; sleepFadeActive = false
                    _state.update { it.copy(sleepTimerEndsAt = null, isPlaying = false) }
                }
            } else if (sleepFadeActive) {
                player.volume = 1f; sleepFadeActive = false   // timer cancelled mid-fade → restore
            }

            // HTTP prefetch N+2 at 2/3 through current song
            if (playing && playState == Player.STATE_READY && !crossfadeInProgress) {
                val dur = player.duration
                val pos = player.currentPosition
                val s = _state.value
                val n2Song = if (s.userQueue.size >= 2) s.userQueue[1]
                             else if (s.userQueue.size == 1) s.queue.getOrNull(s.queueIndex + 2)
                             else s.queue.getOrNull(s.queueIndex + 2)
                if (dur > 0 && pos >= dur * 3 / 4 && n2Song != null && s.isFullStream
                    && prefetchRequestedForSongId != n2Song.id) {
                    prefetchRequestedForSongId = n2Song.id
                    prefetchSong(n2Song)
                    webServer.addLog("PRE", "prefetch N+2 at pos=${pos}ms/${dur}ms song=${n2Song.title}")
                }
            }

            // Crossfade: old player fades out over 5s; new player fades IN only for the last 3s
            if (playing && playState == Player.STATE_READY && !crossfadeInProgress) {
                val dur = player.duration
                val pos = player.currentPosition
                val remaining = dur - pos
                val s = _state.value
                val nextIdx = s.queueIndex + 1
                // Repeat One replays the current song, so there is no "next" to fade
                // into — fading would swap in the wrong track entirely. Repeat All on
                // the last song wraps to index 0, but only if that isn't the same song.
                val wrapsToStart = s.repeatMode == RepeatMode.All &&
                    nextIdx >= s.queue.size && s.queue.size > 1
                val hasNext = s.repeatMode != RepeatMode.One &&
                    (s.userQueue.isNotEmpty() || nextIdx < s.queue.size || wrapsToStart)
                val configuredCrossfadeMs=crossfadeDurationMs
                if (dur > 0 && remaining in 1..configuredCrossfadeMs && hasNext &&
                    com.veacon.veilmusic.data.CrossfadePreferences.isEnabled(s.crossfadeEnabled,configuredCrossfadeMs)) {
                    val nextSong = s.userQueue.firstOrNull()
                        ?: s.queue.getOrNull(nextIdx)
                        ?: s.queue.firstOrNull().takeIf { wrapsToStart }
                    if (cfWindowLoggedForSongId != s.queue.getOrNull(s.queueIndex)?.id) {
                        webServer.addLog("CFXO", "crossfade window: remaining=${remaining}ms next=${nextSong?.title} isFullStream=${s.isFullStream} enabled=${s.crossfadeEnabled} skip=${nextSong?.id == crossfadeSkipSongId}")
                    }
                    // Gapless: consecutive tracks off the same album were often mastered
                    // to run continuous (live records, mixes, segued sides). Fading them
                    // talks over the transition the artist built, so hand off cleanly
                    // instead. albumId is null on some library rows — fall back to name.
                    val cur = s.queue.getOrNull(s.queueIndex)
                    val sameAlbum = cur != null && nextSong != null &&
                        (if (cur.albumId != null && nextSong.albumId != null) cur.albumId == nextSong.albumId
                         else cur.albumName.isNotBlank() && cur.albumName == nextSong.albumName)
                    val consecutive = cur?.trackNumber != null && nextSong?.trackNumber != null &&
                        nextSong.trackNumber == cur.trackNumber + 1
                    val gapless = sameAlbum && consecutive && s.userQueue.isEmpty()
                    // Log once per song, not once per 200ms poll — this block re-evaluates
                    // for the whole crossfade window and each addLog is an HTTP POST.
                    if (cfWindowLoggedForSongId != cur?.id) {
                        cfWindowLoggedForSongId = cur?.id
                        if (gapless) {
                            webServer.addLog("CFXO", "gapless: same album consecutive tracks — no fade into ${nextSong?.title}")
                        }
                    }
                    if (nextSong != null && s.isFullStream && !gapless && nextSong.id != crossfadeSkipSongId) {
                        clearPreparedNext()
                        crossfadeInProgress = true
                        // buildCrossfadeExo has no DRM of its own, so in standalone mode
                        // the fade partner must get a Widevine source too — otherwise it
                        // fetches a proxy URL that may not even be reachable, errors out,
                        // and every transition silently degrades to a hard cut.
                        val cfLx = lx.isEnabled() && s.isFullStream && !nextSong.isMusicVideo
                        val cfLxCached = if (cfLx) lx.cached(nextSong.id) else null
                        val cfStandalone = useStandalone() && !cfLx
                        val cfExo = buildCrossfadeExo().also { e ->
                            e.volume = 0f
                            if (!cfStandalone && !cfLx) {
                                e.setMediaItem(buildMediaItem(nextSong, repo.streamUrl(nextSong.id)))
                                e.prepare()
                                e.play()
                            } else if (cfLxCached != null) {
                                // 预取已命中：直接建好淡入播放器，零等待
                                e.setMediaItem(buildLxMediaItem(nextSong, cfLxCached))
                                e.prepare()
                                e.play()
                            }
                        }
                        val preFadeQueue = s.queue; val preFadeIdx = s.queueIndex; val preFadeUserQueue = s.userQueue
                        val newUserQueue = if (s.userQueue.isNotEmpty()) s.userQueue.drop(1) else s.userQueue
                        val newQueue = if (s.userQueue.isNotEmpty()) {
                            s.queue.toMutableList().also { it.add((s.queueIndex + 1).coerceIn(0, it.size), nextSong) }
                        } else s.queue
                        // Repeat All off the end of the queue wraps to 0; coerceIn would
                        // otherwise clamp to lastIndex and leave the index on the song
                        // we're fading *out* of.
                        val actualNextIdx = if (wrapsToStart && s.userQueue.isEmpty()) 0
                            else (s.queueIndex + 1).coerceIn(0, newQueue.lastIndex)
                        val errListener = object : Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                webServer.addLog("CFXO", "cfExo error: ${error.errorCodeName} — restoring old player")
                                fadeJob?.cancel()
                                crossfadeInProgress = false; crossfadeExo = null; cfExoErrListener = null
                                crossfadeSkipSongId = nextSong.id
                                cfExo.removeListener(this); cfExo.release()
                                player.volume = 1f
                                val prevSong = preFadeQueue.getOrNull(preFadeIdx)
                                _state.update { it.copy(
                                    currentSong = prevSong, song = prevSong, lyrics = emptyList(), motionUrl = null,
                                    queue = preFadeQueue, queueIndex = preFadeIdx, userQueue = preFadeUserQueue,
                                )}
                                if (prevSong != null) { loadLyrics(prevSong.id); loadMotion(prevSong.id) }
                            }
                        }
                        cfExoErrListener = errListener
                        cfExo.addListener(errListener)
                        crossfadeExo = cfExo
                        if (cfStandalone || (cfLx && cfLxCached == null)) {
                            viewModelScope.launch {
                                kotlinx.coroutines.yield() // Let queue/fade setup finish before failure recovery can run.
                                if (cfLx) {
                                    val lxStream = lx.resolve(nextSong)
                                    if (crossfadeExo !== cfExo) return@launch
                                    if (lxStream != null) {
                                        lxSongId = nextSong.id
                                        webServer.addLog("LX", "跨淡 ${nextSong.title} · ${lxStream.kind} ${lxStream.kbps}kbps")
                                        cfExo.setMediaItem(buildLxMediaItem(nextSong, lxStream))
                                        cfExo.prepare()
                                        cfExo.playWhenReady = player.playWhenReady
                                        return@launch
                                    }
                                    webServer.addLog("LX", "跨淡未命中 ${nextSong.title} — 跳过淡化")
                                    // 落雪是唯一音源，不做 Apple 试听/DRM 回退：直接放弃这次淡化，
                                    // 让当前曲目继续播，用户按下一首时再重新解析。
                                    errListener.onPlayerError(androidx.media3.common.PlaybackException(
                                        tr(R.string.msg_037), null,
                                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                                    return@launch
                                }
                                val src = buildStandaloneSource(nextSong)
                                if (crossfadeExo !== cfExo) return@launch
                                if (src == null && !serverPrefs.serverReachable) {
                                    errListener.onPlayerError(androidx.media3.common.PlaybackException(
                                        tr(R.string.msg_037), null,
                                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                                    return@launch
                                }
                                if (src != null) cfExo.setMediaSource(src)
                                else cfExo.setMediaItem(buildMediaItem(nextSong, repo.streamUrl(nextSong.id)))
                                cfExo.prepare()
                                cfExo.playWhenReady = player.playWhenReady
                            }
                        }
                        val oldPlayer = player
                        _state.update { it.copy(
                            currentSong = nextSong, song = nextSong, lyrics = emptyList(), motionUrl = null,
                            queue = newQueue, queueIndex = actualNextIdx, userQueue = newUserQueue,
                            progressMs = 0L,
                        )}
                        loadLyrics(nextSong.id); loadMotion(nextSong.id)
                        if (nextSong.artistId == null || nextSong.albumId == null) enrichSongIds(nextSong.id)
                        val fadeDurationMs = remaining.coerceIn(300L, configuredCrossfadeMs)
                        // Cancel the song-start fade-in first. On a track shorter than
                        // ~2x the crossfade length the two windows overlap, and without
                        // this both jobs drive the same player's volume — one up, one
                        // down — and the old job is orphaned when the field is reassigned.
                        fadeJob?.cancel()
                        fadeJob = viewModelScope.launch {
                            val steps = 50
                            val stepMs = fadeDurationMs / steps
                            val startVol = oldPlayer.volume
                            // Hand the beat bus to the incoming song once it's the louder
                            // one. Otherwise the visuals pulse to the outgoing song's tail
                            // (often a quiet outro) for the whole crossfade and read as dead.
                            var beatPromoted = false
                            for (i in 1..steps) {
                                awaitFadePlayback()
                                val frac = i.toFloat() / steps
                                oldPlayer.volume = (startVol * (1f - frac)).coerceAtLeast(0f)
                                cfExo.volume = frac.coerceAtMost(1f)
                                if (!beatPromoted && frac >= 0.5f && cfExo.playbackState == Player.STATE_READY) {
                                    promoteCrossfadeBeat(); beatPromoted = true
                                }
                                delay(stepMs)
                            }
                            if (!crossfadeInProgress || crossfadeExo == null) {
                                webServer.addLog("CFXO", "cfExo released during fade — aborting swap")
                                return@launch
                            }
                            // If cfExo is still buffering (cache not ready), give it a short grace
                            // period. The old song loops while we wait, so waiting long is worse
                            // than bailing — and if the server is down it's never coming.
                            if (cfExo.playbackState == Player.STATE_BUFFERING) {
                                val waitMs = if (serverPrefs.serverReachable) 8_000L else 0L
                                webServer.addLog("CFXO", "cfExo buffering after fade — waiting ${waitMs}ms (reachable=${serverPrefs.serverReachable})")
                                oldPlayer.volume = 1f
                                val deadline = System.currentTimeMillis() + waitMs
                                while (cfExo.playbackState == Player.STATE_BUFFERING
                                    && System.currentTimeMillis() < deadline
                                    && crossfadeInProgress) {
                                    delay(100)
                                    awaitFadePlayback()
                                }
                            }
                            awaitFadePlayback()
                            if (!crossfadeInProgress || crossfadeExo == null) {
                                webServer.addLog("CFXO", "cfExo released while waiting for ready — aborting")
                                return@launch
                            }
                            if (cfExo.playbackState == Player.STATE_READY) {
                                // Quick 1s crossfade if we had to wait
                                val qSteps = 10
                                repeat(qSteps) { i ->
                                    awaitFadePlayback()
                                    val f = (i + 1).toFloat() / qSteps
                                    oldPlayer.volume = (1f - f).coerceAtLeast(0f)
                                    cfExo.volume = f.coerceAtMost(1f)
                                    delay(100)
                                }
                                oldPlayer.removeListener(playerListener)
                                oldPlayer.volume = 0f; oldPlayer.stop(); oldPlayer.release()
                                cfExo.removeListener(errListener); cfExoErrListener = null
                                cfExo.addListener(playerListener)
                                player = cfExo; crossfadeExo = null; crossfadeInProgress = false
                                promoteCrossfadeBeat()
                                webServer.addLog("CFXO", "fade complete → cfExo isPlaying=${cfExo.isPlaying}")
                                mediaSession?.release(); mediaSession = buildMediaSession(cfExo)
                                _state.update { it.copy(isPlaying = cfExo.isPlaying) }
                                val sNow = _state.value
                                val prefetchNext = sNow.userQueue.firstOrNull() ?: sNow.queue.getOrNull(sNow.queueIndex + 1)
                                if (prefetchNext != null && prefetchRequestedForSongId != prefetchNext.id) {
                                    prefetchRequestedForSongId = prefetchNext.id; prefetchSong(prefetchNext)
                                }
                            } else {
                                webServer.addLog("CFXO", "cfExo state=${cfExo.playbackState} — giving up, playing next directly")
                                crossfadeInProgress = false; crossfadeExo = null; cfExoErrListener = null
                                try { cfExo.removeListener(errListener); cfExo.stop(); cfExo.release() } catch (_: Exception) {}
                                oldPlayer.volume = 1f
                                playQueueItem(_state.value.queueIndex, skipFadeIn = true)
                            }
                        }
                    }
                }
            }

            delay(if (playing && nowPlayingVisible) 200L else if (playing) 1_000L else 500L)
        }
    }

    private fun buildMediaItem(song: Song, uri: String) = MediaItem.Builder()
        .setMediaId(song.id)
        .setUri(uri)
        // 稳定的缓存键：直链带签名、每次解析都不一样，只有 songId + 容器后缀不变。
        // 本地代理（127.0.0.1）本来就是本机数据，不参与缓存。
        .apply {
            if (com.veacon.veilmusic.data.cache.PlaybackCache.shouldCache(uri))
                setCustomCacheKey(com.veacon.veilmusic.data.cache.PlaybackCache.keyFor(song.id, uri))
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artistName)
                .setAlbumTitle(song.albumName)
                .setArtworkUri(song.artworkUrl(600)?.let { android.net.Uri.parse(it) })
                .build()
        ).build()

    fun showNotification(title: String, body: String, id: Int = 42) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "am_alerts"
        if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, tr(R.string.msg_038), NotificationManager.IMPORTANCE_HIGH)
                    .also { it.description = tr(R.string.msg_039) }
            )
        }
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }

    private suspend fun checkAppleServiceStatus() {
        val result = repo.getAppleStatus().getOrNull() ?: return
        if (!result.ok) {
            val affected = result.services
                .filter { it.status != "operational" }
                .joinToString(", ") { it.name }
                .ifEmpty { "Apple Music" }
            webServer.addLog("SVCMON", tr(R.string.msg_040, affected))
            showNotification(
                title = tr(R.string.msg_041),
                body  = tr(R.string.msg_042, affected),
                id    = 43,
            )
        }
    }

    private fun showAuthNotification() {
        val url = webServer.serverUrl()
        showNotification(
            title = tr(R.string.msg_043),
            body  = if (url.isEmpty()) tr(webServer.setupHintId()) else tr(R.string.msg_044, url),
            id    = 42,
        )
    }

    override fun onCleared() {
        cancelOtherDecrypts(null)
        audioDeviceCallback?.let {
            runCatching { context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(it) }
        }
        audioDeviceCallback = null
        saveState()
        if (bookmarkWriterDelegate.isInitialized()) bookmarkWriter.close()
        lyricsJob?.cancel()
        motionJob?.cancel()
        relatedQueueJob?.cancel()
        fadeJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        crossfadeExo?.let { cfExoErrListener?.let { l -> it.removeListener(l) }; it.stop(); it.release() }
        crossfadeExo = null; cfExoErrListener = null
        player.stop()
        player.clearMediaItems()
        player.release()
        diskCache.unprotect("audio")
        super.onCleared()
    }
}
