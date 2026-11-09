package com.veacon.veilmusic.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderModeTest {
    @Test fun storedKeysRoundTrip() {
        for (mode in DecoderMode.entries) assertEquals(mode, DecoderMode.fromKey(mode.key))
    }

    @Test fun missingOrUnknownPreferenceFallsBackToTheHardwareDefault() {
        assertEquals(DecoderMode.HARDWARE, DecoderMode.fromKey(null))
        assertEquals(DecoderMode.HARDWARE, DecoderMode.fromKey(""))
        assertEquals(DecoderMode.HARDWARE, DecoderMode.fromKey("auto"))
    }

    @Test fun storedSoftwareChoiceStillWinsOverTheNewDefault() {
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.fromKey("sw"))
        assertEquals(DecoderMode.HARDWARE, DecoderMode.fromKey("hw"))
    }
}
