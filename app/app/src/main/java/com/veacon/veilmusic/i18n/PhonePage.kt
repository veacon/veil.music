package com.veacon.veilmusic.i18n

import android.content.Context
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 手机配置页的文案本地化。
 *
 * 静态 HTML、JS 内嵌提示、服务端动态消息、日志行都走同一套中→英转换：
 *  - [render] 对整页做字典替换；
 *  - [localize] 对单条动态文本做替换：先按 [LOG_RULES] 把「带变量的整句日志」转成英文，
 *    再按字典做长句优先的字面替换。先整句后碎词，避免短词把句子切断后留下中文碎片。
 */
internal object PhonePage {
    /** TV 端「界面语言」作为最后的兜底（浏览器没给 Accept-Language 时生效）。 */
    fun resolveLanguage(query: String?, cookie: String?, acceptLanguage: String?, appDefault: String = "zh"): String {
        if (query == "zh" || query == "en") return query
        cookie?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("amtv_lang=") }
            ?.substringAfter('=')?.takeIf { it == "zh" || it == "en" }?.let { return it }
        val preferred = acceptLanguage.orEmpty().split(',').mapIndexedNotNull { index, part ->
            val bits = part.trim().split(';')
            val quality = bits.drop(1).firstOrNull { it.trim().startsWith("q=") }
                ?.trim()?.substringAfter('=')?.toDoubleOrNull() ?: 1.0
            if (bits[0].isBlank() || quality <= 0) null else Triple(bits[0], quality, index)
        }.sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { it.third })
        val browser = preferred.firstOrNull()?.first ?: return if (appDefault == "en") "en" else "zh"
        return if (browser.substringBefore('-').equals("zh", true)) "zh" else "en"
    }

    /**
     * 运行时日志里的「中文模板 + 动态变量」整句。逐词字典替换只能覆盖固定部分，
     * 数字/文件名/异常信息会把句子切断留下中文碎片，所以这些句式必须先整体转换。
     * 只匹配明确的中文前缀，不会误伤已是英文的文本或 HTML。
     */
    private val LOG_RULES: List<Pair<Regex, String>> = listOf(
        Regex("""音源初始化完成：(\d+)/(\d+) 个脚本，耗时 (\d+)ms""") to
            "Audio source initialised: \$1/\$2 scripts in \$3ms",
        Regex("""脚本同步 (\d+) 个：(.*)""") to "Synced \$1 scripts: \$2",
        Regex("""没有可用脚本（(.+) 为空）""") to "No usable scripts (\$1 is empty)",
        Regex("""脚本就绪 (.+?) 源=(.+)""") to "Script ready \$1 sources=\$2",
        Regex("""脚本重载：(.+)""") to "Reloading scripts: \$1",
        Regex("""已重载：(\d+) 个脚本（初始化失败，见日志）""") to
            "Reloaded: \$1 scripts (initialisation failed, see log)",
        Regex("""已重载：(\d+) 个脚本""") to "Reloaded: \$1 scripts",
        Regex("""已导入 (.+)（(\d+)B），引擎已重建""") to "Imported \$1 (\$2B), engines rebuilt",
        Regex("""已导入 (.+)，但引擎初始化失败""") to "Imported \$1, but engine initialisation failed",
        Regex("""恢复随包脚本（清理 (\d+) 个）""") to "Restored bundled scripts (removed \$1)",
        Regex("""未找到 (.+)""") to "Not found: \$1",
        Regex("""已删除 (.+)""") to "Deleted \$1",
        Regex("""未命中：(.+)（(\d+) 次尝试 / (\d+)ms）""") to "No match: \$1 (\$2 attempts / \$3ms)",
        Regex("""命中 (.+) → (.+) (\d+)kbps \(([^)]+)\) 解析 (\d+)ms 校验 (\d+)ms""") to
            "Matched \$1 -> \$2 \$3kbps (\$4) resolve \$5ms verify \$6ms",
        Regex("""搜索无候选：(.+)""") to "No search candidates: \$1",
        Regex("""失败明细：(.*)""") to "Failure details: \$1",
        Regex("""标题未命中，按艺人\+时长兜底…""") to
            "Title not matched; falling back to artist + duration…",
        Regex("""搜索平台…""") to "Searching platforms…",
        Regex("""成功：(\S+) (\d+)kbps（(.+)/(.+)）""") to "Matched: \$1 \$2kbps (\$3/\$4)",
        Regex("""全部源失败，跳过""") to "All sources failed, skipping",
        Regex("""音源已关闭（下次播放走 Apple 直连/代理）""") to
            "Audio source off (next playback uses direct/proxy)",
        Regex("""脚本 (\d+) 个""") to "\$1 scripts",
        Regex("""个脚本""") to " scripts",
        Regex("""初始化失败，见日志""") to "initialisation failed, see log",
        Regex("""引擎初始化失败""") to "engine initialisation failed",
        Regex("""脚本初始化失败""") to "script initialisation failed",
        Regex("""搜索无候选""") to "No search candidates",
        Regex("""脚本不支持该源""") to "Script does not support this source",
        Regex("""未返回直链""") to "No stream URL returned",
        Regex("""已连通""") to "Connected",
        Regex("""异常""") to "Exception",
    )

    private val dictionaries = mutableMapOf<String, List<Pair<String, String>>>()
    @Synchronized private fun dictionary(context: Context): List<Pair<String, String>> = dictionaries.getOrPut("en") {
        val obj = JSONObject(context.assets.open("i18n/phone-en.json").bufferedReader().use { it.readText() })
        obj.keys().asSequence().map { it to obj.getString(it) }.sortedByDescending { it.first.length }.toList()
    }

    /** 对任意一段文本（页面、动态提示或一行日志）做中→英替换。 */
    fun localize(context: Context, text: String, language: String): String {
        if (language == "zh" || text.isEmpty()) return text
        var value = text
        // 1) 含变量的整句日志：整体转换，避免数字/文件名切断句子后残留中文碎片。
        for ((pattern, english) in LOG_RULES) value = pattern.replace(value, english)
        // 2) 固定文案：长句优先，短词不会把长句切碎。
        for ((zh, en) in dictionary(context)) value = value.replace(zh, en)
        return value
    }

    fun render(context: Context, html: String, language: String): String {
        if (language == "zh") return html
        val value = localize(context, html, language)
        // Language names stay in their native script in the language selector.
        return value.replace("</span>。", "</span>.")
    }

    fun bookmarkJson(context: Context, language: String): String {
        val source = context.assets.open("i18n/bookmark.json").bufferedReader().use { it.readText() }
        if (language == "zh") return source
        val raw = JSONTokener(source).nextValue() as String
        // This one label is Close in a dialog, rather than Off in a settings toggle.
        val closeLocalized = raw.replace("关闭", "Close")
        return JSONObject.quote(render(context, closeLocalized, language)).replace("<", "\\u003c")
    }

    fun bookmarkHtml(context: Context, language: String): String {
        val code = JSONTokener(bookmarkJson(context, language)).nextValue() as String
        return code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#x27;")
    }
}
