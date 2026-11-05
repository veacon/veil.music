package com.veil.music.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veil.music.BuildConfig
import com.veil.music.data.VeilPreferences
import com.veil.music.data.model.Quality
import com.veil.music.data.model.SourceID
import com.veil.music.data.model.UserScript
import com.veil.music.data.store.ScriptStore
import com.veil.music.media.InAppWebServer
import com.veil.music.source.SourceManager
import com.veil.music.util.DeviceInfo
import com.veil.music.util.DeviceReport
import com.veil.music.util.GithubProxy
import com.veil.music.util.UpdateChecker
import com.veil.music.util.UpdateInfo
import com.veil.music.util.UpdatePreferences
import com.veil.music.util.UpdateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 一个音源脚本**跑起来之后**的真实能力。
 *
 * 脚本头部只写了名字和版本，用户看不出「这个源到底支不支持网易云」。这些数字来自脚本
 * 初始化时上报的 `inited` 能力表（[com.veil.music.source.SourceManager.loaded]），
 * 因此它是「现在这一刻真的能用」的结论，而不是脚本作者的自述。
 */
data class ScriptCoverage(
    /** 声明了 musicUrl 的平台，按酷我/酷狗/QQ/网易/咪咕的固定顺序。 */
    val sources: List<SourceID> = emptyList(),
    /** 该脚本声明过的最高音质档，用来一眼比出「谁是母带源」。 */
    val topQuality: Quality? = null,
)

data class SettingsUiState(
    val homeSource: SourceID = VeilPreferences.DEFAULT_HOME_SOURCE,
    val quality: Quality = Quality.K320,
    val toggleSource: Boolean = true,
    val builtinFallback: Boolean = true,
    val showResolveHint: Boolean = true,
    val scripts: List<UserScript> = emptyList(),
    /** scriptId → 该脚本当前实际提供的能力，未跑起来（停用/加载失败）的脚本不在表里。 */
    val coverage: Map<String, ScriptCoverage> = emptyMap(),
    val serverUrl: String = "",
    val webServerEnabled: Boolean = true,
    val device: DeviceReport? = null,
)

