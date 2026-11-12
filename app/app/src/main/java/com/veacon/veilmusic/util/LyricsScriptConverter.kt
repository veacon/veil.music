package com.veacon.veilmusic.util

import android.os.Build
import com.veacon.veilmusic.data.LyricsScript
import com.veacon.veilmusic.data.network.LyricBackground
import com.veacon.veilmusic.data.network.LyricLine
import com.veacon.veilmusic.data.network.LyricWord

/**
 * 歌词简繁转换。
 *
 * - API 24+ 走系统 ICU（`Traditional-Simplified` / `Simplified-Traditional`），覆盖完整；
 *   转写器实例按需缓存，歌词是一行一条调用，不能每次 `getInstance`。
 * - API 23 没有 ICU，退回到内置常用字表（[T2S_FALLBACK]）的**尽力而为**转换，只覆盖高频字。
 * - 文本里出现假名或谚文时整句跳过：日文 / 韩文歌词混着汉字，做简繁转换只会把原文改错。
 * - 简体→简体、繁体→繁体都是空操作；只有字形确实需要变化时才重写字符串，避免无谓的分配。
 */
object LyricsScriptConverter {

    private val transliterators =
        java.util.concurrent.ConcurrentHashMap<String, android.icu.text.Transliterator?>()

    private fun transliterator(id: String): android.icu.text.Transliterator? {
        if (Build.VERSION.SDK_INT < 24) return null
        return transliterators.computeIfAbsent(id) {
            runCatching { android.icu.text.Transliterator.getInstance(id) }.getOrNull()
        }
    }

    fun convert(text: String, script: LyricsScript): String {
        if (text.isEmpty() || !needsConversion(text)) return text
        val id = if (script == LyricsScript.SIMPLIFIED) "Traditional-Simplified" else "Simplified-Traditional"
        transliterator(id)?.let { t ->
            val out = runCatching { t.transliterate(text) }.getOrNull()
            if (out != null) return out
        }
        return fallback(text, script)
    }

    /** 整份歌词（含逐字与背景人声）一起转换，时间轴保持不变。 */
    fun convert(lines: List<LyricLine>, script: LyricsScript): List<LyricLine> {
        if (lines.isEmpty()) return lines
        return lines.map { line ->
            line.copy(
                text = convert(line.text, script),
                words = line.words.map { it.copy(text = convert(it.text, script)) },
                background = line.background?.let { bg ->
                    bg.copy(
                        text = convert(bg.text, script),
                        words = bg.words.map { it.copy(text = convert(it.text, script)) },
                    )
                },
            )
        }
    }

    internal fun convertWord(word: LyricWord, script: LyricsScript): LyricWord =
        word.copy(text = convert(word.text, script))

    internal fun convertBackground(bg: LyricBackground, script: LyricsScript): LyricBackground = bg.copy(
        text = convert(bg.text, script),
        words = bg.words.map { convertWord(it, script) },
    )

    /** 只有含 CJK 统一表意文字才值得处理；假名/谚文出现时整句跳过。 */
    private fun needsConversion(text: String): Boolean {
        var hasIdeograph = false
        for (ch in text) {
            val code = ch.code
            if (code in 0x3040..0x30FF || code in 0xAC00..0xD7AF || code in 0x1100..0x11FF) return false
            if (code in 0x3400..0x9FFF) hasIdeograph = true
        }
        return hasIdeograph
    }

    private fun fallback(text: String, script: LyricsScript): String {
        val map = if (script == LyricsScript.SIMPLIFIED) T2S_FALLBACK else S2T_FALLBACK
        val out = StringBuilder(text.length)
        var changed = false
        for (ch in text) {
            val mapped = map[ch]
            if (mapped != null && mapped != ch) { out.append(mapped); changed = true } else out.append(ch)
        }
        return if (changed) out.toString() else text
    }

