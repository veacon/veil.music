package com.veil.music.data.store

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 全屏歌词的「自定义外观」素材：替换字体、替换背景图。
 *
 * 素材由手机端管理页（二维码入口）上传，落在应用私有目录 `files/lyric_look/` 下。
 * 每次保存/清除都会把 [version] +1，Compose 侧 collect 这个流后重新解析字体与位图，
 * 无需重启应用即可看到新素材。
 *
 * 这么做的好处是**不把大文件塞进 APK**：字体动辄几 MB，背景图也可能很大，让用户按需
 * 从手机上传比内置更灵活；同时删除素材也只是删私有目录里的一个文件，干净可控。
 */
object LyricLookStore {

    /** 背景图按界面各存一份：墨卷与声轨互不覆盖，全屏歌词保留原来的那张。 */
    enum class BgSlot(val file: String) {
        PLAIN("background.img"),
        INK("background_ink.img"),
        TRACK("background_track.img"),
    }

    private val _version = MutableStateFlow(0L)
    /** 素材版本号。变更后 UI 重新加载字体/位图。 */
    val version: StateFlow<Long> = _version

    private fun dir(ctx: Context): File = File(ctx.filesDir, "lyric_look").apply { mkdirs() }

    fun fontFile(ctx: Context): File = File(dir(ctx), "font.ttf")
    fun backgroundFile(ctx: Context, slot: BgSlot): File = File(dir(ctx), slot.file)

    /** 字体文件存在且大小合理（> 8KB，排除误传的空文件/图标）。 */
    fun hasFont(ctx: Context): Boolean =
        fontFile(ctx).let { it.isFile && it.length() > 8_192 }

    fun hasBackground(ctx: Context, slot: BgSlot): Boolean =
        backgroundFile(ctx, slot).let { it.isFile && it.length() > 1_024 }

    fun bump() {
        _version.value = System.currentTimeMillis()
    }

    /** 把上传的临时文件复制成歌词字体。返回是否成功。 */
    fun saveFont(ctx: Context, tmp: File): Boolean = runCatching {
        if (!tmp.isFile || tmp.length() <= 0) return false
        tmp.copyTo(fontFile(ctx), overwrite = true)
        bump()
        true
    }.getOrDefault(false)

    /** 把上传的临时文件复制成某个界面的歌词背景图。返回是否成功。 */
    fun saveBackground(ctx: Context, slot: BgSlot, tmp: File): Boolean = runCatching {
        if (!tmp.isFile || tmp.length() <= 0) return false
        tmp.copyTo(backgroundFile(ctx, slot), overwrite = true)
        bump()
        true
    }.getOrDefault(false)

    fun clearFont(ctx: Context) {
        runCatching { fontFile(ctx).delete() }
        bump()
    }

    fun clearBackground(ctx: Context, slot: BgSlot) {
        runCatching { backgroundFile(ctx, slot).delete() }
        bump()
    }

    /** 解析自定义字体；不存在或解析失败一律回落 null（调用方再跟随系统字体）。 */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    fun loadFontFamily(ctx: Context): FontFamily? = runCatching {
        if (!hasFont(ctx)) return null
        val typeface = Typeface.createFromFile(fontFile(ctx))
        FontFamily(typeface)
    }.getOrNull()

    /** 解析自定义背景图；不存在或解码失败返回 null。 */
    fun loadBackground(ctx: Context, slot: BgSlot): ImageBitmap? = runCatching {
        if (!hasBackground(ctx, slot)) return null
        BitmapFactory.decodeFile(backgroundFile(ctx, slot).absolutePath)?.asImageBitmap()
    }.getOrNull()
}
