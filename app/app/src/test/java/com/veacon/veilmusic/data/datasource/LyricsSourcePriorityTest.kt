package com.veacon.veilmusic.data.datasource

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌词来源优先级回归测试。
 *
 * 目标顺序（高 → 低）：QQ音乐 → 酷狗 → 网易云 → 酷我 → 咪咕。
 * [ThirdPartyLyrics.orderedSources] 是 fetch 级联的唯一事实来源，
 * 回退与命中都严格按它推进；这里直接断言它，避免依赖 Android/Robolectric 运行时。
 */
class LyricsSourcePriorityTest {

    private val expected = listOf("QQ音乐", "酷狗", "网易云", "酷我", "咪咕")

    @Test fun fullCascadeFollowsPriority() {
        assertEquals(expected, ThirdPartyLyrics.SOURCE_PRIORITY)
        assertEquals(expected, ThirdPartyLyrics.orderedSources(emptySet()))
    }

    @Test fun excludedSourcesKeepRemainingPriority() {
        assertEquals(
            listOf("网易云", "酷我", "咪咕"),
            ThirdPartyLyrics.orderedSources(setOf("QQ音乐", "酷狗")),
        )
    }

    @Test fun excludingAllYieldsEmpty() {
        assertEquals(emptyList<String>(), ThirdPartyLyrics.orderedSources(expected.toSet()))
    }

    @Test fun unknownExclusionIsIgnored() {
        assertEquals(expected, ThirdPartyLyrics.orderedSources(setOf("不存在的源")))
    }
}
