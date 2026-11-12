package com.veacon.veilmusic.util

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 封面马赛克修复开关。
 *
 * 两条不能退让的性质：
 *  1. **只对已确认的机型自动生效** —— 名单外的设备（Robolectric 默认机型、绝大多数电视）
 *     保持 AUTO + 未命中，即渲染路径与 2.2.1 完全一致；
 *  2. 设置页的开关是**两态**（开/关），关闭后不再自动打开（`hasOverride` = true）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RenderCompatTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("render_compat", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun facts(
        manufacturer: String = "Google",
        model: String = "Chromecast",
        board: String = "sabrina",
        hardware: String = "sabrina",
        soc: String = "",
        gpu: String = "Mali-G31",
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        is64: Boolean = true,
        lowRam: Boolean = false,
    ) = RenderDiagnostics.RenderFacts(
        manufacturer = manufacturer,
        model = model,
        board = board,
        hardware = hardware,
        socModel = soc,
        gpuRenderer = gpu,
        supportedAbis = abis,
        is64BitProcess = is64,
        isLowRamDevice = lowRam,
    )

    // ── 风险判定：只覆盖已确认机型 ────────────────────────────────────────

    @Test fun `普通设备不命中`() {
        assertEquals(emptyList<String>(), RenderCompat.riskReasons(facts()))
    }

    @Test fun `创维 A7F Pro 命中机型名单`() {
        val reasons = RenderCompat.riskReasons(
            facts(manufacturer = "SKYWORTH", model = "8R710_A7FP", board = "8R710", hardware = "rtd2886", soc = "RTD2886"),
        )
        assertTrue("reasons=$reasons", reasons.isNotEmpty())
        assertTrue(reasons.any { it.startsWith("model~") })
    }

    @Test fun `Realtek 板型命中硬件名单`() {
        val reasons = RenderCompat.riskReasons(facts(model = "TV", board = "rtd2871", hardware = "rtd2871"))
        assertTrue("reasons=$reasons", reasons.any { it == "hardware~rtd28" })
    }

    @Test fun `风险原因可用于日志`() {
        val reasons = RenderCompat.riskReasons(facts(model = "8R710_A7FP"))
        assertTrue(reasons.joinToString(",").contains("8R710"))
    }

    // ── 开关语义 ──────────────────────────────────────────────────────────

    @Test fun `默认是自动判定_名单外的机型不启用`() {
        assertEquals(RenderCompat.Mode.AUTO, RenderCompat.mode(context))
        assertFalse("未命中名单的机型不得改变渲染路径", RenderCompat.isActive(context))
        assertFalse(RenderCompat.hasOverride(context))
        assertEquals("auto-miss", RenderCompat.describe(context))
    }

    @Test fun `手动开启后强制生效_并与自动判定无关`() {
        RenderCompat.setEnabled(context, true)
        assertTrue(RenderCompat.isActive(context))
        assertTrue(RenderCompat.hasOverride(context))
        assertEquals("always", RenderCompat.describe(context))
    }

    @Test fun `手动关闭后不再自动开启`() {
        RenderCompat.setEnabled(context, false)
        assertFalse(RenderCompat.isActive(context))
        assertTrue("手动关过之后必须记住，不能再自动打开", RenderCompat.hasOverride(context))
        assertEquals("off", RenderCompat.describe(context))
    }

    @Test fun `开关状态可持久化`() {
        RenderCompat.setEnabled(context, true)
        assertEquals(RenderCompat.Mode.ALWAYS, RenderCompat.mode(context))
        RenderCompat.setEnabled(context, false)
        assertEquals(RenderCompat.Mode.OFF, RenderCompat.mode(context))
        // 清掉覆盖后回到自动判定
        context.getSharedPreferences("render_compat", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(RenderCompat.Mode.AUTO, RenderCompat.mode(context))
    }
}
