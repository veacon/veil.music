package com.veil.music.data.lx

import com.veil.music.data.model.Album
import com.veil.music.data.model.BoardInfo
import com.veil.music.data.model.Quality
import com.veil.music.data.model.Song
import com.veil.music.data.model.SonglistInfo
import com.veil.music.data.model.SourceID
import com.veil.music.data.model.Track

/**
 * 音源模型 ↔ UI 模型的双向映射。
 *
 * 这一层存在的唯一目的：让 Compose 屏幕、（改造前的）队列与卡片组件完全不用知道
 * 数据来自酷我脚本还是网易云直连。所有平台差异都在这里收敛。
 */
object LxMapper {

    fun toSong(track: Track): Song = Song(
        id = LxIds.track(track.source, track.songmid),
        title = track.name,
        artistName = track.singer.ifBlank { "未知歌手" },
        albumName = track.albumName.orEmpty(),
        durationMs = (track.duration ?: 0).toLong() * 1000L,
        artworkUrl = track.picURL,
        artworkBgColor = null,
        previewUrl = null,
        hasLyrics = true,
        trackNumber = null,
        genreNames = emptyList(),
        artistId = null,
        albumId = null,
        type = "songs",
    )

    fun toSongs(tracks: List<Track>): List<Song> = tracks.map(::toSong)

    fun songlistToAlbum(info: SonglistInfo): Album = Album(
        id = LxIds.songlist(info.source, info.id),
        title = info.name,
        artistName = info.author,
        artistId = null,
        artworkUrl = info.picURL,
        type = "playlists",
        releaseDate = null,
        trackCount = info.trackCount ?: 0,
        genreNames = emptyList(),
        recordLabel = null,
        copyright = null,
        editorialNotes = null,
        motionUrl = null,
        tagline = info.playCount,
        wideArtworkUrl = null,
    )

    fun boardToAlbum(info: BoardInfo): Album = Album(
        id = LxIds.board(info.source, info.bangid),
        title = info.name,
        artistName = info.source.displayName,
        artistId = null,
        artworkUrl = info.picURL,
        type = "boards",
        releaseDate = null,
        trackCount = 0,
        genreNames = emptyList(),
        recordLabel = null,
        copyright = null,
        editorialNotes = null,
        motionUrl = null,
        tagline = "排行榜",
        wideArtworkUrl = null,
    )

    /**
     * 注册表里没有这首歌时的兜底重建（清过缓存 / 老队列）。
     * 只带得回基础字段，酷狗这类需要 `hash` 的源会走换源逻辑。
     */
    fun rebuildTrack(song: Song): Track? {
        val parsed = LxIds.parse(song.id) ?: return null
        if (parsed.kind != LxIds.KIND_TRACK) return null
        return Track(
            id = song.id,
            name = song.title,
            singer = song.artistName,
            albumName = song.albumName.ifBlank { null },
            albumId = null,
            source = parsed.source,
            songmid = parsed.platformId,
            duration = (song.durationMs / 1000L).toInt().takeIf { it > 0 },
            picURL = song.artworkUrl,
            qualities = listOf(Quality.K128, Quality.K320),
            extras = emptyMap(),
        )
    }

    fun sourceOf(id: String?): SourceID? = LxIds.parse(id)?.source
}
