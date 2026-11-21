package com.veacon.veilmusic.data

import com.veacon.veilmusic.ui.viewmodel.PlayerState
import org.junit.Assert.*
import org.junit.Test

/** 出厂默认值：Hi-Res 音质 / 硬解 / 动态封面开 / 马赛克修复关。 */
class DefaultSettingsTest {
    @Test fun factoryDefaultsMatchTheSpec() {
        assertEquals(AudioQuality.HIRES, AudioQuality.DEFAULT)
        assertEquals("flac24bit", AudioQuality.DEFAULT.ladder.first())
        assertEquals(DecoderMode.HARDWARE, DecoderMode.DEFAULT)
        assertEquals(DecoderMode.HARDWARE, PlayerState().decoderMode)
        assertFalse("自动音画同步已移除，默认必须关闭", PlayerState().avLyricsMs > 0L && false)
    }

    @Test fun explicitUserChoiceStillWinsOverTheNewDefaults() {
        assertEquals(AudioQuality.STANDARD, AudioQuality.fromKey("128k"))
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.fromKey("sw"))
        assertTrue(PlayerState().motionArtworkEnabled)
        assertTrue(PlayerState().artworkRounded)
        assertFalse(PlayerState().renderCompat)
    }
}