/** 「检查更新」的三态：闲着 / 正在查 / 查完了（带一条给人看的话）。 */
data class UpdateUiState(
    val checking: Boolean = false,
    val message: String? = null,
    val available: UpdateInfo? = null,
    /** 仓库还没配置，UI 用灰色提示而不是报错——这是预留状态，不是故障。 */
    val unconfigured: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: VeilPreferences,
    private val scripts: ScriptStore,
    private val sources: SourceManager,
    private val webServer: InAppWebServer,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            homeSource = prefs.homeSource.value,
            quality = prefs.playQuality.value,
            toggleSource = prefs.toggleSource.value,
            builtinFallback = prefs.builtinFallback.value,
            showResolveHint = prefs.showResolveHint.value,
            serverUrl = webServer.serverUrl(),
            webServerEnabled = webServer.isEnabled,
        )
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _update = MutableStateFlow(
        UpdateUiState(unconfigured = !UpdateChecker.isConfigured)
    )
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()

    /** 「检查更新」下面可选的 GitHub 加速线路，选过就记住（电视直连 GitHub 基本不通）。 */
    private val _proxy = MutableStateFlow(UpdatePreferences.githubProxy(context))
    val proxy: StateFlow<GithubProxy> = _proxy.asStateFlow()

    fun setUpdateProxy(p: GithubProxy) {
        _proxy.value = p
        UpdatePreferences.setGithubProxy(context, p)
        // 换线路后把上一次的结论清掉：留着「检查失败」会让人以为新线路也不行。
        if (!_update.value.checking) _update.value = UpdateUiState()
    }

    init {
        viewModelScope.launch {
            scripts.scripts.collect { list -> _state.value = _state.value.copy(scripts = list) }
        }
        // 脚本是异步加载的（QuickJS 上下文 + 一次 init 往返），这里持续跟随已加载列表，
        // 把每个脚本上报的平台/音质拍平成给 UI 用的小结构。加载完一个就更新一个。
        viewModelScope.launch {
            sources.loaded.collect { loaded ->
                val map = loaded.associate { ls ->
                    val caps = ls.capabilities.sources
                    val platforms = SourceID.onlineSources.filter { src ->
                        caps[src]?.actions?.contains("musicUrl") == true
                    }
                    val best = Quality.orderedHighToLow.firstOrNull { q ->
                        caps.values.any { q in it.qualities }
                    }
                    ls.script.id to ScriptCoverage(platforms, best)
                }
                _state.value = _state.value.copy(coverage = map)
            }
        }
        // 设备信息要读 MediaCodecList / AudioCapabilities，都是毫秒级的系统调用，
        // 但没必要阻塞首帧：放到后台线程算好再推给 UI。
        viewModelScope.launch {
            val report = runCatching { DeviceInfo.collect(context) }.getOrNull()
            _state.value = _state.value.copy(device = report)
        }
    }

    fun setHomeSource(source: SourceID) {
        prefs.setHomeSource(source)
        _state.value = _state.value.copy(homeSource = source)
    }

    fun setQuality(quality: Quality) {
        prefs.setPlayQuality(quality)
        _state.value = _state.value.copy(quality = quality)
    }

    fun setToggleSource(enabled: Boolean) {
        prefs.setToggleSource(enabled)
        _state.value = _state.value.copy(toggleSource = enabled)
    }

    fun setBuiltinFallback(enabled: Boolean) {
        prefs.setBuiltinFallback(enabled)
        _state.value = _state.value.copy(builtinFallback = enabled)
    }

    fun setShowResolveHint(enabled: Boolean) {
        prefs.setShowResolveHint(enabled)
        _state.value = _state.value.copy(showResolveHint = enabled)
    }

    fun setScriptEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { scripts.setEnabled(id, enabled) }
    }

    fun removeScript(id: String) {
        viewModelScope.launch { scripts.remove(id) }
    }

    /** 把被删掉 / 停用的音源脚本全部装回来。 */
    fun restoreBuiltIns() {
        viewModelScope.launch { scripts.restoreBuiltIns() }
    }

    /** 手机管理页的地址依赖当前局域网 IP，弹二维码前重新取一次，避免 DHCP 换址后显示旧地址。 */
    fun refreshServerUrl() {
        _state.value = _state.value.copy(serverUrl = webServer.serverUrl())
    }

    fun setWebServerEnabled(enabled: Boolean) {
        webServer.applyEnabled(enabled)
        _state.value = _state.value.copy(
            webServerEnabled = enabled,
            serverUrl = webServer.serverUrl(),
        )
    }

    /**
     * 检查更新。
     *
     * 三种结论分开说：有新版本 / 已是最新 / 仓库还没发过 Release —— 最后一种不是错误，
     * 老代码把它当「检查失败 HTTP 404」，看着像按钮坏了。
     */
    fun checkUpdate(includeBeta: Boolean = false) {
        if (_update.value.checking) return
        if (!UpdateChecker.isConfigured) {
            _update.value = UpdateUiState(
                unconfigured = true,
                message = "更新源未配置（发布仓库填好之后可用）",
            )
            return
        }
        _update.value = UpdateUiState(checking = true)
        viewModelScope.launch {
            UpdateChecker.check(includeBeta, _proxy.value).fold(
                onSuccess = { result ->
                    _update.value = when (result) {
                        is UpdateResult.Newer -> UpdateUiState(
                            message = "发现新版本 v${result.info.version}",
                            available = result.info,
                        )
                        UpdateResult.UpToDate -> UpdateUiState(
                            message = "已是最新版本 v${BuildConfig.VERSION_NAME}",
                        )
                        UpdateResult.NoRelease -> UpdateUiState(
                            message = "仓库还没有发布 APK（${UpdateChecker.REPO} 暂无可下载版本）",
                        )
                    }
                },
                onFailure = { e ->
                    _update.value = UpdateUiState(
                        message = "检查失败：${e.message ?: "网络不可用"}（当前线路 ${_proxy.value.label}，可换一条再试）",
                    )
                },
            )
        }
    }
}
