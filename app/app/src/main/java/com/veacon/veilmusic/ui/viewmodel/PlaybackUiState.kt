package com.veacon.veilmusic.ui.viewmodel

import com.veacon.veilmusic.data.model.Song
import com.veacon.veilmusic.ui.background.FluidQuality

/** Only fields read by navigation; progress/lyrics changes do not invalidate the shell. */
internal data class ShellPlaybackUiState(
    val isPlaying: Boolean, val isInPip: Boolean, val currentSong: Song?,
    val queue: List<Song>, val queueIndex: Int, val motionArtworkEnabled: Boolean,
    val lowPowerMode: Boolean, val mutExpired: Boolean, val isFullStream: Boolean,
    /** 顶栏用歌名替换「正在播放」（实验室开关）——只有导航壳读它。 */
    val navTitleAsSong: Boolean,
    /** 实验室：底部迷你播放胶囊是否显示。壳只读这个布尔决定挂不挂。 */
    val miniCapsuleEnabled: Boolean,
    val miniCapsuleHeightPct: Int,
    val miniCapsuleWidthPct: Int,
)
internal fun PlayerState.shellUiState(): ShellPlaybackUiState =
    ShellPlaybackUiState(isPlaying,isInPip,currentSong,
        queue,queueIndex,motionArtworkEnabled,lowPowerMode,mutExpired,isFullStream,navTitleAsSong,miniCapsuleEnabled,miniCapsuleHeightPct,miniCapsuleWidthPct)

/** Settings observes preferences/output changes, never the playback clock or queue. */
internal data class SettingsPlaybackUiState(
    val volumeLeveling: Boolean, val backgroundPlayEnabled: Boolean, val fluidQuality: FluidQuality,
    val lyricsScale: Float, val motionArtworkEnabled: Boolean, val artworkRounded: Boolean,
    val avSyncAuto: Boolean, val avOutputLabel: String, val avOnBluetooth: Boolean,
    val decoderMode: com.veacon.veilmusic.data.DecoderMode,
    val unifySampleRate: Boolean, val audioOffload: Boolean,
    val lyricsScript: com.veacon.veilmusic.data.LyricsScript,
    val renderCompat: Boolean,
    val renderCompatManual: Boolean,
    val navTitleAsSong: Boolean,
    val miniCapsuleEnabled: Boolean,
    val miniCapsuleHeightPct: Int,
    val miniCapsuleWidthPct: Int,
)
internal fun PlayerState.settingsUiState() = SettingsPlaybackUiState(volumeLeveling,
    backgroundPlayEnabled,fluidQuality,lyricsScale,motionArtworkEnabled,artworkRounded,
    avSyncAuto,avOutputLabel,avOnBluetooth,decoderMode,unifySampleRate,audioOffload,
    lyricsScript,renderCompat,renderCompatManual,navTitleAsSong,
    miniCapsuleEnabled,miniCapsuleHeightPct,miniCapsuleWidthPct)
