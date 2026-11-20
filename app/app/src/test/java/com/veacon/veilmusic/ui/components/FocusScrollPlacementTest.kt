package com.veacon.veilmusic.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 横向滚动预测的边界夹取测试。
 *
 * 回归点："同一行超过五个元素，滑到最后一个后回到第一个仍错位"。
 * 根因是 reachableFocusScroll 的下限没有与上限对称地扣除起始侧 contentPadding：
 * LazyListItemInfo.offset 从容器起点量起，行首 item 在未滚到容器起点前 offset 为正，
 * 若直接 coerceAtMost(0) 会把下限压成 0，裁掉负向（回退）预测滚动。
 */
class FocusScrollPlacementTest {

    private val viewportEnd = 1000
    private val afterPadding = 28

    @Test
    fun lowerBoundSubtractsStartPadding() {
        // 起始侧 padding = 100px；行首 item offset=40（还差 60 才到容器起点），
        // 因此最多还能向左预测滚动 60px，下限应为 -60。
        assertEquals(-60f, reachableFocusScroll(-300f, 40, null, viewportEnd, afterPadding, 100), 0f)
        // 已滚到容器起点：offset == beforePadding → 下限 0，不再预测回退。
        assertEquals(0f, reachableFocusScroll(-300f, 100, null, viewportEnd, afterPadding, 100), 0f)
        // 完全越过起点（offset < beforePadding）→ 允许更大的回退预测。
        assertEquals(-180f, reachableFocusScroll(-300f, -80, null, viewportEnd, afterPadding, 100), 0f)
    }

    @Test
    fun zeroStartPaddingKeepsLegacyBehavior() {
        // 未显式传入起始 padding（默认 0）时，行为与旧版一致，避免影响无 contentPadding 的行。
        assertEquals(0f, reachableFocusScroll(-300f, 0, null, viewportEnd, afterPadding), 0f)
        assertEquals(-80f, reachableFocusScroll(-300f, -80, null, viewportEnd, afterPadding), 0f)
    }

    @Test
    fun upperBoundAndRtlRemainUnchanged() {
        assertEquals(148f, reachableFocusScroll(500f, null, 1120, viewportEnd, afterPadding, 100), 0f)
        assertEquals(0f, reachableFocusScroll(500f, null, 972, viewportEnd, afterPadding, 100), 0f)
        assertEquals(60f, reachableFocusScroll(300f, 40, null, viewportEnd, afterPadding, 100, isRtl = true), 0f)
        assertEquals(-148f, reachableFocusScroll(-500f, null, 1120, viewportEnd, afterPadding, 100, isRtl = true), 0f)
    }
}
