package com.veacon.veilmusic.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** 歌词行切换「挤压/聚焦」弹性（对齐 am_atv 的 scale + 强明暗对比）。 */
class LyricElasticTest {
    @Test fun elasticOffKeepsLegacyScale() {
        // 关闭弹性：所有行 scale 1.0，观感与升级前一致。
        assertEquals(1f, LyricElastic.lineScaleTarget(true, false), 0.001f)
        assertEquals(1f, LyricElastic.lineScaleTarget(false, false), 0.001f)
    }

    @Test fun elasticOnShrinksInactiveLines() {
        // 开启：当前行 1.0、非当前行 0.98（am_atv NormalScale）。
        assertEquals(1f, LyricElastic.lineScaleTarget(true, true), 0.001f)
        assertEquals(0.98f, LyricElastic.lineScaleTarget(false, true), 0.001f)
    }

    @Test fun elasticOffKeepsLegacyAlpha() {
        // 关闭：当前行 1.0、已唱 0.35、未唱 0.40（旧的层次）。
        assertEquals(1f, LyricElastic.lineAlphaTarget(true, false, false), 0.001f)
        assertEquals(0.35f, LyricElastic.lineAlphaTarget(false, true, false), 0.001f)
        assertEquals(0.40f, LyricElastic.lineAlphaTarget(false, false, false), 0.001f)
    }

    @Test fun elasticOnStronglyDimsInactiveLines() {
        // 开启：当前行 0.94、非当前行压暗到 0.18（am_atv Primary/Tertiary），对比强烈才凸显当前行。
        assertEquals(0.94f, LyricElastic.lineAlphaTarget(true, false, true), 0.001f)
        assertEquals(0.18f, LyricElastic.lineAlphaTarget(false, true, true), 0.001f)
        assertEquals(0.18f, LyricElastic.lineAlphaTarget(false, false, true), 0.001f)
    }

    @Test fun springMatchesAmllLineScaleParams() {
        assertEquals(0.88f, LyricElastic.LINE_SCALE_DAMPING_RATIO, 0.02f)
        assertEquals(100f, LyricElastic.LINE_SCALE_STIFFNESS, 0.001f)
    }

    @Test fun scrollKeepsAmAtvDuration() {
        assertEquals(750, LyricElastic.LINE_SCROLL_DURATION_MS)
    }
}
