package com.veil.music.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.veil.music.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次自动更新的进度。
 *
 * 更新是「一条龙」：检查 → 下载 → 调起系统安装器。状态机就是为了让设置页那行字
 * 能如实反映走到哪一步，而不是只留一个「检查」按钮让人反复点。
 */
data class UpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    /** 0f–1f，下载进度；未知总长度时保持 0。 */
    val progress: Float = 0f,
    /** 已下载完、正在等系统安装器（或已交给它）。 */
    val installing: Boolean = false,
    val message: String? = null,
    val available: UpdateInfo? = null,
    /** 下载好的 APK，可直接「立即安装」。 */
    val apk: File? = null,
    /** 系统里还没允许本应用安装 APK，需要用户去授权。 */
    val needsPermission: Boolean = false,
    /** 仓库未配置，UI 灰色提示而不是报错。 */
    val unconfigured: Boolean = false,
) {
    val busy: Boolean get() = checking || downloading
}

/**
 * 自动更新：发现新版本就下载，下载完直接调起系统安装器。
 *
 * 单例而不是放在 ViewModel 里，原因有两个：
 * 1. 启动时的静默检查和设置页的手动检查共用同一份状态，不会出现「设置页显示已是最新、
 * 启动逻辑却在另下一份包」；
 * 2. ViewModel 随页面销毁，下载到一半切页就被取消 —— 更新应该活到装完为止。
 *
 * 安装**不卸载旧版**，走系统的覆盖安装：`scripts.json`（音源）、`library.json`（歌单）、
 * `SharedPreferences`（各项设置）、歌词字体/背景都存在应用私有目录，覆盖安装会原样保留。
 * 唯一会被覆盖安装拒绝的情况是签名不一致（包不是同一把钥匙签的），这时不会静默失败，
 * 而是明确告诉用户「先备份再卸载重装」。
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(
        UpdateState(unconfigured = !UpdateChecker.isConfigured)
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 用的是哪条线路 —— 由设置页写入，下载与检查都跟随它。 */
    var proxy: GithubProxy = UpdatePreferences.githubProxy(context)

    fun applyProxy(p: GithubProxy) {
        proxy = p
        UpdatePreferences.setGithubProxy(context, p)
        if (!_state.value.busy) _state.value = UpdateState(unconfigured = !UpdateChecker.isConfigured)
    }

    /**
     * 检查并按需自动下载安装。
     *
     * @param autoInstall 发现新版本就自动下载并调起安装器（启动检查与手动检查都是 true）。
     * @param includeBeta 是否包含 prerelease（dev 通道）。
     */
    fun check(autoInstall: Boolean = true, includeBeta: Boolean = false) {
        if (_state.value.busy) return
        if (!UpdateChecker.isConfigured) {
            _state.value = UpdateState(
                unconfigured = true,
                message = "更新源未配置（发布仓库填好之后可用）",
            )
            return
        }
        _state.value = UpdateState(checking = true, message = "正在检查更新…")
        scope.launch {
            UpdateChecker.check(includeBeta, proxy).fold(
                onSuccess = { result ->
                    when (result) {
                        is UpdateResult.Newer -> {
                            _state.update {
                                it.copy(
                                    checking = false,
                                    available = result.info,
                                    message = "发现新版本 v${result.info.version}，开始下载…",
                                )
                            }
                            if (autoInstall) download(result.info) else Unit
                        }
                        UpdateResult.UpToDate -> _state.value = UpdateState(
                            message = "已是最新版本 v${BuildConfig.VERSION_NAME}",
                        )
                        UpdateResult.NoRelease -> _state.value = UpdateState(
                            message = "仓库还没有发布 APK（${UpdateChecker.REPO} 暂无可下载版本）",
                        )
                    }
                },
                onFailure = { e ->
                    _state.value = UpdateState(
                        message = "检查失败：${e.message ?: "网络不可用"}（当前线路 $proxy，可换一条再试）",
                    )
                },
            )
        }
    }

    /** 下载安装包；完成后自动校验签名并调起安装器。 */
    fun download(info: UpdateInfo? = _state.value.available) {
        if (info == null) return
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, progress = 0f, installing = false, message = "正在下载 v${info.version}…") }
        scope.launch {
            UpdateChecker.download(context, info, proxy) { p ->
                _state.update { it.copy(progress = p, message = "正在下载 v${info.version}… ${(p * 100).toInt()}%") }
            }.fold(
                onSuccess = { apk ->
                    _state.update { it.copy(downloading = false, progress = 1f, apk = apk) }
                    verifyAndInstall(apk, info)
                },
                onFailure = { e ->
                    _state.value = UpdateState(
                        available = info,
                        message = "下载失败：${e.message ?: "网络中断"}（可换一条加速线路重试）",
                    )
                },
            )
        }
    }

    /** 用户点「立即安装」时直接走这一步（比如上次授权完权限回来）。 */
    fun installPending() {
        val apk = _state.value.apk ?: return
        val info = _state.value.available ?: return
        verifyAndInstall(apk, info)
    }

    /**
     * 装之前先确认两件事：包是不是同一个应用/同一把签名、系统允不允许装。
     *
     * 签名不一致时覆盖安装会被系统拒绝（INSTALL_FAILED_UPDATE_INCOMPATIBLE），用户看到的是
     * 「应用未安装」四个字，完全不知道该怎么办。这里提前讲清楚，并指向手机管理页的备份入口。
     */
    private fun verifyAndInstall(apk: File, info: UpdateInfo) {
        val pm = context.packageManager
        val archived = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }.getOrNull()
        if (archived == null || archived.packageName != context.packageName) {
            _state.update { it.copy(message = "下载的安装包无效（不是薄音的 APK），已放弃安装") }
            return
        }
        if (!sameSignature(pm, archived)) {
            _state.update {
                it.copy(
                    message = "安装包签名与已安装版本不一致，无法覆盖安装。请先在手机管理页「备份数据」，再卸载后重装并恢复。",
                )
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            _state.update {
                it.copy(
                    needsPermission = true,
                    message = "已下载 v${info.version}，但系统还没允许本应用安装应用：请在打开的设置里允许后回来点「安装」",
                )
            }
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        _state.update {
            it.copy(installing = true, needsPermission = false, message = "v${info.version} 已下载，正在打开安装程序…")
        }
        runCatching { UpdateChecker.install(context, apk) }.onFailure { e ->
            _state.update { it.copy(installing = false, message = "无法打开安装程序：${e.message}") }
        }
    }

    @Suppress("DEPRECATION")
    private fun sameSignature(pm: PackageManager, archived: android.content.pm.PackageInfo): Boolean {
        val a = archived.signatures?.firstOrNull()?.toByteArray() ?: return false
        val installed = runCatching { pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES) }.getOrNull()
        val b = installed?.signatures?.firstOrNull()?.toByteArray() ?: return false
        return a.contentEquals(b)
    }
}