    /**
     * 繁体 → 简体常用字表（API 23 兜底，也是 [com.veacon.veilmusic.luoxue.TrackMatcher] 的同一份来源）。
     * 覆盖歌词与艺人名里的高频字，不求全。
     */
    val T2S_FALLBACK: Map<Char, Char> = mapOf(
        '們' to '们', '來' to '来', '傳' to '传', '這' to '这', '個' to '个', '時' to '时', '說' to '说',
        '後' to '后',
        // 补充高频歌词用字（API 23 兜底表，尽力而为；API 24+ 一律走系统 ICU 全量表）。
        '圍' to '围', '陣' to '阵', '際' to '际', '陸' to '陆', '顆' to '颗', '絡' to '络',
        '鄰' to '邻', '鏈' to '链', '錄' to '录', '銀' to '银', '鋼' to '钢', '鏡' to '镜',
        '鹹' to '咸', '悶' to '闷', '頗' to '颇', '遙' to '遥', '醞' to '酝', '絡' to '络',
        '愛' to '爱', '願' to '愿', '夢' to '梦', '聲' to '声', '與' to '与', '為' to '为', '無' to '无',
        '誰' to '谁', '過' to '过', '還' to '还', '開' to '开', '關' to '关', '讓' to '让', '對' to '对',
        '從' to '从', '會' to '会', '學' to '学', '國' to '国', '樂' to '乐', '團' to '团', '隊' to '队',
        '聽' to '听', '寫' to '写', '絕' to '绝', '續' to '续', '變' to '变', '張' to '张', '陳' to '陈',
        '鄧' to '邓', '黃' to '黄', '劉' to '刘', '蕭' to '萧', '楊' to '杨', '葉' to '叶', '蘇' to '苏',
        '龍' to '龙', '鳳' to '凤', '齊' to '齐', '瑪' to '玛', '貝' to '贝', '爾' to '尔', '維' to '维',
        '亞' to '亚', '歐' to '欧', '陽' to '阳', '華' to '华', '萬' to '万', '億' to '亿', '點' to '点',
        '線' to '线', '麵' to '面', '髮' to '发', '鬥' to '斗', '氣' to '气', '風' to '风', '雲' to '云',
        '電' to '电', '車' to '车', '馬' to '马', '鳥' to '鸟', '魚' to '鱼', '歲' to '岁', '謝' to '谢',
        '應' to '应', '該' to '该', '現' to '现', '實' to '实', '認' to '认', '覺' to '觉', '記' to '记',
        '憶' to '忆', '靜' to '静', '樣' to '样', '麼' to '么', '沒' to '没', '屬' to '属', '於' to '于',
        '給' to '给', '帶' to '带', '頭' to '头', '淚' to '泪', '溫' to '温', '諾' to '诺', '遠' to '远',
        '長' to '长', '別' to '别', '離' to '离', '牽' to '牵', '擁' to '拥', '間' to '间', '經' to '经',
        '歷' to '历', '練' to '练', '習' to '习', '慣' to '惯', '尋' to '寻', '將' to '将', '遺' to '遗',
        '錯' to '错', '誤' to '误', '選' to '选', '擇' to '择', '堅' to '坚', '傷' to '伤', '癒' to '愈',
        '醫' to '医', '藥' to '药', '復' to '复', '興' to '兴', '奮' to '奋', '爭' to '争', '勝' to '胜',
        '負' to '负', '輸' to '输', '贏' to '赢', '敗' to '败', '課' to '课', '業' to '业', '師' to '师',
        '親' to '亲', '誼' to '谊', '緣' to '缘', '運' to '运', '機' to '机', '決' to '决', '計' to '计',
        '畫' to '画', '擋' to '挡', '掃' to '扫', '據' to '据', '歡' to '欢', '麗' to '丽', '飄' to '飘',
        '灑' to '洒', '灘' to '滩', '灣' to '湾', '濕' to '湿', '濱' to '滨', '燈' to '灯', '燒' to '烧',
        '燦' to '灿', '爛' to '烂', '獲' to '获', '環' to '环', '當' to '当', '療' to '疗', '監' to '监',
        '盡' to '尽', '睜' to '睁', '瞼' to '睑', '礙' to '碍', '禮' to '礼', '禱' to '祷', '種' to '种',
        '積' to '积', '穩' to '稳', '窮' to '穷', '競' to '竞', '筆' to '笔', '節' to '节', '簡' to '简',
        '籤' to '签', '類' to '类', '糧' to '粮', '紅' to '红', '純' to '纯', '紙' to '纸', '級' to '级',
        '紀' to '纪', '約' to '约', '紛' to '纷', '細' to '细', '終' to '终', '結' to '结', '統' to '统',
        '絲' to '丝', '綠' to '绿', '緊' to '紧', '緩' to '缓', '編' to '编', '縫' to '缝', '纜' to '缆',
        '網' to '网', '羅' to '罗', '義' to '义', '聯' to '联', '職' to '职', '臉' to '脸', '臨' to '临',
        '舉' to '举', '舊' to '旧', '艙' to '舱', '蓋' to '盖', '藍' to '蓝', '藝' to '艺', '處' to '处',
        '號' to '号', '蟲' to '虫', '衝' to '冲', '褲' to '裤', '見' to '见', '觀' to '观', '規' to '规',
        '視' to '视', '討' to '讨', '訓' to '训', '訊' to '讯', '講' to '讲', '許' to '许', '評' to '评',
        '詞' to '词', '試' to '试', '詩' to '诗', '話' to '话', '詳' to '详', '語' to '语', '誠' to '诚',
        '調' to '调', '談' to '谈', '請' to '请', '論' to '论', '讀' to '读', '讚' to '赞', '豐' to '丰',
        '豬' to '猪', '財' to '财', '責' to '责', '貴' to '贵', '買' to '买', '貸' to '贷', '費' to '费',
        '貼' to '贴', '賀' to '贺', '資' to '资', '賣' to '卖', '質' to '质', '贈' to '赠', '轉' to '转',
        '輪' to '轮', '軟' to '软', '輕' to '轻', '輛' to '辆', '輩' to '辈', '輝' to '辉', '邊' to '边',
        '達' to '达', '遷' to '迁', '鄉' to '乡', '醜' to '丑', '釋' to '释', '鐘' to '钟', '鐵' to '铁',
        '門' to '门', '閃' to '闪', '閉' to '闭', '問' to '问', '閒' to '闲', '隨' to '随', '隱' to '隐',
        '難' to '难', '雛' to '雏', '雜' to '杂', '雞' to '鸡', '靈' to '灵', '響' to '响', '頁' to '页',
        '頂' to '顶', '項' to '项', '順' to '顺', '須' to '须', '預' to '预', '領' to '领', '頻' to '频',
        '題' to '题', '顏' to '颜', '顧' to '顾', '顯' to '显', '飛' to '飞', '飯' to '饭', '飽' to '饱',
        '飾' to '饰', '養' to '养', '餘' to '余', '館' to '馆', '驚' to '惊', '魯' to '鲁', '鮮' to '鲜',
        '鳴' to '鸣', '鴻' to '鸿', '鵰' to '雕', '熱' to '热', '營' to '营', '臥' to '卧', '芻' to '刍',
        '蘊' to '蕴', '註' to '注', '誌' to '志', '誦' to '诵', '諒' to '谅', '謀' to '谋', '證' to '证',
        '識' to '识', '譯' to '译', '議' to '议', '護' to '护', '鬱' to '郁', '驗' to '验', '體' to '体',
        '鬧' to '闹', '鴨' to '鸭', '鷹' to '鹰', '麥' to '麦', '黨' to '党', '齒' to '齿', '龜' to '龟',
    )

    /** 简体 → 繁体（由 [T2S_FALLBACK] 反查；多对一时取第一个，仅用于 API 23 兜底）。 */
    val S2T_FALLBACK: Map<Char, Char> = buildMap {
        for ((t, s) in T2S_FALLBACK) putIfAbsent(s, t)
    }
}
