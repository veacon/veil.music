package com.veacon.veilmusic.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** 背景律动开关与权限状态（Visualizer 只读本应用播放会话的频谱）。 */
class SpectrumPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("spectrum_prefs", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean("enabled", false)
    fun setEnabled(on: Boolean) { prefs.edit().putBoolean("enabled", on).apply() }
    fun permissionRequested(): Boolean = prefs.getBoolean("permission_requested", false)
    fun markPermissionRequested() { prefs.edit().putBoolean("permission_requested", true).apply() }

    /** 用户是否拒绝过麦克风授权；用于区分「尚未请求」与「已拒绝/不再询问」。 */
    fun permissionDenied(): Boolean = prefs.getBoolean("permission_denied", false)

    /**
     * 记录一次授权结果。被拒绝时关闭律动开关，避免开关开着却永远采不到频谱、
     * 也不会再触发自动弹窗（只有用户重新打开开关才再请求）。
     */
    fun recordPermissionResult(granted: Boolean) {
        prefs.edit()
            .putBoolean("permission_requested", true)
            .putBoolean("permission_denied", !granted)
            .apply()
        if (!granted) setEnabled(false)
    }

    fun clearPermissionDenied() { prefs.edit().putBoolean("permission_denied", false).apply() }
}

/** 麦克风/律动权限状态，供界面给出明确提示。 */
enum class SpectrumPermissionState {
    /** 已授权，可以采集频谱。 */
    Granted,
    /** 请求过但被拒绝（含「不再询问」）；需到系统设置里开启。 */
    Denied,
    /** 设备不支持 Visualizer 频谱采集，律动不可用。 */
    Unavailable,
    /** 尚未请求过授权。 */
    NotRequested,
}

/**
 * 读取当前麦克风权限状态。
 * 先判断设备是否支持 Visualizer，再区分已授权 / 已拒绝 / 尚未请求。
 */
fun microphonePermissionState(context: Context, prefs: SpectrumPreferences): SpectrumPermissionState {
    val visualizerSupported = runCatching {
        android.media.audiofx.Visualizer.getMaxCaptureRate() > 0
    }.getOrDefault(false)
    if (!visualizerSupported) return SpectrumPermissionState.Unavailable
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) return SpectrumPermissionState.Granted
    return if (prefs.permissionRequested()) SpectrumPermissionState.Denied
    else SpectrumPermissionState.NotRequested
}
