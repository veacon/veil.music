package com.veacon.veilmusic.data

import com.veacon.veilmusic.ui.viewmodel.PlayerState
import org.junit.Assert.*
import org.junit.Test

/** 出厂默认三项：Hi-Res 无损 / 硬解 / 自动音画同步关闭。已存的用户选择仍优先。 */
class DefaultSettingsTest {
    @Test fun factoryDefaultsMatchTheSpec() {
        assertEquals(AudioQuality.HIRES, AudioQuality.DEFAULT)
        assertEquals("flac24bit", AudioQuality.DEFAULT.ladder.first())
        assertEquals(DecoderMode.HARDWARE, DecoderMode.DEFAULT)
        assertEquals(DecoderMode.HARDWARE, PlayerState().decoderMode)
        assertFalse(PlayerState().avSyncAuto)
    }

    @Test fun explicitUserChoiceStillWinsOverTheNewDefaults() {
        assertEquals(AudioQuality.STANDARD, AudioQuality.fromKey("128k"))
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.fromKey("sw"))
        assertTrue(PlayerState(avSyncAuto = true).avSyncAuto)
    }
}
