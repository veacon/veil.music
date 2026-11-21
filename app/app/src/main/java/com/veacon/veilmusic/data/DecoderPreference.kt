package com.veacon.veilmusic.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import java.io.File

/**
 * 解码方式（硬解 / FFmpeg 软解）的持久化。
 *
 * 只放 SharedPreferences 的话，卸载重装（或清除数据）就会被重置；这里再加一份外部镜像：
 * Android 10+ 写进 `Download/VeilMusic/decoder.txt`（MediaStore，卸载后文件保留），
 * 启动时若 prefs 为空就从中恢复。低版本退化到公共 Download 目录，写失败则静默忽略。
 */
object DecoderPreference {

    const val KEY = "decoder_mode"
    private val relDir: String get() = "${Environment.DIRECTORY_DOWNLOADS}/VeilMusic"
    private const val FILE_NAME = "decoder.txt"

    fun read(context: Context, prefs: SharedPreferences): DecoderMode {
        val stored = prefs.getString(KEY, null)
        if (stored != null) return DecoderMode.fromKey(stored)
        // prefs 没有（首次安装 / 重装）：尝试外部镜像
        val mirror = runCatching { readMirror(context) }.getOrNull()
        if (!mirror.isNullOrBlank()) {
            prefs.edit { putString(KEY, mirror) }
            return DecoderMode.fromKey(mirror)
        }
        return DecoderMode.DEFAULT
    }

    fun write(context: Context, prefs: SharedPreferences, mode: DecoderMode) {
        prefs.edit { putString(KEY, mode.key) }
        runCatching { writeMirror(context, mode.key) }
    }

    // ── MediaStore 镜像 ──

    private fun writeMirror(context: Context, value: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = existingUri(context)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(value.toByteArray()) }
                return
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relDir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val created = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(created)?.use { it.write(value.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(created, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VeilMusic")
            if (dir.exists() || dir.mkdirs()) File(dir, FILE_NAME).writeText(value)
        }
    }

    private fun readMirror(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = existingUri(context) ?: return null
            return context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()).trim() }
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VeilMusic")
        val f = File(dir, FILE_NAME)
        return if (f.exists()) f.readText().trim() else null
    }

    private fun existingUri(context: Context): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(FILE_NAME, "%VeilMusic%")
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        return null
    }
}
