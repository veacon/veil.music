package com.veacon.veilmusic.media

import android.app.Application
import android.content.Context
import com.veacon.veilmusic.data.*
import com.veacon.veilmusic.data.auth.TokenVerification
import com.veacon.veilmusic.data.auth.TokenVerifier
import com.veacon.veilmusic.data.di.NetworkModule
import com.veacon.veilmusic.data.local.LocalLibraryStore
import com.veacon.veilmusic.data.local.PlaylistImporter
import com.veacon.veilmusic.data.repository.MusicRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.Socket

/**
 * 手机配置页的英文完整性回归。
 *
 * 覆盖三层：静态 HTML 页面、运行时日志行（含动态变量）、服务端动态提示。
 * 唯一允许残留的中文是语言切换器里的「中文」——语言名按惯例保留母语写法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PhoneEnglishCoverageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun chineseFragments(text: String): Set<String> =
        Regex("""[\u4e00-\u9fff]+""").findAll(text).map { it.value }.toSet()

    private fun server(): InAppWebServer {
        clearPhoneNetworks(context)
        addPhoneNetwork(context, false, "192.168.1.4")
        val type = Class.forName("sun.misc.Unsafe")
        val unsafe = type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val repo = type.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, MusicRepository::class.java) as MusicRepository
        val lx = com.veacon.veilmusic.luoxue.LxPlayback(
            context, AudioQualityPreferences(context))
        val importer = PlaylistImporter(okhttp3.OkHttpClient(),
            LocalLibraryStore(context, NetworkModule.provideMoshi()))
        return InAppWebServer(context, MutPreferences(context), repo,
            LyricsOffsetPreferences(context), CrossfadePreferences(context),
            StandalonePreferences(context), ServerPreferences(context), BeatAnalyzer(),
            TokenVerifier { TokenVerification.Valid }, lx, importer, LyricsScriptPreferences(context))
    }

    private fun request(port: Int, method: String, path: String, body: String = ""): String =
        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 5000
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
                if (method == "POST") {
                    append("Content-Type: application/x-www-form-urlencoded\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                }
                append("\r\n")
            }
            client.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
            if (method == "POST") client.getOutputStream().write(bytes)
            client.getOutputStream().flush()
            client.getInputStream().bufferedReader(Charsets.UTF_8).readText()
        }

    private fun withServer(block: (InAppWebServer) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = server()
        try {
            s.boot(scope)
            runBlocking { withTimeout(5000) { while (!s.running) delay(10) } }
            block(s)
        } finally { s.stop(); scope.cancel() }
    }

    @Test fun labTogglesFromPhoneUpdateTheSharedPreferences() = withServer { s ->
        // 手机页改的是与 TV 端同一份 prefs；响应走重定向，页面本身不应带任何中文。
        val lyricsReply = request(s.port, "POST", "/set-lyrics-script?lang=en", "script=TRADITIONAL")
            .substringAfter("\r\n\r\n")
        assertEquals(emptySet<String>(), chineseFragments(lyricsReply))
        assertEquals(LyricsScript.TRADITIONAL, LyricsScriptPreferences(context).current())

        val compatReply = request(s.port, "POST", "/set-render-compat?lang=en", "compat=1")
            .substringAfter("\r\n\r\n")
        assertEquals(emptySet<String>(), chineseFragments(compatReply))
        assertTrue(com.veacon.veilmusic.util.RenderCompat.isActive(context))
        assertEquals("always", com.veacon.veilmusic.util.RenderCompat.describe(context))

        // 关掉后回到「手动关闭」，渲染路径与旧版本一致。
        request(s.port, "POST", "/set-render-compat?lang=en", "compat=0")
        assertFalse(com.veacon.veilmusic.util.RenderCompat.isActive(context))
        assertEquals("off", com.veacon.veilmusic.util.RenderCompat.describe(context))
    }

    @Test fun englishPageHasNoChineseExceptTheLanguageName() = withServer { s ->
        val html = request(s.port, "GET", "/?lang=en").substringAfter("\r\n\r\n")
        assertTrue(html.take(120), html.startsWith("<!DOCTYPE") || html.contains("<html"))
        assertTrue("English header copy missing", html.contains("AMusic Setup"))
        val leftovers = chineseFragments(html) - setOf("中文")
        assertTrue("Untranslated Chinese on the English page: $leftovers", leftovers.isEmpty())
    }

    @Test fun chinesePageStillRendersChineseAndSwitchIsImmediate() = withServer { s ->
        val zh = request(s.port, "GET", "/?lang=zh").substringAfter("\r\n\r\n")
        assertTrue(zh.contains("AMusic 配置"))
        val en = request(s.port, "GET", "/?lang=en").substringAfter("\r\n\r\n")
        assertFalse(en.contains("AMusic 配置"))
    }

    @Test fun dynamicServerMessagesAreEnglish() = withServer { s ->
        val emptyImport = request(s.port, "POST", "/lx-import?json=1&lang=en",
            "name=&url=&text=").substringAfter("\r\n\r\n")
        assertTrue(emptyImport, emptyImport.contains("No content"))
        assertEquals(emptySet<String>(), chineseFragments(emptyImport))

        // on=0 avoids the bearer scrape (network) while still exercising the localised reply.
        val toggle = request(s.port, "POST", "/set-apple-display?json=1&lang=en",
            "on=0").substringAfter("\r\n\r\n")
        assertTrue(toggle, toggle.contains("display"))
        assertEquals(emptySet<String>(), chineseFragments(toggle))
    }

    @Test fun runtimeLogLinesWithDynamicValuesAreFullyEnglish() {
        val samples = listOf(
            "音源初始化完成：10/10 个脚本，耗时 842ms",
            "脚本同步 3 个：a.js, b.js, c.js",
            "没有可用脚本（/data/scripts 为空）",
            "脚本就绪 kulao 1.2 源=[wy, qq]",
            "脚本重载：手动重载",
            "已重载：10 个脚本（初始化失败，见日志）",
            "已导入 custom.js（2048B），引擎已重建",
            "已导入 custom.js，但引擎初始化失败",
            "恢复随包脚本（清理 2 个）",
            "未找到 old.js",
            "已删除 old.js",
            // 歌名是用户数据、不参与本地化；用英文占位，避免把数据误判成未翻译的中文文案。
            "未命中：Song A（3 次尝试 / 1200ms）",
            "命中 Song A → flac 999kbps (wy/flac24bit/kulao) 解析 842ms 校验 31ms",
            "搜索无候选：timeout",
            "失败明细：wy timeout | qq 404",
            "标题未命中，按艺人+时长兜底…",
            "搜索平台…",
            "成功：flac 999kbps（wy/kulao）",
            "全部源失败，跳过",
            "音源已关闭（下次播放走 Apple 直连/代理）",
            "脚本 10 个",
            "记入最近播放 Song A",
            "搜索无候选",
            "脚本不支持该源",
            "未返回直链",
            "已连通",
            "异常",
        )
        for (sample in samples) {
            val out = com.veacon.veilmusic.i18n.PhonePage.localize(context, sample, "en")
            assertTrue("Still Chinese: '$sample' -> '$out'", chineseFragments(out).isEmpty())
            assertEquals("Chinese must be untouched: $sample", sample,
                com.veacon.veilmusic.i18n.PhonePage.localize(context, sample, "zh"))
        }
    }

    @Test fun everyDynamicTemplateHasAnEnglishVariantWithoutChinese() {
        val keys = listOf("import.ok", "import.fail", "lx.empty", "lx.import.fail",
            "lx.delete.fail", "lx.reload.fail", "lx.reset.fail", "lx.stats", "lx.lastError",
            "apple.toggle.on", "apple.toggle.off", "apple.signin.ok", "apple.signin.invalid",
            "apple.signin.unavailable", "apple.signin.disabled", "apple.signout.ok")
        for (key in keys) {
            val en = com.veacon.veilmusic.i18n.PhoneText.of(key, "en", "Playlist", 7, "12", "80%", "US")
            assertNotEquals("Missing template key: $key", key, en)
            assertEquals("Chinese leaked into $key: $en", emptySet<String>(), chineseFragments(en))
        }
    }
}
