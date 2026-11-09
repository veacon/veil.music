package com.veacon.veilmusic.data.updates

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 更新包下载线路。前缀是**内置常量**，用户只能从这几项里选，不能输入任意 URL ——
 * 避免把更新链路变成可指向任意主机的开放代理。
 *
 * 安全性不依赖前缀可达的主机：下载完成后仍会做 SHA-256 校验 + APK 签名/版本校验
 * （见 [AppUpdates]），任何被篡改的内容都装不上。
 *
 * 加速线路若不支持 Range 或请求失败，[AppUpdates] 会自动回退直连重试一次。
 */
enum class DownloadSource(val key: String, val zh: String, val en: String, val prefix: String) {
    DIRECT("direct", "直连 · GitHub 官方", "Direct · official GitHub", ""),
    GHFAST("ghfast", "加速 1 · ghfast.top", "Mirror 1 · ghfast.top", "https://ghfast.top/"),
    GHPROXY_COM("ghproxycom", "加速 2 · gh-proxy.com", "Mirror 2 · gh-proxy.com", "https://gh-proxy.com/"),
    GHPROXY_NET("ghproxynet", "加速 3 · ghproxy.net", "Mirror 3 · ghproxy.net", "https://ghproxy.net/");

    /** 加速前缀直接拼在完整 URL 前，例如 https://ghfast.top/https://github.com/... */
    fun url(original: String): String = if (prefix.isEmpty()) original else prefix + original

    companion object {
        fun fromKey(key: String?): DownloadSource = entries.firstOrNull { it.key == key } ?: DIRECT
    }
}

/** 记住用户选的下载线路；下次检查更新时弹窗会默认停在它上面。 */
@Singleton
class UpdateDownloadPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("update_download_prefs", Context.MODE_PRIVATE)
    private val _source = MutableStateFlow(DownloadSource.fromKey(prefs.getString("source", null)))
    val source: StateFlow<DownloadSource> = _source

    fun current(): DownloadSource = _source.value

    fun select(source: DownloadSource) {
        prefs.edit { putString("source", source.key) }
        _source.value = source
    }
}
