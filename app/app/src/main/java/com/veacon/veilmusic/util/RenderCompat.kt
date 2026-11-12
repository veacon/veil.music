package com.veacon.veilmusic.util

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 封面渲染兼容模式。
 *
 * ### 背景（bug：个别电视上专辑封面出现马赛克）
 * 播放页的封面叠在「流体背景」的 GL 层之上。两种合成方式在部分电视 GPU/显示后端上会留下未刷新的
 * 缓冲区，表现为封面区域右半边紫底 + 彩色马赛克块：
 *
 *  1. `Modifier.clip(圆角)` —— 给封面开一张**离屏图层**；
 *  2. 封面里的动态封面 `TextureView` —— 在背景的另一个 GL 层上再叠一路活动纹理。
 *
 * 实测（创维 A7F Pro / Realtek RTD2886，Android 13，32 位进程）：
 * 「动态开 + 圆角关」和「动态关 + 圆角开」都会花，两者都关就正常。
 *
 * ### 处理
 * 这一切都收在实验室功能的一个开关（**封面马赛克修复**）后面，**其它设备不受任何影响**：
 *
 *  - 开关关（默认给未命中名单的机型）：代码路径与 2.2.1 完全一致 —— 封面圆角仍用
 *    `Modifier.clip`（离屏图层）、仍会叠动态封面；
 *  - 开关开：圆角改用 canvas 裁剪（[com.veacon.veilmusic.ui.components.artworkCornerClip]）
 *    而不是图层裁剪；动态封面**照常叠加**，界面观感保持不变；
 *  - 未手动改过时按机型自动判定：只有**已确认**会造成马赛克的机型会默认打开；
 *    用户手动开/关过之后就不再自动判定（[hasOverride]）。
 */
object RenderCompat {

    private const val PREF = "render_compat"
    private const val KEY_MODE = "mode"

    /**
     * 供运行中的界面观察：模式一旦被改写就自增。
     *
     * TV 的实验室开关与手机配置页写的是同一份 prefs，靠它让电视端界面即时跟着切换渲染路径
     * （否则手机改完得重启应用才生效）。
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    /** 自动（按机型判定）/ 强制开启 / 强制关闭。 */
    enum class Mode { AUTO, ALWAYS, OFF }

    /**
     * 已确认会在封面区域出马赛克的后端。
     *
     * 命中规则刻意保守：只覆盖已经收到真机反馈的机型，避免把大量正常设备也降级。
     * 新增条目时请在注释里写清机型与现象，别凭猜测扩大范围。
     */
    private val RISKY_MODELS = listOf(
        // 创维 A7F Pro 系列（Realtek RTD2886，Android 13，32 位进程）——
        // 开动态封面或开封面圆角都会花屏，两者都关正常。
        "8R710",
    )

    private val RISKY_HARDWARE = listOf(
        // Realtek 电视 SoC 家族：与上面同一套显示后端（RTD28xx / RTD29xx）。
        "rtd28",
        "rtd29",
    )

    fun mode(context: Context): Mode {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        return Mode.entries.firstOrNull { it.name == raw } ?: Mode.AUTO
    }

    fun setMode(context: Context, mode: Mode) {
        val changed = this.mode(context) != mode
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit { putString(KEY_MODE, mode.name) }
        if (changed) _revision.value += 1
    }

    /** 开关直控：开启 → 强制开启，关闭 → 强制关闭。 */
    fun setEnabled(context: Context, enabled: Boolean) =
        setMode(context, if (enabled) Mode.ALWAYS else Mode.OFF)

    /** 是否已被手动改过（false = 还在按机型自动判定）。 */
    fun hasOverride(context: Context): Boolean = mode(context) != Mode.AUTO

    /** 风险命中原因（空 = 不在名单里）。写进日志/bug report，方便远程判断。 */
    fun riskReasons(facts: RenderDiagnostics.RenderFacts): List<String> {
        val haystack = listOf(facts.model, facts.board, facts.hardware, facts.socModel)
            .joinToString(" ").lowercase()
        val reasons = mutableListOf<String>()
        RISKY_MODELS.firstOrNull { haystack.contains(it.lowercase()) }
            ?.let { reasons += "model~$it" }
        RISKY_HARDWARE.firstOrNull { haystack.contains(it) }
            ?.let { reasons += "hardware~$it" }
        if (!facts.is64BitProcess && facts.isLowRamDevice) reasons += "32bit+low-ram"
        return reasons
    }

    /** 当前是否走兼容渲染。AUTO 时完全由机型名单决定（正常机型不启用，即不改变原有合成路径）。 */
    fun isActive(context: Context): Boolean = when (mode(context)) {
        Mode.ALWAYS -> true
        Mode.OFF -> false
        Mode.AUTO -> riskReasons(RenderDiagnostics.facts(context)).isNotEmpty()
    }

    /**
     * 设置页显示用：这一项当前为何是这个状态
     * （`auto-hit` 自动命中 / `auto-miss` 自动未命中 / `always` 手动开 / `off` 手动关）。
     */
    fun describe(context: Context): String = when (mode(context)) {
        Mode.ALWAYS -> "always"
        Mode.OFF -> "off"
        Mode.AUTO -> if (riskReasons(RenderDiagnostics.facts(context)).isNotEmpty()) "auto-hit" else "auto-miss"
    }
}
