package com.veacon.veilmusic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 定时深浅色：半小时粒度、跨零点、绕回、边界对齐。 */
class AppearanceScheduleTest {
    private fun t(h: Int, m: Int) = h * 60 + m

    @Test fun sameTimeNeverGoesDark() {
        for (slot in 0 until 24 * 60 / AppearanceSchedule.SLOT_MINUTES) {
            val min = slot * AppearanceSchedule.SLOT_MINUTES
            assertFalse(AppearanceSchedule.shouldBeDark(min, min, min))
        }
    }

    @Test fun windowInsideOneDay() {
        val start = t(9, 0)
        val end = t(18, 30)
        assertFalse(AppearanceSchedule.shouldBeDark(t(8, 30), start, end))
        assertTrue(AppearanceSchedule.shouldBeDark(t(9, 0), start, end))
        assertTrue(AppearanceSchedule.shouldBeDark(t(18, 0), start, end))
        assertFalse(AppearanceSchedule.shouldBeDark(t(18, 30), start, end))
    }

    /** 默认时段 19:30 → 07:00，跨零点。 */
    @Test fun windowAcrossMidnight() {
        val start = AppearanceSchedule.DEFAULT_START_MIN
        val end = AppearanceSchedule.DEFAULT_END_MIN
        assertTrue(AppearanceSchedule.shouldBeDark(t(19, 30), start, end))
        assertFalse(AppearanceSchedule.shouldBeDark(t(19, 29), start, end))
        assertTrue(AppearanceSchedule.shouldBeDark(t(23, 59), start, end))
        assertTrue(AppearanceSchedule.shouldBeDark(t(0, 0), start, end))
        assertTrue(AppearanceSchedule.shouldBeDark(t(6, 59), start, end))
        assertFalse(AppearanceSchedule.shouldBeDark(t(7, 0), start, end))
        assertFalse(AppearanceSchedule.shouldBeDark(t(12, 0), start, end))
    }

    @Test fun stepWrapsAroundMidnight() {
        assertEquals(t(0, 0), AppearanceSchedule.step(t(23, 30), 1))
        assertEquals(t(23, 30), AppearanceSchedule.step(t(0, 0), -1))
    }

    @Test fun labelAndSnapAreHalfHourAligned() {
        assertEquals("19:30", AppearanceSchedule.label(AppearanceSchedule.DEFAULT_START_MIN))
        assertEquals("07:00", AppearanceSchedule.label(AppearanceSchedule.DEFAULT_END_MIN))
        // 传入非整半点也应对齐到最近的 30 分钟槽。
        assertEquals(t(19, 30), AppearanceSchedule.step(t(19, 40), 0))
    }
}
