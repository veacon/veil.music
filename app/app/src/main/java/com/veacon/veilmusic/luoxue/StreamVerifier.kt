package com.veacon.veilmusic.luoxue

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 直链校验：很多脚本会"假成功"（返回 128k mp3 冒充无损），
 * 必须按真实字节判断容器与码率，否则无损目标无从谈起。
 */
class StreamVerifier(private val http: OkHttpClient) {

    companion object { private const val SNIFF_BYTES = 16 }

    data class Probe(
        val ok: Boolean,
        val kind: String,
        val mimeType: String,
        val kbps: Int,
        val bytes: Long,
        val status: Int,
        val err: String = "",
    )

    fun probe(url: String, durationSec: Int): Probe {
        // 校验只需要文件头的魔数（fLaC / ID3 / ftyp…）和 Content-Range 里的总长度。
        // 原来申请并读满 64KB，在弱网/慢 CDN 上首曲要为一个字节判断多等一个往返；
        // 缩到 16B 后立即关闭连接，解析链路里每个候选都能省下这段下载时间。
        val req = Request.Builder().url(url)
            .header("User-Agent", PlatformSearch.UA)
            .header("Range", "bytes=0-${SNIFF_BYTES - 1}")
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: resp.body?.contentLength() ?: 0L
                val head = runCatching {
                    val source = resp.body?.source()
                    if (source == null) ByteArray(0) else {
                        val buf = ByteArray(SNIFF_BYTES)
                        var off = 0
                        while (off < SNIFF_BYTES) {
                            val n = source.read(buf, off, SNIFF_BYTES - off)
                            if (n < 0) break
                            off += n
                        }
                        buf.copyOf(off)
                    }
                }.getOrDefault(ByteArray(0))

                val kind = sniff(head)
                val kbps = if (total > 0 && durationSec > 0) {
                    ((total * 8) / durationSec / 1000).toInt()
                } else 0
                Probe(
                    ok = resp.code in 200..399 && kind != "unknown",
                    kind = kind,
                    mimeType = mimeOf(kind),
                    kbps = kbps,
                    bytes = total,
                    status = resp.code,
                )
            }
        } catch (e: Exception) {
            Probe(false, "unknown", "", 0, 0, 0, e.javaClass.simpleName + ":" + (e.message ?: ""))
        }
    }

    private fun sniff(b: ByteArray): String {
        if (b.size < 12) return "unknown"
        fun at(i: Int, s: String) = b.size >= i + s.length &&
            String(b, i, s.length, Charsets.ISO_8859_1) == s

        if (at(0, "fLaC")) return "flac"
        if (at(0, "ID3")) return "mp3"
        if (b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) return "mp3"
        if (at(4, "ftyp")) return "mp4/aac"
        if (at(0, "OggS")) return "ogg"
        if (at(0, "RIFF")) return "wav"
        if (at(0, "#EXTM")) return "hls"
        return "unknown"
    }

    private fun mimeOf(kind: String): String = when (kind) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "mp4/aac" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "hls" -> "application/x-mpegURL"
        else -> ""
    }
}
