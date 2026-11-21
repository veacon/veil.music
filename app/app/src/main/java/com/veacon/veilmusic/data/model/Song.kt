package com.veacon.veilmusic.data.model

import androidx.compose.runtime.Immutable

/**
 * 列表/队列里到处传的领域对象：标成 @Immutable，Compose 才能跳过它没变的子树
 * （波次2 P0-6：Song 全字段 final + 不可变列表，编译器可安全跳过重组）。
 */
@Immutable
data class Song(
    val id:             String,
    val title:          String,
    val artistName:     String,
    val albumName:      String,
    val durationMs:     Long,
    val artworkUrl:     String?,
    val artworkBgColor: String?,
    val previewUrl:     String?,
    val hasLyrics:      Boolean = false,
    val trackNumber:    Int?    = null,
    val genreNames:     List<String> = emptyList(),
    val artistId:       String? = null,
    val albumId:        String? = null,
    val type:           String  = "songs",
    val dateAdded:      String? = null,
) {
    /** Music-video rows (catalog `music-videos` / library `library-music-videos`)
     *  play through the fullscreen video path, not the audio player. */
    val isMusicVideo: Boolean get() = type.contains("music-video")

    fun artworkUrl(size: Int) = artworkUrl
        ?.replace("{w}", "$size")
        ?.replace("{h}", "$size")
        ?.replace("{f}", "jpg")

    val durationFormatted: String get() {
        val s = durationMs / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
