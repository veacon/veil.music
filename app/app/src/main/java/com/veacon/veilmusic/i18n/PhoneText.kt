package com.veacon.veilmusic.i18n

/**
 * 手机配置页的**动态**文案（服务端拼出来的提示、状态与错误）。
 *
 * 静态 HTML 走 [PhonePage.render] 的字典替换；但导入结果、脚本操作失败、令牌登录结果这类
 * 带变量的句子必须按 key 生成，否则英文模式下会漏出中文，或把中文模板错误地逐字替换。
 * 每条都是「中文模板 / 英文模板」，参数用 `String.format` 的 `%1$s` 形式，顺序可自由调整。
 * 注意：Kotlin 字符串里 `$` 必须写成 `\$`，否则会被当成字符串模板变量。
 */
internal object PhoneText {
    private val templates: Map<String, Pair<String, String>> = mapOf(
        "import.ok" to ("已导入「%1\$s」%2\$d 首" to "Imported %2\$d songs into “%1\$s”"),
        "import.fail" to ("导入失败：%1\$s" to "Import failed: %1\$s"),
        "lx.empty" to ("没有内容（文本和 URL 都是空的）" to "No content (both text and URL are empty)"),
        "lx.import.fail" to ("导入失败：%1\$s" to "Import failed: %1\$s"),
        "lx.delete.fail" to ("删除失败：%1\$s" to "Delete failed: %1\$s"),
        "lx.reload.fail" to ("重载失败：%1\$s" to "Reload failed: %1\$s"),
        "lx.reset.fail" to ("恢复失败：%1\$s" to "Restore failed: %1\$s"),
        "lx.stats" to ("脚本 %1\$s 个 · 命中率 %2\$s · 缓存 %3\$s 首" to
            "%1\$s scripts · hit rate %2\$s · %3\$s cached"),
        "lx.lastError" to (" · 最近失败：%1\$s" to " · Last failure: %1\$s"),

        "apple.toggle.on" to ("已开启 Apple Music 展示（仅界面展示，不参与播放）" to
            "Apple Music display enabled (display only, never used for playback)"),
        "apple.toggle.off" to ("已关闭 Apple Music 展示" to "Apple Music display disabled"),
        "apple.signin.ok" to ("展示账号已登录（%1\$s）" to "Display account signed in (%1\$s)"),
        "apple.signin.invalid" to ("令牌无效，未保存。" to "Invalid token. Nothing was saved."),
        "apple.signin.unavailable" to ("暂时无法连接 Apple Music，令牌未更改。" to
            "Cannot reach Apple Music right now. Your token was not changed."),
        "apple.signin.disabled" to ("展示开关未开启，未处理令牌。" to
            "Display integration is off. Token ignored."),
        "apple.signout.ok" to ("已退出展示登录。" to "Signed out of the display account."),
    )

    fun of(key: String, language: String, vararg args: Any?): String {
        val (zh, en) = templates[key] ?: return key
        val template = if (language == "en") en else zh
        return runCatching { String.format(template, *args) }.getOrDefault(template)
    }
}
