package com.veil.music.data.lx

import com.veil.music.data.model.SourceID

/**
 * 各个界面之间只传字符串 id（原 Apple 版到处是 `pl.xxx` / `ra.xxx` 前缀），
 * 所以这里把「平台 + 类型 + 平台侧 id」编进一个字符串，任何一层都能还原出来。
 *
 *  - 歌曲：`kw:1234567`
 *  - 歌单：`wy:pl:24381616`
 *  - 榜单：`tx:bd:26`
 *
 * 编码是幂等的，解析失败一律返回 null，调用方按「不是本类型」处理，不会崩。
 */
object LxIds {

    const val KIND_TRACK = "t"
    const val KIND_SONGLIST = "pl"
    const val KIND_BOARD = "bd"

    fun track(source: SourceID, songmid: String): String = "${source.key}:$songmid"

    fun songlist(source: SourceID, id: String): String = "${source.key}:$KIND_SONGLIST:$id"

    fun board(source: SourceID, bangid: String): String = "${source.key}:$KIND_BOARD:$bangid"

    data class Parsed(val source: SourceID, val kind: String, val platformId: String)

    fun parse(id: String?): Parsed? {
        if (id.isNullOrBlank()) return null
        val parts = id.split(':', limit = 3)
        if (parts.size < 2) return null
        val source = SourceID.fromKey(parts[0]) ?: return null
        return when (parts.size) {
            2 -> Parsed(source, KIND_TRACK, parts[1])
            else -> {
                val kind = parts[1]
                if (kind != KIND_SONGLIST && kind != KIND_BOARD) return null
                Parsed(source, kind, parts[2])
            }
        }
    }

    fun isSonglist(id: String?): Boolean = parse(id)?.kind == KIND_SONGLIST

    fun isBoard(id: String?): Boolean = parse(id)?.kind == KIND_BOARD

    /** 歌单 / 榜单都渲染成卡片，卡片点击走同一个详情页。 */
    fun isCollection(id: String?): Boolean = isSonglist(id) || isBoard(id)
}
